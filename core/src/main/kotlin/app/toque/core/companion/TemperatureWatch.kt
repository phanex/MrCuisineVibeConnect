package app.toque.core.companion

import app.toque.core.protocol.Mcc

/**
 * « Température atteinte » : quand l'alerter, une fois par programme et par température demandée.
 * Kotlin pur ; [CompanionHub] l'appelle à chaque Snapshot sous son verrou.
 *
 * - Seulement pour une cuisson manuelle à une phase ou une étape d'une recette guidée ([eligible]),
 *   qui chauffe (niveau > 0) et dont le minuteur avance ([running]).
 * - La première fois que le bol arrive à [toleranceC] °C de la température demandée (certaines ne sont
 *   jamais atteintes au degré près : l'eau bout à 100 °C).
 * - Jamais si le bol y était déjà au départ ou à la reprise (rien à attendre) : la décision est prise
 *   au premier relevé après chaque passage en marche.
 * - Température relevée en cours de route avec un bol en dessous : de nouveau armée ; abaissée sous la
 *   température du bol : plus rien à attendre (refroidissement).
 * - Capteur de cette base : 0 sous 37 °C (jamais « atteinte »), et seulement avec un relevé ([hasSample]).
 *   Sur le Smart, l'appelant ne donne un relevé que quand la base mesure (en chauffant).
 * - [targetC] : consigne exacte quand le niveau ne la dit pas (40 °C du Smart, niveau 37 °C) ; 0 : celle
 *   du niveau.
 */
class TemperatureWatch(private val toleranceC: Int = 2) {
    private var job = Long.MIN_VALUE
    private var target = 0
    /** Plus haute température déjà annoncée pour ce programme. */
    private var announced = 0
    private var decided = false
    private var armed = false
    private var wasRunning = false

    private fun reached(actualC: Int) = target > 0 && actualC > 0 && actualC >= target - toleranceC

    /** Température demandée (°C) à annoncer maintenant, ou null. */
    fun update(jobGeneration: Long, eligible: Boolean, running: Boolean, tempLevel: Int, hasSample: Boolean, actualC: Int,
               targetC: Int = 0): Int? {
        val t = if (eligible && tempLevel > 0) (if (targetC > 0) targetC else Mcc.celsiusForLevel(tempLevel)) else 0
        if (jobGeneration != job) {
            job = jobGeneration
            target = t
            announced = 0
            decided = false
            armed = false
            wasRunning = false
        } else if (t != target) {
            val old = target
            target = t
            if (t > old) decided = false
            else if (hasSample && reached(actualC)) armed = false
        }
        if (!running) {
            wasRunning = false
            return null
        }
        if (!wasRunning) {
            // Départ ou reprise : décider de nouveau au premier relevé.
            wasRunning = true
            decided = false
        }
        if (!hasSample || target <= 0) return null
        if (!decided) {
            decided = true
            armed = target > announced && !reached(actualC)
            return null
        }
        if (armed && reached(actualC)) {
            armed = false
            announced = target
            return target
        }
        return null
    }
}
