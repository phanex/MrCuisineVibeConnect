package app.toque.core

import app.toque.core.machine.ControllerConfig
import app.toque.core.machine.EventType
import app.toque.core.machine.FaultCode
import app.toque.core.machine.JobState
import app.toque.core.machine.LinkState
import app.toque.core.machine.MachineController
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.MachineLoop
import app.toque.core.machine.Programs
import app.toque.core.machine.Transport
import app.toque.core.protocol.BoardCodec
import app.toque.core.protocol.Direction
import app.toque.core.protocol.Mcc
import app.toque.core.protocol.Smart
import app.toque.core.protocol.SmartCodec
import app.toque.core.protocol.SmartStreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Carte du Smart simulée, d'après les captures : répond à chaque commande avec la même
 * séquence, renvoie en écho la vitesse, le niveau et la fonction, ne décompte rien.
 */
class SmartSimBoard {
    private val parser = SmartStreamParser(Smart.TX_SIZE)
    var lidOpen = false
    var bowlPresent = true
    var weight = 0
    var temperature = 0
    var errorCode = 0
    /** Octet 16 : code retour (3 = la base refuse de tourner). */
    var returnCode = 0
    /** La base répond sans reprendre la commande (écho d'un arrêt). */
    var ignoresCommands = false
    /** Décalage des séquences répondues (réponses à des commandes que Toque n'a pas envoyées). */
    var seqShift = 0
    var silent = false
    /** Bit 5 de l'octet d'état : « température atteinte ». */
    var aboveTarget = false
    var tareOffset = 0
    val commands = ArrayList<Smart.Command>()

    fun feed(bytes: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        // Turbo : trame courte (35 octets), réponse avec la touche turbo [27] = 10 et la fonction C6
        if (Smart.isTurbo(bytes)) {
            val c = decodeAnyTx(bytes)
            commands += c
            if (!silent) out += reply(c).also {
                it[25] = 0; it[27] = Smart.TURBO_MOTOR_VALUE.toByte()
                it[Smart.RX_L] = Smart.checksum(it, Smart.RX_L).toByte()
            }
            return out
        }
        for (f in parser.feed(bytes, bytes.size, ArrayList())) {
            val c = Smart.decodeTx(f)
            commands += c
            if (c.tare) tareOffset = weight
            if (!silent) out += reply(c)
        }
        return out
    }

    private fun reply(cmd: Smart.Command): ByteArray {
        val c = if (!ignoresCommands) cmd else Smart.Command(
            seq = cmd.seq, cmd = cmd.cmd, temperatureLevel = 0, speedHalfSteps = 0, reverse = false, function = 0,
            tare = false, hours = 0, minutes = 0, seconds = 0, unexpectedBytes = emptyList(),
        )
        val r = ByteArray(Smart.RX_SIZE)
        r[0] = 0xAA.toByte(); r[1] = 0; r[2] = Smart.RX_L.toByte()
        r[3] = 0x01; r[4] = 0x0F; r[7] = (c.seq + seqShift).toByte(); r[8] = 0x08; r[9] = c.cmd.toByte()
        r[10] = 0x0C; r[11] = 0x12
        var st = 0
        if (lidOpen) st = st or 0x80
        if (!bowlPresent) st = st or 0x40
        if (aboveTarget) st = st or 0x20
        r[12] = st.toByte(); r[15] = errorCode.toByte(); r[Smart.RX_RETURN_CODE] = returnCode.toByte()
        r[17] = c.temperatureLevel.toByte()
        r[20] = (temperature shr 8).toByte(); r[21] = temperature.toByte()
        r[25] = c.speedHalfSteps.toByte(); r[26] = (if (c.reverse) 1 else 0).toByte(); r[28] = c.function.toByte()
        val w = weight - tareOffset
        r[30] = (w shr 24).toByte(); r[31] = (w shr 16).toByte(); r[32] = (w shr 8).toByte(); r[33] = w.toByte()
        r[39] = c.hours.toByte(); r[40] = c.minutes.toByte(); r[41] = c.seconds.toByte()
        r[Smart.RX_L] = Smart.checksum(r, Smart.RX_L).toByte()
        return r
    }
}

