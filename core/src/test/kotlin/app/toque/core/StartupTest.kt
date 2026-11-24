package app.toque.core

import app.toque.core.machine.LinkState
import app.toque.core.machine.Snapshot
import app.toque.core.machine.Startup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Écran d'attente du démarrage : il reste le temps que la base soit prête, et ne cache jamais rien d'utile. */
class StartupTest {
    private val opened = Snapshot(transportName = "Base moteur (/dev/ttyMT0)", transportUp = true)

    @Test fun waitsWhileTheServiceOpensTheLink() {
        // Le service machine n'a encore rien envoyé : l'état de l'interface est celui par défaut.
        assertTrue(Startup.waiting(Snapshot(), heard = false, elapsedMs = 0))
        assertTrue(Startup.waiting(Snapshot(), heard = false, elapsedMs = 20_000))
    }

    @Test fun waitsUntilTheBaseIsReady() {
        // Liaison ouverte, pas encore de réponse, puis « Connexion… », puis « Base prête ».
        assertTrue(Startup.waiting(opened, heard = true, elapsedMs = 9_000))
        assertTrue(Startup.waiting(opened.copy(link = LinkState.ARMING, hasSample = true), heard = true, elapsedMs = 9_300))
        assertFalse(Startup.waiting(opened.copy(link = LinkState.READY, hasSample = true), heard = true, elapsedMs = 9_600))
    }

    @Test fun aRefusedLinkIsShownAtOnce() {
        // Root refusé, port série pris, jumeau injoignable : la liaison n'est pas ouverte.
        val refused = Snapshot(transportName = "Base moteur (/dev/ttyMT0)")
        assertFalse(Startup.waiting(refused, heard = true, elapsedMs = 1_000))
        // Liaison ouverte puis perdue.
        assertFalse(Startup.waiting(opened.copy(link = LinkState.LOST), heard = true, elapsedMs = 5_000))
    }

    @Test fun neverHidesAnAlertOrARunningProgram() {
        // Interface relancée seule pendant une cuisson : le service machine, lui, n'a pas cessé de tourner.
        val cooking = opened.copy(link = LinkState.READY, hasSample = true, jobActive = true, jobState = "RUNNING")
        assertFalse(Startup.waiting(cooking, heard = true, elapsedMs = 0))
        assertFalse(Startup.waiting(opened.copy(faultCode = "BOARD_ERROR", faultMessage = "E12"), heard = true, elapsedMs = 0))
        assertFalse(Startup.waiting(opened.copy(link = LinkState.ARMING, jobActive = true, jobState = "PAUSED"), heard = true, elapsedMs = 0))
        assertFalse(Startup.waiting(opened.copy(link = LinkState.ARMING, turboActive = true), heard = true, elapsedMs = 0))
    }

    @Test fun givesUpAfterTheLongestWait() {
        assertTrue(Startup.waiting(Snapshot(), heard = false, elapsedMs = Startup.MAX_WAIT_MS - 1))
        assertFalse(Startup.waiting(Snapshot(), heard = false, elapsedMs = Startup.MAX_WAIT_MS))
        assertFalse(Startup.waiting(opened, heard = true, elapsedMs = Startup.MAX_WAIT_MS + 5_000))
    }
}
