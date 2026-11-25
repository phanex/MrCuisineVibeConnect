package app.toque.core.recipes

import app.toque.core.machine.Accessory
import app.toque.core.robot.RecipeCompat
import app.toque.core.robot.RobotProfile
import app.toque.core.robot.RobotProfiles
import java.text.Normalizer
import kotlin.math.floor

/**
 * Réglage machine écrit dans le texte d'une étape (recettes Mealie, souvent écrites pour un robot de
 * cuisine : « cuire 5 min/100 °C/vitesse 1 », « 10 Sek./Stufe 5 », « Turbo 3 fois », « mode pétrin
 * 2 min »), pour le proposer à vérifier : il n'est jamais lancé sans l'accord de l'utilisateur
 * ([Step.needsReview]). Français, anglais, allemand, espagnol, catalan et italien.
 *
 * Prudent : dans le doute, rien. Un seul réglage par étape (deux vitesses, ou une vitesse et un
 * Turbo : rien) ; une seule durée dans la phrase du réglage ; une fourchette donne sa plus petite
 * valeur, une vitesse à virgule est arrondie en dessous ; rien en °F ni avec le Varoma ; une cuisson
 * manuelle sans vitesse écrite n'est pas devinée.
 */
object StepText {

    private const val NUM = "\\d+(?:\\.\\d+)?"
    private const val TO = "(?:-|–|—|a|to|bis|ou|or|o|y|i)"
    private const val UNIT_H = "h|hr|hrs|heures?|hours?|horas?|hores|std|stunden?|ore|ora"
    private const val UNIT_M = "min|mins|mn|minutes?|minutos?|minuten|minuts?|minuti"
    private const val UNIT_S = "s|sec|secs|secondes?|seconds?|seg|segs|segundos?|sek|sekunden?|segons?|secondi"