/** Décode une trame émise, y compris la trame Turbo (vitesse 10 = 20 demi-pas, fonction C6). */
fun decodeAnyTx(f: ByteArray): Smart.Command =
    if (Smart.isTurbo(f)) Smart.Command(
        seq = f[7].toInt() and 0xFF, cmd = Smart.CMD_SET, temperatureLevel = 0, speedHalfSteps = 20, reverse = false,
        function = Smart.FN_TURBO, tare = false, hours = 0, minutes = 0, seconds = Smart.TURBO_SECONDS,
        unexpectedBytes = emptyList(),
    ) else Smart.decodeTx(f)

/** Banc : contrôleur + codec Smart + carte simulée, cadencés à 200 ms comme l'appli d'origine. */
class SmartBench(config: ControllerConfig = ControllerConfig()) {
    val clock = FakeClock()
    val board = SmartSimBoard()
    val codec = SmartCodec()
    val c = MachineController(clock, config)
    val events = ArrayList<MachineEvent>()
    val tx = ArrayList<Pair<Long, Smart.Command>>()
    /** Trames émises telles quelles (octets). */
    val raw = ArrayList<ByteArray>()

    init {
        c.listener = { events += it }
        c.onTransportUp("banc Smart", codec)
    }

    fun step(ms: Long = 200) {
        var left = ms
        while (left > 0) {
            clock.now += 200
            left -= 200
            val frame = c.tick()
            raw += frame.copyOf()
            tx += (clock.now - 1_000_000L) to decodeAnyTx(frame)
            for (r in board.feed(frame)) c.onBytes(r)
        }
    }

    fun ready(): SmartBench {
        step(1_000)
        assertEquals(LinkState.READY, c.link)
        return this
    }

    val snap get() = c.snapshot()
    fun last() = tx.last().second
}

class SmartControllerTest {

    @Test fun readOnlyIdleSendsOnlyStopFrames() {
        val b = SmartBench().ready()
        b.board.weight = 1035
        b.board.lidOpen = true
        b.step(2_000)
        assertTrue(b.tx.all { it.second.function == 0 && it.second.speedHalfSteps == 0 && it.second.temperatureLevel == 0 })
        assertEquals(1035, b.snap.weightG)
        assertTrue(b.snap.lidOpen)
        assertTrue(b.snap.bowlDetected)
        assertEquals(3090, b.snap.firmware)
        // au repos, chaque trame STOP a une nouvelle séquence
        val seqs = b.tx.map { it.second.seq }
        assertEquals(seqs.size, seqs.toSet().size)
    }

    @Test fun manualSpeedOneTenSecondsRepeatsThenStops() {
        val b = SmartBench().ready()
        val v = b.c.startJob(Programs.manual(1, 0, Direction.CLOCKWISE, 10_000))
        assertTrue(v.message, v.ok)
        b.step(12_000)
        val active = b.tx.filter { it.second.function == Smart.FN_MANUAL }
        // 10 s à 200 ms = 50 trames actives (± une)
        assertTrue("trames actives : ${active.size}", active.size in 49..51)
        assertTrue(active.all { it.second.speedHalfSteps == 2 && it.second.temperatureLevel == 0 && !it.second.reverse })
        assertTrue(active.all { it.second.seconds == 10 && it.second.minutes == 0 })
        assertEquals("même trame répétée pendant le programme", 1, active.map { it.second.seq }.toSet().size)
        assertEquals(0, b.last().function)
        b.step(3_000)
        assertEquals(JobState.FINISHED.name, b.snap.jobState)
        assertTrue(b.events.any { it.type == EventType.JOB_FINISHED })
        assertEquals(0, b.last().function)
    }

    @Test fun heat37OneMinuteUsesSmartLevelOne() {
        val b = SmartBench().ready()
        assertTrue(b.c.startJob(Programs.manual(1, Mcc.levelForCelsius(37), Direction.CLOCKWISE, 60_000)).ok)
        b.step(5_000)
        val c = b.last()
        assertEquals(1, c.function)
        assertEquals(1, c.temperatureLevel)
        assertEquals(2, c.speedHalfSteps)
        assertEquals(1, c.minutes)
        assertTrue(b.snap.heaterOn)
        assertEquals(37, b.snap.targetTempC)
        assertEquals(1, b.snap.speedLevel)
    }

