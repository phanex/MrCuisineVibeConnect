package app.toque.core

import app.toque.core.protocol.Direction
import app.toque.core.protocol.Mcc
import app.toque.core.protocol.MccProtocolException
import app.toque.core.protocol.Operation
import app.toque.core.protocol.Smart
import app.toque.core.protocol.SmartCodec
import app.toque.core.protocol.SmartStreamParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/**
 * Protocole du Monsieur Cuisine Smart, vérifié sur les trames réelles des captures du 2026-11-20
 * (fichiers TSV de core/src/test/resources/smart/, extraites par tools/smart/extract_frames.py) :
 * 01 à 05 = phase 3 (repos, balance, vitesse 1, 37 °C) ; 06 à 14 = phase 5 (vitesses, sens
 * inverse, turbo, pétrin, mixer, trancheuse, 100/70/40 °C, vapeur, saisir, œufs, sous vide,
 * mijoter, fermenter, smoothie, riz, chauffer l'eau, prélavage), toutes avec l'appli d'origine.
 */
class SmartProtocolTest {
    class Row(val time: String, val dir: String, val raw: ByteArray)

    companion object {
        val CAPTURES = listOf(
            "01-repos", "02-balance-couvercle", "03-rotation-v1-30s", "04-chauffe-37C-v1-1min",
            "05-eau-chaude-puis-37C-1min",
            "06-v5-4s", "07-v2-inverse", "08-turbo-puis-malaxer-ferme", "10-malaxer-molle", "11-malaxer-liquide",
            "12-mixer", "13-trancheuse", "14-eau",
            // trames envoyées par Toque-Smart lui-même sur le robot (essais de la phase 5)
            "15-toque-essais",
            // programmes à étapes de l'appli d'origine, capturés en entier (phase R2, 2026-11-21)
            "16-etapes-mc30",
        )

        fun capture(name: String): List<Row> =
            // Captures compressées (gzip) : quelques Mo de texte hexadécimal sinon.
            java.util.zip.GZIPInputStream(SmartProtocolTest::class.java.getResourceAsStream("/smart/$name.tsv.gz")!!).bufferedReader().use { r ->
                r.readLines().filter { it.isNotBlank() }.map {
                    val c = it.split('\t')
                    Row(c[0], c[1], Mcc.parseHex(c[2]))
                }
            }

        fun all(): List<Row> = CAPTURES.flatMap { capture(it) }
    }

    @Test fun capturesAreLoaded() {
        val rows = all()
        assertTrue(rows.count { it.dir == "TX" } > 4_000)
        assertTrue(rows.count { it.dir == "RX" } > 4_000)
    }

    @Test fun everyCapturedCommandIsRebuiltByteForByte() {
        var turbo = 0
        for (row in all().filter { it.dir == "TX" }) {
            if (row.raw.size == Smart.TURBO_SIZE) {
                assertTrue(Mcc.hex(row.raw), Smart.isTurbo(row.raw))
                assertArrayEquals(row.raw, Smart.buildTurbo(row.raw[7].toInt() and 0xFF))
                turbo++
                continue
            }
            val c = Smart.decodeTx(row.raw)
            assertTrue("octets inattendus ${c.unexpectedBytes} dans ${Mcc.hex(row.raw)}", c.unexpectedBytes.isEmpty())
            val rebuilt = Smart.buildTx(
                c.seq, c.cmd, c.temperatureLevel, c.speedHalfSteps, c.reverse, c.function, c.tare,
                c.hours, c.minutes, c.seconds, c.celsius,
            )
            assertArrayEquals(Mcc.hex(row.raw), row.raw, rebuilt)
        }
        assertTrue(turbo >= 10)
    }

    @Test fun everyCapturedStatusDecodesWithoutWarning() {
        for (row in all().filter { it.dir == "RX" }) {
            val t = Smart.decodeRx(row.raw)
            assertTrue("${t.warnings} dans ${Mcc.hex(row.raw)}", t.warnings.isEmpty())
            assertEquals(3090, t.firmwareVersion)
            assertFalse(t.errorDetected)
            assertEquals(0, t.actualRpm)
        }
    }