    private val DURATION = Regex("($NUM)(?:\\s*$TO\\s*$NUM)?\\s*($UNIT_H|$UNIT_M|$UNIT_S)(?![a-z])" +
        "(?:\\s*(?:et\\s+|and\\s+|und\\s+|y\\s+|i\\s+|e\\s+)?(\\d{1,2})(?:\\s*($UNIT_M|$UNIT_S)(?![a-z])|(?![\\d.])(?!\\s*[a-z°%])))?")
    private val SPEED = Regex("\\b(?:vitesse|vit|speed|stufe|vel|velocidad|velocitat|velocita|velocidade)\\s*($NUM)(?:\\s*$TO\\s*$NUM)?")
    /** Vitesse « mijotage », « soft », « Sanftrührstufe », « cuchara » : rien d'équivalent sûr. */
    private val SOFT = Regex("\\b(?:mijotage|sanftruhrstufe|velocidad\\s+cuchara|velocitat\\s+cullera|speed\\s*soft|soft\\s*speed)\\b|/\\s*soft\\b|\\bsoft\\s*/")
    private val TEMPERATURE = Regex("(\\d{2,3})(?:\\s*(?:-|–|a|to|bis)\\s*\\d{2,3})?\\s*(?:°\\s*c?|degres?(?:\\s*c)?\\b|degrees?\\b|grad\\b|grados\\b|graus\\b|gradi\\b)")
    private val FAHRENHEIT = Regex("\\d\\s*°\\s*f\\b|\\bfahrenheit\\b")
    private val REVERSE = Regex("\\b(?:sens\\s+inverse|inverse|reverse|linkslauf|gegenlauf|giro\\s+a\\s+la\\s+izquierda|giro\\s+inverso|gir\\s+a\\s+l'esquerra|gir\\s+invers|antihoraire|counter-?clockwise|anti-?clockwise|antiorario)\\b|[⟲↺]")
    private val WHISK = Regex("\\b(?:fouet|whisk|butterfly|ruhraufsatz|ruehraufsatz|schmetterling|mariposa|papallona|farfalla)\\b")
    /** « retirer le fouet », « remove the butterfly » : dans la même phrase, on ne sait plus. */
    private val REMOVE = Regex("\\b(?:retirer|retirez|enlever|enlevez|oter|otez|sans|remove|without|take\\s+out|entfernen|abnehmen|ohne|quitar|retirar|sin|treure|traieu|sense|togliere|senza)\\b")
    private val TURBO = Regex("\\bturbo\\b")
    private val PULSES = Regex("(\\d+)(?:\\s*-\\s*\\d+)?\\s*(?:x\\b|fois\\b|times\\b|mal\\b|veces\\b|vegades\\b|volte\\b|impulsions?\\b|pulses?\\b|impulsos?\\b|impulse\\b)|(?<![a-z])[x×]\\s*(\\d+)\\b")
    private val KNEAD = Regex("\\b(?:mode\\s+petrin|petrin|programme\\s+petrir|epi|teig-?\\s*modus|teigmodus|knetmodus|knetstufe|knetprogramm|programm\\s+kneten|dough\\s+mode|knead(?:ing)?\\s+(?:mode|program(?:me)?)|modo\\s+amasar|funcion\\s+amasar|programa\\s+amasar|espiga|mode\\s+pastar|modalita\\s+impasto|funzione\\s+spiga|spiga)\\b")
    private val STEAM = Regex("\\b(?:programme\\s+vapeur|mode\\s+vapeur|fonction\\s+vapeur|dampfgarprogramm|dampfgarmodus|programm\\s+dampfgaren|steam(?:ing)?\\s+(?:mode|program(?:me)?|function)|modo\\s+vapor|programa\\s+(?:de\\s+)?vapor|funcion\\s+vapor|mode\\s+vapor|programma\\s+vapore|funzione\\s+vapore)\\b")
    private val TOAST = Regex("\\b(?:programme\\s+rissoler|mode\\s+rissoler|fonction\\s+rissoler|anbraten-?\\s*(?:modus|programm|funktion)|anbratmodus|programm\\s+anbraten|browning\\s+(?:mode|program(?:me)?|function)|saute\\s+mode|modo\\s+(?:dorar|sofreir)|programa\\s+(?:dorar|sofreir)|funcion\\s+(?:dorar|sofreir)|mode\\s+rossejar|programma\\s+rosolare|funzione\\s+rosolare)\\b")
    private val ABBREVIATION = Regex("\\b(min|mins|sek|sec|secs|seg|segs|std|vit|vel|ca|env|approx|h)\\.")
    private val CLAUSE = Regex("[.!?;\\n]+(?:\\s|$)|\\s(?:puis|ensuite|then|dann|danach|anschliessend|luego|despues|despres|poi)\\s")

    /** Le réglage tel qu'écrit dans [text], sans regarder le robot ; null : aucun, ou pas sûr. */
    fun parse(text: String): StepMachine? {
        val t = norm(text)
        if (t.isEmpty() || "varoma" in t || FAHRENHEIT.containsMatchIn(t)) return null
        val speeds = SPEED.findAll(t).toList()
        val turbo = TURBO.find(t)
        val programs = listOf(StepProgram.KNEAD to KNEAD, StepProgram.STEAM to STEAM, StepProgram.TOAST to TOAST)
            .mapNotNull { (p, re) -> re.find(t)?.let { p to it } }
        val kinds = (if (speeds.isNotEmpty() || SOFT.containsMatchIn(t)) 1 else 0) + (if (turbo != null) 1 else 0) + programs.size
        if (kinds != 1 || speeds.size > 1) return null
        val clauses = clauses(t)
        fun clauseAt(i: Int) = clauses.first { i in it.first }.second

        if (turbo != null) {
            val c = clauseAt(turbo.range.first)
            val m = PULSES.find(c)
            val n = m?.let { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull() } ?: 1
            if (n < 1) return null
            return StepSettings.default(StepProgram.TURBO).copy(pulses = n.coerceAtMost(StepSettings.MAX_PULSES))
        }
        if (programs.isNotEmpty()) {
            val (p, match) = programs[0]
            val seconds = oneDuration(clauseAt(match.range.first)) ?: return null
            return StepSettings.default(p).copy(seconds = seconds)
        }
        val sp = speeds.firstOrNull() ?: return null  // vitesse « soft » seule
        val c = clauseAt(sp.range.first)
        if (SOFT.containsMatchIn(c)) return null
        val speed = floor(sp.groupValues[1].toDouble()).toInt()
        if (speed !in 1..10) return null
        val seconds = oneDuration(c) ?: return null
        val temps = TEMPERATURE.findAll(c).toList()
        if (temps.size > 1) return null
        val tempC = temps.firstOrNull()?.groupValues?.get(1)?.toInt() ?: 0
        val whisk = WHISK.find(c)
        if (whisk != null && REMOVE.containsMatchIn(c)) return null
        return StepMachine(
            program = StepProgram.MANUAL,
            seconds = seconds,
            speed = speed,
            tempC = tempC,
            reverse = REVERSE.containsMatchIn(c),
            accessory = if (whisk != null) Accessory.WHISK else Accessory.BLADE,
        )
    }

