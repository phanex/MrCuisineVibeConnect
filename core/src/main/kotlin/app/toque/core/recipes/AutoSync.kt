package app.toque.core.recipes

import java.util.Calendar
import java.util.TimeZone

/**
 * Quand lancer la synchro automatique des recettes Lidl. Une par nuit (03 h 30) ; si le robot était
 * éteint à cette heure-là, elle est rattrapée plus tard, quand il est resté inutilisé un moment.
 * Jamais avec une horloge pas encore réglée (le robot redémarre en 2010 après une coupure), jamais
 * dans les premières minutes après l'allumage, jamais pendant une cuisson.
 */
object AutoSync {
    const val NIGHT_HOUR = 3
    const val NIGHT_MINUTE = 30
    /** Pas dans les 10 premières minutes après l'allumage du robot. */
    const val MIN_UPTIME_MS = 10 * 60_000L
    /** Robot inutilisé depuis 10 minutes : ni geste, ni programme, ni couvercle, ni balance. */
    const val MIN_IDLE_MS = 10 * 60_000L
    /** Au moins 6 heures après la synchro précédente (une synchro faite à la main la veille au soir compte). */
    const val MIN_GAP_MS = 6 * 3600_000L
    /** Lancée dans les 3 heures qui suivent 03 h 30 : la synchro de la nuit ; plus tard : un rattrapage. */
    const val NIGHT_WINDOW_MS = 3 * 3600_000L

    enum class Decision(val run: Boolean, val reason: String) {
        RUN_NIGHT(true, "synchro de la nuit"),
        RUN_CATCH_UP(true, "rattrapage de la synchro de la nuit"),
        OFF(false, "désactivée"),
        NOT_DUE(false, "déjà faite"),
        CLOCK_UNSET(false, "horloge pas encore réglée"),
        JUST_STARTED(false, "robot allumé depuis moins de 10 min"),
        COOKING(false, "cuisson en cours"),
        IN_USE(false, "robot utilisé depuis moins de 10 min"),
    }

    /** Dernier passage à 03 h 30 (heure locale de [zone]) avant [now], ou à [now]. */
    fun lastSlot(now: Long, zone: TimeZone): Long {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, NIGHT_HOUR)
        c.set(Calendar.MINUTE, NIGHT_MINUTE)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis > now) c.add(Calendar.DAY_OF_MONTH, -1)
        return c.timeInMillis
    }

    /**
     * [now] : heure du robot ; [clockSet] : horloge plausible ; [lastSyncMs] : fin de la dernière synchro
     * (0 : jamais) ; [uptimeMs] : temps depuis l'allumage ; [idleMs] : temps sans aucune activité, -1 si
     * l'interface de Toque n'est pas à l'écran ; [cooking] : programme ou recette guidée en cours.
     */
    fun decide(
        now: Long,
        zone: TimeZone,
        clockSet: Boolean,
        enabled: Boolean,
        lastSyncMs: Long,
        uptimeMs: Long,
        idleMs: Long,
        cooking: Boolean,
    ): Decision {
        if (!enabled) return Decision.OFF
        if (!clockSet) return Decision.CLOCK_UNSET
        // Une synchro « dans le futur » date d'une horloge fausse : comme jamais faite.
        val last = if (lastSyncMs > now) 0L else lastSyncMs
        val slot = lastSlot(now, zone)
        if (last >= slot || now - last < MIN_GAP_MS) return Decision.NOT_DUE
        if (uptimeMs < MIN_UPTIME_MS) return Decision.JUST_STARTED
        if (cooking) return Decision.COOKING
        if (idleMs < MIN_IDLE_MS) return Decision.IN_USE
        return if (now - slot < NIGHT_WINDOW_MS) Decision.RUN_NIGHT else Decision.RUN_CATCH_UP
    }
}
