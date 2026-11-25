package app.toque.companion

import app.toque.core.companion.CommandResult
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import app.toque.ToqueApp
import app.toque.core.companion.CompanionHub
import app.toque.core.companion.CompanionStore
import app.toque.core.companion.RobotInfo
import app.toque.core.companion.WaitingCodec
import app.toque.core.i18n.I18n
import app.toque.ui.MainActivity
import app.toque.ui.Ui
import java.io.File
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Toque Compagnon côté robot, dans le processus de l'interface : le [CompanionHub], nourri par
 * MachineClient (instantanés, évènements) et par l'écran de cuisson (session), et son serveur,
 * ouvert seulement quand [CompanionHub.wanted]. Rien ici ne doit planter ni bloquer l'interface :
 * chaque appel au hub est protégé, l'ouverture du port se fait sur un fil à part et est retentée.
 *
 * Fichiers (écrits par ce processus seulement) : `compagnon/telephones.json` (identifiant du robot et
 * téléphones appairés, empreintes des jetons seulement) et `compagnon/attente.json` (recette reçue
 * qui attend sur l'accueil, pour la retrouver après une relance de l'interface).
 */
class RobotCompanion(private val app: ToqueApp) {
    enum class Change { WAITING, PHONES, PAIRING, SERVER }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "toque-compagnon").apply { isDaemon = true } }
    private val dir = File(app.filesDir, "compagnon")
    private val waitingFile = File(dir, "attente.json")
    private val listeners = ArrayList<(Change) -> Unit>()
    private var started = false
    private var lastLoggedError = ""

    /** Port d'écoute (essais : 0, un port libre). */
    var port: Int = CompanionHub.DEFAULT_PORT
    /** Adresse d'écoute ; null : toutes (le Wi-Fi). Essais : la boucle locale. */
    var bind: InetAddress? = null
    /** Adresse IPv4 du robot sur le Wi-Fi, pour le QR code ; null sans Wi-Fi. */
    var addressProvider: () -> String? = { LanAddress.ipv4() }

    /** Dernier échec d'ouverture du port (vide si tout va bien). */
    @Volatile var serverError: String = ""
        private set

    private val remoteStartListeners = ArrayList<(String) -> Unit>()

    /** L'activité de l'interface (la recette guidée est un de ses écrans), tant qu'elle existe. */
    private var activity: WeakReference<MainActivity>? = null

    val hub: CompanionHub = CompanionHub(RobotHost(app, main, { name -> fireRemoteStart(name) }, { stopGuidedRecipe() },
        { accent, light -> appearanceFromPhone(accent, light) }),
        store = FileStore(File(dir, "telephones.json")), log = { line -> app.log.write("compagnon", line) })

    init {
        hub.setRemoteStart(app.prefs.remoteStart)
        // L'activité est suivie ici, sans rien lui demander d'autre que stopGuidedRecipe.
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                if (a is MainActivity) activity = WeakReference(a)
            }
            override fun onActivityDestroyed(a: Activity) {
                if (activity?.get() === a) activity = null
            }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        })
    }

    /** « Arrêter la recette » depuis un téléphone (fil principal) : false sans recette guidée ouverte. */
    private fun stopGuidedRecipe(): Boolean {
        val ok = activity?.get()?.stopGuidedRecipe() ?: false
        if (ok) app.log.write("compagnon", "recette guidée arrêtée depuis un téléphone")
        return ok
    }

    /**
     * Apparence demandée par un téléphone (fil principal) : l'interface ouverte décide (refus si elle
     * n'est pas libre) ; sans interface, enregistrée pour la prochaine ouverture.
     */
    private fun appearanceFromPhone(accent: Int?, light: Boolean?): CommandResult {
        val a = activity?.get()
        val r = if (a != null && !a.isFinishing) a.appearanceFromPhone(accent, light) else {
            if (light != null) app.prefs.lightTheme = light
            if (accent != null) app.prefs.accentColor = accent
            Ui.setTheme(app.prefs.lightTheme)
            Ui.setAccent(if (app.prefs.accentColor != 0) app.prefs.accentColor else Ui.ACCENTS[0])
            refreshRobot()
            CommandResult.OK
        }
        if (r.ok) app.log.write("compagnon", "apparence changée depuis un téléphone")
        return r
    }

    /** Réglage « Lancement depuis le téléphone » : enregistré et donné au hub (la permission change aussitôt). */
    fun setRemoteStart(on: Boolean) {
        app.prefs.remoteStart = on
        hub.setRemoteStart(on)
    }

    /** Cuisson lancée depuis un téléphone (son nom), sur le fil principal. Renvoie de quoi se désabonner. */
    fun onRemoteStart(l: (phoneName: String) -> Unit): () -> Unit {
        remoteStartListeners += l
        return { remoteStartListeners -= l }
    }

    private fun fireRemoteStart(name: String) {
        app.log.write("compagnon", "cuisson manuelle lancée depuis $name")
        for (l in ArrayList(remoteStartListeners)) try {
            l(name)
        } catch (t: Throwable) {
            app.log.crash("compagnon", t)
        }
    }

    private val retry = Runnable { syncServer() }

    /** Branche le hub sur la machine et les écouteurs ; une seule fois, sur le fil principal. */
    fun start() {
        if (started) return
        started = true
        app.machine.onSnapshot { s -> safe("instantané") { hub.updateMachine(s) } }
        app.machine.onEvent { e -> safe("évènement") { hub.onMachineEvent(e) } }
        refreshRobot()
        hub.onWaitingChanged {
            saveWaiting()
            main.post { fire(Change.WAITING) }
        }
        hub.onPhonesChanged { main.post { syncServer(); fire(Change.PHONES) } }
        hub.onPairingChanged { main.post { syncServer(); fire(Change.PAIRING) } }
        // Nombre de personnes changé sur la page d'une recette : la recette en attente, si c'est elle, suit.
        app.recipes.onAdjusted { id -> safe("personnes") { app.recipes.getAdjusted(id)?.let { hub.recipeServingsChanged(id, it.servings) } } }
        io.execute { restoreWaiting() }
        syncServer()
    }

    /**
     * Nom, version, langue, couleur et thème du robot : au démarrage, et à chaque fois que l'interface
     * est reconstruite (changement de langue, de couleur ou de thème dans les Réglages). Le téléphone
     * reprend la langue et la couleur.
     */
    fun refreshRobot() = safe("infos") {
        val version = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        hub.updateRobot(RobotInfo(appVersion = version, language = I18n.code,
            accent = String.format(java.util.Locale.ROOT, "#%06X", Ui.ACCENT and 0xFFFFFF),
            theme = if (Ui.light) "light" else "dark"))
    }

    /** Changements pour l'interface, toujours sur le fil principal. Renvoie de quoi se désabonner. */
    fun onChange(l: (Change) -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    private fun fire(c: Change) {
        for (l in ArrayList(listeners)) try {
            l(c)
        } catch (t: Throwable) {
            app.log.crash("compagnon", t)
        }
    }

    /**
     * Ouvre ou ferme le serveur selon [CompanionHub.wanted] ; sans effet s'il est déjà dans le bon état.
     * La fermeture attend [STOP_DELAY_MS] puis revérifie : la réponse au téléphone qui vient de se
     * retirer (« Oublier ») a le temps de partir.
     */
    fun syncServer() = sync(stopNow = false)

    private val delayedStop = Runnable { sync(stopNow = true) }

    private fun sync(stopNow: Boolean) {
        main.removeCallbacks(retry)
        io.execute {
            var failed = false
            try {
                if (hub.wanted) {
                    if (!hub.running) {
                        hub.start(port, bind)
                        app.log.write("compagnon", "serveur ouvert sur le port ${hub.port}")
                    }
                    serverError = ""
                    lastLoggedError = ""
                } else if (hub.running) {
                    if (stopNow) {
                        hub.stop()
                        app.log.write("compagnon", "serveur fermé")
                    } else main.post {
                        main.removeCallbacks(delayedStop)
                        main.postDelayed(delayedStop, STOP_DELAY_MS)
                    }
                }
            } catch (t: Throwable) {
                failed = true
                val msg = t.message ?: t.toString()
                serverError = msg
                // Une ligne par panne, pas une toutes les 30 s.
                if (msg != lastLoggedError) {
                    lastLoggedError = msg
                    app.log.write("compagnon", "port $port indisponible : $msg (nouvel essai dans ${RETRY_MS / 1000} s)")
                }
            }
            main.post {
                if (failed) {
                    main.removeCallbacks(retry)
                    main.postDelayed(retry, RETRY_MS)
                }
                fire(Change.SERVER)
            }
        }
    }

    // ------------------------------------------------------------------ recette en attente

    private fun saveWaiting() {
        // Lue au moment d'écrire : l'état le plus récent gagne, dans l'ordre des changements.
        io.execute {
            try {
                val w = hub.waitingRecipe
                if (w == null) waitingFile.delete() else FileStore.write(waitingFile, WaitingCodec.encode(w))
            } catch (t: Throwable) {
                app.log.write("compagnon", "recette en attente non enregistrée : $t")
            }
        }
    }

    private fun restoreWaiting() {
        try {
            if (!waitingFile.isFile) return
            val w = WaitingCodec.decode(waitingFile.readText())
            // Recette supprimée entre-temps (synchronisation) : rien à proposer.
            if (w == null || app.recipes.get(w.recipeId) == null) {
                waitingFile.delete()
                return
            }
            hub.restoreWaiting(w)
        } catch (t: Throwable) {
            app.log.write("compagnon", "recette en attente illisible : $t")
        }
    }

    private inline fun safe(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            app.log.write("compagnon", "$what : $t")
        }
    }

    companion object {
        const val RETRY_MS = 30_000L
        const val STOP_DELAY_MS = 3_000L
    }
}

/** [CompanionStore] dans un fichier à lui, remplacé d'un coup (fichier temporaire puis renommage). */
class FileStore(private val file: File) : CompanionStore {
    override fun load(): String? = synchronized(this) { if (file.isFile) file.readText() else null }

    override fun save(text: String) = synchronized(this) { write(file, text) }

    companion object {
        fun write(file: File, text: String) {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw java.io.IOException("renommage impossible : $file")
            }
        }
    }
}
