package app.toque.core.machine

import app.toque.core.i18n.tr
import app.toque.core.protocol.Direction
import app.toque.core.protocol.Mcc
import app.toque.core.protocol.Smart

/**
 * Bornes de saisie d'une cuisson manuelle : celles de l'écran Cuisson manuelle du robot. Le
 * téléphone reprend celles que le robot annonce (LimitsWire) ; le contrôleur revérifie tout.
 */
data class ManualLimits(
    val maxSpeedHeated: Int = Limits.MAX_SPEED_HEATED,
    val maxSpeedReverse: Int = Limits.MAX_SPEED_REVERSE,
    val whiskMaxSpeed: Int = Accessory.WHISK.maxSpeed,
    val maxTempLevelNoStir: Int = Limits.MAX_TEMP_LEVEL_NO_STIR,
    val minSeconds: Int = MIN_SECONDS,
    val maxSeconds: Int = (Limits.MAX_MS_DEFAULT / 1000).toInt(),
    val maxSecondsHighSpeed: Int = (Limits.MAX_MS_HIGH_SPEED / 1000).toInt(),
    /** Au-dessus de cette vitesse, la durée maximale est [maxSecondsHighSpeed]. */
    val highSpeedAbove: Int = HIGH_SPEED_ABOVE,
    /** Températures du robot (°C, sans 0) : le Smart a 40 °C en plus de celles du Connect. */
    val temperaturesC: List<Int> = CONNECT_TEMPERATURES,
    /** Chauffe lame à l'arrêt (vitesse 0) : seulement sur un robot qui la sait faire (Connect). */
    val heatNoStir: Boolean = true,
) {
    fun maxSpeed(accessory: Accessory): Int = if (accessory == Accessory.WHISK) whiskMaxSpeed else accessory.maxSpeed

    companion object {
        /** Durée minimale réglable sur l'écran (le contrôleur accepte dès 1 s). */
        const val MIN_SECONDS = 5
        const val HIGH_SPEED_ABOVE = 6
        val CONNECT_TEMPERATURES: List<Int> = Mcc.TEMPERATURE_LEVELS.filter { it > 0 }
    }
}

/**
 * Réglage d'une cuisson manuelle et ses règles de saisie, partagés par l'écran du robot et le
 * téléphone : mêmes crans, mêmes corrections automatiques, mêmes messages. Immuable.
 */