    @Test fun lidOpeningStopsImmediately() {
        val b = SmartBench().ready()
        b.c.startJob(Programs.manual(1, 0, Direction.CLOCKWISE, 60_000))
        b.step(2_000)
        assertEquals(1, b.last().function)
        b.board.lidOpen = true
        b.step(400)
        assertEquals(JobState.PAUSED.name, b.snap.jobState)
        assertEquals(0, b.last().function)
        b.board.lidOpen = false
        b.step(2_000)
        assertEquals("rien ne redémarre tout seul", 0, b.last().function)
    }

    @Test fun refusesToStartWithLidOpenOrBowlMissingWhenRequired() {
        val b = SmartBench(ControllerConfig(requireBowl = true)).ready()
        b.board.lidOpen = true
        b.board.bowlPresent = false
        b.step(400)
        val v = b.c.startJob(Programs.manual(1, 0, Direction.CLOCKWISE, 10_000))
        assertFalse(v.ok)
        assertTrue(v.has("lid"))
        assertTrue(v.has("bowl"))
    }

    /** Lance [spec], laisse tourner, et renvoie les trames actives (fonction non nulle). */
    private fun run(spec: app.toque.core.machine.JobSpec, ms: Long): Pair<SmartBench, List<Smart.Command>> {
        val b = SmartBench().ready()
        val v = b.c.startJob(spec)
        assertTrue(v.message, v.ok)
        b.step(ms)
        return b to b.tx.map { it.second }.filter { it.function != 0 }
    }

    @Test fun kneadIsSentLikeTheOriginalApp() {
        // vitesse 4 avec une température non mesurée : confirmation de mixage demandée d'abord
        assertTrue(SmartBench().ready().c.startJob(Programs.knead(45_000)).has(app.toque.core.machine.SafetyPolicy.HOT_BLEND))
        val (b, active) = run(Programs.knead(45_000).copy(hotBlendConfirmed = true), 50_000)
        assertTrue(active.size > 200)
        // une seule trame, inchangée (même séquence) : fonction 5, vitesse 4, sens normal, 45 s
        assertEquals(1, active.map { it.seq }.toSet().size)
        val c = active.first()
        assertEquals(listOf(Smart.FN_KNEAD, 8, 0, 0), listOf(c.function, c.speedHalfSteps, if (c.reverse) 1 else 0, c.temperatureLevel))
        assertEquals(45, c.seconds)
        assertEquals(0, b.last().function)
        assertEquals(JobState.FINISHED.name, b.snap.jobState)
    }

    @Test fun steamIsSentLikeTheOriginalApp() {
        val (_, active) = run(Programs.steam(60_000), 3_000)
        val c = active.first()
        assertTrue(active.all { it.seq == c.seq })
        assertEquals(listOf(Smart.FN_STEAM, 2, 0, Smart.LEVEL_120C), listOf(c.function, c.speedHalfSteps, if (c.reverse) 1 else 0, c.temperatureLevel))
        assertEquals(1, c.minutes)
    }

    @Test fun searIsSentLikeTheOriginalApp() {
        val (_, active) = run(Programs.toast(70_000), 3_000)
        val c = active.first()
        assertTrue(active.all { it.seq == c.seq })
        assertEquals(listOf(Smart.FN_TOAST, 2, 1, Smart.LEVEL_130C), listOf(c.function, c.speedHalfSteps, if (c.reverse) 1 else 0, c.temperatureLevel))
    }

    @Test fun programsWithOtherParametersAreRefusedBeforeStarting() {
        val b = SmartBench().ready()
        val odd = Programs.manual(1, 0, Direction.CLOCKWISE, 10_000).copy(
            kind = app.toque.core.machine.JobKind.TURBO,
            phases = listOf(app.toque.core.machine.Phase(app.toque.core.machine.Setting(app.toque.core.protocol.Operation.TURBO, 10, 0, Direction.COUNTER_CLOCKWISE), 10_000)),
        )
        assertFalse(b.c.startJob(odd).ok)
        b.step(1_000)
        assertTrue(b.tx.all { it.second.function == 0 })
    }

