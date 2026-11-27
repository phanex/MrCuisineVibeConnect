package app.toque.core

import app.toque.core.machine.JobState
import app.toque.core.machine.LinkState
import app.toque.core.machine.PauseReason
import app.toque.core.machine.Snapshot
import app.toque.core.ring.KnobRing
import app.toque.core.ring.Rgb
import app.toque.core.ring.RingColour
import app.toque.core.ring.RingLeds
import app.toque.core.ring.RingLook
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

    @Test fun pausedIsYellow() {
        assertEquals(RingColour.YELLOW, RingColour.of(job(JobState.PAUSED, PauseReason.USER)))
        // Couvercle ouvert, ou bol retiré : la base voit les deux comme un couvercle ouvert.
        assertEquals(RingColour.YELLOW, RingColour.of(job(JobState.PAUSED, PauseReason.LID).copy(lidOpen = true)))
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
        assertEquals(RingColour.YELLOW, RingColour.of(job(JobState.PAUSED, PauseReason.LID), cookWaiting = true))
        // « À vous » avant « en cours ».
        assertEquals(RingColour.GREEN, RingColour.of(job(JobState.RUNNING), cookWaiting = true))
        assertEquals(RingColour.GREEN, RingColour.of(ready.copy(turboActive = true), cookWaiting = true))
        assertEquals(RingColour.CYAN, RingColour.of(job(JobState.RUNNING), cookWaiting = false))
    }

    @Test fun coloursStayWithinTheScale() {
        for (c in RingColour.values()) c.rgb?.let { assertTrue("$c", it.valid) }
        assertEquals(Rgb(0, 79, 79), RingColour.CYAN.rgb)
        assertEquals(Rgb(0, 79, 0), RingColour.GREEN.rgb)
        assertEquals(Rgb(79, 30, 0), RingColour.YELLOW.rgb)
        // La pause est du même jaune que celui qu'on peut choisir pour l'anneau.
        assertEquals(RingLook.Tint.YELLOW.full, RingColour.YELLOW.rgb)
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

    // ------------------------------------------------------------------ réglages de l'utilisateur

    @Test fun levelsGoFromTheNightLightToTheStrongest() {
        assertEquals(listOf(3, 10, 25, 50, 79), RingLook.LEVELS.toList())
        // Le niveau par défaut est le blanc d'origine du robot.
        assertEquals(Rgb.USUAL_WHITE, RingLook.rgb(RingLook.Tint.WHITE, RingLook.DEFAULT_LEVEL))
        assertEquals(Rgb(0, 0, 0), RingLook.rgb(RingLook.Tint.RED, 0))
        for (tint in RingLook.Tint.values()) for (level in 0..RingLook.MAX_LEVEL) {
            val c = RingLook.rgb(tint, level)
            assertTrue("$tint niveau $level : $c", c.valid)
            // Une couleur garde toutes ses LED au plus faible niveau (l'orange ne devient pas rouge).
            if (level > 0) {
                assertEquals("$tint niveau $level", tint.full.red > 0, c.red > 0)
                assertEquals("$tint niveau $level", tint.full.green > 0, c.green > 0)
                assertEquals("$tint niveau $level", tint.full.white > 0, c.white > 0)
            }
        }
        assertEquals(RingColour.CYAN.rgb, RingLook.rgb(RingLook.Tint.CYAN, 5))
        // Réglés à l'œil sur un vrai anneau : à ne pas changer sans le revoir sur un robot.
        assertEquals(Rgb(79, 12, 0), RingLook.Tint.ORANGE.full)
        assertEquals(Rgb(79, 30, 0), RingLook.Tint.YELLOW.full)
        assertEquals(Rgb(79, 0, 43), RingLook.Tint.PINK.full)
        assertEquals(RingLook.Tint.WHITE, RingLook.Tint.of("inconnue"))
        assertEquals(RingLook.Tint.PINK, RingLook.Tint.of("pink"))
    }

    @Test fun nothingSetLeavesTheRingAsItWas() {
        val s = RingLook.Settings()
        assertEquals(null, s.on)
        assertEquals(null, s.standby)
        // Code couleur coupé sans rien régler : pareil.
        assertEquals(null, RingLook.Settings(colourCode = false).on)
        assertEquals(null, RingLook.Settings(colourCode = false).standby)
    }

    @Test fun standbyIsNeverBrighterThanOn() {
        for (level in 0..RingLook.MAX_LEVEL) for (standby in 0..RingLook.MAX_LEVEL + 3) {
            val s = RingLook.Settings(level = level, standbyLevel = standby)
            assertTrue("allumé $level, veille $standby", s.sleepLevel <= s.onLevel)
            assertTrue("allumé $level, veille $standby", s.sleep.white <= (s.on ?: Rgb.USUAL_WHITE).white)
        }
        // Allumé baissé sous la veille : la veille suit.
        assertEquals(2, RingLook.Settings(level = 2, standbyLevel = 4).sleepLevel)
        assertEquals(Rgb(0, 0, 10), RingLook.Settings(level = 2, standbyLevel = 4).sleep)
        // Même chose qu'allumé : la veille ne touche pas l'anneau.
        assertEquals(null, RingLook.Settings(level = 2, standbyLevel = 4).standby)
        assertEquals(Rgb(0, 0, 3), RingLook.Settings(level = 2, standbyLevel = 1).standby)
        assertEquals(Rgb(0, 0, 0), RingLook.Settings(level = 2, standbyLevel = 0).standby)
    }

    @Test fun coloursOnlyCountWithTheColourCodeOff() {
        val on = RingLook.Settings(colourCode = true, level = 3, tint = RingLook.Tint.RED, standbyLevel = 1, standbyTint = RingLook.Tint.GREEN)
        assertEquals(Rgb(0, 0, 25), on.on)
        assertEquals(Rgb(0, 0, 3), on.standby)
        val off = on.copy(colourCode = false)
        assertEquals(Rgb(25, 0, 0), off.on)
        assertEquals(Rgb(0, 3, 0), off.standby)
        // Une couleur choisie sans toucher à la luminosité : au niveau d'origine.
        assertEquals(Rgb(50, 0, 0), RingLook.Settings(colourCode = false, tint = RingLook.Tint.RED).on)
        // Même niveau mais autre couleur en veille : la veille écrit.
        assertEquals(Rgb(0, 25, 0), off.copy(standbyLevel = 3).standby)
        assertEquals(null, off.copy(standbyLevel = 3, standbyTint = RingLook.Tint.RED).standby)
    }

    @Test fun theCustomColourIsTheUsersOwnMix() {
        val mine = Rgb(79, 38, 10)
        val night = Rgb(0, 40, 79)
        val s = RingLook.Settings(colourCode = false, level = 5, tint = RingLook.Tint.CUSTOM, standbyLevel = 3, standbyTint = RingLook.Tint.CUSTOM,
            custom = mine, standbyCustom = night)
        // Allumé et veille ont chacun leur mélange.
        assertEquals(mine, s.on)
        assertEquals(mine, s.onMix)
        assertEquals(night, s.sleepMix)
        // Les niveaux atténuent le mélange, sans perdre une LED.
        assertEquals(Rgb(0, 13, 25), s.standby)
        assertEquals(Rgb(3, 1, 1), s.copy(level = 1).on)
        // Même niveau mais autre mélange : la veille écrit ; même mélange : elle ne touche pas l'anneau.
        assertEquals(night, s.copy(standbyLevel = 5).standby)
        assertEquals(null, s.copy(standbyLevel = 5, standbyCustom = mine).standby)
        // Avec le code couleur, l'anneau au repos reste blanc.
        assertEquals(Rgb(0, 0, 79), s.copy(colourCode = true).on)
        // Rien de composé, ou un mélange inutilisable : du blanc en attendant.
        assertEquals(Rgb(0, 0, 79), s.copy(custom = null).on)
        assertEquals(Rgb(0, 0, 79), s.copy(custom = Rgb(0, 0, 0)).onMix)
        assertEquals(Rgb(0, 0, 79), s.copy(standbyCustom = Rgb(80, 0, 0)).sleepMix)
        // Le mélange personnalisé ne change rien aux couleurs prévues.
        assertEquals(RingLook.Tint.YELLOW.full, s.copy(tint = RingLook.Tint.YELLOW).onMix)

        // Gardé en texte.
        assertEquals(mine, RingLook.parseMix(mine.toString()))
        for (bad in listOf(null, "", "79 38", "a b c", "0 0 0", "80 0 0", "1 2 3 4")) assertEquals("« $bad »", null, RingLook.parseMix(bad))
    }

    @Test fun theChosenBrightnessReplacesTheWhiteOfTheColourCode() {
        val leds = FakeLeds()
        val ring = KnobRing(leds)
        ring.show(RingColour.WHITE)
        ring.idle(Rgb(0, 0, 10))
        assertEquals(listOf(white, Rgb(0, 0, 10)), leds.writes)
        // Le blanc du robot est noté : il revient si Toque s'arrête ou rend l'écran.
        assertEquals(white, leds.note)
        ring.show(RingColour.CYAN)
        ring.idle(Rgb(0, 0, 25))
        // Pendant un programme, le réglage attend : la couleur d'état reste, à pleine force.
        assertEquals(cyan, leds.current)
        ring.show(RingColour.WHITE)
        assertEquals(Rgb(0, 0, 25), leds.current)
        ring.idle(Rgb(0, 0, 25))
        assertEquals(4, leds.writes.size)
        // Retour au blanc du robot.
        ring.idle(null)
        assertEquals(white, leds.current)
        assertEquals(null, leds.note)
    }

    @Test fun withTheColourCodeOffTheChosenColourStays() {
        val leds = FakeLeds()
        val ring = KnobRing(leds)
        val pink = Rgb(25, 0, 25)
        ring.idle(pink)
        ring.show(null)
        ring.show(null)
        assertEquals(listOf(pink), leds.writes)
        assertEquals(white, leds.note)
        // Veille dans une autre couleur, puis réveil sur la couleur choisie.
        ring.standby(Rgb(3, 0, 0))
        assertEquals(white, leds.note)
        ring.idle(Rgb(0, 10, 0))
        assertEquals(Rgb(3, 0, 0), leds.current)
        ring.wake()
        assertEquals(Rgb(0, 10, 0), leds.current)
        // Code couleur remis : les états reprennent, le repos garde la luminosité réglée.
        ring.show(RingColour.GREEN)
        assertEquals(green, leds.current)
    }

    @Test fun releaseGivesTheRobotsWhiteBackOnce() {
        val leds = FakeLeds()
        val ring = KnobRing(leds)
        ring.idle(Rgb(0, 0, 10))
        ring.show(null)
        ring.release()
        assertEquals(listOf(Rgb(0, 0, 10), white), leds.writes)
        assertEquals(null, leds.note)
        ring.release()
        assertEquals(2, leds.writes.size)
        // Toque revient devant : le réglage est remis.
        ring.show(null)
        assertEquals(Rgb(0, 0, 10), leds.current)
        // Réglé sur le blanc du robot lui-même : rien à noter, rien à rendre.
        val same = FakeLeds()
        val ring2 = KnobRing(same)
        ring2.idle(white)
        ring2.show(RingColour.WHITE)
        ring2.release()
        assertEquals(listOf(white), same.writes)
    }

    @Test fun previewShowsAStandbyChoiceThenGoesBack() {
        val leds = FakeLeds()
        val ring = KnobRing(leds)
        ring.show(RingColour.WHITE)
        ring.preview(Rgb(0, 0, 3))
        assertEquals(Rgb(0, 0, 3), leds.current)
        assertEquals(white, leds.note)
        ring.preview(null)
        ring.preview(null)
        assertEquals(listOf(white, Rgb(0, 0, 3), white), leds.writes)
        assertEquals(null, leds.note)
        // Code couleur coupé, rien de réglé : l'aperçu rend l'anneau comme il était.
        val quiet = FakeLeds(current = Rgb(0, 0, 40))
        val ring2 = KnobRing(quiet)
        ring2.show(null)
        ring2.preview(Rgb(3, 0, 0))
        ring2.preview(null)
        assertEquals(listOf(Rgb(3, 0, 0), Rgb(0, 0, 40)), quiet.writes)
        // Un aperçu en cours quand la veille arrive : le réveil montre ce qu'il faut.
        ring.preview(Rgb(0, 0, 3))
        ring.standby(Rgb(0, 0, 0))
        ring.preview(null)
        assertEquals(Rgb(0, 0, 0), leds.current)
        ring.wake()
        assertEquals(white, leds.current)
    }

    @Test fun aSettingMadeDuringAPreviewDoesNotFlashOnTheRing() {
        val leds = FakeLeds()
        val ring = KnobRing(leds)
        ring.show(null)
        ring.idle(Rgb(25, 0, 0))
        // On compose la veille : sa couleur est tenue, puis une autre, sans repasser par « allumé ».
        ring.preview(Rgb(0, 79, 0))
        ring.preview(Rgb(0, 79, 79))
        // « Allumé » réglé pendant ce temps : retenu, pas écrit.
        ring.idle(Rgb(79, 0, 0))
        ring.preview(Rgb(79, 30, 0))
        assertEquals(listOf(Rgb(25, 0, 0), Rgb(0, 79, 0), Rgb(0, 79, 79), Rgb(79, 30, 0)), leds.writes)
        // Fin de l'aperçu : le dernier réglage d'« allumé », d'un seul coup.
        ring.preview(null)
        assertEquals(Rgb(79, 0, 0), leds.writes.last())
        assertEquals(5, leds.writes.size)
        // Éteint se montre aussi (veille « Éteinte »).
        ring.preview(Rgb(0, 0, 0))
        assertEquals(Rgb(0, 0, 0), leds.current)
        assertEquals(white, leds.note)
    }

    @Test fun anInvalidChoiceIsNeverWritten() {
        val leds = FakeLeds()
        val ring = KnobRing(leds)
        ring.show(RingColour.WHITE)
        ring.idle(Rgb(0, 0, 200))
        ring.preview(Rgb(200, 0, 0))
        ring.standby(Rgb(0, 80, 0))
        assertEquals(listOf(white), leds.writes)
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
