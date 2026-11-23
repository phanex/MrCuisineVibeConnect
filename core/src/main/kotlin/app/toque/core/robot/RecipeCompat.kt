package app.toque.core.robot

import app.toque.core.i18n.tr
import app.toque.core.machine.Programs
import app.toque.core.protocol.ConnectCodec
import app.toque.core.protocol.SmartCodec
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram

/**
 * Une recette est-elle faisable sur un robot ? Oui si le site la destine à ce robot (quand il le
 * dit) et si chaque réglage de ses étapes passe : programme présent sur le robot, température de
 * sa table, accessoire, et trame acceptée par son protocole. Les autres
 * recettes ne sont pas montrées (seulement comptées dans les Réglages).
 */
object RecipeCompat {

    /** Raisons pour lesquelles [r] n'est pas faisable sur [p] ; vide : faisable. */
    fun reasons(r: Recipe, p: RobotProfile): List<String> {
        val out = ArrayList<String>(1)
        if (r.devices.isNotEmpty() && p.lidlDevice !in r.devices)
            out += tr("prévue pour un autre robot (%s)", r.devices.joinToString())
        for (s in r.steps) {
            val m = s.machine ?: continue
            val why = stepReason(m, p) ?: continue
            if (why !in out) out += why
        }
        return out
    }

    fun supports(r: Recipe, p: RobotProfile): Boolean = reasons(r, p).isEmpty()

    /** Pourquoi ce réglage d'étape ne passe pas sur [p] (null : il passe). */
    fun stepReason(m: StepMachine, p: RobotProfile): String? {
        val caps = p.capabilities
        if (!caps.has(m.program.feature)) return tr("%s : absent de ce robot", m.program.label)
        if (m.program == StepProgram.TURBO) {
            return if (m.accessory !in caps.accessories) tr("%s : absent de ce robot", m.accessory.label) else null
        }
        if (m.accessory !in caps.accessories) return tr("%s : absent de ce robot", m.accessory.label)
        if (m.program == StepProgram.MANUAL) {
            if (m.tempC != 0 && m.tempC !in caps.temperaturesC) return tr("Température %d °C absente de la machine", m.tempC)
            if (m.speed == 0 && m.tempC > 0 && !caps.has(Feature.HEAT_NO_STIR))
                return tr("%s : absent de ce robot", Feature.HEAT_NO_STIR.label)
            if (m.speed > caps.maxSpeed) return tr("Vitesse %d : absente de ce robot", m.speed)
            if (m.reverse && m.speed > 0 && !caps.reverse) return tr("Sens inverse : absent de ce robot")
        }
        // Le protocole du robot accepte-t-il chaque trame ? (Le Smart n'accepte que les réglages vus sur
        // son appli d'origine.) Les règles de sécurité, elles, restent vérifiées au lancement de
        // l'étape, comme avant : une recette n'est pas cachée pour une durée trop longue, par exemple.
        // Connect : son protocole accepte tout réglage sans paramètre propre au Smart ; les étapes du
        // Smart sont déjà écartées plus haut (programme ou température absents). Rien de plus à
        // calculer : le tri des ~2 000 recettes reste rapide sur la tablette.
        if (p.validator === ConnectCodec) return null
        val job = m.toJob("vérification") ?: return null
        val spec = if (p.validator is SmartCodec) Programs.forSmart(job) else job
        for (ph in spec.phases) {
            val s = ph.setting
            if (s.isRest) continue
            if (!p.validator.accepts(s.operation, s.speed, s.temperatureLevel, s.direction, s.program, s.smartLevel, s.programCelsius))
                return tr("Réglage impossible sur ce robot : %s", m.summary)
        }
        return null
    }
}
