package app.toque.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.toque.ToqueApp
import app.toque.core.time.KitchenTimer
import app.toque.core.time.KitchenTimerList
import java.io.File

/**
 * Minuteurs de cuisine du processus de l'interface : ils continuent d'un écran à l'autre, et quand
 * l'activité est recréée ou l'interface relancée (enregistrés à chaque changement). Ils ne touchent
 * jamais la machine.
 *
 * Horloge : le temps écoulé depuis le démarrage de la tablette (ne saute pas quand l'heure se règle
 * par le réseau). Après une coupure de courant, ce temps repart de zéro et on ne sait plus combien
 * de temps a passé : les minuteurs sont perdus, et l'écran le dit ([lost]).
 */
class Timers private constructor(val app: ToqueApp) {
    private val store = app.getSharedPreferences("toque-minuteurs", Context.MODE_PRIVATE)
    private val bootId = readBootId()
    private val list: KitchenTimerList
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()
    /** Minuteurs terminés dont la sonnerie est déjà partie. */
    private val announced = HashSet<Long>()
    /** Fin de la sonnerie en cours (0 : aucune). */
    private var ringUntil = 0L

    /** Minuteurs perdus dans un redémarrage du robot, à annoncer une fois. */
    var lost: List<KitchenTimer> = emptyList()

    /** Un minuteur vient de se terminer (l'écran affiche le message et sort de la veille). */
    var onRing: (() -> Unit)? = null

    init {
        val restored = KitchenTimerList.restore(store.getString(KEY, "").orEmpty(), bootId, now())
        list = restored.list
        lost = restored.lost
        if (lost.isNotEmpty()) save()
        schedule()
    }

    fun now(): Long = SystemClock.elapsedRealtime()

    /** En cours, celui qui finit le premier d'abord. */
    val running: List<KitchenTimer> get() = list.running(now())

    /** Terminés, en attente de « OK ». */
    val ringing: List<KitchenTimer> get() = list.ringing(now())

    /** La sonnerie joue encore (deux minutes au plus après la fin). */
    val alarmActive: Boolean get() = ringUntil > now() && ringing.isNotEmpty()

    fun canAdd(): Boolean = list.canAdd(now())

    fun start(label: String, ms: Long): KitchenTimer? {
        val t = list.add(label, ms, now()) ?: return null
        changed()
        return t
    }

    fun extend(id: Long, ms: Long) {
        if (list.extend(id, ms, now())) changed()
    }

    /** Annule un minuteur en cours. */
    fun cancel(id: Long) {
        if (list.remove(id)) changed()
    }

    /** « OK » : les minuteurs terminés s'effacent, la sonnerie s'arrête. */
    fun acknowledge() {
        val done = ringing
        if (done.isEmpty()) return
        for (t in done) {
            list.remove(t.id)
            announced -= t.id
        }
        ringUntil = 0L
        changed()
    }

    /** Appelé à chaque seconde tant qu'un minuteur existe, et à chaque changement. Renvoie de quoi se désabonner. */
    fun listen(l: () -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    private fun changed() {
        save()
        notifyListeners()
        schedule()
    }

    private fun notifyListeners() {
        for (l in ArrayList(listeners)) l()
    }

    private fun save() {
        store.edit().putString(KEY, list.encode(bootId, now())).apply()
    }

    private val tick = Runnable {
        check()
        notifyListeners()
        schedule()
    }

    /** Prochain passage : à la seconde, ou pile à la fin du prochain minuteur. */
    private fun schedule() {
        main.removeCallbacks(tick)
        if (list.all.isEmpty()) return
        val next = running.firstOrNull()?.remaining(now()) ?: 1_000L
        main.postDelayed(tick, minOf(1_000L, next).coerceAtLeast(20L))
        check()
    }

    private fun check() {
        val fresh = ringing.filter { it.id !in announced }
        if (fresh.isEmpty()) return
        for (t in fresh) announced += t.id
        ringUntil = now() + RING_MS
        ring()
        onRing?.invoke()
    }

    /** Sonnerie répétée jusqu'à « OK », deux minutes au plus ; muette si le son est coupé dans les réglages. */
    private val ringAgain = Runnable { ring() }

    private fun ring() {
        main.removeCallbacks(ringAgain)
        if (!alarmActive) return
        if (app.prefs.sound) Sounds.timerAlarm()
        main.postDelayed(ringAgain, Sounds.TIMER_ALARM_MS + 1_200L)
    }

    private fun shutdown() {
        main.removeCallbacks(tick)
        main.removeCallbacks(ringAgain)
        listeners.clear()
        onRing = null
    }

    companion object {
        private const val KEY = "timers"
        /** Sonnerie : deux minutes au plus, puis le message reste seul à l'écran. */
        const val RING_MS = 120_000L
        private var instance: Timers? = null

        fun of(c: Context): Timers {
            val app = ToqueApp.of(c)
            instance?.let { if (it.app === app) return it else it.shutdown() }
            return Timers(app).also { instance = it }
        }

        /** Relance de l'interface (essais) : les minuteurs sont relus depuis leur enregistrement. */
        fun reload(c: Context): Timers {
            instance?.shutdown()
            instance = null
            return of(c)
        }

        /** Identifiant de ce démarrage de la tablette (noyau Linux) ; vide s'il est illisible. */
        private fun readBootId(): String = try {
            File("/proc/sys/kernel/random/boot_id").readText().trim()
        } catch (_: Exception) {
            ""
        }
    }
}