    /**
     * Le réglage de [text] à proposer sur [robot], ou null. Une température absente de sa table prend
     * celle juste en dessous ; un réglage que le robot ne sait pas faire n'est pas proposé ; un réglage
     * que la sécurité refuse n'est gardé que si un réglage permis plus bas existe (proposé à la cuisson,
     * voir [PermittedSetting]).
     */
    fun propose(text: String, robot: RobotProfile = RobotProfiles.active): StepMachine? {
        var m = parse(text) ?: return null
        val table = robot.capabilities.temperaturesC
        if (m.program == StepProgram.MANUAL && m.tempC > 0 && m.tempC !in table)
            m = m.copy(tempC = table.filter { it <= m.tempC }.maxOrNull() ?: return null)
        return try {
            if (RecipeCompat.stepReason(m, robot) != null) null
            else if (m.violations().isNotEmpty() && PermittedSetting.of(m) == null) null
            else m
        } catch (_: RuntimeException) {
            null
        }
    }

    /** La seule durée de [clause] (fourchette : la plus petite), en secondes ; null s'il n'y en a pas exactement une. */
    private fun oneDuration(clause: String): Int? {
        val all = DURATION.findAll(clause).toList()
        if (all.size != 1) return null
        val m = all[0]
        var s = m.groupValues[1].toDouble() * unitSeconds(m.groupValues[2])
        val extra = m.groupValues[3]
        val first = unitSeconds(m.groupValues[2])
        // « 1 h 30 », « 1 min 30 s » ; un nombre seul après des minutes ou des secondes n'en fait pas partie.
        val u = when {
            extra.isEmpty() -> 0
            m.groupValues[4].isNotEmpty() -> unitSeconds(m.groupValues[4])
            first == 3600 -> 60
            else -> 0
        }
        // « 10 sec et 20 sec » : deux durées.
        if (m.groupValues[4].isNotEmpty() && u >= first) return null
        if (u in 1 until first) s += extra.toInt() * u
        val seconds = Math.round(s).toInt()
        return if (seconds >= 1) seconds else null
    }

    private val HOURS = Regex(UNIT_H)
    private val MINUTES = Regex(UNIT_M)

    private fun unitSeconds(unit: String): Int = when {
        HOURS.matches(unit) -> 3600
        MINUTES.matches(unit) -> 60
        else -> 1
    }

    /** Les phrases de [t] avec leurs positions. */
    private fun clauses(t: String): List<Pair<IntRange, String>> {
        val out = ArrayList<Pair<IntRange, String>>()
        var start = 0
        for (m in CLAUSE.findAll(t)) {
            out += (start until m.range.last + 1) to t.substring(start, m.range.first)
            start = m.range.last + 1
        }
        out += (start..t.length) to t.substring(start)
        return out
    }

    /** Minuscules sans accents, « ½ » et virgule décimale en point, abréviations sans leur point. */
    private fun norm(s: String): String {
        var t = s.replace('’', '\'').replace(' ', ' ').replace(' ', ' ').replace('º', '°').replace('˚', '°')
        for ((g, v) in listOf("½" to "5", "¼" to "25", "¾" to "75")) {
            t = t.replace(Regex("(\\d)\\s*$g"), "$1.$v").replace(g, "0.$v")
        }
        t = Normalizer.normalize(t.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        t = t.replace(Regex("(\\d),(\\d)"), "$1.$2")
        return ABBREVIATION.replace(t, "$1").trim()
    }
}
