package app.toque.core.machine

import app.toque.core.i18n.tr
import app.toque.core.protocol.Telemetry
import kotlin.math.abs

/**
 * Suivi du bol et de la balance : stabilité du poids (tampon circulaire sans allocation),
 * apprentissage du sens du capteur du bol, mémorisation de la masse du bol vide et remise à zéro
 * automatique au retour du bol vide sur le socle.
 */
internal class BowlScaleTracker(
    private val config: () -> ControllerConfig,
    private val isBusyOrFaulted: () -> Boolean,
    private val requestTare: () -> Unit,
    private val emit: (EventType, String, Int) -> Unit,
) {
    /** Bol mémorisé, appris pendant cette session ou lu dans la configuration. */
    var emptyBowlG: Int = config().emptyBowlG
        private set

    /** Valeur du bit « bol » quand le bol est en place, relevée couvercle fermé. */
    var bowlBitSeated: Boolean? = config().bowlBitSeated
        private set

    var scaleStableComputed: Boolean = false
        private set

    private var bowlRaw: Boolean? = null
    private var bowlRawSinceMs = 0L
    /** État du bol après anti-rebond ; null = inconnu (début de liaison). */
    private var bowlIn: Boolean? = null
    private var stableSinceMs: Long? = null
    /** Poids lu socle vide, dans le repère du zéro actuel de la base ; null = inconnu. */
    private var baseRefG: Int? = null
    /** Bol replacé à cet instant, masse encore à évaluer. */
    private var bowlReturnedAtMs: Long? = null
    private var lastReturnMassG: Int? = null
    private var learnUntilMs = Long.MIN_VALUE / 4
    private var bowlHintShown = false
    private var closedBowlBit: Boolean? = null
    private var closedBowlBitSinceMs = 0L

    // Tampon circulaire primitif des poids récents (aucune allocation Pair/Long/Int par trame).
    private val weightTimes = LongArray(WEIGHT_CAP)
    private val weightVals = IntArray(WEIGHT_CAP)
    private var weightHead = 0
    private var weightCount = 0

    /** Bol présent d'après la base, en tenant compte de l'inversion réglée pour ce robot. */
    fun bowlPresent(t: Telemetry): Boolean = t.bowlNtcDetected != config().invertBowl

    /** Bol retiré, et pas seulement couvercle ouvert : le bit « bol » n'a plus sa valeur « en place ». */
    fun bowlRemoved(t: Telemetry): Boolean {
        val seated = bowlBitSeated ?: return false
        return t.lidOpen && t.bowlNtcDetected != seated
    }

    /** Met à jour la stabilité de la balance, le sens du capteur et la détection du retour du bol. */
    fun onSample(t: Telemetry, now: Long) {
        val cfg = config()
        if (t.lidOpen) closedBowlBit = null
        else if (closedBowlBit != t.bowlNtcDetected) {
            closedBowlBit = t.bowlNtcDetected
            closedBowlBitSinceMs = now
        } else if (now - closedBowlBitSinceMs >= cfg.bowlSenseMs) bowlBitSeated = closedBowlBit

        pushWeight(now, t.weight)
        scaleStableComputed = if (weightCount >= 3) {
            var minW = Int.MAX_VALUE
            var maxW = Int.MIN_VALUE
            for (i in 0 until weightCount) {
                val w = weightVals[(weightHead + i) and WEIGHT_MASK]
                if (w < minW) minW = w
                if (w > maxW) maxW = w
            }
            (maxW - minW) <= 5
        } else false

        val raw = bowlPresent(t)
        if (raw != bowlRaw) {
            bowlRaw = raw
            bowlRawSinceMs = now
        }
        if (now - bowlRawSinceMs >= BowlScale.DEBOUNCE_MS && raw != bowlIn) {
            val was = bowlIn
            bowlIn = raw
            if (!raw) {
                bowlReturnedAtMs = null
                learnUntilMs = Long.MIN_VALUE / 4
            } else if (was == false) {
                bowlReturnedAtMs = now
                lastReturnMassG = null
            }
        }
        if (t.scaleState != "normal" || !scaleStableComputed) {
            stableSinceMs = null
            return
        }
        val since = stableSinceMs ?: now.also { stableSinceMs = it }
        if (now - since < BowlScale.SETTLE_MS || bowlRaw != bowlIn) return
        if (bowlIn == false) {
            baseRefG = t.weight
            return
        }
        val returned = bowlReturnedAtMs ?: return
        if (now - returned > BowlScale.RETURN_WINDOW_MS) {
            bowlReturnedAtMs = null
            return
        }
        val base = baseRefG ?: return
        val mass = t.weight - base
        val last = lastReturnMassG
        if (last != null && abs(mass - last) <= BowlScale.TOLERANCE_G) return
        lastReturnMassG = mass
        learnUntilMs = now + BowlScale.LEARN_WINDOW_MS
        if (cfg.bowlAutoZero) onBowlReturned(mass)
    }

    private fun onBowlReturned(massG: Int) {
        val empty = emptyBowlG
        when {
            empty <= 0 -> if (!bowlHintShown) {
                bowlHintShown = true
                emit(EventType.SCALE_INFO, tr("Bol replacé. S'il est vide, appuyez sur Tare : Toque retiendra son poids et remettra ensuite la balance à zéro à chaque retour du bol vide."), 0)
            }
            abs(massG - empty) <= BowlScale.TOLERANCE_G -> if (!isBusyOrFaulted()) {
                requestTare()
                bowlReturnedAtMs = null
                learnUntilMs = Long.MIN_VALUE / 4
                emit(EventType.SCALE_ZEROED, tr("Bol vide replacé : balance remise à zéro"), 0)
            }
            massG > empty + BowlScale.TOLERANCE_G ->
                emit(EventType.SCALE_INFO, tr("Bol replacé avec environ %d g de contenu : balance laissée telle quelle", massG - empty), 0)
            else -> Unit // plus léger que le bol mémorisé (couvercle pas encore posé…) : on attend
        }
    }

    /** Apprentissage éventuel du poids du bol vide lors d'une tare manuelle. */
    fun onManualTare(t: Telemetry?, now: Long, busy: Boolean) {
        val base = baseRefG
        if (config().bowlAutoZero && bowlIn == true && t != null && base != null && t.scaleState == "normal" &&
            now <= learnUntilMs && !busy) {
            val mass = t.weight - base
            val unchanged = lastReturnMassG?.let { abs(mass - it) <= BowlScale.TOLERANCE_G } == true
            if (unchanged && mass in BowlScale.MIN_EMPTY_G..BowlScale.MAX_EMPTY_G) {
                emptyBowlG = mass
                learnUntilMs = Long.MIN_VALUE / 4
                emit(EventType.BOWL_LEARNED, tr("Poids du bol vide mémorisé : %d g", mass), mass)
            }
        }
    }

    fun forgetEmptyBowl() {
        emptyBowlG = 0
        bowlHintShown = false
    }

    fun onTareRequested() {
        clearStability()
        stableSinceMs = null
    }

    fun onTareSent(currentWeight: Int?) {
        baseRefG = if (currentWeight == null) null else baseRefG?.minus(currentWeight)
    }

    fun clearStability() {
        weightCount = 0
        weightHead = 0
        scaleStableComputed = false
    }

    fun onLinkDown() {
        baseRefG = null
        bowlIn = null
        bowlRaw = null
        bowlReturnedAtMs = null
        stableSinceMs = null
    }

    private fun pushWeight(now: Long, weight: Int) {
        if (weightCount == WEIGHT_CAP) {
            weightHead = (weightHead + 1) and WEIGHT_MASK
            weightCount--
        }
        val tail = (weightHead + weightCount) and WEIGHT_MASK
        weightTimes[tail] = now
        weightVals[tail] = weight
        weightCount++
        while (weightCount > 0 && now - weightTimes[weightHead] > 600L) {
            weightHead = (weightHead + 1) and WEIGHT_MASK
            weightCount--
        }
    }

    private companion object {
        const val WEIGHT_CAP = 16
        const val WEIGHT_MASK = WEIGHT_CAP - 1
    }
}
