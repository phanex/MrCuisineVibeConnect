package app.toque.core

import app.toque.core.machine.EventType
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.MachineLoop
import app.toque.core.machine.SimBoard
import app.toque.core.machine.SimTransport
import app.toque.core.machine.MonotonicClock
import app.toque.core.machine.Transport
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le chien de garde de la boucle machine, avec des liaisons qui se bloquent vraiment (fils réels, délais courts). */
class MachineLoopWatchdogTest {
    /** Liaison qui ne répond à rien : `write` peut être bloqué, et `close` le débloque (comme une vraie fermeture). */
    private class FakeTransport(override val name: String = "fake") : Transport {
        val writers = CopyOnWriteArrayList<String>()
        val closed = AtomicBoolean(false)
        val writesDone = AtomicInteger()
        @Volatile var blockWrites = false
        /** Vrai : `close` ne débloque rien (liaison vraiment coincée). */
        @Volatile var closeIsUseless = false
        private val released = CountDownLatch(1)

        override fun open() {}

        override fun write(frame: ByteArray) {
            writers += Thread.currentThread().name
            if (blockWrites) {
                // Les écritures bloquées ne sortent que sur fermeture : IOException, comme un tube coupé.
                released.await()
                throw IOException("fermé pendant l'écriture")
            }
            writesDone.incrementAndGet()
        }

        override fun read(buffer: ByteArray): Int {
            Thread.sleep(20)
            return if (closed.get()) -1 else 0
        }

        override fun close() {
            closed.set(true)
            if (!closeIsUseless) released.countDown()
        }

        fun free() = released.countDown()
    }

    private val loops = ArrayList<MachineLoop>()
    private val transports = ArrayList<FakeTransport>()
    private val events = CopyOnWriteArrayList<MachineEvent>()
    private val logs = CopyOnWriteArrayList<String>()
    private val unrecoverable = AtomicInteger()

    @After fun stop() {
        for (t in transports) t.free()
        for (l in loops) l.shutdown()
    }

    private fun waitFor(ms: Long, ok: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (!ok() && System.currentTimeMillis() < end) Thread.sleep(10)
        return ok()
    }

    private fun loop(
        stallMs: Long = 300, fatalMs: Long = 1_500,
        factory: () -> Transport,
    ): MachineLoop = MachineLoop(
        transportFactory = factory,
        onEvent = { events += it },
        log = { logs += it },
        stallMs = stallMs, fatalStallMs = fatalMs, watchdogCheckMs = 50, guardMs = 100,
        onUnrecoverable = { unrecoverable.incrementAndGet() },
    ).also { loops += it }

    private fun stalledEvents() = events.filter { it.type == EventType.FAULT }

    /** Un blocage = une ligne de journal du chien de garde (les défauts du contrôleur s'ajoutent aux événements). */
    private fun stallLogs() = logs.filter { it.contains("ne répond plus depuis") }

    @Test
    fun aHealthyLoopIsLeftAlone() {
        val t = FakeTransport().also { transports += it }
        val l = loop { t }
        l.start()
        assertTrue(waitFor(2_000) { t.writesDone.get() > 10 })
        Thread.sleep(800)   // plusieurs fois le seuil
        assertTrue("aucun défaut attendu : ${events.map { it.message }}", stalledEvents().isEmpty())
        assertFalse(t.closed.get())
        assertEquals(0, unrecoverable.get())
        assertFalse(logs.any { it.contains("chien de garde") })
    }

    @Test
    fun aBlockedWriteIsReportedStoppedAndUnblockedByClosingTheLink() {
        val first = FakeTransport("premiere").also { transports += it }
        val second = FakeTransport("seconde").also { transports += it }
        val given = AtomicInteger()
        val l = loop { if (given.getAndIncrement() == 0) first else second }
        l.start()
        assertTrue(waitFor(2_000) { first.writesDone.get() > 3 })

        first.blockWrites = true   // la boucle se coince dans write()
        assertTrue("le blocage doit être signalé", waitFor(3_000) { stalledEvents().isNotEmpty() })
        assertTrue(stalledEvents().first().message.isNotBlank())
        assertTrue("la liaison doit être fermée pour débloquer l'écriture", waitFor(2_000) { first.closed.get() })
        // Le chien de garde a tenté d'écrire lui-même un STOP (depuis son fil jetable).
        assertTrue("STOP du chien de garde attendu : ${first.writers.distinct()}", first.writers.contains("toque-watchdog-stop"))

        // La boucle repart par le chemin habituel : écriture en échec, puis réouverture sur une nouvelle liaison.
        assertTrue("la liaison doit être rouverte", waitFor(6_000) { second.writesDone.get() > 3 })
        assertEquals("abandon inutile : la fermeture a suffi", 0, unrecoverable.get())
        assertTrue(logs.any { it.contains("est repartie") })
    }

