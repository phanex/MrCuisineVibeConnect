/*
 * Protocole série de la base moteur du Monsieur Cuisine Connect (MCLauncher build 292).
 *
 * Adapté du « Monsieur Cuisine Developer SDK » (licence MIT, voir
 * docs/LICENSE-monsieur-cuisine-developer-sdk.txt). Les faits de protocole viennent de
 * leur analyse statique ; rien ici n'a été mesuré sur une vraie machine.
 */
package app.toque.core.protocol

import app.toque.core.i18n.tr
import java.io.IOException

class MccProtocolException(message: String) : IOException(message)

/** Sens de rotation. Sur le fil (octet 11 TX) : 0 = horaire, 1 = antihoraire (inverse). */
enum class Direction(val androidValue: Int) {
    COUNTER_CLOCKWISE(0),
    CLOCKWISE(1);

    val wire: Int get() = 1 - androidValue
}

/** Opérations TX observées dans le build 292. */
enum class Operation(val wire: Int) {
    STOP(0x00),
    STANDARD(0x01),
    STEAM(0x03),
    TOAST(0x04),
    KNEAD(0x05),
    SLEEP(0x0F),
    TURBO(0xC6);
}

/** Une trame d'état reçue de la base (27 octets), décodée sans interprétation supplémentaire. */
class Telemetry(
    val raw: ByteArray,
    val status: Int,
    val motorHeaterState: Int,
    val motorOn: Boolean,
    val heaterOn: Boolean,
    val scaleStable: Boolean,
    val direction: Direction,
    val errorDetected: Boolean,
    val errorCode: Int,
    val androidErrorCode: Int,
    val aboveTargetTemperature: Boolean,
    val bowlNtcDetected: Boolean,
    val lidOpen: Boolean,
    val extendedState: Int,
    val targetSpeedLevel: Int,
    val targetRpm: Int,
    val actualRpm: Int,
    val targetTemperatureLevel: Int,
    val targetTemperature: Int,
    val actualTemperature: Int,
    val weight: Int,
    val firmwareVersion: Int,
    val scaleStateRaw: Int,
    val scaleState: String,
    val calibrationStep: Int,
    val unknownByte24: Int,
    val warnings: List<String>,
    /** Niveau de vitesse renvoyé tel quel par la carte (Smart : écho de la commande), -1 sinon. */
    val reportedSpeedLevel: Int = -1,
) {
    val targetSpeedLevelSigned: Int get() = if (targetSpeedLevel < 128) targetSpeedLevel else targetSpeedLevel - 256
    val targetTemperatureLevelSigned: Int get() = if (targetTemperatureLevel < 128) targetTemperatureLevel else targetTemperatureLevel - 256
    val unknownByte24Signed: Int get() = if (unknownByte24 < 128) unknownByte24 else unknownByte24 - 256
    val androidScaleState: String get() = if (scaleState == "not_available") "overload" else scaleState
    val actualSpeedLevel: Int get() = if (reportedSpeedLevel >= 0) reportedSpeedLevel else Mcc.actualSpeedLevel(actualRpm)

    /** Moteur réellement à l'arrêt selon cet échantillon (bit moteur ET régime mesuré nul). */
    val motorStopped: Boolean get() = !motorOn && actualRpm == 0

    /**
     * Vrai quand la base ne mesure pas le régime lame en marche : certaines bases du Connect (SKMC 1100 A1
     * de dadoux63, issue #10 ; SKMC 1200 de McSoda) laissent les octets 9-10 à 0 alors que la lame tourne.
     */
    val speedFromTarget: Boolean get() = reportedSpeedLevel < 0 && motorOn && actualRpm == 0 && targetSpeedLevelSigned > 0

    /**
     * Niveau de vitesse montré à l'écran : celui du régime mesuré, ou la vitesse demandée que la base renvoie
     * (octet 6) quand elle ne mesure pas le régime ([speedFromTarget]). Écran seulement : l'arrêt de la lame
     * se juge sur le bit moteur ([motorStopped]), jamais sur ce niveau.
     */
    val shownSpeedLevel: Int get() = if (speedFromTarget) targetSpeedLevelSigned.coerceAtMost(10) else actualSpeedLevel
    @Volatile private var cachedHex: String? = null
    val hex: String get() = cachedHex ?: Mcc.hex(raw).also { cachedHex = it }
}

