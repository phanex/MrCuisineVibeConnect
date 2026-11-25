package app.toque.core

import app.toque.core.companion.Alerts
import app.toque.core.companion.CompanionHub
import app.toque.core.companion.CookSession
import app.toque.core.companion.JournalPage
import app.toque.core.companion.MemoryStore
import app.toque.core.companion.SystemCompanionClock
import app.toque.core.companion.TemperatureWatch
import app.toque.core.companion.WaitingFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** « Température atteinte » : la règle pure, puis le journal du hub et l'attente « quiet ». */
class TemperatureWatchTest {
    /** Niveau 13 = 100 °C, 9 = 80 °C, 17 = 120 °C (table Mcc). */
    private fun seq(w: TemperatureWatch, vararg temps: Int, level: Int = 13, job: Long = 1, eligible: Boolean = true, running: Boolean = true) =
        temps.map { w.update(job, eligible, running, level, true, it) }

    @Test fun risingAnnouncesOnceWithinTwoDegrees() {
        val w = TemperatureWatch()
        assertEquals(listOf(null, null, null, 100, null, null), seq(w, 22, 60, 97, 98, 100, 99))
        // Bol à 99 °C sans jamais atteindre 100 : annoncée quand même (tolérance 2 °C).
        assertEquals(listOf(null, null, 100), seq(TemperatureWatch(), 40, 90, 98))
    }

    @Test fun alreadyHotAtStartNothingToWaitFor() {
        assertTrue(seq(TemperatureWatch(), 99, 100, 100).all { it == null })
    }

    @Test fun sensorAtZeroAndNoSample() {
        val w = TemperatureWatch()
        // Sous 37 °C la base lit 0 : jamais « atteinte », même pour une cible de 37 °C.
        assertTrue(seq(w, 0, 0, 0, level = 1).all { it == null })
        assertEquals(37, w.update(1, true, true, 1, true, 37))
        // Sans relevé : rien ne se décide ni ne s'annonce.
        val n = TemperatureWatch()
        assertNull(n.update(1, true, true, 13, false, 0))
        assertNull(n.update(1, true, true, 13, false, 100))
        assertNull(n.update(1, true, true, 13, true, 50))
        assertEquals(100, n.update(1, true, true, 13, true, 99))
    }

    @Test fun targetRaisedArmsAgainTargetLoweredBelowTheBowlDoesNot() {
        val w = TemperatureWatch()
        assertEquals(listOf(null, 80), seq(w, 30, 79, level = 9))
        // Relevée à 100 °C, bol en dessous : de nouveau.
        assertEquals(listOf(null, null, 100), seq(w, 80, 90, 99, level = 13))
        // Abaissée à 80 °C : bol déjà au-dessus, rien (refroidissement), même en repassant par 80.
        assertTrue(seq(w, 99, 85, 79, level = 9).all { it == null })
        // Relevée à 100 °C alors qu'elle a déjà été annoncée : rien.
        assertTrue(seq(w, 80, 99, level = 13).all { it == null })
        // Abaissée en chauffe (bol encore en dessous) : annoncée à la nouvelle cible.
        val v = TemperatureWatch()
        assertEquals(listOf(null, null), seq(v, 30, 50))
        assertEquals(listOf(null, 80), seq(v, 60, 79, level = 9))
    }

    @Test fun pauseAndResume() {
        val w = TemperatureWatch()
        seq(w, 30, 60)
        // En pause : jamais, même si le bol passe la cible.
        assertTrue(seq(w, 99, running = false).all { it == null })
        // Reprise avec un bol déjà chaud : rien à attendre.
        assertTrue(seq(w, 99, 100).all { it == null })
        // Pause, le bol refroidit, reprise en dessous : de nouveau, si pas encore annoncée.
        val v = TemperatureWatch()
        seq(v, 30, 60)
        seq(v, 50, running = false)
        assertEquals(listOf(null, null, 100), seq(v, 50, 80, 99))
        // Déjà annoncée : une pause et une reprise froide ne la répètent pas.
        seq(v, 60, running = false)
        assertTrue(seq(v, 60, 99).all { it == null })
    }

    @Test fun notEligibleOrNotHeatingOrNewJob() {
        // Programme seul (écran Programmes) : jamais.
        assertTrue(seq(TemperatureWatch(), 30, 99, 120, level = 17, eligible = false).all { it == null })
        // Sans chauffe : jamais.
        assertTrue(seq(TemperatureWatch(), 30, 99, level = 0).all { it == null })
        // Nouveau programme : de nouveau armée.
        val w = TemperatureWatch()
        assertEquals(listOf(null, 100), seq(w, 30, 99))
        assertEquals(listOf(null, null, 100), seq(w, 20, 30, 99, job = 2))
    }

    // ------------------------------------------------------------------ hub

