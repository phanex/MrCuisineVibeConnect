package app.toque.core

import app.toque.core.machine.JobState
import app.toque.core.machine.LinkState
import app.toque.core.machine.PauseReason
import app.toque.core.machine.Snapshot
import app.toque.core.ring.KnobRing
import app.toque.core.ring.Rgb
import app.toque.core.ring.RingColour
import app.toque.core.ring.RingLeds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnobRingTest {
    private val ready = Snapshot(link = LinkState.READY, hasSample = true, actualTempC = 25)

    private fun job(state: JobState, pause: PauseReason? = null) =
        ready.copy(jobActive = true, jobKind = "MANUAL", jobState = state.name, pauseReason = pause?.name ?: "")

    // ------------------------------------------------------------------ choix de la couleur

    @Test fun problemIsRed() {
        assertEquals(RingColour.RED, RingColour.of(ready.copy(faultCode = "BOARD_ERROR", faultMessage = "x")))
        assertEquals(RingColour.RED, RingColour.of(job(JobState.PAUSED, PauseReason.SAFETY)))
    }

    @Test fun pausedIsOrange() {
        assertEquals(RingColour.ORANGE, RingColour.of(job(JobState.PAUSED, PauseReason.USER)))
        // Couvercle ouvert, ou bol retiré : la base voit les deux comme un couvercle ouvert.
        assertEquals(RingColour.ORANGE, RingColour.of(job(JobState.PAUSED, PauseReason.LID).copy(lidOpen = true)))
    }

    @Test fun yourTurnIsGreen() {
        assertEquals(RingColour.GREEN, RingColour.of(job(JobState.FINISHED)))
        assertEquals(RingColour.GREEN, RingColour.of(ready, cookWaiting = true))
        // Arrêté à la main, ou annulé après une pause trop longue : encore affiché, il attend « OK ».
        assertEquals(RingColour.GREEN, RingColour.of(job(JobState.ABORTED).copy(endReason = "Arrêté")))
        assertEquals(RingColour.GREEN, RingColour.of(job(JobState.ABORTED).copy(endReason = "Pause trop longue : programme annulé")))
    }

    @Test fun runningIsCyanColdOrHot() {
        for (st in listOf(JobState.STARTING, JobState.RUNNING, JobState.STOPPING)) {
            assertEquals(RingColour.CYAN, RingColour.of(job(st)))
            assertEquals(RingColour.CYAN, RingColour.of(job(st).copy(actualTempC = 95)))
        }
        assertEquals(RingColour.CYAN, RingColour.of(ready.copy(turboActive = true)))
    }

    @Test fun otherwiseWhite() {
        assertEquals(RingColour.WHITE, RingColour.of(Snapshot()))
        assertEquals(RingColour.WHITE, RingColour.of(ready))
        // « OK » appuyé : plus de programme à l'écran.
        assertEquals(RingColour.WHITE, RingColour.of(job(JobState.ABORTED).copy(jobActive = false, jobState = "")))
        // Bol chaud sans programme : l'anneau ne le montre pas (le bandeau, si).
        assertEquals(RingColour.WHITE, RingColour.of(ready.copy(actualTempC = 80)))
        assertTrue(ready.copy(actualTempC = 80).hot)
    }

    @Test fun priorityOrder() {
        // Défaut avant tout le reste.
        assertEquals(RingColour.RED, RingColour.of(job(JobState.PAUSED, PauseReason.USER).copy(faultCode = "X"), cookWaiting = true))
        assertEquals(RingColour.RED, RingColour.of(job(JobState.RUNNING).copy(faultCode = "X")))
        // Programme interrompu par une alerte : rouge tant qu'elle est là, vert une fois acquittée.
        assertEquals(RingColour.RED, RingColour.of(job(JobState.ABORTED).copy(faultCode = "X")))
        // Pause avant « à vous ».
        assertEquals(RingColour.ORANGE, RingColour.of(job(JobState.PAUSED, PauseReason.LID), cookWaiting = true))
        // « À vous » avant « en cours ».
        assertEquals(RingColour.GREEN, RingColour.of(job(JobState.RUNNING), cookWaiting = true))
        assertEquals(RingColour.GREEN, RingColour.of(ready.copy(turboActive = true), cookWaiting = true))
        assertEquals(RingColour.CYAN, RingColour.of(job(JobState.RUNNING), cookWaiting = false))
    }

    @Test fun coloursStayWithinTheScale() {
        for (c in RingColour.values()) c.rgb?.let { assertTrue("$c", it.valid) }
        assertEquals(Rgb(0, 79, 79), RingColour.CYAN.rgb)
        assertEquals(Rgb(0, 79, 0), RingColour.GREEN.rgb)
        assertEquals(Rgb(79, 30, 0), RingColour.ORANGE.rgb)
        assertEquals(Rgb(79, 0, 0), RingColour.RED.rgb)
        assertEquals(null, RingColour.WHITE.rgb)
    }

    // ------------------------------------------------------------------ écritures sur l'anneau

    private class FakeLeds(var current: Rgb? = Rgb(0, 0, 50), var writable: Boolean = true) : RingLeds {
        var note: Rgb? = null
        var reads = 0
        val writes = ArrayList<Rgb>()

        override fun read(): Rgb? {
            reads++
            return note ?: current
        }

        override fun write(colour: Rgb, restore: Rgb?): Boolean {
            if (!writable || current == null) return false
            assertTrue("au-delà de 79 : $colour", colour.valid)
            restore?.let { assertTrue("note au-delà de 79 : $it", it.valid) }
            writes += colour
            current = colour
            note = restore
            return true
        }
    }

    private val white = Rgb(0, 0, 50)
    private val cyan = RingColour.CYAN.rgb!!
    private val green = RingColour.GREEN.rgb!!
    private val red = RingColour.RED.rgb!!

    @Test fun writesOnlyOnChange() {
        val leds = FakeLeds()
        val ring = KnobRing(leds)
        repeat(10) { ring.show(RingColour.WHITE) }
        repeat(10) { ring.show(RingColour.CYAN) }
        repeat(10) { ring.show(RingColour.CYAN) }
        ring.show(RingColour.GREEN)
        ring.show(RingColour.GREEN)
        ring.show(RingColour.WHITE)
        assertEquals(listOf(white, cyan, green, white), leds.writes)
        // Blanc lu une seule fois.
        assertEquals(1, leds.reads)
    }

    @Test fun whiteIsTheRingsOwnAndNotedWhileColoured() {
        val own = Rgb(0, 0, 30)
        val leds = FakeLeds(current = own)
        val ring = KnobRing(leds)
        ring.show(RingColour.RED)
        assertEquals(own, leds.note)
        ring.show(RingColour.WHITE)
        assertEquals(listOf(red, own), leds.writes)
        assertEquals(null, leds.note)
    }

    @Test fun restartAfterCrashKeepsTheNotedWhite() {
        val leds = FakeLeds(current = cyan)
        leds.note = Rgb(0, 0, 50)
        val ring = KnobRing(leds)
        ring.show(RingColour.WHITE)
        assertEquals(listOf(white), leds.writes)
    }

    @Test fun whiteAboveTheScaleIsReplaced() {
        val logs = ArrayList<String>()
        val leds = FakeLeds(current = Rgb(0, 0, 200))
        val ring = KnobRing(leds) { logs += it }
        ring.show(RingColour.WHITE)
        assertEquals(listOf(Rgb.USUAL_WHITE), leds.writes)
        assertEquals(1, logs.size)
    }

    @Test fun releaseRestoresWhite() {
        val leds = FakeLeds()
        val ring = KnobRing(leds)
        ring.show(RingColour.CYAN)
        ring.release()
        assertEquals(listOf(cyan, white), leds.writes)
        assertEquals(null, leds.note)
        // Rien de plus si on relâche encore.
        ring.release()
        assertEquals(2, leds.writes.size)
    }

    @Test fun turningTheCodeOffRestoresWhiteThenLeavesTheRing() {
        val leds = FakeLeds()
        val ring = KnobRing(leds)
        ring.show(RingColour.GREEN)
        ring.show(null)
        ring.show(null)
        assertEquals(listOf(green, white), leds.writes)
        ring.show(RingColour.GREEN)
        assertEquals(listOf(green, white, green), leds.writes)
    }

    @Test fun standbyDimsThenWakeShowsTheCurrentColour() {
        val leds = FakeLeds()
        val ring = KnobRing(leds)
        ring.show(RingColour.GREEN)
        ring.standby(3)
        assertEquals(Rgb(0, 0, 3), leds.current)
        // Ce qui change pendant la veille n'est pas écrit…
        ring.show(RingColour.CYAN)
        ring.show(RingColour.WHITE)
        assertEquals(listOf(green, Rgb(0, 0, 3)), leds.writes)
        // … le réveil montre la couleur du moment.
        ring.wake()
        assertEquals(listOf(green, Rgb(0, 0, 3), white), leds.writes)
        ring.standby(0)
        ring.standby(0)
        ring.wake()
        ring.wake()
        assertEquals(listOf(green, Rgb(0, 0, 3), white, Rgb(0, 0, 0), white), leds.writes)
    }

    @Test fun standbyNoteIsTheWhite() {
        val leds = FakeLeds()
        val ring = KnobRing(leds)
        ring.show(RingColour.RED)
        ring.standby(3)
        // Toque arrêté pendant la veille : la note donne le blanc, pas le rouge.
        assertEquals(white, leds.note)
    }

    @Test fun standbyWithTheCodeOffIsAsBefore() {
        val leds = FakeLeds(current = Rgb(0, 0, 40))
        val ring = KnobRing(leds)
        ring.show(null)
        assertTrue(leds.writes.isEmpty())
        ring.standby(3)
        ring.wake()
        assertEquals(listOf(Rgb(0, 0, 3), Rgb(0, 0, 40)), leds.writes)
        assertEquals(null, leds.note)
    }

    @Test fun nightLevelNeverAboveTheScale() {
        val leds = FakeLeds()
        val ring = KnobRing(leds)
        ring.standby(500)
        assertEquals(Rgb(0, 0, 79), leds.current)
    }

    @Test fun missingLedsAreLoggedOnceAndLeftAlone() {
        val logs = ArrayList<String>()
        val leds = FakeLeds(current = null)
        val ring = KnobRing(leds) { logs += it }
        repeat(5) { ring.show(RingColour.CYAN); ring.show(RingColour.RED) }
        ring.standby(3)
        ring.wake()
        ring.release()
        assertTrue(leds.writes.isEmpty())
        assertEquals(1, logs.size)
        assertEquals(1, leds.reads)
        assertFalse(ring.available)
    }

    @Test fun refusedWriteIsLoggedOnce() {
        val logs = ArrayList<String>()
        val leds = FakeLeds(writable = false)
        val ring = KnobRing(leds) { logs += it }
        repeat(5) { ring.show(RingColour.CYAN); ring.show(RingColour.RED) }
        ring.release()
        assertEquals(1, logs.size)
        assertFalse(ring.available)
    }
}
