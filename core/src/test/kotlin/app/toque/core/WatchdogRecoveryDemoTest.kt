package app.toque.core

import app.toque.core.protocol.Direction
import app.toque.core.machine.MachineLoop
import app.toque.core.machine.MonotonicClock
import app.toque.core.machine.Programs
import app.toque.core.machine.SimBoard
import app.toque.core.machine.SimTransport
import app.toque.core.machine.Transport
import app.toque.core.protocol.BoardCodec
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Après un blocage que le chien de garde rattrape sans redémarrer le processus, un programme doit
 * encore pouvoir tourner. Base simulée du projet (SimBoard), fils réels, délais courts.
 * (Test proposé par badrbelhaj1 sur la PR #65.)
 */
class WatchdogRecoveryDemoTest {
    /** Base simulée dont l'écriture peut se bloquer ; la fermeture la débloque (IOException). Garde les trames écrites. */
    private class BlockableSim(private val inner: SimTransport) : Transport {
        override val name: String get() = inner.name
        override val codec: BoardCodec get() = inner.codec
        val frames = CopyOnWriteArrayList<ByteArray>()
        @Volatile var blockWrites = false
        private val released = CountDownLatch(1)

        override fun open() = inner.open()

        override fun write(frame: ByteArray) {
            if (blockWrites) {
                released.await()
                throw IOException("fermé pendant l'écriture")
            }
            frames += frame.copyOf()
            inner.write(frame)
        }

        override fun read(buffer: ByteArray): Int = inner.read(buffer)

        override fun close() {
            released.countDown()
            inner.close()
        }
    }

    private val made = CopyOnWriteArrayList<BlockableSim>()
    private val logs = CopyOnWriteArrayList<String>()
    private var loop: MachineLoop? = null

    @After fun stop() {
        for (t in made) t.close()
        loop?.shutdown()
    }

    private fun waitFor(ms: Long, ok: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (!ok() && System.currentTimeMillis() < end) Thread.sleep(20)
        return ok()
    }

    private fun newLoop(): MachineLoop {
        val l = MachineLoop(
            transportFactory = { BlockableSim(SimTransport(SimBoard(MonotonicClock))).also { made += it } },
            log = { logs += it },
            stallMs = 300, fatalStallMs = 20_000, watchdogCheckMs = 50, guardMs = 100,
        )
        loop = l
        l.start()
        assertTrue("liaison prête", waitFor(8_000) { l.call { it.snapshot().ready } })
        return l
    }

    /** Lance une cuisson manuelle vitesse 2 et dit si la base reçoit autre chose que des trames à vitesse 0. */
    private fun aJobRuns(afterARecoveredStall: Boolean): Boolean {
        val l = newLoop()
        if (afterARecoveredStall) {
            made[0].blockWrites = true
            assertTrue("blocage vu : $logs", waitFor(5_000) { logs.any { it.contains("ne répond plus depuis") } })
            assertTrue("boucle repartie : $logs", waitFor(8_000) { logs.any { it.contains("est repartie") } })
            assertTrue("pas d'abandon du processus", logs.none { it.contains("abandon") })
            assertTrue("liaison rouverte : $logs", waitFor(15_000) {
                made.size >= 2 && l.call { it.snapshot().link } == app.toque.core.machine.LinkState.READY
            })
            // L'alerte de l'écriture ratée s'acquitte, comme sur le robot (« Acquitter ») ; ensuite tout est prêt.
            val ack = l.call { it.acknowledgeFault() }
            assertTrue("acquittement accepté : ${ack.message}", ack.ok)
            assertTrue("prête après acquittement", waitFor(5_000) { l.call { it.snapshot().ready } })
        }
        val t = made.last()
        val verdict = l.call { it.startJob(Programs.manual(2, 0, Direction.CLOCKWISE, 10_000)) }
        assertTrue("démarrage accepté : ${verdict.message}", verdict.ok)
        val from = t.frames.size
        Thread.sleep(1_500)
        val sent = t.frames.drop(from)
        assertTrue("des trames partent toujours", sent.size > 5)
        // Octet 5 de la trame : la vitesse demandée (0 dans une trame STOP).
        return sent.any { it[5].toInt() != 0 }
    }

    /** Un programme en cours au moment du blocage ne repart jamais tout seul : défaut à acquitter, trames à vitesse 0. */
    @Test
    fun aJobRunningWhenTheLoopStallsDoesNotComeBackByItself() {
        val l = newLoop()
        assertTrue(l.call { it.startJob(Programs.manual(2, 0, Direction.CLOCKWISE, 60_000)) }.ok)
        assertTrue("le programme tourne", waitFor(3_000) { made[0].frames.any { it[5].toInt() != 0 } })
        made[0].blockWrites = true
        assertTrue(waitFor(5_000) { logs.any { it.contains("ne répond plus depuis") } })
        assertTrue(waitFor(15_000) { made.size >= 2 && l.call { it.snapshot().link } == app.toque.core.machine.LinkState.READY })
        Thread.sleep(1_500)
        val snap = l.call { it.snapshot() }
        assertTrue("défaut à acquitter : ${snap.faultMessage}", snap.hasFault)
        assertTrue("rien ne repart sur la nouvelle liaison", made.last().frames.isNotEmpty() && made.last().frames.all { it[5].toInt() == 0 })
    }

    /** Même chose quand le fil machine se perd ailleurs que dans la liaison, puis revient. */
    @Test
    fun aJobRunningWhenTheThreadStallsElsewhereDoesNotComeBackByItself() {
        val l = newLoop()
        assertTrue(l.call { it.startJob(Programs.manual(2, 0, Direction.CLOCKWISE, 60_000)) }.ok)
        assertTrue("le programme tourne", waitFor(3_000) { made[0].frames.any { it[5].toInt() != 0 } })
        l.post { Thread.sleep(1_200) }   // le fil machine ne fait plus rien pendant 1,2 s, puis revient
        assertTrue(waitFor(5_000) { logs.any { it.contains("ne répond plus depuis") } })
        assertTrue(waitFor(15_000) { made.size >= 2 && l.call { it.snapshot().link } == app.toque.core.machine.LinkState.READY })
        Thread.sleep(1_500)
        val snap = l.call { it.snapshot() }
        assertTrue("défaut à acquitter : ${snap.faultMessage}", snap.hasFault)
        assertTrue("rien ne repart sur la nouvelle liaison", made.last().frames.isNotEmpty() && made.last().frames.all { it[5].toInt() == 0 })
        assertTrue(l.call { it.acknowledgeFault() }.ok)
        assertTrue(waitFor(5_000) { l.call { it.snapshot().ready } })
        assertTrue(l.call { it.startJob(Programs.manual(2, 0, Direction.CLOCKWISE, 10_000)) }.ok)
        assertTrue("après acquittement, un programme repart", waitFor(3_000) { made.last().frames.any { it[5].toInt() != 0 } })
    }

    @Test
    fun withoutAStallAJobRuns() {
        assertTrue(aJobRuns(afterARecoveredStall = false))
    }

    @Test
    fun afterARecoveredStallAJobStillRuns() {
        assertTrue("après un blocage rattrapé, la base ne reçoit plus que des trames STOP", aJobRuns(afterARecoveredStall = true))
    }
}
