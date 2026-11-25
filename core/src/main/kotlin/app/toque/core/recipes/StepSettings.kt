package app.toque.core.recipes

import app.toque.core.i18n.tr
import app.toque.core.machine.Accessory
import app.toque.core.machine.Limits
import app.toque.core.machine.ManualLimits
import app.toque.core.machine.ManualSettings
import app.toque.core.machine.SafetyPolicy
import app.toque.core.protocol.SmartProgram
import app.toque.core.robot.Feature
import app.toque.core.robot.RecipeCompat
import app.toque.core.robot.RobotProfile

/**
 * Un genre de réglage que l'éditeur d'étape du robot propose (manuel, pétrir, vapeur…) : les valeurs
 * que l'on y règle ([params]), son réglage de départ ([default], qui donne aussi les valeurs fixes des
 * autres champs), et ses bornes telles que l'éditeur les présente. 0 : sans objet.
 */
data class SettingKind(
    val program: StepProgram,
    /** Champs réglables : seconds, tempC, speed, reverse, accessory, pulses (les autres restent ceux de [default]). */
    val params: List<String>,
    val default: StepMachine,
    val minSeconds: Int = 0,
    val maxSeconds: Int = 0,
    /** Pas de la durée (0 : celui de la cuisson manuelle, [ManualSettings.nextSeconds]). */
    val secondsStep: Int = 0,
    val minTempC: Int = 0,
    val maxTempC: Int = 0,
    val minPulses: Int = 0,
    val maxPulses: Int = 0,
    /** Le rappel de l'éditeur du robot sous ce réglage (vide : aucun). */
    val hint: String = "",
)

/**
 * Les réglages d'étape de l'éditeur de recettes du robot, en un seul endroit : l'éditeur d'étape du
 * robot y prend ses réglages de départ et ses bornes, le robot les annonce au téléphone, et juge
 * avec eux un réglage écrit sur le téléphone ([refusal]). Rien ici ne démarre quoi que ce soit.
 */
object StepSettings {
    const val SECONDS = "seconds"
    const val TEMP = "tempC"
    const val SPEED = "speed"
    const val REVERSE = "reverse"
    const val ACCESSORY = "accessory"
    const val PULSES = "pulses"
    val FIELDS = listOf(SECONDS, SPEED, TEMP, REVERSE, ACCESSORY, PULSES)

    /** Impulsions conseillées d'un Turbo, comme l'éditeur du robot. */
    const val MIN_PULSES = 1
    const val MAX_PULSES = 10

    /** Réglage de départ d'un genre choisi dans l'éditeur (durée et température par défaut de l'appli d'origine). */
    fun default(p: StepProgram): StepMachine = when (p) {
        StepProgram.MANUAL -> StepMachine(seconds = 60, speed = 2)
        StepProgram.KNEAD -> StepMachine(StepProgram.KNEAD, seconds = 90, speed = 4)
        StepProgram.STEAM -> StepMachine(StepProgram.STEAM, seconds = 20 * 60)
        StepProgram.TOAST -> StepMachine(StepProgram.TOAST, seconds = 7 * 60)
        StepProgram.TURBO -> StepMachine(StepProgram.TURBO, pulses = 3)
        StepProgram.MIXER -> StepMachine(StepProgram.MIXER, seconds = 30)
        StepProgram.SMOOTHIE -> StepMachine(StepProgram.SMOOTHIE, seconds = 60)
        StepProgram.SOUS_VIDE -> StepMachine(StepProgram.SOUS_VIDE, seconds = 3600, tempC = 60)
        StepProgram.SLOW_COOK -> StepMachine(StepProgram.SLOW_COOK, seconds = 2 * 3600, tempC = 90)
        StepProgram.FERMENT -> StepMachine(StepProgram.FERMENT, seconds = 8 * 3600, tempC = 40)
    }

