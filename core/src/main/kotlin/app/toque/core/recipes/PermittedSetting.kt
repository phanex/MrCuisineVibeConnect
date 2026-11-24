package app.toque.core.recipes

import app.toque.core.i18n.tr
import app.toque.core.machine.Accessory
import app.toque.core.machine.Limits
import app.toque.core.machine.SafetyPolicy
import app.toque.core.protocol.Mcc
import app.toque.core.robot.RecipeCompat
import app.toque.core.robot.RobotProfiles

/**
 * Réglage permis le plus proche d'un réglage d'étape refusé par la sécurité ([StepMachine.violations]).
 * Les règles ne changent pas : on ne fait que baisser ce qui dépasse. Jamais plus de vitesse, de
 * chaleur ou de temps que la recette, jamais un autre programme, un autre sens ou un autre accessoire.
 *
 * - vitesse au-dessus du maximum permis (chauffe, sens inverse, accessoire) : ce maximum ;
 * - température absente de la table du robot choisi : la valeur de sa table juste en dessous ;
 * - sans mélange au-dessus de la température permise : cette température ;
 * - durée au-dessus du maximum (selon la vitesse finale) : ce maximum.
 *
 * Rien n'est proposé quand le réglage est déjà permis, ou quand il n'y a pas de devinette honnête :
 * durée nulle, vitesse négative, température sous la première valeur de la table, Turbo avec le
 * fouet, programme que le robot choisi n'a pas, tout refus qui n'est pas un « trop ».
 */
object PermittedSetting {

    /** Le réglage permis le plus proche de [m], ou null (déjà permis, ou rien d'honnête à proposer). */
    fun of(m: StepMachine): StepMachine? = try {
        nearest(m)
    } catch (_: IllegalStateException) {
        // Réglage que le cœur ne sait pas décrire (programme du Smart à une température hors de sa
        // plage : StepMachine.toJob s'y refuse) : rien à proposer.
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun nearest(m: StepMachine): StepMachine? {
        // Turbo : son seul refus est le fouet ; changer d'accessoire n'est pas baisser.
        if (m.program == StepProgram.TURBO) return null
        if (m.violations().isEmpty()) return null
        // Durée nulle (ou trop courte) : on ne devine pas une durée.
        if (m.seconds * 1000L < Limits.MIN_MS) return null

        var tempC = m.tempC
        var speed = m.speed
        if (m.program == StepProgram.MANUAL) {
            // La table est celle du robot choisi (le Smart a 40 °C en plus), comme dans violations().
            val table = RobotProfiles.active.capabilities.temperaturesC
            if (tempC != 0 && tempC !in table) {
                // Sous la première température de la table (ou négative) : plus rien de plus bas qui chauffe.
                tempC = table.filter { it <= tempC }.maxOrNull() ?: return null
            }
            if (speed < 0) return null
            val level = Mcc.levelAtMost(tempC)
            var max = m.accessory.maxSpeed
            if (level > 0) max = minOf(max, Limits.MAX_SPEED_HEATED)
            if (m.reverse) max = minOf(max, Limits.MAX_SPEED_REVERSE)
            if (speed > max) speed = max
            if (speed == 0 && level > Limits.MAX_TEMP_LEVEL_NO_STIR) tempC = Mcc.celsiusForLevel(Limits.MAX_TEMP_LEVEL_NO_STIR)
        }
        val lowered = m.copy(speed = speed, tempC = tempC)
        // Durée maximale d'après le réglage baissé (au-dessus de la vitesse 6, elle est plus courte).
        val job = lowered.toJob("") ?: return null
        val maxMs = SafetyPolicy.maxDurationMs(job)
        val seconds = if (m.seconds * 1000L > maxMs) (maxMs / 1000L).toInt() else m.seconds
        val out = lowered.copy(seconds = seconds)
        // Ce qui reste refusé (vitesse minimale, sens…) n'est pas un « trop » : rien à proposer.
        if (out == m || out.violations().isNotEmpty() || !atMost(out, m)) return null
        // Jamais un réglage que le robot choisi ne sait pas faire (chauffe sans mélange sur le Smart…).
        if (RecipeCompat.stepReason(out, RobotProfiles.active) != null) return null
        return out
    }

    /** [a] ne demande rien de plus que [b] : même programme, sens, accessoire et impulsions, chaque valeur au plus celle de [b]. */
    fun atMost(a: StepMachine, b: StepMachine): Boolean =
        a.program == b.program && a.reverse == b.reverse && a.accessory == b.accessory && a.pulses == b.pulses &&
            a.seconds <= b.seconds && a.speed <= b.speed && a.tempC <= b.tempC

    /** Ce qui change de [from] à [to], lisible : « vitesse 3 au lieu de 4 ». */
    fun changes(from: StepMachine, to: StepMachine): String {
        val out = ArrayList<String>(3)
        if (to.seconds != from.seconds) out += tr("%s au lieu de %s",
            SafetyPolicy.formatDuration(to.seconds * 1000L), SafetyPolicy.formatDuration(from.seconds * 1000L))
        if (to.tempC != from.tempC) out += tr("%d °C au lieu de %d °C", to.tempC, from.tempC)
        if (to.speed != from.speed) out += tr("vitesse %d au lieu de %d", to.speed, from.speed)
        return out.joinToString(", ")
    }

    /**
     * Le réglage tel que la recette l'écrit. Comme [StepMachine.summary], sauf la température :
     * celle de la recette, même absente de la table (le résumé montre celle que la machine ferait).
     */
    fun asked(m: StepMachine): String {
        if (m.program != StepProgram.MANUAL || m.tempC == 0 || Mcc.levelForCelsius(m.tempC) >= 0) return m.summary
        return buildString {
            append(SafetyPolicy.formatDuration(m.seconds * 1000L))
            append(" · ").append(m.tempC).append(" °C")
            append(" · ").append(if (m.speed == 0) tr("sans mélange") else tr("vitesse %d", m.speed))
            if (m.reverse && m.speed > 0) append(" · ").append(tr("inverse"))
            if (m.accessory == Accessory.WHISK) append(" · ").append(tr("fouet"))
        }
    }
}