    @Test fun boardAnswersWithTheSequenceOfTheCommand() {
        val rows = capture("01-repos")
        val tx = rows.filter { it.dir == "TX" }.map { Smart.decodeTx(it.raw).seq }.toSet()
        val rx = rows.filter { it.dir == "RX" }.map { Smart.rxSeq(it.raw) }
        assertTrue(rx.drop(1).all { it in tx })
    }

    /**
     * Toutes les réponses réelles reprennent la commande de même séquence ([Smart.echoes]) et ont
     * un code retour nul : la vérification des réponses ne peut pas arrêter une cuisson normale.
     * Seules les trames que Toque envoie (08 02, 08 03) comptent ; couvercle ouvert ou bol absent
     * ne sont pas vérifiés.
     */
    @Test fun everyCapturedAnswerEchoesItsCommand() {
        var checked = 0
        for (name in CAPTURES) {
            val sent = ArrayList<ByteArray>()
            for (row in capture(name)) {
                if (row.dir == "TX") { sent += row.raw; continue }
                val rx = row.raw
                assertEquals(0, rx[Smart.RX_RETURN_CODE].toInt())
                val tx = sent.takeLast(8).lastOrNull { it[7] == rx[7] } ?: continue
                if (tx[9].toInt() != Smart.CMD_SET && tx[9].toInt() != Smart.CMD_TARE) continue
                if (rx[12].toInt() and Smart.STATUS_LID_OR_BOWL != 0) continue
                assertTrue("$name ${row.time} : ${Mcc.hex(tx)} -> ${Mcc.hex(rx)}", Smart.echoes(tx, rx))
                checked++
            }
        }
        assertTrue("réponses vérifiées : $checked", checked > 20_000)
    }

    @Test fun echoRejectsAnotherSpeedLevelOrStoppedFunction() {
        val tx = Smart.buildTx(5, temperatureLevel = 3, speedHalfSteps = 4, function = Smart.FN_MANUAL)
        fun rx(level: Int, speed: Int, fn: Int, reverse: Int = 0): ByteArray {
            val r = ByteArray(Smart.RX_SIZE)
            r[7] = 5; r[9] = Smart.CMD_SET.toByte(); r[17] = level.toByte(); r[25] = speed.toByte()
            r[26] = reverse.toByte(); r[28] = fn.toByte()
            return r
        }
        assertTrue(Smart.echoes(tx, rx(3, 4, 1)))
        assertFalse(Smart.echoes(tx, rx(3, 2, 1)))
        assertFalse(Smart.echoes(tx, rx(0, 4, 1)))
        assertFalse(Smart.echoes(tx, rx(3, 4, 0)))
        assertFalse("lame en marche : le sens compte", Smart.echoes(tx, rx(3, 4, 1, reverse = 1)))
        val stop = Smart.buildTx(6)
        val r = rx(0, 0, 0, reverse = 1).also { it[7] = 6 }
        assertTrue("à l'arrêt, la base garde le dernier sens", Smart.echoes(stop, r))
    }

    @Test fun codecFlagsRefusalWrongEchoAndForeignSequences() {
        val c = SmartCodec(initialSeq = 20)
        val f = c.encodeTx(Operation.STANDARD, 2, 0, Direction.CLOCKWISE, false, 60)
        fun answer(seq: Int, speed: Int, fn: Int, rc: Int = 0, status: Int = 0): ByteArray {
            val r = ByteArray(Smart.RX_SIZE)
            r[0] = 0xAA.toByte(); r[2] = Smart.RX_L.toByte(); r[3] = 1; r[4] = 0x0F
            r[7] = seq.toByte(); r[8] = 8; r[9] = Smart.CMD_SET.toByte(); r[10] = 0x0C; r[11] = 0x12
            r[12] = status.toByte(); r[Smart.RX_RETURN_CODE] = rc.toByte(); r[25] = speed.toByte(); r[28] = fn.toByte()
            r[Smart.RX_L] = Smart.checksum(r, Smart.RX_L).toByte()
            return r
        }
        val seq = f[7].toInt() and 0xFF
        assertFalse(c.decodeRx(answer(seq, 4, 1)).errorDetected)
        assertEquals(Smart.ERR_REFUSED + 3, c.decodeRx(answer(seq, 4, 1, rc = 3)).errorCode)
        assertFalse("couvercle ouvert : la base refuse d'elle-même", c.decodeRx(answer(seq, 0, 0, rc = 3, status = 0x80)).errorDetected)
        assertEquals(Smart.ERR_ECHO, c.decodeRx(answer(seq, 0, 0)).errorCode)
        assertFalse(c.decodeRx(answer(seq + 50, 4, 1)).errorDetected)
        assertFalse(c.decodeRx(answer(seq + 51, 4, 1)).errorDetected)
        assertEquals(Smart.ERR_FOREIGN, c.decodeRx(answer(seq + 52, 4, 1)).errorCode)
        assertFalse("une bonne réponse remet le compte à zéro", c.decodeRx(answer(seq, 4, 1)).errorDetected)
        assertTrue(Smart.errorMessage(Smart.ERR_REFUSED + 3).contains("3"))
    }

