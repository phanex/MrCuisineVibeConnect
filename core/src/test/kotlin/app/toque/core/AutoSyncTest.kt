package app.toque.core

import app.toque.core.recipes.AutoSync
import app.toque.core.recipes.AutoSync.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Quand la synchro automatique des recettes part, et pourquoi elle attend. */
class AutoSyncTest {
    private val paris = TimeZone.getTimeZone("Europe/Paris")
    private val min = 60_000L
    private val hour = 60 * min

    private fun at(day: Int, h: Int, m: Int = 0, zone: TimeZone = paris): Long = Calendar.getInstance(zone).apply {
        clear()
        set(2026, Calendar.NOVEMBER, day, h, m, 0)
    }.timeInMillis

    private fun decide(
        now: Long,
        last: Long,
        clockSet: Boolean = true,
        enabled: Boolean = true,
        uptime: Long = 2 * hour,
        idle: Long = 30 * min,
        cooking: Boolean = false,
    ) = AutoSync.decide(now, paris, clockSet, enabled, last, uptime, idle, cooking)

    @Test fun lastSlot() {
        assertEquals(at(23, 3, 30), AutoSync.lastSlot(at(23, 3, 30), paris))
        assertEquals(at(23, 3, 30), AutoSync.lastSlot(at(23, 22, 15), paris))
        assertEquals(at(22, 3, 30), AutoSync.lastSlot(at(23, 3, 29), paris))
        // Passage à l'heure d'hiver (25 octobre 2026) : toujours 03 h 30 heure locale.
        val noon = Calendar.getInstance(paris).apply { clear(); set(2026, Calendar.OCTOBER, 25, 12, 0, 0) }.timeInMillis
        val c = Calendar.getInstance(paris).apply { timeInMillis = AutoSync.lastSlot(noon, paris) }
        assertEquals(25, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(3, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, c.get(Calendar.MINUTE))
    }

    @Test fun nightRunWhenTheRobotStaysOn() {
        assertEquals(Decision.RUN_NIGHT, decide(at(23, 3, 31), last = at(22, 3, 35)))
        // Déjà faite cette nuit : rien jusqu'à la prochaine.
        assertEquals(Decision.NOT_DUE, decide(at(23, 14), last = at(23, 3, 40)))
        assertEquals(Decision.NOT_DUE, decide(at(24, 3, 29), last = at(23, 3, 40)))
        assertEquals(Decision.RUN_NIGHT, decide(at(24, 3, 30), last = at(23, 3, 40)))
    }

    @Test fun missedNightCaughtUpWhenIdle() {
        // Robot éteint la nuit, rallumé le soir : la synchro de la nuit est rattrapée, robot inutilisé.
        val evening = at(23, 22, 15)
        assertEquals(Decision.RUN_CATCH_UP, decide(evening, last = at(21, 3, 30)))
        assertEquals(Decision.RUN_CATCH_UP, decide(evening, last = 0))
        // Mais pas juste après l'allumage, ni pendant une cuisson, ni pendant qu'on s'en sert.
        assertEquals(Decision.JUST_STARTED, decide(evening, last = 0, uptime = 61_000))
        assertEquals(Decision.JUST_STARTED, decide(evening, last = 0, uptime = AutoSync.MIN_UPTIME_MS - 1))
        assertEquals(Decision.COOKING, decide(evening, last = 0, cooking = true))
        assertEquals(Decision.COOKING, decide(evening, last = 0, cooking = true, idle = 2 * hour))
        assertEquals(Decision.IN_USE, decide(evening, last = 0, idle = 9 * min))
        // Toque pas à l'écran (appli d'origine) : on ne sait pas, on attend.
        assertEquals(Decision.IN_USE, decide(evening, last = 0, idle = -1))
        assertEquals(Decision.RUN_CATCH_UP, decide(evening, last = 0, uptime = AutoSync.MIN_UPTIME_MS, idle = AutoSync.MIN_IDLE_MS))
    }

    @Test fun neverWithAWrongClock() {
        // Le robot redémarre en 2010 après une coupure : rien tant que l'heure n'est pas réglée.
        val jan2010 = 1_262_304_000_000L
        assertEquals(Decision.CLOCK_UNSET, decide(jan2010, last = at(20, 3, 30), clockSet = false))
        assertEquals(Decision.CLOCK_UNSET, decide(at(23, 22, 16), last = 0, clockSet = false, uptime = 3 * hour))
        // Une synchro notée « dans le futur » (horloge fausse à ce moment-là) ne bloque pas la suivante.
        assertEquals(Decision.RUN_CATCH_UP, decide(at(23, 22, 16), last = at(30, 12)))
    }

    @Test fun offAndRecentManualSync() {
        assertEquals(Decision.OFF, decide(at(23, 3, 31), last = 0, enabled = false))
        // Synchro faite à la main à 1 h : celle de 3 h 30 attend six heures en tout.
        assertEquals(Decision.NOT_DUE, decide(at(23, 3, 31), last = at(23, 1)))
        assertEquals(Decision.RUN_CATCH_UP, decide(at(23, 7, 1), last = at(23, 1)))
    }

    @Test fun onlyRunDecisionsRun() {
        for (d in Decision.values()) assertEquals(d.name, d == Decision.RUN_NIGHT || d == Decision.RUN_CATCH_UP, d.run)
        assertTrue(Decision.values().all { it.reason.isNotBlank() })
        assertFalse(Decision.NOT_DUE.run)
    }
}
