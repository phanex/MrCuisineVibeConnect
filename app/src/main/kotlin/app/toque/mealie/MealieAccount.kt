package app.toque.mealie

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import app.toque.Http
import app.toque.ToqueApp
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.mealie.MealieClient
import app.toque.core.mealie.MealieRecipes
import app.toque.core.mealie.MealieSync
import app.toque.core.recipes.Recipe
import app.toque.core.site.ToqueSite
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Un serveur Mealie (recettes auto-hébergées) relié au robot : toutes ses recettes arrivent en
 * lecture seule, la nuit et à la demande. Le mot de passe ne sert qu'à créer un jeton propre au
 * robot (« Toque (robot) », visible et révocable dans Mealie) ; il n'est jamais gardé.
 * Tout le réseau passe par un seul fil d'arrière-plan ; les réponses reviennent sur le fil de l'interface.
 */
class MealieAccount(private val app: ToqueApp) {
    private val prefs get() = app.prefs
    private val repo get() = app.recipes
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "toque-mealie").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val listeners = ArrayList<() -> Unit>()
    /** Empreintes des recettes reçues (uuid=empreinte) : dans un fichier à part, les réglages (relus par le processus machine) restent petits. */
    private val indexFile = File(app.filesDir, "mealie-index.txt")

    private val transport = ToqueSite.Transport { method, url, headers, body ->
        val (code, text) = Http.send(method, url, headers, body)
        ToqueSite.Reply(code, text)
    }

    /** Synchro en cours (bouton désactivé dans les Réglages). */
    val busy: Boolean get() = running.get()

    private val store = object : MealieSync.Store {
        override var index: Map<String, String>
            get() = readIndex()
            set(v) = writeIndex(v)
        override fun mealieIds() = repo.mealieIds()
        // Délié pendant la synchro : plus rien n'est enregistré.
        override fun save(recipes: List<Recipe>) { if (recipes.isNotEmpty() && prefs.mealieLinked) repo.upsertAll(recipes, rebuild = false) }
        override fun removeMealieExcept(keep: Set<String>) = repo.removeMealieExcept(keep)
    }

    /** Prévient (sur le fil de l'interface) quand le serveur relié ou la synchro changent. */
    fun onChange(l: () -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    private fun changed() = main.post { for (l in ArrayList(listeners)) l() }

    // ------------------------------------------------------------------ liaison

    /** Relie avec l'identifiant et le mot de passe Mealie ; [done] reçoit null (relié) ou le message d'erreur. */
    fun linkWithPassword(url: String, user: String, password: String, done: (String?) -> Unit) =
        link(url, done) { base -> MealieClient.createToken(base, MealieClient.login(base, user, password, transport), transport) }

    /** Relie avec un jeton d'API créé dans Mealie (Profil › Jetons d'API) ; le robot ne pourra pas le retirer. */
    fun linkWithToken(url: String, token: String, done: (String?) -> Unit) = link(url, done) { token.trim() to 0L }

    private fun link(url: String, done: (String?) -> Unit, token: (String) -> Pair<String, Long>) {
        exec.execute {
            val base = MealieClient.normalize(url)
            val error = if (base == null) tr("Adresse illisible : par exemple http://192.168.1.20:9000") else try {
                MealieClient(base, "", transport).version()
                val (t, id) = token(base)
                // Le jeton doit ouvrir la liste des recettes avant d'être gardé.
                MealieClient(base, t, transport).page(1, 1)
                if (base != prefs.mealieUrl) writeIndex(emptyMap())
                prefs.mealieUrl = base
                prefs.mealieToken = t
                prefs.mealieTokenId = id
                app.log.write("mealie", "robot relié à $base")
                null
            } catch (e: MealieClient.MealieException) {
                e.message ?: tr("Erreur")
            } catch (e: IOException) {
                tr("Serveur Mealie injoignable : vérifiez l'adresse et le Wi-Fi.")
            }
            main.post { done(error) }
            changed()
            if (error == null) syncNow()
        }
    }

    /** Délie : le jeton du robot est retiré de Mealie (si joignable), les recettes Mealie et leurs photos partent du robot. */
    fun unlink() {
        val base = prefs.mealieUrl
        val token = prefs.mealieToken
        val id = prefs.mealieTokenId
        prefs.forgetMealie()
        app.log.write("mealie", "robot délié de Mealie")
        changed()
        exec.execute {
            if (id > 0 && base.isNotBlank()) try {
                MealieClient(base, token, transport).deleteToken(id)
            } catch (_: IOException) {
                // Hors ligne : le jeton reste dans Mealie, où on peut le supprimer (Profil › Jetons d'API).
            }
            writeIndex(emptyMap())
            repo.removeMealieExcept(emptySet())
            try {
                photos(download = false)
            } catch (_: Exception) {
            }
            changed()
        }
    }

    // ------------------------------------------------------------------ synchro

    /** Synchro (fil d'arrière-plan, bloquante) : appelée la nuit et par le bouton des Réglages. */
    fun syncNow(): MealieSync.Result? {
        if (!prefs.mealieLinked) return null
        if (!running.compareAndSet(false, true)) return null
        changed()
        try {
            val base = prefs.mealieUrl
            val r = try {
                MealieSync(MealieClient(base, prefs.mealieToken, transport), store).run { !prefs.mealieLinked || prefs.mealieUrl != base }
            } catch (e: Exception) {
                app.log.crash("mealie", e)
                MealieSync.Result(false, 0, 0, 0, tr("Mealie : erreur (%s)", e.message ?: e.toString()))
            }
            if (!prefs.mealieLinked) return r
            // Catalogue rechargé tout de suite : les photos à chercher se lisent dans l'index à jour.
            if (r.added + r.updated + r.removed > 0) repo.refresh()
            val photos = try {
                photos()
            } catch (e: Exception) {
                app.log.crash("mealie", e)
                0
            }
            if (photos > 0) repo.photosChanged()
            prefs.mealieLastMs = System.currentTimeMillis()
            prefs.mealieLastResult = SimpleDateFormat(tr("dd/MM à HH:mm"), I18n.locale).format(Date()) + " : " + r.message
            app.log.write("mealie", r.message)
            return r
        } finally {
            running.set(false)
            changed()
        }
    }

    /** Synchro en arrière-plan ; [done] reçoit le résultat sur le fil de l'interface (null : rien à faire). */
    fun sync(done: (MealieSync.Result?) -> Unit = {}) {
        exec.execute {
            val r = syncNow()
            main.post { done(r) }
        }
    }

    /**
     * Photos des recettes Mealie : la petite version du serveur, réduite à 800 px de large au plus,
     * une seule fois (une photo changée dans Mealie change de nom). Celles des recettes parties sont
     * effacées. Rend le nombre de photos reçues. [download] faux : seulement le ménage.
     */
    private fun photos(download: Boolean = true): Int {
        if (!repo.loaded) return 0
        val dir = repo.imagesDir
        val wanted = HashMap<String, String>()
        if (prefs.mealieLinked) for (e in repo.index.entries) {
            val uuid = MealieRecipes.photoUuid(e.image) ?: continue
            if (MealieRecipes.isMealie(e.id)) wanted[e.image] = uuid
        }
        var got = 0
        var failed = 0
        if (download && wanted.isNotEmpty()) {
            val client = MealieClient(prefs.mealieUrl, "", transport)
            for ((name, uuid) in wanted) {
                if (!prefs.mealieLinked) break
                if (File(dir, name).length() > 0) continue
                if (!savePhoto(client.photoUrl(uuid), File(dir, name))) {
                    if (++failed >= 3) break  // serveur injoignable : la prochaine synchro reprendra
                    continue
                }
                got++
                // Le catalogue garde en mémoire le contenu du dossier des photos : il apprend celle-ci.
                repo.photoSaved(name)
            }
        }
        dir.listFiles()?.forEach { f ->
            if (MealieRecipes.PHOTO_NAME.matches(f.name) && f.name !in wanted) {
                f.delete()
                repo.photoRemoved(f.name)
            }
        }
        if (got > 0) app.log.write("mealie", "$got photo(s) de recettes reçue(s)")
        return got
    }

    /** Photo enregistrée dans [dest] : telle quelle jusqu'à 800 px de large, réduite au-delà. Faux si illisible ou injoignable. */
    private fun savePhoto(url: String, dest: File): Boolean {
        val bytes = try {
            Http.bytes(url, maxBytes = 4 * 1024 * 1024)
        } catch (e: IOException) {
            return false
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return false
        val tmp = File(dest.parentFile, dest.name + ".part")
        if (bounds.outWidth <= MAX_WIDTH) {
            tmp.writeBytes(bytes)
        } else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
            if (bmp.width > MAX_WIDTH) {
                val scaled = Bitmap.createScaledBitmap(bmp, MAX_WIDTH, bmp.height * MAX_WIDTH / bmp.width, true)
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }
            @Suppress("DEPRECATION")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.WEBP, 82, it) }
            bmp.recycle()
        }
        if (tmp.renameTo(dest)) return true
        tmp.delete()
        return false
    }

    private fun readIndex(): Map<String, String> = try {
        if (!indexFile.exists()) emptyMap() else indexFile.readLines().mapNotNull { line ->
            val k = line.substringBefore('=', "")
            if (k.isEmpty()) null else k to line.substringAfter('=')
        }.toMap()
    } catch (e: IOException) {
        emptyMap()
    }

    private fun writeIndex(v: Map<String, String>) {
        try {
            val tmp = File(indexFile.parentFile, indexFile.name + ".part")
            tmp.writeText(v.entries.joinToString("\n") { "${it.key}=${it.value.replace('\n', ' ')}" })
            if (!tmp.renameTo(indexFile)) {
                indexFile.delete()
                tmp.renameTo(indexFile)
            }
        } catch (e: IOException) {
            app.log.write("mealie", "index non écrit : ${e.message}")
        }
    }

    companion object {
        private const val MAX_WIDTH = 800
    }
}