    @Test fun turboSendsTheFixedTurboFrameThenStop() {
        val b = SmartBench().ready()
        // température non mesurée : il faut d'abord confirmer que le contenu est froid
        val refused = b.c.turboPress()
        assertTrue(refused.has(app.toque.core.machine.SafetyPolicy.COLD_CONFIRM))
        assertEquals(1, refused.violations.size)
        assertTrue(b.c.confirmCold().ok)
        val v = b.c.turboPress()
        assertTrue(v.message, v.ok)
        b.step(1_000)
        val turbo = b.tx.map { it.second }.filter { it.function == Smart.FN_TURBO }
        assertTrue(turbo.size >= 4)
        assertEquals(1, turbo.map { it.seq }.toSet().size)
        assertTrue(b.snap.motorOn)
        b.c.turboRelease()
        b.step(400)
        assertEquals(0, b.last().function)
        // limité à 3 s par appui, même sans relâchement
        b.step(2_000)
        assertTrue(b.c.turboPress().ok)
        b.step(4_000)
        assertEquals(0, b.last().function)
        assertTrue(b.events.any { it.type == app.toque.core.machine.EventType.TURBO_END })
        // ouvrir le couvercle oublie la confirmation
        b.board.lidOpen = true
        b.step(400)
        b.board.lidOpen = false
        b.step(400)
        assertTrue(b.c.turboPress().has(app.toque.core.machine.SafetyPolicy.COLD_CONFIRM))
    }

    @Test fun linkLossClearsTheColdConfirmation() {
        val b = SmartBench().ready()
        b.c.turboPress()
        assertTrue(b.c.confirmCold().ok)
        // liaison coupée (port libéré), couvercle ouvert/fermé hors liaison, puis reprise
        b.c.onTransportDown("port libéré")
        b.c.onTransportUp("banc Smart", b.codec)
        b.step(1_000)
        assertEquals(LinkState.READY, b.c.link)
        assertTrue(b.c.turboPress().has(app.toque.core.machine.SafetyPolicy.COLD_CONFIRM))
    }

    @Test fun lidOpenedWhileTheDialogIsShownRefusesTheConfirmation() {
        val b = SmartBench().ready()
        assertTrue(b.c.turboPress().has(app.toque.core.machine.SafetyPolicy.COLD_CONFIRM))
        b.board.lidOpen = true
        b.step(400)
        assertFalse(b.c.confirmCold().ok)   // couvercle ouvert
        b.board.lidOpen = false
        b.step(400)
        assertFalse(b.c.confirmCold().ok)   // ouvert depuis le refus : il faut rappuyer sur Turbo
        assertTrue(b.c.turboPress().has(app.toque.core.machine.SafetyPolicy.COLD_CONFIRM))
        assertTrue(b.c.confirmCold().ok)
    }

    @Test fun coldConfirmationExpires() {
        val b = SmartBench().ready()
        b.c.turboPress()
        assertTrue(b.c.confirmCold().ok)
        b.step(app.toque.core.machine.Limits.COLD_CONFIRM_MS + 1_000)
        assertTrue(b.c.turboPress().has(app.toque.core.machine.SafetyPolicy.COLD_CONFIRM))
    }

    @Test fun turboRightAfterStoppingAReverseProgramWaitsForTheBlade() {
        val b = SmartBench().ready()
        b.c.turboPress()
        assertTrue(b.c.confirmCold().ok)
        assertTrue(b.c.startJob(Programs.manual(3, 0, Direction.COUNTER_CLOCKWISE, 60_000)).ok)
        b.step(3_000)
        b.c.stop()
        b.step(400)
        assertTrue(b.c.turboPress().has("moving"))
        b.step(2_400)
        val v = b.c.turboPress()
        assertTrue(v.message, v.ok)
    }

    @Test fun turboStaysForbiddenWhenAHotTemperatureIsMeasured() {
        val b = SmartBench().ready()
        b.board.temperature = 75
        b.step(400)
        assertFalse(b.c.confirmCold().ok)
        val v = b.c.turboPress()
        assertTrue(v.has("turbo_hot"))
        assertFalse(v.has(app.toque.core.machine.SafetyPolicy.COLD_CONFIRM))
    }