    @Test fun weightLidAndBowlFromTheScaleCapture() {
        val t = capture("02-balance-couvercle").filter { it.dir == "RX" }.map { Smart.decodeRx(it.raw) }
        assertTrue("paquet de riz lu ~1035 g", t.any { it.weight in 1030..1040 })
        assertTrue("poids négatif (couvercle retiré)", t.any { it.weight < -400 })
        assertTrue(t.any { it.lidOpen })
        assertTrue(t.any { !it.lidOpen })
        assertTrue(t.all { it.bowlNtcDetected })
    }

    @Test fun heatingCaptureShowsRealTemperatureAndFlags() {
        val t = capture("05-eau-chaude-puis-37C-1min").filter { it.dir == "RX" }.map { Smart.decodeRx(it.raw) }
        assertTrue("bol retiré", t.any { !it.bowlNtcDetected })
        assertTrue(t.any { it.actualTemperature == 37 && it.aboveTargetTemperature })
        val running = t.filter { it.motorOn }
        assertTrue(running.isNotEmpty())
        assertTrue(running.all { it.heaterOn && it.actualSpeedLevel == 1 && it.targetTemperature == 37 })
        assertTrue(t.any { it.motorStopped && !it.heaterOn })
    }

    @Test fun rotationCaptureEchoesSpeedOneWithoutHeat() {
        val t = capture("03-rotation-v1-30s").filter { it.dir == "RX" }.map { Smart.decodeRx(it.raw) }
        val running = t.filter { it.motorOn }
        assertTrue(running.size > 90)
        assertTrue(running.all { !it.heaterOn && it.actualSpeedLevel == 1 && it.direction == Direction.CLOCKWISE })
    }

