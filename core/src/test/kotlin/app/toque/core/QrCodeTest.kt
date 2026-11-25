package app.toque.core

import app.toque.core.qr.QrCode
import app.toque.core.qr.QrCode.ErrorCorrection
import com.google.zxing.BinaryBitmap
import com.google.zxing.Result
import com.google.zxing.EncodeHintType
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/** La preuve est le décodage par ZXing (bibliothèque de test seulement), plus une comparaison exacte avec son encodeur. */
class QrCodeTest {
    private val random = Random(42)

    /** Octets au hasard ; le premier >= 0x80 force le mode octets chez ZXing (comparaison exacte). */
    private fun bytes(n: Int) = ByteArray(n).also { random.nextBytes(it); if (n > 0) it[0] = 0xE9.toByte() }

    private val zxingLevels = mapOf(
        ErrorCorrection.L to ErrorCorrectionLevel.L, ErrorCorrection.M to ErrorCorrectionLevel.M,
        ErrorCorrection.Q to ErrorCorrectionLevel.Q, ErrorCorrection.H to ErrorCorrectionLevel.H,
    )

    /** Compare [qr] module par module avec l'encodeur de ZXing (même version, même masque, choisi ou imposé). */
    private fun assertSameAsZxing(data: ByteArray, level: ErrorCorrection, mask: Int?, qr: QrCode) {
        // ISO-8859-1 : ZXing encode les octets tels quels, sans segment ECI.
        val hints = HashMap<EncodeHintType, Any>()
        if (mask != null) hints[EncodeHintType.QR_MASK_PATTERN] = mask
        val ref = Encoder.encode(String(data, Charsets.ISO_8859_1), zxingLevels.getValue(level), hints)
        val what = "longueur ${data.size}, niveau $level, masque ${mask ?: "auto"}"
        assertEquals(what, ref.version.versionNumber, qr.version)
        assertEquals(what, ref.maskPattern, qr.mask)
        val m = ref.matrix
        assertEquals(m.width, qr.size)
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            assertEquals("$what ($x, $y)", m.get(x, y).toInt() == 1, qr.isDark(x, y))
        }
    }

    /** Dessine la matrice avec la zone de silence, [scale] pixels par module. */
    private fun render(qr: QrCode, scale: Int): BinaryBitmap {
        val side = (qr.size + 2 * QrCode.QUIET_ZONE) * scale
        val pixels = IntArray(side * side) { 0xFFFFFF }
        for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr.isDark(x, y)) {
            for (dy in 0 until scale) for (dx in 0 until scale) {
                pixels[((y + QrCode.QUIET_ZONE) * scale + dy) * side + (x + QrCode.QUIET_ZONE) * scale + dx] = 0
            }
        }
        return BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
    }

    /**
     * Lit [qr] avec le détecteur complet de ZXing (celui d'un téléphone, sans indice « image pure »).
     * Sur une image parfaite, ce détecteur rate parfois une échelle précise selon le contenu (~1 cas
     * sur 50, codes identiques à ceux de l'encodeur de ZXing) : on essaie donc 2, puis 3, puis 4
     * pixels par module, et le premier succès compte.
     */
    private fun decode(qr: QrCode, scales: IntArray = intArrayOf(2, 3, 4)): Pair<ByteArray, String> {
        var result: Result? = null
        var error: ReaderException? = null
        for (scale in scales) {
            try { result = QRCodeReader().decode(render(qr, scale)); break } catch (e: ReaderException) { error = e }
        }
        if (result == null) throw error!!
        assertEquals(qr.errorCorrection.name, result.resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL])
        @Suppress("UNCHECKED_CAST")
        val segments = result.resultMetadata[ResultMetadataType.BYTE_SEGMENTS] as List<ByteArray>
        var all = ByteArray(0)
        for (s in segments) all += s
        return all to result.text
    }

    private fun assertRoundTrip(data: ByteArray, level: ErrorCorrection, mask: Int? = null): QrCode {
        val qr = QrCode.encode(data, level, mask)
        assertEquals(qr.version * 4 + 17, qr.size)
        val what = "longueur ${data.size}, niveau $level, version ${qr.version}, masque ${qr.mask}"
        assertSameAsZxing(data, level, mask, qr)
        val decoded = try { decode(qr).first } catch (e: ReaderException) { throw AssertionError("$what : $e", e) }
        assertArrayEquals(what, data, decoded)
        return qr
    }

    @Test fun everyLengthUpToVersion10AtLevelM() {
        val max = QrCode.capacity(10, ErrorCorrection.M)
        assertEquals(213, max)
        for (n in 1..max) {
            val qr = assertRoundTrip(bytes(n), ErrorCorrection.M)
            assertTrue(n <= QrCode.capacity(qr.version, ErrorCorrection.M))
            if (qr.version > 1) assertTrue("version minimale", n > QrCode.capacity(qr.version - 1, ErrorCorrection.M))
        }
    }

    @Test fun everyVersionBoundaryAtEveryLevel() {
        for (level in ErrorCorrection.values()) for (v in 1..40) {
            val cap = QrCode.capacity(v, level)
            assertEquals(v, assertRoundTrip(bytes(cap), level).version)
            if (v < 40) assertEquals(v + 1, assertRoundTrip(bytes(cap + 1), level).version)
        }
    }

    @Test fun knownCapacities() {
        // Tableau 7 de la norme, mode octets.
        assertEquals(listOf(17, 14, 11, 7), ErrorCorrection.values().map { QrCode.capacity(1, it) })
        assertEquals(listOf(271, 213, 151, 119), ErrorCorrection.values().map { QrCode.capacity(10, it) })
        assertEquals(listOf(2953, 2331, 1663, 1273), ErrorCorrection.values().map { QrCode.capacity(40, it) })
    }

    @Test fun everyForcedMaskAtEveryLevel() {
        for (level in ErrorCorrection.values()) for (mask in 0..7) for (n in intArrayOf(5, 40, 120)) {
            assertEquals(mask, assertRoundTrip(bytes(n), level, mask).mask)
        }
    }

    @Test fun emptyContent() {
        val qr = QrCode.encode(ByteArray(0))
        assertEquals(1, qr.version)
        assertEquals(21, qr.size)
    }

    @Test fun utf8WithAccents() {
        val text = "Crème brûlée, œufs, façon grand-mère — 200 °C ✓"
        val qr = QrCode.encode(text)
        val (raw, _) = decode(qr)
        assertEquals(text, String(raw, Charsets.UTF_8))
    }

    @Test fun pairingUrl() {
        val url = "http://192.168.0.10:8080/#pair=" + "aZ09-_".repeat(7) + "x"
        val qr = QrCode.encode(url)
        assertEquals(ErrorCorrection.M, qr.errorCorrection)
        assertEquals(5, qr.version) // 74 octets : de 63 à 84 en version 5, niveau M
        assertEquals(37, qr.size)
        assertEquals(url, decode(qr).second)
        // Plus long (150 octets) et à petite échelle (2 pixels par module).
        val long = "http://192.168.100.200:65535/#pair=" + "k".repeat(115)
        assertEquals(150, long.length)
        val qr2 = QrCode.encode(long)
        assertEquals(8, qr2.version)
        assertEquals(long, decode(qr2, intArrayOf(2)).second)
    }

    @Test fun tooLong() {
        val max = QrCode.capacity(40, ErrorCorrection.H)
        QrCode.encode(ByteArray(max), ErrorCorrection.H)
        try {
            QrCode.encode(ByteArray(max + 1), ErrorCorrection.H)
            fail("exception attendue")
        } catch (e: QrCode.TooLongException) {
            assertEquals(max + 1, e.length)
            assertEquals(max, e.capacity)
        }
        try {
            QrCode.encode(ByteArray(1), mask = 8)
            fail("exception attendue")
        } catch (e: IllegalArgumentException) {
        }
    }

    /** Comparaison exacte avec ZXing pour des tailles plus variées, sans passer par la lecture. */
    @Test fun matchesZxingEncoderBitForBit() {
        for (level in ErrorCorrection.values()) for (n in intArrayOf(1, 7, 17, 30, 62, 100, 150, 213, 400, 1000)) {
            val data = bytes(n)
            for (mask in listOf<Int?>(null, 0, 3, 7)) assertSameAsZxing(data, level, mask, QrCode.encode(data, level, mask))
        }
    }
}