    @Test fun heatingClearsTheColdConfirmation() {
        val b = SmartBench().ready()
        assertFalse(b.c.confirmCold().ok)   // sans refus « contenu froid ? » préalable
        b.c.turboPress()
        assertTrue(b.c.confirmCold().ok)
        assertTrue(b.c.startJob(Programs.manual(1, 1, Direction.CLOCKWISE, 2_000)).ok)
        b.step(5_000)
        assertTrue(b.c.turboPress().has(app.toque.core.machine.SafetyPolicy.COLD_CONFIRM))
    }

    @Test fun silentBoardAbortsAndKeepsSendingStop() {
        val b = SmartBench().ready()
        b.c.startJob(Programs.manual(1, 0, Direction.CLOCKWISE, 60_000))
        b.step(2_000)
        b.board.silent = true
        b.step(2_000)
        assertEquals(FaultCode.COMM_LOST.name, b.snap.faultCode)
        assertEquals(JobState.ABORTED.name, b.snap.jobState)
        b.step(1_000)
        assertTrue(b.tx.takeLast(5).all { it.second.function == 0 })
    }

    @Test fun boardErrorAborts() {
        val b = SmartBench().ready()
        b.c.startJob(Programs.manual(1, 0, Direction.CLOCKWISE, 60_000))
        b.step(2_000)
        b.board.errorCode = 7
        b.step(400)
        assertEquals(FaultCode.BOARD_ERROR.name, b.snap.faultCode)
        assertEquals(0, b.last().function)
    }

    @Test fun userStopSendsStopOnNextFrame() {
        val b = SmartBench().ready()
        b.c.startJob(Programs.manual(2, 0, Direction.CLOCKWISE, 60_000))
        b.step(2_000)
        b.c.stop()
        b.step(200)
        assertEquals(0, b.last().function)
    }

    @Test fun tareIsSentOnlyAtRest() {
        val b = SmartBench().ready()
        b.board.weight = 500
        b.c.tare()
        b.step(200)
        assertTrue(b.last().tare)
        assertEquals(Smart.CMD_TARE, b.last().cmd)
        b.step(400)
        assertFalse(b.last().tare)
        assertEquals(0, b.snap.weightG)
        b.c.startJob(Programs.manual(1, 0, Direction.CLOCKWISE, 60_000))
        b.step(1_000)
        b.c.tare()
        b.step(1_000)
        assertTrue(b.tx.none { it.second.tare && it.second.function != 0 })
        assertEquals("la tare ne coupe pas le programme", 1, b.last().function)
    }

    @Test fun tareRightAfterStopWaitsForARealStopFrame() {
        val b = SmartBench().ready()
        b.c.startJob(Programs.manual(1, 0, Direction.CLOCKWISE, 60_000))
        b.step(2_000)
        b.c.stop()
        b.c.tare()
        b.step(200)
        assertEquals(Smart.CMD_SET, b.last().cmd)
        assertFalse(b.last().tare)
        assertEquals(0, b.last().function)
        b.step(200)
        assertTrue(b.last().tare)
    }

    @Test fun contentIsTreatedAsHotBecauseTheSmartTemperatureCanReadZero() {
        val b = SmartBench().ready()
        b.board.temperature = 0
        val v = b.c.startJob(Programs.manual(5, 0, Direction.CLOCKWISE, 10_000))
        assertTrue(v.has(app.toque.core.machine.SafetyPolicy.HOT_BLEND))
        assertTrue(b.c.startJob(Programs.manual(5, 0, Direction.CLOCKWISE, 10_000, hotBlendConfirmed = true)).ok)
        b.step(3_000)
        val speeds = b.tx.filter { it.second.function != 0 }.map { it.second.speedHalfSteps / 2 }
        assertEquals("montée progressive : $speeds", 1, speeds.first())
        assertTrue(speeds.zipWithNext().all { (x, y) -> y - x in 0..1 })
        assertEquals(5, speeds.last())
    }