    /** Programme du Smart qui porte ce genre (bornes de son appli d'origine), ou null. */
    fun smartProgram(p: StepProgram): SmartProgram? = when (p) {
        StepProgram.MIXER -> SmartProgram.MIXER
        StepProgram.SMOOTHIE -> SmartProgram.SMOOTHIE
        StepProgram.SOUS_VIDE -> SmartProgram.SOUS_VIDE
        StepProgram.SLOW_COOK -> SmartProgram.MIJOTER_BRASSE
        StepProgram.FERMENT -> SmartProgram.FERMENTER_BRASSE
        else -> null
    }

    /** Le rappel de l'éditeur du robot sous un programme (vide : aucun). */
    fun hint(p: StepProgram): String = when (p) {
        StepProgram.STEAM -> tr("Mettez au moins 500 g d'eau dans le bol. 120 °C, la lame reste immobile.")
        StepProgram.TOAST -> tr("Dorer oignons, lardons, viandes à 130 °C, avec un brassage doux en sens inverse.")
        StepProgram.KNEAD -> tr("Pâtes à pain, pizza, brioche. Alterne sens inverse et normal à vitesse 4.")
        StepProgram.MIXER, StepProgram.SMOOTHIE, StepProgram.SOUS_VIDE, StepProgram.SLOW_COOK, StepProgram.FERMENT ->
            tr("Programme du Monsieur Cuisine Smart, comme sur son appli d'origine.")
        else -> ""
    }

    /** Le genre [p] tel que l'éditeur du robot le présente. */
    fun kind(p: StepProgram): SettingKind {
        val d = default(p)
        val sp = smartProgram(p)
        return when {
            p == StepProgram.MANUAL -> SettingKind(p, listOf(SECONDS, TEMP, SPEED, REVERSE, ACCESSORY), d,
                ManualLimits.MIN_SECONDS, (Limits.MAX_MS_DEFAULT / 1000).toInt())
            p == StepProgram.TURBO -> SettingKind(p, listOf(PULSES), d, minPulses = MIN_PULSES, maxPulses = MAX_PULSES)
            sp != null -> {
                val step = if (sp.maxS <= 600) 10 else if (sp.maxS <= 3 * 3600) 5 * 60 else 15 * 60
                SettingKind(p, if (p.heated) listOf(SECONDS, TEMP) else listOf(SECONDS), d, sp.minS, sp.maxS, step,
                    if (p.heated) sp.minCelsius else 0, if (p.heated) sp.maxCelsius else 0, hint = hint(p))
            }
            else -> {
                // Pétrir, vapeur, rissoler : durée seule, de un pas à la durée maximale du programme.
                val step = when (p) { StepProgram.KNEAD -> 15; StepProgram.TOAST -> 30; else -> 60 }
                val max = when (p) {
                    StepProgram.KNEAD -> (Limits.MAX_MS_KNEAD / 1000).toInt()
                    StepProgram.TOAST -> (Limits.MAX_MS_TOAST / 1000).toInt()
                    else -> (Limits.MAX_MS_DEFAULT / 1000).toInt()
                }
                SettingKind(p, listOf(SECONDS), d, step, max, step, hint = hint(p))
            }
        }
    }

    /** Les genres que l'éditeur du robot propose sur [robot] (les programmes qu'il a), dans l'ordre de l'éditeur. */
    fun kinds(robot: RobotProfile): List<SettingKind> =
        StepProgram.values().filter { robot.capabilities.has(it.feature) }.map { kind(it) }

    /** Bornes de la cuisson manuelle de l'éditeur sur [robot] (comme l'écran Cuisson manuelle du robot). */
    fun manualLimits(robot: RobotProfile): ManualLimits =
        ManualLimits(temperaturesC = robot.capabilities.temperaturesC, heatNoStir = robot.capabilities.has(Feature.HEAT_NO_STIR))