    @Test fun parserReassemblesFragmentsAndSkipsGarbage() {
        val frames = capture("03-rotation-v1-30s").filter { it.dir == "RX" }.map { it.raw }
        val rnd = Random(42)
        val stream = java.io.ByteArrayOutputStream()
        for (f in frames) {
            if (rnd.nextInt(5) == 0) stream.write(byteArrayOf(0xAA.toByte(), 0x00, 0x34, 0x13, 0x55)) // début de trame bidon
            if (rnd.nextInt(5) == 0) stream.write(rnd.nextInt(256))
            stream.write(f)
        }
        val bytes = stream.toByteArray()
        val p = SmartStreamParser()
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < bytes.size) {
            val n = minOf(1 + rnd.nextInt(60), bytes.size - i)
            p.feed(bytes.copyOfRange(i, i + n), n, out)
            i += n
        }
        assertEquals(frames.size, out.size)
        for (k in frames.indices) assertArrayEquals(frames[k], out[k])
    }

    @Test fun parserRejectsABadChecksumAndRecovers() {
        val frames = capture("01-repos").filter { it.dir == "RX" }.map { it.raw }
        val bad = frames[0].copyOf()
        bad[30] = (bad[30] + 1).toByte()
        val p = SmartStreamParser()
        val out = p.feed(bad + frames[1], bad.size + frames[1].size, ArrayList())
        assertEquals(1, out.size)
        assertArrayEquals(frames[1], out[0])
        assertTrue(p.invalidFrames >= 1)
        try {
            Smart.decodeRx(bad)
            fail()
        } catch (_: MccProtocolException) {
        }
    }

    @Test fun parserIgnoresCommandFrames() {
        val tx = capture("01-repos").filter { it.dir == "TX" }.map { it.raw }
        val p = SmartStreamParser()
        for (f in tx) assertTrue(p.feed(f, f.size, ArrayList()).isEmpty())
    }

    // --------------------------------------------------------------- codec (émission)

    /** Trame émise de la capture, par séquence (les séquences reviennent : on précise la fonction). */
    private fun capturedTx(name: String, seq: Int, function: Int = 0): ByteArray =
        capture(name).first {
            it.dir == "TX" && it.raw.size == Smart.TX_SIZE && Smart.decodeTx(it.raw).let { c -> c.seq == seq && c.function == function }
        }.raw

    @Test fun stopFrameMatchesTheIdleCapture() {
        val c = SmartCodec(initialSeq = 0xBC)
        val f = c.encodeTx(Operation.STOP, 0, 0, Direction.CLOCKWISE, false, 0)
        assertEquals(
            "AA 00 25 02 4F 42 00 BD 08 02 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 7F",
            Mcc.hex(f),
        )
    }

    @Test fun tareFrameMatchesTheScaleCapture() {
        val c = SmartCodec(initialSeq = 0x9F)
        val f = c.encodeTx(Operation.STOP, 0, 0, Direction.CLOCKWISE, true, 0)
        assertArrayEquals(capturedTx("02-balance-couvercle", 0xA0), f)
    }

    @Test fun manualSpeedOneThirtySecondsMatchesTheCaptureAndRepeats() {
        val c = SmartCodec(initialSeq = 176)
        val f = c.encodeTx(Operation.STANDARD, 1, 0, Direction.CLOCKWISE, false, 30)
        assertArrayEquals(capturedTx("03-rotation-v1-30s", 177, function = 1), f)
        // l'appli d'origine renvoie la même trame (même séquence) pendant tout le programme
        assertArrayEquals(f, c.encodeTx(Operation.STANDARD, 1, 0, Direction.CLOCKWISE, false, 30))
        val stop = c.encodeTx(Operation.STOP, 0, 0, Direction.CLOCKWISE, false, 0)
        assertEquals(178, Smart.decodeTx(stop).seq)
        assertEquals(0, Smart.decodeTx(stop).function)
    }

    @Test fun manual37DegreesOneMinuteMatchesTheCapture() {
        val c = SmartCodec(initialSeq = 97)
        val f = c.encodeTx(Operation.STANDARD, 1, Mcc.levelForCelsius(37), Direction.CLOCKWISE, false, 60)
        assertArrayEquals(capturedTx("04-chauffe-37C-v1-1min", 98, function = 1), f)
    }

    @Test fun aChangedSettingGetsANewSequence() {
        val c = SmartCodec(initialSeq = 10)
        val a = c.encodeTx(Operation.STANDARD, 2, 0, Direction.CLOCKWISE, false, 60)
        val b = c.encodeTx(Operation.STANDARD, 3, 0, Direction.CLOCKWISE, false, 60)
        assertEquals(11, Smart.decodeTx(a).seq)
        assertEquals(12, Smart.decodeTx(b).seq)
        assertEquals(6, Smart.decodeTx(b).speedHalfSteps)
    }

    @Test fun reverseSetsByte17() {
        val f = SmartCodec().encodeTx(Operation.STANDARD, 1, 0, Direction.COUNTER_CLOCKWISE, false, 10)
        assertEquals(1, f[17].toInt())
        assertEquals(0, SmartCodec().encodeTx(Operation.STANDARD, 1, 0, Direction.CLOCKWISE, false, 10)[17].toInt())
    }

    @Test fun sequenceSkips102AndWraps() {
        val c = SmartCodec(initialSeq = 100)
        val seqs = (0 until 3).map { Smart.decodeTx(c.stopFrame()).seq }
        assertEquals(listOf(101, 103, 104), seqs)
        val w = SmartCodec(initialSeq = 254)
        assertEquals(listOf(255, 0, 1), (0 until 3).map { Smart.decodeTx(w.stopFrame()).seq })
    }

    @Test fun everyToqueTemperatureLevelKeepsItsCelsius() {
        for (l in 0..19) assertEquals(Mcc.celsiusForLevel(l), Smart.celsiusForLevel(Smart.levelForToqueLevel(l)))
        assertEquals(1, Smart.levelForToqueLevel(1))
        assertEquals(3, Smart.levelForToqueLevel(2))
        assertEquals(20, Smart.levelForToqueLevel(19))
    }

    @Test fun refusesWhatWasNotValidated() {
        val c = SmartCodec()
        assertFalse(c.supports(Operation.SLEEP))
        assertThrows { c.encodeTx(Operation.SLEEP, 1, 0, Direction.CLOCKWISE, false, 10) }
        // programmes : seulement les paramètres capturés avec l'appli d'origine
        val cw = Direction.CLOCKWISE
        val ccw = Direction.COUNTER_CLOCKWISE
        for ((op, speed, lvl, dir) in listOf(
            Quad(Operation.KNEAD, 3, 0, cw), Quad(Operation.KNEAD, 4, 1, cw), Quad(Operation.KNEAD, 5, 0, ccw),
            Quad(Operation.STEAM, 0, 17, cw), Quad(Operation.STEAM, 1, 16, cw), Quad(Operation.STEAM, 1, 17, ccw),
            Quad(Operation.TOAST, 0, 19, ccw), Quad(Operation.TOAST, 1, 19, cw), Quad(Operation.TOAST, 1, 18, ccw),
            Quad(Operation.TURBO, 9, 0, cw), Quad(Operation.TURBO, 10, 1, cw), Quad(Operation.TURBO, 10, 0, ccw),
        )) {
            assertFalse("$op $speed $lvl $dir", c.accepts(op, speed, lvl, dir))
            assertThrows { c.encodeTx(op, speed, lvl, dir, false, 10) }
        }
        assertThrows { c.encodeTx(Operation.KNEAD, 4, 0, cw, true, 10) }
        assertThrows { c.encodeTx(Operation.STANDARD, 11, 0, Direction.CLOCKWISE, false, 10) }
        assertThrows { c.encodeTx(Operation.STANDARD, 1, 20, Direction.CLOCKWISE, false, 10) }
        assertThrows { c.encodeTx(Operation.STANDARD, 1, 0, Direction.CLOCKWISE, true, 10) }
        assertThrows { c.encodeTx(Operation.STANDARD, 0, 0, Direction.CLOCKWISE, false, 10) }
        assertThrows { Smart.buildTx(1, cmd = 0x01 + 0xFD) }
    }

    @Test fun neverProducesAFirmwareUpdateCommand() {
        val c = SmartCodec()
        for (op in Operation.values()) for (speed in 0..10) for (lvl in 0..19)
            for (dir in Direction.values()) for (tare in listOf(false, true)) {
                val f = try {
                    c.encodeTx(op, speed, lvl, dir, tare, 90)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                assertEquals(0x08, f[8].toInt() and 0xFF)
                assertTrue((f[9].toInt() and 0xFF) in 2..3)
                if (op == Operation.TURBO) assertTrue(Smart.isTurbo(f)) else Smart.validate(f, Smart.TX_SIZE)
                c.encodeTx(Operation.STOP, 0, 0, dir, false, 0)
            }
    }

    // ------------------------------------------- phase 5 : réglages capturés, octet pour octet

    private data class Quad(val op: Operation, val speed: Int, val level: Int, val dir: Direction)

    /** Première trame active (fonction non nulle) de la capture, à partir de [from] (horodatage). */
    private fun firstActive(name: String, from: String = ""): ByteArray =
        capture(name).first {
            it.dir == "TX" && it.time >= from && it.raw.size == Smart.TX_SIZE && Smart.decodeTx(it.raw).function != 0
        }.raw

    /** Le codec, placé juste avant la séquence de la trame capturée, doit la reproduire à l'identique. */
    private fun assertCodecRebuilds(captured: ByteArray, op: Operation, speed: Int, level: Int, dir: Direction, durationS: Int) {
        val seq = captured[7].toInt() and 0xFF
        val c = SmartCodec(initialSeq = if (seq == 103) 101 else (seq + 255) % 256)
        val f = c.encodeTx(op, speed, level, dir, false, durationS)
        assertEquals(Mcc.hex(captured), Mcc.hex(f))
        // renvoyée telle quelle (même séquence) tant que le réglage ne change pas
        assertArrayEquals(f, c.encodeTx(op, speed, level, dir, false, durationS))
    }

    private val cw = Direction.CLOCKWISE
    private val ccw = Direction.COUNTER_CLOCKWISE

    @Test fun manualSpeedFive() =
        assertCodecRebuilds(firstActive("06-v5-4s"), Operation.STANDARD, 5, 0, cw, 4)

    @Test fun manualSpeedTwoReverse() {
        val f = firstActive("07-v2-inverse")
        assertEquals(1, f[17].toInt())
        assertCodecRebuilds(f, Operation.STANDARD, 2, 0, ccw, 4)
    }

    @Test fun slicerIsManualSpeedFive() =
        assertCodecRebuilds(firstActive("13-trancheuse"), Operation.STANDARD, 5, 0, cw, 4)

    @Test fun manualHundredSeventyAndFortyDegrees() {
        assertCodecRebuilds(firstActive("14-eau", "19:17"), Operation.STANDARD, 1, Mcc.levelForCelsius(100), cw, 4)
        assertCodecRebuilds(firstActive("14-eau", "19:18"), Operation.STANDARD, 1, Mcc.levelForCelsius(70), cw, 4)
        // 40 °C : niveau 2, propre au Smart (absent de la table de Toque)
        val f40 = firstActive("14-eau", "19:19")
        assertEquals(2, Smart.decodeTx(f40).temperatureLevel)
        assertEquals(40, Smart.celsiusForLevel(2))
        assertArrayEquals(f40, Smart.buildTx(f40[7].toInt() and 0xFF, temperatureLevel = 2, speedHalfSteps = 2,
            function = Smart.FN_MANUAL, seconds = 4))
    }

    @Test fun kneadFirmAndLiquidDoughForwardSoftDoughReverse() {
        assertCodecRebuilds(firstActive("08-turbo-puis-malaxer-ferme"), Operation.KNEAD, 4, 0, cw, 4)
        assertCodecRebuilds(firstActive("11-malaxer-liquide"), Operation.KNEAD, 4, 0, cw, 4)
        assertCodecRebuilds(firstActive("10-malaxer-molle"), Operation.KNEAD, 4, 0, ccw, 4)
    }

    @Test fun steamOneHundredTwentyDegreesSpeedOne() {
        val f = firstActive("14-eau", "19:20")
        assertEquals(Smart.FN_STEAM, Smart.decodeTx(f).function)
        assertEquals(Smart.LEVEL_120C, Smart.decodeTx(f).temperatureLevel)
        assertCodecRebuilds(f, Operation.STEAM, 1, 17, cw, 4)
    }

    @Test fun searOneHundredThirtyDegreesSpeedOneReverse() {
        val f = firstActive("14-eau", "19:21")
        assertEquals(Smart.FN_TOAST, Smart.decodeTx(f).function)
        assertEquals(Smart.LEVEL_130C, Smart.decodeTx(f).temperatureLevel)
        assertCodecRebuilds(f, Operation.TOAST, 1, 19, ccw, 4)
    }

    @Test fun turboFrameMatchesTheCaptureAndRepeatsUntilRelease() {
        val rows = capture("08-turbo-puis-malaxer-ferme").filter { it.dir == "TX" }
        val captured = rows.first { it.raw.size == Smart.TURBO_SIZE }.raw
        assertEquals(
            "AA 00 22 02 4F 42 00 02 08 02 00 00 00 00 00 00 00 00 0A C6 00 00 00 00 00 00 00 05 00 00 00 00 00 06 9C",
            Mcc.hex(captured),
        )
        // l'appli d'origine renvoie la même trame pendant tout l'appui, puis un STOP normal
        val turbo = rows.filter { it.raw.size == Smart.TURBO_SIZE }
        assertTrue(turbo.all { it.raw.contentEquals(captured) })
        val after = rows[rows.indexOfLast { it.raw.size == Smart.TURBO_SIZE } + 1].raw
        assertEquals(0, Smart.decodeTx(after).function)

        val c = SmartCodec(initialSeq = 0x42, initialTurboSeq = 1)
        val f = c.encodeTx(Operation.TURBO, 10, 0, cw, false, 3)
        assertArrayEquals(captured, f)
        assertArrayEquals(captured, c.encodeTx(Operation.TURBO, 10, 0, cw, false, 3))
        // le compteur principal n'avance pas pendant le turbo (STOP suivant = séquence 0x43)
        assertArrayEquals(after, c.encodeTx(Operation.STOP, 0, 0, cw, false, 0))
        // nouvel appui = nouvelle séquence turbo
        assertEquals(3, c.encodeTx(Operation.TURBO, 10, 0, cw, false, 3)[7].toInt())
    }

    @Test fun toqueOnTheRobotSentTheSamePayloadsAsTheOriginalApp() {
        // Charge utile hors séquence et durée (octets 8-24 et 28-36) : réglage final de chaque essai.
        fun key(f: ByteArray) = (8 until f.size - 1).filter { it !in 25..27 }.map { f[it] }
        val original = CAPTURES.filter { it != "15-toque-essais" }.flatMap { capture(it) }
            .filter { it.dir == "TX" }.map { key(it.raw) }.toSet()
        val toque = capture("15-toque-essais").filter { it.dir == "TX" }.map { it.raw }
        val finals = listOf(
            Smart.buildTx(0, speedHalfSteps = 10, function = 1),                         // vitesse 5
            Smart.buildTx(0, temperatureLevel = 8, speedHalfSteps = 2, function = 1),    // 70 °C v1
            Smart.buildTx(0, speedHalfSteps = 4, reverse = true, function = 1),          // v2 inverse
            Smart.buildTx(0, speedHalfSteps = 8, function = Smart.FN_KNEAD),             // pétrin
            Smart.buildTurbo(0),                                                         // turbo
        )
        for (f in finals) {
            assertTrue("absent des essais Toque : ${Mcc.hex(f)}", toque.any { key(it) == key(f) })
            assertTrue("absent des captures d'origine : ${Mcc.hex(f)}", key(f) in original)
        }
    }

    @Test fun turboEchoCountsAsMotorRunning() {
        val rx = capture("08-turbo-puis-malaxer-ferme").first {
            it.dir == "RX" && (it.raw[28].toInt() and 0xFF) == Smart.FN_TURBO
        }.raw
        val t = Smart.decodeRx(rx)
        assertTrue(t.motorOn)
        assertEquals(10, t.targetSpeedLevel)
        assertTrue(t.warnings.isEmpty())
    }

    @Test fun automaticProgramsOfTheOriginalAppAreRecorded() {
        // Programmes que Toque n'envoie pas (pas d'équivalent dans Toque) : on fige ce que fait
        // l'appli d'origine. Octets : 10 niveau, 12 °C, 16 vitesse × 2, 17 sens, 19 fonction.
        fun at(t: String) = Smart.decodeTx(firstActive("14-eau", t))
        data class E(val fn: Int, val level: Int, val celsius: Int, val speedX2: Int, val reverse: Boolean)
        fun E.of(c: Smart.Command) = assertEquals(this, E(c.function, c.temperatureLevel, c.celsius, c.speedHalfSteps, c.reverse))
        E(8, 1, 130, 2, false).of(at("19:23"))    // Cuire des œufs
        E(9, 1, 60, 2, true).of(at("19:24"))      // Sous-vide
        E(10, 1, 90, 2, true).of(at("19:25"))     // Mijoter
        E(11, 1, 40, 2, true).of(at("19:27"))     // Fermenter
        E(13, 0, 0, 20, false).of(at("19:28"))    // Smoothie
        E(1, 14, 100, 2, true).of(at("19:30"))    // Cuire du riz
        E(3, 14, 100, 2, true).of(at("19:31"))    // Chauffer l'eau
        E(6, 1, 0, 20, false).of(at("19:32:1"))   // Prélavage rapide
        E(7, 1, 0, 20, false).of(at("19:32:4"))   // Prélavage long
        val mixer = Smart.decodeTx(firstActive("12-mixer"))
        E(12, 0, 0, 16, false).of(mixer)           // Mixer
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        fail("exception attendue")
    }
}
