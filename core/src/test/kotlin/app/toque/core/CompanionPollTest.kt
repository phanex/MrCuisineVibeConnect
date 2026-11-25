package app.toque.core

import app.toque.core.companion.CompanionApi
import app.toque.core.companion.CompanionHub
import app.toque.core.companion.HttpHandler
import app.toque.core.companion.HttpLimits
import app.toque.core.companion.HttpRequest
import app.toque.core.companion.HttpResponse
import app.toque.core.companion.HttpServer
import app.toque.core.companion.MemoryStore
import app.toque.core.companion.SystemCompanionClock
import app.toque.core.companion.client.CompanionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Une attente longue par téléphone (`replace=1`) ; après une coupure, la preuve est refaite avant le jeton. */
class CompanionPollTest {
    private val localhost = InetAddress.getByName("127.0.0.1")

    /** Une attente sur son fil : son résultat et sa durée. */
    private class Poll(block: () -> Long) {
        val result = AtomicLong(-1)
        val ms = AtomicLong(-1)
        private val t0 = System.nanoTime()
        val thread = Thread {
            result.set(block())
            ms.set((System.nanoTime() - t0) / 1_000_000)
        }.apply { isDaemon = true; start() }

        fun join(): Long {
            thread.join(10_000)
            return ms.get()
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            if (cond()) return
            Thread.sleep(20)
        }
        fail("attente trop longue : $what")
    }

    private fun hub() = CompanionHub(FakeHost(), SystemCompanionClock, MemoryStore(), Random(1)).also { it.updateMachine(Snaps.idle()) }

    @Test fun aSecondPollFromTheSamePhoneEndsTheFirstAtOnce() {
        val hub = hub()
        val rev = hub.currentRevision
        val first = Poll { hub.awaitChange(rev, 5_000, phoneId = "a1b2c3d4") }
        waitFor("première attente inscrite") { hub.pollCount == 1 }
        val other = Poll { hub.awaitChange(rev, 900, phoneId = "e5f6a7b8") }
        val second = Poll { hub.awaitChange(rev, 900, phoneId = "a1b2c3d4") }
        // La première rend la main aussitôt (même révision : rien de neuf), la place est libérée.
        val ms = first.join()
        assertTrue("$ms ms", ms in 0..800)
        assertEquals(rev, first.result.get())
        // La nouvelle et celle d'un autre téléphone vont à leur terme.
        assertTrue(second.join() >= 850)
        assertTrue(other.join() >= 850)
        assertEquals(0, hub.pollCount)
    }

    @Test fun withoutTheFlagPollsBehaveAsBefore() {
        // Ancienne appli du téléphone : pas de replace=1, deux attentes du même téléphone vont à leur terme.
        val hub = hub()
        val rev = hub.currentRevision
        val a = Poll { hub.awaitChange(rev, 700) }
        Thread.sleep(100)
        val b = Poll { hub.awaitChange(rev, 700) }
        // Et une attente « une seule » ne coupe pas celles sans le drapeau.
        val c = Poll { hub.awaitChange(rev, 300, phoneId = "a1b2c3d4") }
        assertTrue(a.join() >= 650)
        assertTrue(b.join() >= 650)
        assertTrue(c.join() >= 250)
        // Un changement répond à toutes, comme avant.
        val d = Poll { hub.awaitChange(rev, 5_000, phoneId = "a1b2c3d4") }
        val e = Poll { hub.awaitChange(rev, 5_000) }
        Thread.sleep(150)
        hub.updateRobot(app.toque.core.companion.RobotInfo(appVersion = "9"))
        assertTrue(d.join() < 1_000)
        assertTrue(e.join() < 1_000)
        assertTrue(d.result.get() > rev)
    }

    @Test fun quietStillIgnoresTelemetryWithTheFlag() {
        val hub = hub()
        val rev = hub.currentRevision
        val p = Poll { hub.awaitChange(rev, 1_200, quiet = true, phoneId = "a1b2c3d4") }
        Thread.sleep(CompanionHub.TELEMETRY_MIN_MS + 50)
        hub.updateMachine(Snaps.idle().copy(actualTempC = 23, weightG = 4))
        assertTrue(hub.currentRevision > rev)
        // Télémesure seule : l'attente « quiet » continue jusqu'à son terme.
        assertTrue(p.join() >= 1_100)
        // Un changement d'état y répond.
        val r1 = hub.currentRevision
        val q = Poll { hub.awaitChange(r1, 5_000, quiet = true, phoneId = "a1b2c3d4") }
        Thread.sleep(150)
        hub.updateMachine(Snaps.running())
        assertTrue(q.join() < 1_000)
        assertTrue(q.result.get() > r1)
    }

    @Test fun cancelledPollsFreeTheirPlaceOnTheRobot() {
        // Trois connexions au plus par adresse (l'attente coupée, la preuve refaite, la nouvelle attente) :
        // sans replace=1, les attentes coupées garderaient leur place et une relance serait refusée.
        val hub = CompanionHub(FakeHost(), store = MemoryStore(), limits = HttpLimits(maxPerClient = 3))
        hub.updateMachine(Snaps.idle())
        hub.start(0, localhost)
        try {
            val c = CompanionClient("127.0.0.1", hub.port)
            c.pairWithCode(hub.pairing.open().code)
            val rev = c.state().revision
            repeat(4) { i ->
                val p = Poll {
                    try {
                        c.state(rev, 60, replace = true).revision
                    } catch (_: IOException) {
                        -2L
                    }
                }
                waitFor("attente n° $i sur le robot") { hub.pollsTaken == i + 1L }
                c.cancelAll()
                assertTrue(p.join() < 2_000)
                assertEquals(-2L, p.result.get())
            }
            // La dernière attente coupée finit elle aussi dès la suivante ; une lecture passe.
            val last = Poll { c.state(rev, 60, replace = true).revision }
            waitFor("anciennes attentes terminées") { hub.pollsTaken == 5L && hub.pollCount == 1 }
            assertEquals(rev, c.state().revision)
            c.cancelAll()
            last.join()
        } finally {
            hub.stop()
        }
    }

    @Test fun aCutPollIsFollowedByANewProofBeforeTheToken() {
        val hub = CompanionHub(FakeHost(), store = MemoryStore())
        hub.updateMachine(Snaps.idle())
        val api = CompanionApi(hub)
        val proves = AtomicInteger()
        // Le serveur du robot, qui compte les preuves demandées.
        val server = HttpServer(object : HttpHandler {
            override fun handle(request: HttpRequest): HttpResponse {
                if (request.path == "/api/prove") proves.incrementAndGet()
                return api.handle(request)
            }
        }, 0, localhost, trusted = { hub.isTrusted(it) })
        server.start()
        try {
            val c = CompanionClient("127.0.0.1", server.boundPort)
            c.pairWithCode(hub.pairing.open().code)
            val rev = c.state().revision
            assertEquals(1, proves.get())
            // Réponses normales : pas de nouvelle preuve.
            c.state()
            assertEquals(1, proves.get())
            // Attente coupée, même exprès (retour à l'écran) : la preuve est refaite avant le jeton.
            val p = Poll { try { c.state(rev, 30, replace = true).revision } catch (_: IOException) { -2L } }
            waitFor("attente en cours") { hub.pollsTaken == 1L }
            c.cancelAll()
            p.join()
            assertEquals(-2L, p.result.get())
            c.state()
            assertEquals(2, proves.get())
        } finally {
            server.stop()
        }
    }
}