    @Test fun bridgeStopFrameIsWrittenAsOctalEscapes() {
        val t = app.toque.core.machine.RootSerialTransport.forSmart()
        val frame = SmartCodec(5).stopFrame()
        val text = t.shellOctal(frame)
        assertEquals(frame.size * 4, text.length)
        val back = text.chunked(4).map { assertEquals('\\', it[0]); it.substring(1).toInt(8).toByte() }.toByteArray()
        assertTrue(back.contentEquals(frame))
    }

    @Test fun waitsForTheBladeBeforeRestarting() {
        val b = SmartBench().ready()
        b.c.startJob(Programs.manual(1, 0, Direction.CLOCKWISE, 3_000))
        b.step(6_000)
        assertEquals(JobState.FINISHED.name, b.snap.jobState)
        val lastActive = b.tx.last { it.second.function != 0 }.first
        b.c.clearFinished()
        assertTrue(b.c.startJob(Programs.manual(1, 0, Direction.CLOCKWISE, 3_000)).ok)
        b.step(4_000)
        val firstAgain = b.tx.first { it.first > lastActive + 200 && it.second.function != 0 }.first
        assertTrue("redémarrage après ${firstAgain - lastActive} ms", firstAgain - lastActive >= b.codec.stopSettleMs)
    }