data class ManualSettings(
    val seconds: Int = 60,
    val tempLevel: Int = 0,
    val speed: Int = 2,
    val reverse: Boolean = false,
    val accessory: Accessory = Accessory.BLADE,
    val limits: ManualLimits = ManualLimits(),
    /** Smart, 40 °C : niveau de sa table ; [tempLevel] est alors celui de Toque juste en dessous (37 °C). Null sinon. */
    val smartLevel: Int? = null,
) {
    /** Le réglage obtenu et les messages à montrer (corrections automatiques), dans l'ordre. */
    data class Edit(val settings: ManualSettings, val messages: List<String> = emptyList())

    val tempC: Int get() = smartLevel?.let { Smart.celsiusForLevel(it) } ?: if (tempLevel == 0) 0 else Mcc.celsiusForLevel(tempLevel)

    /** Le même réglage à [c] °C (0 : sans chauffe) : niveau de Toque, et celui du Smart pour 40 °C. */
    fun withCelsius(c: Int): ManualSettings = copy(tempLevel = if (c > 0) Mcc.levelAtMost(c) else 0, smartLevel = smartLevelFor(c))

    /** Durée maximale (s) à cette vitesse. */
    val maxSeconds: Int get() = if (speed > limits.highSpeedAbove) limits.maxSecondsHighSpeed else limits.maxSeconds

    /** Durée suivante (+1) ou précédente (−1) : 5 s sous la minute, 30 s sous 10 min, puis 1 min. */
    fun nextSeconds(s: Int, dir: Int): Int {
        val step = when {
            dir > 0 && s < 60 || dir < 0 && s <= 60 -> 5
            dir > 0 && s < 600 || dir < 0 && s <= 600 -> 30
            else -> 60
        }
        return (s + dir * step).coerceIn(limits.minSeconds, maxSeconds)
    }

    /** Un cran de plus ([dir] = 1) ou de moins (−1) sur la carte [card] : 0 durée, 1 température, 2 vitesse. */
    fun change(card: Int, dir: Int): Edit {
        val msg = ArrayList<String>(2)
        var seconds = seconds
        var tempLevel = tempLevel
        var smartLevel = smartLevel
        var speed = speed
        when (card) {
            CARD_TIME -> seconds = nextSeconds(seconds, dir)
            CARD_TEMP -> {
                // Crans des températures du robot (0 = sans chauffe).
                val steps = listOf(0) + limits.temperaturesC
                val i = steps.indexOf(tempC).let { if (it >= 0) it else steps.indexOfLast { c -> c <= tempC }.coerceAtLeast(0) }
                val c = steps[(i + dir).coerceIn(0, steps.size - 1)]
                tempLevel = if (c > 0) Mcc.levelAtMost(c) else 0
                smartLevel = smartLevelFor(c)
                if (tempLevel > 0 && speed > limits.maxSpeedHeated) {
                    speed = limits.maxSpeedHeated
                    msg += tr("En chauffe, vitesse %d maximum", limits.maxSpeedHeated)
                }
                if (tempLevel == 0 && speed == 0) {
                    speed = 1
                    msg += tr("Sans chauffe, vitesse 1 minimum")
                }
            }
            CARD_SPEED -> {
                var max = 10
                var why = 0
                if (tempLevel > 0) { max = limits.maxSpeedHeated; why = 1 }
                if (reverse && max > limits.maxSpeedReverse) { max = limits.maxSpeedReverse; why = 2 }
                val acc = limits.maxSpeed(accessory)
                if (acc < max) { max = acc; why = 3 }
                // En chauffe, la vitesse descend jusqu'à 0 : la lame reste à l'arrêt (si le robot le sait faire).
                val min = if (tempLevel > 0 && limits.heatNoStir) 0 else 1
                val next = (speed + dir).coerceIn(min, 10)
                if (dir < 0 && speed == min && min == 1) msg += tr("Sans chauffe, vitesse 1 minimum")
                if (next > max) {
                    msg += when (why) {
                        1 -> tr("En chauffe, vitesse %d maximum", max)
                        2 -> tr("En sens inverse, vitesse %d maximum", max)
                        3 -> tr("Avec le fouet, vitesse %d maximum", max)
                        else -> tr("Vitesse %d maximum", max)
                    }
                    // Parti au-dessus du maximum (réglage d'une recette que la sécurité refuse) : « − » ramène au
                    // maximum, au lieu de ne rien changer tant que la température n'a pas été baissée.
                    if (dir < 0) speed = max
                } else speed = next
            }
        }
        var out = copy(seconds = seconds, tempLevel = tempLevel, smartLevel = smartLevel, speed = speed)
        if (card != CARD_TIME) out = out.capNoStirTemperature(msg)
        if (out.seconds > out.maxSeconds) out = out.copy(seconds = out.maxSeconds)
        return Edit(out, msg)
    }

    /** Sans mélange, la base chauffe par l'opération vapeur : 120 °C au plus, comme le programme Vapeur. */
    private fun capNoStirTemperature(msg: MutableList<String>): ManualSettings {
        if (speed != 0 || tempLevel <= limits.maxTempLevelNoStir) return this
        msg += tr("Sans mélange, %d °C maximum", Mcc.celsiusForLevel(limits.maxTempLevelNoStir))
        return copy(tempLevel = limits.maxTempLevelNoStir, smartLevel = null)
    }

    /** Sens de rotation ; null : sans mélange, pas de sens (rien ne change). */
    fun withReverse(r: Boolean): Edit? {
        if (speed == 0) return null
        if (r && speed > limits.maxSpeedReverse)
            return Edit(copy(speed = limits.maxSpeedReverse, reverse = true), listOf(tr("En sens inverse, vitesse %d maximum", limits.maxSpeedReverse)))
        return Edit(copy(reverse = r))
    }

    fun withAccessory(acc: Accessory): Edit {
        val max = limits.maxSpeed(acc)
        if (speed > max) return Edit(copy(accessory = acc, speed = max), listOf(tr("%s : vitesse %d maximum", acc.label, max)))
        return Edit(copy(accessory = acc))
    }

    /** Sens envoyé : jamais l'inverse sans mélange. */
    val direction: Direction get() = if (reverse) Direction.COUNTER_CLOCKWISE else Direction.CLOCKWISE

    fun spec(hotBlendConfirmed: Boolean = false, label: String = JobKind.MANUAL.label): JobSpec =
        Programs.manual(speed, tempLevel, direction, seconds * 1000L, accessory, hotBlendConfirmed, label, smartLevel = smartLevel)

    /** Ce qui s'oppose au démarrage, mixage à chaud compris ([bowlC] : température du bol, null si inconnue). */
    fun violations(bowlC: Int?): List<Violation> = SafetyPolicy.checkJob(spec(), bowlC)

    /**
     * Le réglage sort des bornes de l'écran du robot (premier écart), ou null. Rien n'est corrigé :
     * sert à refuser un réglage reçu d'ailleurs (téléphone).
     */
    fun outOfBounds(): Violation? = when {
        speed !in 0..10 -> Violation("speed_range", tr("Vitesse hors plage (0 à 10)"))
        tempLevel !in 0 until Mcc.TEMPERATURE_LEVELS.size -> Violation("temp_range", tr("Température hors table"))
        seconds < limits.minSeconds -> Violation("too_short", tr("Durée trop courte"))
        seconds > maxSeconds -> Violation("too_long", tr("Durée maximale : %s", SafetyPolicy.formatDuration(maxSeconds * 1000L)))
        speed == 0 && tempLevel == 0 -> Violation("speed_min", tr("Vitesse minimale : 1"))
        speed == 0 && !limits.heatNoStir -> Violation("speed_min", tr("Vitesse minimale : 1"))
        tempC > 0 && tempC !in limits.temperaturesC -> Violation("temp_range", tr("Température hors table"))
        speed == 0 && tempLevel > limits.maxTempLevelNoStir ->
            Violation("no_stir_temp", tr("Sans mélange, %d °C maximum", Mcc.celsiusForLevel(limits.maxTempLevelNoStir)))
        speed == 0 && reverse -> Violation("no_stir_dir", tr("Sans mélange : sens normal uniquement"))
        tempLevel > 0 && speed > limits.maxSpeedHeated -> Violation("heated_speed", tr("En chauffe, vitesse %d maximum", limits.maxSpeedHeated))
        reverse && speed > limits.maxSpeedReverse -> Violation("reverse_speed", tr("En sens inverse, vitesse %d maximum", limits.maxSpeedReverse))
        speed > limits.maxSpeed(accessory) -> Violation("accessory_speed", tr("%s : vitesse %d maximum", accessory.label, limits.maxSpeed(accessory)))
        else -> null
    }

    companion object {
        const val CARD_TIME = 0
        const val CARD_TEMP = 1
        const val CARD_SPEED = 2

        /** Niveau de la table du Smart pour une température absente de celle de Toque (40 °C), sinon null. */
        fun smartLevelFor(c: Int): Int? =
            if (c > 0 && Mcc.levelForCelsius(c) < 0 && c in Smart.TEMPERATURE_LEVELS) Smart.TEMPERATURE_LEVELS.indexOf(c) else null
    }
}
