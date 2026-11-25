package app.toque.core

import app.toque.core.companion.ApiError
import app.toque.core.companion.CookSession
import app.toque.core.companion.Permissions
import app.toque.core.companion.StateWire
import app.toque.core.companion.WaitingFor
import app.toque.core.machine.JobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/** POST /api/machine/clear : le bouton OK du robot depuis un téléphone. */
class CompanionClearTest {
    private class Setup {
        val f = HubFixture()
        val token = f.pair()
        val phone = f.hub.pairing.authenticate(token)!!

        init {
            f.hub.updateMachine(Snaps.idle())
            // Un « OK » accepté : la machine publie l'instantané sans le programme.
            f.host.afterClear = { f.hub.updateMachine(Snaps.idle()) }
        }

        fun ended(state: JobState, reason: String) = Snaps.running().copy(jobState = state.name, endReason = reason, remainingMs = 0)
        fun clear() = f.req("POST", "/api/machine/clear", token)
        fun state() = StateWire.fromMap(f.json(f.req("GET", "/api/state", token)))
    }

    @Test fun allowedOnlyWhileAnEndedJobIsShown() {
        val t = Setup()
        assertEquals("nothing_ended", t.state().can.clear.code)
        for (st in listOf(JobState.STARTING, JobState.RUNNING, JobState.STOPPING, JobState.PAUSED)) {
            t.f.hub.updateMachine(Snaps.running().copy(jobState = st.name))
            val p = t.state().can.clear
            assertFalse(st.name, p.allowed)
            assertEquals(st.name, "nothing_ended", p.code)
            assertTrue(p.reason.isNotEmpty())
        }
        t.f.hub.updateMachine(t.ended(JobState.FINISHED, "Terminé"))
        assertTrue(t.state().can.clear.allowed)
        t.f.hub.updateMachine(t.ended(JobState.ABORTED, "Arrêté"))
        assertTrue(t.state().can.clear.allowed)
        // Turbo seul : rien à fermer.
        t.f.hub.updateMachine(Snaps.idle().copy(turboActive = true))
        assertFalse(t.state().can.clear.allowed)
    }

    @Test fun okClearsTheEndedJobAndTheReplyAlreadyShowsIt() {
        val t = Setup()
        t.f.hub.updateMachine(t.ended(JobState.ABORTED, "Arrêté"))
        val seq = t.state().journalSeq
        val r = t.clear()
        assertEquals(200, r.status)
        assertEquals(listOf("clear"), t.f.host.calls)
        val revision = (t.f.json(r)["revision"] as Number).toLong()
        val st = t.state()
        assertEquals(revision, st.revision)
        assertNull(st.job)
        assertFalse(st.can.clear.allowed)
        // Pas de ligne de journal.
        assertEquals(seq, st.journalSeq)
    }

    @Test fun aFinishedJobIsClearedToo() {
        val t = Setup()
        t.f.hub.updateMachine(t.ended(JobState.FINISHED, "Terminé"))
        assertEquals(200, t.clear().status)
        assertNull(t.state().job)
    }

    @Test fun nothingEndedIsRefusedWithoutCallingTheRobot() {
        val t = Setup()
        var r = t.clear()
        assertEquals(409, r.status)
        assertEquals("nothing_ended", ApiError.fromMap(t.f.json(r)).code)
        t.f.hub.updateMachine(Snaps.running())
        r = t.clear()
        assertEquals(409, r.status)
        assertEquals("nothing_ended", ApiError.fromMap(t.f.json(r)).code)
        assertTrue(t.f.host.calls.isEmpty())
    }

    @Test fun aRobotRefusalComesBack() {
        val t = Setup()
        t.f.hub.updateMachine(t.ended(JobState.ABORTED, "Arrêté"))
        t.f.host.result = app.toque.core.companion.CommandResult.timeout()
        val r = t.clear()
        assertEquals(504, r.status)
        assertEquals("timeout", ApiError.fromMap(t.f.json(r)).code)
        assertTrue(t.state().can.clear.allowed)
    }

    @Test fun theRevisionMovesSoALongPollWakes() {
        val t = Setup()
        t.f.hub.updateMachine(t.ended(JobState.ABORTED, "Arrêté"))
        val since = t.f.hub.currentRevision
        val seen = AtomicLong(-1)
        val waiter = Thread { seen.set(t.f.hub.awaitChange(since, 5_000, quiet = true)) }
        waiter.start()
        Thread.sleep(100)
        assertEquals(200, t.clear().status)
        waiter.join(3_000)
        assertTrue(seen.get() > since)
    }

    @Test fun anyPhoneMayClearWhateverEndedDuringARecipeToo() {
        val t = Setup()
        val second = t.f.pair()
        t.f.hub.updateSession(CookSession.of(CompanionData.veloute, 1, setOf(0), waitingFor = WaitingFor.RESUME))
        t.f.hub.updateMachine(t.ended(JobState.ABORTED, "Pause trop longue : programme annulé"))
        val r = t.f.req("POST", "/api/machine/clear", second)
        assertEquals(200, r.status)
    }

    @Test fun withoutATokenNothingIsCleared() {
        val t = Setup()
        t.f.hub.updateMachine(t.ended(JobState.ABORTED, "Arrêté"))
        assertEquals(401, t.f.req("POST", "/api/machine/clear", null).status)
        assertTrue(t.f.host.calls.isEmpty())
    }

    @Test fun anOlderRobotSaysNothingAboutClear() {
        val old = Permissions.fromMap(mapOf("pause" to mapOf("allowed" to true), "start" to mapOf("allowed" to true)))
        assertFalse(old.clear.allowed)
        assertEquals("", old.clear.code)
        // Un état complet sans « can.clear » se lit aussi.
        val t = Setup()
        val m = LinkedHashMap(t.f.json(t.f.req("GET", "/api/state", t.token)))
        @Suppress("UNCHECKED_CAST")
        val can = LinkedHashMap(m["can"] as Map<String, Any?>)
        assertTrue(can.containsKey("clear"))
        can.remove("clear")
        m["can"] = can
        val st = StateWire.fromMap(m)
        assertFalse(st.can.clear.allowed)
        assertEquals("", st.can.clear.code)
    }
}