    @Test fun loopShutdownSendsStopFrames() {
        val board = SmartSimBoard()
        val written = LinkedBlockingQueue<ByteArray>()
        val transport = object : Transport {
            override val name = "test"
            override val codec: BoardCodec = SmartCodec()
            private val rx = LinkedBlockingQueue<ByteArray>()
            @Volatile private var closed = false
            override fun open() {}
            override fun write(frame: ByteArray) {
                if (closed) throw IOException("fermé")
                written.put(frame)
                for (r in board.feed(frame)) rx.put(r)
            }
            override fun read(buffer: ByteArray): Int {
                while (!closed) {
                    val r = rx.poll(50, TimeUnit.MILLISECONDS) ?: continue
                    System.arraycopy(r, 0, buffer, 0, r.size)
                    return r.size
                }
                return -1
            }
            override fun close() { closed = true }
        }
        val loop = MachineLoop({ transport }, periodMs = 50)
        loop.start()
        try {
            val deadline = System.currentTimeMillis() + 5_000
            while (loop.call { it.link } != LinkState.READY && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue(loop.call { it.startJob(Programs.manual(1, 0, Direction.CLOCKWISE, 60_000)) }.ok)
            val until = System.currentTimeMillis() + 3_000
            while (board.commands.none { it.function == 1 } && System.currentTimeMillis() < until) Thread.sleep(50)
            assertTrue(board.commands.any { it.function == 1 })
        } finally {
            loop.shutdown()
        }
        val frames = ArrayList<ByteArray>()
        written.drainTo(frames)
        assertTrue(frames.takeLast(3).all { Smart.decodeTx(it).function == 0 })
    }

    @Test fun afterEmergencyStopTheLoopOnlySendsStop() {
        val board = SmartSimBoard()
        val written = LinkedBlockingQueue<ByteArray>()
        val transport = object : Transport {
            override val name = "test"
            override val codec: BoardCodec = SmartCodec()
            private val rx = LinkedBlockingQueue<ByteArray>()
            @Volatile private var closed = false
            override fun open() {}
            override fun write(frame: ByteArray) {
                written.put(frame)
                for (r in board.feed(frame)) rx.put(r)
            }
            override fun read(buffer: ByteArray): Int {
                while (!closed) {
                    val r = rx.poll(50, TimeUnit.MILLISECONDS) ?: continue
                    System.arraycopy(r, 0, buffer, 0, r.size)
                    return r.size
                }
                return -1
            }
            override fun close() { closed = true }
        }
        val loop = MachineLoop({ transport }, periodMs = 50)
        loop.start()
        try {
            val deadline = System.currentTimeMillis() + 5_000
            while (loop.call { it.link } != LinkState.READY && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue(loop.call { it.startJob(Programs.manual(1, 0, Direction.CLOCKWISE, 60_000)) }.ok)
            val until = System.currentTimeMillis() + 3_000
            while (board.commands.none { it.function == 1 } && System.currentTimeMillis() < until) Thread.sleep(50)
            assertTrue(board.commands.any { it.function == 1 })
            loop.emergencyStop()
            Thread.sleep(100)
            written.clear()
            Thread.sleep(500)
            val after = ArrayList<ByteArray>()
            written.drainTo(after)
            assertTrue(after.isNotEmpty())
            assertTrue(after.all { Smart.decodeTx(it).function == 0 })
        } finally {
            loop.shutdown()
        }
    }

    // ------------------------------------------------------------------ réponses vérifiées
    // Retour de Tidusneo24150 (issue #59) : code retour (octet 16) et échos, comme Camybrith Cooking.

    @Test fun boardRefusalStopsEverything() {
        val b = SmartBench().ready()
        assertTrue(b.c.startJob(Programs.manual(2, 0, Direction.CLOCKWISE, 60_000)).ok)
        b.step(1_000)
        assertEquals(Smart.FN_MANUAL, b.last().function)
        b.board.returnCode = 3
        b.step(400)
        assertEquals(FaultCode.BOARD_ERROR.name, b.snap.faultCode)
        assertTrue(b.snap.faultMessage, b.snap.faultMessage.contains("3"))
        assertEquals(JobState.ABORTED.name, b.snap.jobState)
        assertEquals(0, b.last().function)
        assertFalse("la cause est toujours là", b.c.acknowledgeFault().ok)
        b.board.returnCode = 0
        b.step(400)
        assertTrue(b.c.acknowledgeFault().ok)
        b.step(2_000)
        assertEquals("rien ne redémarre tout seul", 0, b.last().function)
    }

    @Test fun returnCodeWithLidOpenOrBowlMissingIsOnlyAPause() {
        val b = SmartBench().ready()
        assertTrue(b.c.startJob(Programs.manual(2, 0, Direction.CLOCKWISE, 60_000)).ok)
        b.step(1_000)
        b.board.lidOpen = true
        b.board.returnCode = 3
        b.step(600)
        assertEquals("", b.snap.faultCode)
        assertEquals(JobState.PAUSED.name, b.snap.jobState)
        b.board.lidOpen = false
        b.board.bowlPresent = false
        b.step(600)
        assertEquals("", b.snap.faultCode)
    }

    @Test fun boardThatDoesNotEchoTheCommandStopsEverything() {
        val b = SmartBench().ready()
        b.board.ignoresCommands = true
        b.step(1_000)
        assertEquals("au repos, l'écho d'un arrêt est le bon", "", b.snap.faultCode)
        assertTrue(b.c.startJob(Programs.manual(3, 0, Direction.CLOCKWISE, 60_000)).ok)
        b.step(400)
        assertEquals(FaultCode.BOARD_ERROR.name, b.snap.faultCode)
        assertEquals(JobState.ABORTED.name, b.snap.jobState)
        b.step(200)
        assertEquals("STOP dès la trame suivante", 0, b.last().function)
    }

    @Test fun answersToSequencesToqueDidNotSendStopEverythingAfterThree() {
        val b = SmartBench().ready()
        assertTrue(b.c.startJob(Programs.manual(1, 0, Direction.CLOCKWISE, 60_000)).ok)
        b.step(1_000)
        b.board.seqShift = 40
        b.step(400)
        assertEquals("deux réponses étrangères sont tolérées", "", b.snap.faultCode)
        b.step(200)
        assertEquals(FaultCode.BOARD_ERROR.name, b.snap.faultCode)
        b.step(200)
        assertEquals("STOP dès la trame suivante", 0, b.last().function)
    }

    @Test fun turboAndTareAnswersAreAccepted() {
        val b = SmartBench().ready()
        b.board.weight = 500
        b.c.tare()
        b.step(1_000)
        assertEquals("", b.snap.faultCode)
        assertTrue(b.c.turboPress().has(app.toque.core.machine.SafetyPolicy.COLD_CONFIRM))
        assertTrue(b.c.confirmCold().ok)
        val v = b.c.turboPress()
        assertTrue(v.message, v.ok)
        b.step(1_000)
        assertTrue(b.tx.any { it.second.function == Smart.FN_TURBO })
        assertEquals("", b.snap.faultCode)
        b.c.turboRelease()
        b.step(600)
        assertEquals("", b.snap.faultCode)
    }
}