    @Test
    fun aLoopThatNothingUnblocksIsGivenUpOnceAfterTheFatalDelay() {
        val t = FakeTransport().also { it.closeIsUseless = true; transports += it }
        val l = loop(fatalMs = 800) { t }
        l.start()
        assertTrue(waitFor(2_000) { t.writesDone.get() > 3 })

        t.blockWrites = true
        assertTrue("abandon attendu après le délai fatal", waitFor(4_000) { unrecoverable.get() > 0 })
        Thread.sleep(500)
        assertEquals("un seul abandon par blocage", 1, unrecoverable.get())
        assertEquals("un seul blocage signalé", 1, stallLogs().size)
        assertTrue(stalledEvents().isNotEmpty())
        assertTrue(t.closed.get())
    }

    @Test
    fun aStalledThreadThatIsNotTheTransportStillGetsStopFramesFromTheWatchdog() {
        val t = FakeTransport().also { transports += it }
        val l = loop(fatalMs = 800) { t }
        l.start()
        assertTrue(waitFor(2_000) { t.writesDone.get() > 3 })

        val gate = CountDownLatch(1)
        l.post { gate.await() }   // le fil machine se perd dans autre chose que la liaison
        try {
            assertTrue(waitFor(3_000) { stalledEvents().isNotEmpty() })
            assertTrue(waitFor(2_000) { t.writers.contains("toque-watchdog-stop") })
            assertTrue("abandon attendu", waitFor(4_000) { unrecoverable.get() > 0 })
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun aSlowLinkOpeningIsNotAStall() {
        val t = FakeTransport().also { transports += it }
        val openingFor = 900L   // trois fois le seuil (demande d'autorisation root, par exemple)
        val l = loop(stallMs = 300, fatalMs = 600) {
            Thread.sleep(openingFor)
            t
        }
        l.start()
        assertTrue(waitFor(4_000) { t.writesDone.get() > 3 })
        Thread.sleep(500)
        assertTrue("l'ouverture ne doit pas déclencher le chien de garde : ${logs}", stalledEvents().isEmpty())
        assertEquals(0, unrecoverable.get())
        assertFalse(t.closed.get())
    }

    @Test
    fun theLoopThatRecoversByItselfResetsTheWatchdog() {
        val t = FakeTransport().also { transports += it }
        val l = loop(stallMs = 300, fatalMs = 5_000) { t }
        l.start()
        assertTrue(waitFor(2_000) { t.writesDone.get() > 3 })

        val gate = CountDownLatch(1)
        l.post { gate.await() }
        assertTrue(waitFor(2_000) { stalledEvents().isNotEmpty() })
        gate.countDown()   // la boucle repart seule, avant le délai fatal
        assertTrue("retour à la normale journalisé", waitFor(3_000) { logs.any { it.contains("est repartie") } })
        assertEquals(0, unrecoverable.get())

        // Un second blocage est de nouveau traité (le chien de garde s'est réarmé).
        val gate2 = CountDownLatch(1)
        // La liaison a été fermée par le premier blocage : la boucle la rouvre (nouvelle instance non fournie ici),
        // donc on ne vérifie que le signalement.
        l.post { gate2.await() }
        assertTrue("second blocage signalé", waitFor(3_000) { stallLogs().size >= 2 })
        gate2.countDown()
    }

    @Test
    fun shuttingDownStopsTheWatchdogThread() {
        fun watchdogs() = Thread.getAllStackTraces().keys.count { it.name == "toque-watchdog" && it.isAlive }
        val before = watchdogs()
        val t = FakeTransport().also { transports += it }
        val l = loop { t }
        l.start()
        assertTrue(waitFor(1_000) { watchdogs() > before })
        l.shutdown()
        assertTrue("le fil du chien de garde doit se terminer", waitFor(2_000) { watchdogs() <= before })
        // Et il ne signale plus rien après l'arrêt.
        val n = events.size
        Thread.sleep(600)
        assertEquals(n, events.size)
    }

    @Test
    fun theRealSimulatedBoardRunsWithTheDefaultWatchdogUntouched() {
        val board = SimBoard(MonotonicClock)
        val l = MachineLoop(transportFactory = { SimTransport(board) }, onEvent = { events += it }, log = { logs += it })
        loops += l
        l.start()
        Thread.sleep(1_500)
        assertTrue(stalledEvents().isEmpty())
        assertFalse(logs.any { it.contains("chien de garde") })
    }
}