    private fun journal(f: HubFixture, token: String) = JournalPage.fromMap(f.json(f.req("GET", "/api/journal", token)))

    private fun heating(tempC: Int, level: Int = 13, kind: String = "MANUAL") =
        Snaps.running(kind = kind, tempC = tempC).copy(jobTempLevel = level, heaterOn = true)

    @Test fun manualCookingWritesOneLine() {
        val f = HubFixture()
        val token = f.pair()
        f.hub.updateMachine(Snaps.idle())
        for (t in listOf(22, 50, 90, 98, 100, 99)) f.hub.updateMachine(heating(t))
        val lines = journal(f, token).entries.filter { it.alert == Alerts.TEMP_REACHED }
        assertEquals(1, lines.size)
        assertEquals("temp_reached", lines[0].kind)
        assertTrue(lines[0].text, lines[0].text.contains("100"))
        assertTrue(lines[0].activityId > 0)
    }

    @Test fun standaloneProgramNeverButSteamStepInARecipeYes() {
        val f = HubFixture()
        val token = f.pair()
        f.hub.updateMachine(Snaps.idle())
        // Vapeur lancée seule depuis l'écran Programmes : rien.
        for (t in listOf(30, 80, 119)) f.hub.updateMachine(heating(t, 17, "STEAM").copy(jobLabel = "Vapeur", phaseCount = 2))
        f.hub.updateMachine(Snaps.idle())
        // Cuisson manuelle à plusieurs phases : rien non plus.
        for (t in listOf(30, 99)) f.hub.updateMachine(heating(t).copy(phaseCount = 2))
        f.hub.updateMachine(Snaps.idle())
        assertTrue(journal(f, token).entries.none { it.alert == Alerts.TEMP_REACHED })
        // Étape vapeur d'une recette guidée : oui, rattachée à l'étape.
        f.hub.updateSession(CookSession.of(CompanionData.veloute, 0, emptySet(), listOf(0), WaitingFor.NOTHING))
        for (t in listOf(30, 80, 119)) f.hub.updateMachine(heating(t, 17, "STEAM").copy(jobLabel = "Vapeur", phaseCount = 2))
        val lines = journal(f, token).entries.filter { it.alert == Alerts.TEMP_REACHED }
        assertEquals(1, lines.size)
        assertEquals(0, lines[0].stepIndex)
        assertTrue(lines[0].text.contains("120"))
    }

    @Test fun quietPollIgnoresTelemetryOnly() {
        val hub = CompanionHub(FakeHost(), SystemCompanionClock, MemoryStore(), Random(1))
        hub.updateMachine(Snaps.idle())
        val r0 = hub.currentRevision
        Thread.sleep(CompanionHub.TELEMETRY_MIN_MS + 50)
        // Le bol refroidit d'un degré, la balance bouge : télémesure seule.
        hub.updateMachine(Snaps.idle().copy(actualTempC = 21, weightG = 3))
        val r1 = hub.currentRevision
        assertTrue(r1 > r0)
        // Attente normale : répond tout de suite ; attente « quiet » : va jusqu'au bout.
        assertEquals(r1, timed(0, 150) { hub.awaitChange(r0, 2_000) })
        assertEquals(r1, timed(250, 1_500) { hub.awaitChange(r0, 300, quiet = true) })
        // Changement d'état (programme lancé) : répond aux deux.
        val t = Thread { Thread.sleep(200); hub.updateMachine(heating(70)) }
        t.start()
        val r2 = timed(150, 1_500) { hub.awaitChange(r1, 5_000, quiet = true) }
        t.join()
        assertTrue(r2 > r1)
        // Une attente « quiet » partie d'avant ce changement : tout de suite.
        assertEquals(r2, timed(0, 150) { hub.awaitChange(r0, 5_000, quiet = true) })
        // Inconnue ici (appli du robot redémarrée) : tout de suite.
        assertEquals(r2, timed(0, 150) { hub.awaitChange(r2 + 50, 5_000, quiet = true) })
        // Température qui monte (bol déjà chaud : rien de structurel) puis atteinte : la ligne du journal répond.
        Thread.sleep(CompanionHub.TELEMETRY_MIN_MS + 50)
        hub.updateMachine(heating(75))
        val r3 = hub.currentRevision
        assertEquals(r3, timed(250, 1_500) { hub.awaitChange(r3, 300, quiet = true) })
        val u = Thread { Thread.sleep(200); hub.updateMachine(heating(99)) }
        u.start()
        val r4 = timed(150, 1_500) { hub.awaitChange(r3, 5_000, quiet = true) }
        u.join()
        assertTrue(r4 > r3)
    }

    private fun timed(minMs: Long, maxMs: Long, block: () -> Long): Long {
        val t0 = System.nanoTime()
        val r = block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("$ms ms, attendu entre $minMs et $maxMs", ms in minMs..maxMs)
        return r
    }
}