    /**
     * Réglage de départ de l'écran de cuisson manuelle, d'après [preset] (null : 1 min, vitesse 2) : la
     * température de la table juste en dessous, la vitesse ramenée dans ses bornes. Le même sur le robot
     * et sur le téléphone.
     */
    fun manualStart(preset: StepMachine?, limits: ManualLimits): ManualSettings {
        val table = limits.temperaturesC
        val c0 = preset?.tempC?.let { c -> if (c == 0) 0 else table.lastOrNull { it <= c } ?: table.first() } ?: 0
        return ManualSettings(
            seconds = preset?.seconds?.takeIf { it > 0 } ?: 60,
            // Vitesse 0 = chauffe sans mélange : seulement avec une température, et si le robot le permet.
            speed = (preset?.speed ?: 2).coerceIn(if (c0 > 0 && limits.heatNoStir) 0 else 1, 10),
            reverse = preset?.reverse ?: false,
            accessory = preset?.accessory ?: Accessory.BLADE,
            limits = limits,
        ).withCelsius(c0)
    }

    /** Le réglage d'étape que donne l'écran de cuisson manuelle (« Utiliser ce réglage ») : pas de sens sans mélange. */
    fun manualPicked(s: ManualSettings): StepMachine =
        StepMachine(seconds = s.seconds, speed = s.speed, tempC = s.tempC, reverse = s.reverse && s.speed > 0, accessory = s.accessory)

    fun value(m: StepMachine, field: String): Any = when (field) {
        SECONDS -> m.seconds
        SPEED -> m.speed
        TEMP -> m.tempC
        REVERSE -> m.reverse
        ACCESSORY -> m.accessory
        else -> m.pulses
    }

    /**
     * Pourquoi le robot [robot] refuse [m] écrit ailleurs que dans son éditeur (le téléphone), ou null :
     * ce que son éditeur ne propose pas (genre absent du robot, champ qui n'est pas celui du genre,
     * valeur hors de ses bornes), puis les règles de sécurité ([StepMachine.violations]) et ce que le
     * robot sait faire ([RecipeCompat.stepReason]). Ne lève jamais : un réglage que le cœur ne sait pas
     * décrire (programme du Smart hors de sa plage) est refusé.
     */
    fun refusal(m: StepMachine, robot: RobotProfile): String? = try {
        editor(m, robot) ?: m.violations().firstOrNull()?.message ?: RecipeCompat.stepReason(m, robot)
    } catch (_: IllegalStateException) {
        impossible(m)
    } catch (_: IllegalArgumentException) {
        impossible(m)
    }

    private fun impossible(m: StepMachine): String {
        val what = try { m.summary } catch (_: RuntimeException) { m.program.label }
        return tr("Réglage impossible sur ce robot : %s", what)
    }

    /** Ce que l'éditeur d'étape du robot ne laisse pas régler, ou null. */
    private fun editor(m: StepMachine, robot: RobotProfile): String? {
        if (!robot.capabilities.has(m.program.feature)) return tr("%s : absent de ce robot", m.program.label)
        val k = kind(m.program)
        for (f in FIELDS) if (f !in k.params && value(m, f) != value(k.default, f)) return tr("Réglage non proposé par l'éditeur du robot")
        return when (m.program) {
            StepProgram.MANUAL -> {
                val caps = robot.capabilities
                if (m.tempC != 0 && m.tempC !in caps.temperaturesC) return tr("Température %d °C absente de la machine", m.tempC)
                ManualSettings(seconds = m.seconds, speed = m.speed, reverse = m.reverse, accessory = m.accessory, limits = manualLimits(robot))
                    .withCelsius(m.tempC).outOfBounds()?.message
            }
            StepProgram.TURBO -> if (m.pulses !in k.minPulses..k.maxPulses) tr("Impulsions : de %d à %d", k.minPulses, k.maxPulses) else null
            else -> when {
                m.seconds !in k.minSeconds..k.maxSeconds -> tr("Durée : de %s à %s",
                    SafetyPolicy.formatDuration(k.minSeconds * 1000L), SafetyPolicy.formatDuration(k.maxSeconds * 1000L))
                k.maxTempC > 0 && m.tempC !in k.minTempC..k.maxTempC -> tr("%s : de %d à %d °C", m.program.label, k.minTempC, k.maxTempC)
                else -> null
            }
        }
    }
}