object Mcc {
    const val TX_SIZE = 15
    const val RX_SIZE = 27

    /** Niveau de température (0..19) -> °C, table de l'appli d'origine. */
    val TEMPERATURE_LEVELS: IntArray = intArrayOf(
        0, 37, 45, 50, 55, 60, 65, 70, 75, 80,
        85, 90, 95, 100, 105, 110, 115, 120, 125, 130,
    )

    private val ERROR_MAP = mapOf(1 to 1, 3 to 2, 4 to 3, 5 to 4, 6 to 5, 7 to 12, 8 to 13)

    fun celsiusForLevel(level: Int): Int = TEMPERATURE_LEVELS[level.coerceIn(0, 19)]

    /** Niveau exact pour une température de la table, sinon -1. */
    fun levelForCelsius(celsius: Int): Int = TEMPERATURE_LEVELS.indexOf(celsius)

    /** Niveau le plus proche sans dépasser la température demandée (0 si < 37 °C). */
    fun levelAtMost(celsius: Int): Int {
        var best = 0
        for (i in TEMPERATURE_LEVELS.indices) if (TEMPERATURE_LEVELS[i] <= celsius) best = i
        return best
    }

    fun checksum(bytes: ByteArray, end: Int = bytes.size): Int {
        var total = 0
        for (i in 0 until end) total = (total + (bytes[i].toInt() and 0xFF)) and 0xFF
        return total
    }

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 3)
        for ((i, b) in bytes.withIndex()) {
            if (i > 0) sb.append(' ')
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    fun parseHex(text: String): ByteArray {
        val compact = text.filter { !it.isWhitespace() }
        require(compact.isNotEmpty() && compact.length % 2 == 0) { "hex invalide" }
        return ByteArray(compact.length / 2) { compact.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Encode une trame de commande. Refuse les valeurs hors plage au lieu de les remplacer. */
    fun encodeTx(
        operation: Operation = Operation.STOP,
        speed: Int = 0,
        temperatureLevel: Int = 0,
        direction: Direction = Direction.CLOCKWISE,
        tare: Boolean = false,
    ): ByteArray {
        require(speed in 0..10) { "vitesse hors plage 0..10" }
        require(temperatureLevel in 0..19) { "niveau de température hors plage 0..19" }
        val raw = ByteArray(TX_SIZE)
        raw[0] = 0x55
        raw[1] = TX_SIZE.toByte()
        raw[2] = 0xA1.toByte()
        raw[3] = operation.wire.toByte()
        raw[4] = (if (tare) 0xA9 else 0).toByte()
        raw[5] = speed.toByte()
        raw[6] = temperatureLevel.toByte()
        // 7..10 réservés à zéro, 12 = calibration balance normale (jamais générée ici)
        raw[11] = direction.wire.toByte()
        raw[13] = checksum(raw, 13).toByte()
        raw[14] = 0xAA.toByte()
        return raw
    }

    fun validate(raw: ByteArray, expectedSize: Int? = null) {
        val size = raw.size
        if ((size != TX_SIZE && size != RX_SIZE) || (expectedSize != null && size != expectedSize))
            throw MccProtocolException("longueur de trame")
        if ((raw[0].toInt() and 0xFF) != 0x55 || (raw[1].toInt() and 0xFF) != size)
            throw MccProtocolException("en-tête ou octet de longueur")
        val kind = if (size == TX_SIZE) 0xA1 else 0xB1
        if ((raw[2].toInt() and 0xFF) != kind) throw MccProtocolException("type de trame")
        if ((raw[size - 1].toInt() and 0xFF) != 0xAA) throw MccProtocolException("terminateur")
        if ((raw[size - 2].toInt() and 0xFF) != checksum(raw, size - 2)) throw MccProtocolException("somme de contrôle")
    }

    private val ACTUAL_SPEED_UPPER = intArrayOf(558, 1008, 1437, 3359, 6349, 9309, 12312, 15315, 18232)

    fun actualSpeedLevel(rpm: Int): Int {
        if (rpm <= 0) return 0
        for (i in ACTUAL_SPEED_UPPER.indices) if (rpm <= ACTUAL_SPEED_UPPER[i]) return i + 1
        return 10
    }

    private fun u(raw: ByteArray, i: Int): Int = raw[i].toInt() and 0xFF
    private fun word(raw: ByteArray, i: Int): Int = (u(raw, i) shl 8) or u(raw, i + 1)

    fun decodeRx(raw: ByteArray): Telemetry {
        validate(raw, RX_SIZE)
        val status = u(raw, 3)
        val mode = status and 3
        val error = status and 0x10 != 0
        val ext = u(raw, 4)
        val errCode = u(raw, 5)
        val spd = u(raw, 6)
        val tmpLvl = u(raw, 11)
        val scaleRaw = u(raw, 22)
        val calStep = u(raw, 23)
        var warnings: ArrayList<String>? = null
        fun warn(msg: String) {
            if (warnings == null) warnings = ArrayList(2)
            warnings!!.add(msg)
        }
        if (spd > 10) warn("Niveau de vitesse cible hors 0..10")
        if (tmpLvl > 19) warn("Niveau de température cible hors 0..19")
        if (ext != 0 && ext != 3 && ext != 4 && ext != 5) warn("État étendu inconnu")
        if (error && errCode !in ERROR_MAP) warn("Erreur non répertoriée")
        if (!error && errCode != 0) warn("Code d'erreur sans drapeau d'erreur")
        if (scaleRaw and 0xF8 != 0) warn("Bits de balance inconnus")
        if (scaleRaw and 7 == 0) warn("Aucun drapeau de balance")
        if (calStep !in 0..5 && calStep != 7) warn("Étape de calibration inconnue")
        val scale = when {
            scaleRaw and 4 != 0 -> "overload"
            scaleRaw and 1 != 0 -> "calibration"
            scaleRaw and 2 != 0 -> "normal"
            else -> "not_available"
        }
        val weight = (u(raw, 16) shl 24) or (u(raw, 17) shl 16) or (u(raw, 18) shl 8) or u(raw, 19)
        return Telemetry(
            raw = raw.copyOf(),
            status = status,
            motorHeaterState = mode,
            motorOn = mode == 1 || mode == 3,
            heaterOn = mode == 1 || mode == 2,
            scaleStable = status and 0x04 != 0,
            direction = if (status and 0x08 != 0) Direction.CLOCKWISE else Direction.COUNTER_CLOCKWISE,
            errorDetected = error,
            errorCode = errCode,
            androidErrorCode = if (error) ERROR_MAP[errCode] ?: 6 else 0,
            aboveTargetTemperature = status and 0x20 != 0,
            bowlNtcDetected = status and 0x40 != 0,
            lidOpen = status and 0x80 != 0,
            extendedState = ext,
            targetSpeedLevel = spd,
            targetRpm = word(raw, 7),
            actualRpm = word(raw, 9),
            targetTemperatureLevel = tmpLvl,
            targetTemperature = word(raw, 12),
            actualTemperature = word(raw, 14),
            weight = weight,
            firmwareVersion = word(raw, 20),
            scaleStateRaw = scaleRaw,
            scaleState = scale,
            calibrationStep = calStep,
            unknownByte24 = u(raw, 24),
            warnings = warnings ?: emptyList(),
        )
    }

    /** Message lisible pour un code d'erreur brut de la base. */
    fun boardErrorMessage(code: Int): String = when (code) {
        1 -> tr("Erreur de communication signalée par la base")
        3 -> tr("Surtension secteur")
        4 -> tr("Sous-tension secteur")
        5 -> tr("Erreur moteur")
        6 -> tr("Pas d'eau (vapeur)")
        7 -> tr("Moteur en surchauffe : laissez refroidir")
        8 -> tr("Durée maximale dépassée")
        else -> tr("Erreur de la base (code %d)", code)
    }
}

/**
 * Découpage robuste d'un flux d'octets : réassemble les trames fragmentées, accepte les
 * trames collées et se resynchronise après des octets corrompus. Garde au plus 26 octets.
 */
class MccStreamParser(expectedSize: Int = Mcc.RX_SIZE) : FixedFrameParser(
    frameSize = expectedSize.also { require(it == Mcc.TX_SIZE || it == Mcc.RX_SIZE) },
    byte0 = 0x55,
    byte1 = expectedSize,
    byte2 = if (expectedSize == Mcc.TX_SIZE) 0xA1 else 0xB1,
    validator = { Mcc.validate(it, expectedSize) },
)
