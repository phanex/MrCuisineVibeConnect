package app.toque.core

import app.toque.core.machine.DemoCodec
import app.toque.core.machine.JobState
import app.toque.core.machine.Programs
import app.toque.core.protocol.ConnectCodec
import app.toque.core.protocol.Direction
import app.toque.core.protocol.Mcc
import app.toque.core.protocol.Operation
import app.toque.core.protocol.SmartCodec
import app.toque.core.protocol.SmartProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Démo d'un autre robot : ses règles, la base simulée du Connect. */
class DemoCodecTest {

    private fun smartDemo(): Bench = Bench().also { it.c.onTransportUp("démo", DemoCodec(SmartCodec())) }.ready()

    @Test fun connectDemoKeepsTheConnectCodec() {
        assertSame(ConnectCodec, DemoCodec.forRobot(ConnectCodec))
        assertTrue(DemoCodec.forRobot(SmartCodec()) is DemoCodec)
    }

    @Test fun smartProgramRunsOnTheSimulatedBase() {
        val b = smartDemo()
        val v = b.c.startJob(Programs.smartProgram(SmartProgram.SOUS_VIDE, 20 * 60_000L, "Sous-vide", celsius = 60))
        assertTrue(v.message, v.ok)
        b.step(3_000)
        assertEquals(JobState.RUNNING.name, b.snap.jobState)
        val f = b.tx.last()
        assertEquals(Operation.STANDARD.wire, f[1])
        assertEquals(1, f[2])
        assertEquals(Mcc.levelForCelsius(60), f[3])
        assertEquals(Direction.COUNTER_CLOCKWISE.wire, f[4])
    }

    @Test fun smartManualFortyDegreesIsAccepted() {
        val b = smartDemo()
        val v = b.c.startJob(Programs.manual(1, Mcc.levelForCelsius(37), Direction.CLOCKWISE, 60_000, smartLevel = 2))
        assertTrue(v.message, v.ok)
        b.step(3_000)
        assertEquals(Mcc.levelForCelsius(37), b.tx.last()[3])
    }

    @Test fun connectDemoStillRefusesSmartSettings() {
        val b = Bench().ready()
        assertFalse(b.c.startJob(Programs.smartProgram(SmartProgram.MIXER, 30_000L, "Mixer")).ok)
        assertFalse(b.c.startJob(Programs.manual(1, Mcc.levelForCelsius(37), Direction.CLOCKWISE, 60_000, smartLevel = 2)).ok)
    }

    @Test fun smartRulesStillApplyInTheDemo() {
        val b = smartDemo()
        // Le Smart n'a pas la chauffe sans mélange : refusée aussi en démo.
        assertFalse(b.c.startJob(Programs.manual(0, Mcc.levelForCelsius(60), Direction.CLOCKWISE, 60_000)).ok)
    }
}
