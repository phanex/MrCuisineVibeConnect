package app.toque.core.recipes

import java.text.Normalizer

/**
 * Durée écrite dans le texte d'une étape à la main (« laisser reposer 30 min », « enfourner 1 h 30 »),
 * pour proposer un minuteur tout prêt. Français d'abord, et les mêmes unités en anglais, espagnol,
 * allemand et catalan (le catalogue existe dans ces langues).
 *
 * Prudente : dans le doute, rien. Pas de durée en notation machine (« 5 min/100 °C/vitesse 1 »),
 * pas d'intervalle (« toutes les 5 min »), rien au-delà de [MAX_SECONDS]. Une fourchette
 * (« 20 à 25 min ») donne sa plus petite valeur : on regarde au plus tôt.
 */
object StepDurations {
    /** Le minuteur le plus long (une nuit de levée). */
    const val MAX_SECONDS = 12 * 3600
    private const val MIN_SECONDS = 60

    private enum class Span(val seconds: Int) { HOUR(3600), MINUTE(60), SECOND(1) }

    private val UNITS: Map<String, Span> = HashMap<String, Span>().apply {
        for (w in listOf("h", "hr", "hrs", "heure", "heures", "hour", "hours", "hora", "horas", "hores", "std", "stunde", "stunden"))
            put(w, Span.HOUR)
        for (w in listOf("min", "mins", "mn", "minute", "minutes", "minuto", "minutos", "minuten", "minut", "minuts"))
            put(w, Span.MINUTE)
        for (w in listOf("s", "sec", "secs", "seconde", "secondes", "second", "seconds", "seg", "segs", "segundo", "segundos",
            "sek", "sekunde", "sekunden", "segon", "segons"))
            put(w, Span.SECOND)
    }

    /** Entre deux nombres : une fourchette (« 20 à 25 », « 2-3 », « 20 bis 25 »). */
    private val RANGE = setOf("-", "–", "—", "à", "a", "to", "bis", "ou", "or", "o", "y", "i", "und", "and", "et", "al")
    /** « 1 heure et demie », « 1 hora y media », « 1 hour and a half ». */
    private val AND = setOf("et", "y", "i", "and", "und")
    private val HALF = setOf("demie", "demi", "media", "mitja", "half", "halb")
    /** « une heure », « an hour », « eine Stunde » : un seul mot pour 1. */
    private val ONE = setOf("une", "un", "one", "an", "a", "una", "eine", "einer", "ein")
    /** Mots qui font d'une durée un intervalle : « toutes les 5 min », « every 5 minutes », « cada 5 minutos », « alle 5 Minuten ». */
    private val EVERY = setOf("every", "cada", "alle", "chaque")
    /** Une quantité, pas une durée : « 1 h 30 g » n'est pas « 1 h 30 ». */
    private val MEASURES = setOf("g", "kg", "mg", "l", "ml", "cl", "dl", "cm", "mm", "c", "cs", "cc")

    /** Vitesse du robot : la durée voisine est un réglage machine, pas un minuteur. */
    private val SPEED = setOf("vitesse", "stufe", "velocidad", "velocitat", "speed", "turbo")

    private class Tok(val text: String, val number: Double?)

    private fun tokens(text: String): List<Tok> {
        val out = ArrayList<Tok>()
        val t = text.replace(' ', ' ').replace(' ', ' ')
        var i = 0
        while (i < t.length) {
            val ch = t[i]
            when {
                ch.isDigit() -> {
                    var j = i
                    while (j < t.length && t[j].isDigit()) j++
                    // Décimale : « 1,5 h », « 1.5 hours » (le chiffre suit directement).
                    if (j + 1 < t.length && (t[j] == ',' || t[j] == '.') && t[j + 1].isDigit()) {
                        j++
                        while (j < t.length && t[j].isDigit()) j++
                    }
                    val s = t.substring(i, j)
                    out += Tok(s, s.replace(',', '.').toDoubleOrNull())
                    i = j
                }
                ch == '½' -> { out += Tok("½", 0.5); i++ }
                ch == '¼' -> { out += Tok("¼", 0.25); i++ }
                ch == '¾' -> { out += Tok("¾", 0.75); i++ }
                ch.isLetter() -> {
                    var j = i
                    while (j < t.length && t[j].isLetter()) j++
                    out += Tok(fold(t.substring(i, j)), null)
                    i = j
                }
                ch.isWhitespace() -> i++
                else -> { out += Tok(ch.toString(), null); i++ }
            }
        }
        return out
    }

    /** Minuscules sans accents (« Stunden », « à » restent lisibles : « à » garde son accent pour RANGE). */
    private fun fold(w: String): String {
        val l = w.lowercase()
        if (l == "à") return l
        return Normalizer.normalize(l, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    }

    private fun unitAt(toks: List<Tok>, i: Int): Span? = toks.getOrNull(i)?.takeIf { it.number == null }?.let { UNITS[it.text] }

    /** Indice après l'unité en [i] et son point d'abréviation éventuel (« Min. », « Std. »). */
    private fun afterUnit(toks: List<Tok>, i: Int): Int = if (toks.getOrNull(i + 1)?.text == ".") i + 2 else i + 1

    /**
     * La première durée d'au moins une minute écrite dans [text], en secondes ; null s'il n'y en a pas,
     * si elle dépasse [MAX_SECONDS] ou si elle est écrite comme un réglage machine ou un intervalle.
     */
    fun find(text: String): Int? {
        for (d in all(text)) {
            if (d < MIN_SECONDS) continue
            return if (d > MAX_SECONDS) null else d
        }
        return null
    }

    /** Toutes les durées lisibles de [text], dans l'ordre, en secondes (y compris celles de moins d'une minute). */
    fun all(text: String): List<Int> {
        val toks = tokens(text)
        val out = ArrayList<Int>()
        var i = 0
        while (i < toks.size) {
            val r = readAt(toks, i)
            if (r == null) { i++; continue }
            if (r.first > 0) out += r.first
            i = maxOf(r.second, i + 1)
        }
        return out
    }

    /** Durée commençant au jeton [i] : (secondes, indice suivant), secondes = 0 pour une durée écartée ; null si rien. */
    private fun readAt(toks: List<Tok>, i: Int): Pair<Int, Int>? {
        val t = toks[i]
        var value: Double
        var j: Int
        val word = t.number == null
        if (!word) {
            value = t.number!!
            j = i + 1
            // Fourchette : la plus petite valeur.
            val sep = toks.getOrNull(j)
            if (sep != null && sep.number == null && sep.text in RANGE && toks.getOrNull(j + 1)?.number != null) j += 2
        } else {
            // « une demi-heure », « half an hour », « media hora », « mitja hora », « eine halbe Stunde ».
            var k = i
            if (t.text in ONE) k++
            val half = toks.getOrNull(k)?.text?.let { it in HALF || it == "halbe" } == true
            if (half) {
                k++
                if (toks.getOrNull(k)?.text == "-") k++
                if (toks.getOrNull(k)?.text in ONE) k++
                val u = unitAt(toks, k)
                if (u != Span.HOUR || toks[k].text == "h") return null
                return finish(toks, i, 1800, afterUnit(toks, k))
            }
            // « un quart d'heure », « a quarter of an hour », « un cuarto de hora », « un quart d'hora ».
            if (toks.getOrNull(k)?.text == "quart" || toks.getOrNull(k)?.text == "quarter" || toks.getOrNull(k)?.text == "cuarto") {
                var m = k + 1
                while (m < toks.size && m <= k + 4 && unitAt(toks, m) == null) m++
                val u = unitAt(toks, m)
                if (u != Span.HOUR || toks[m].text == "h" || m > k + 4) return null
                return finish(toks, i, 900, afterUnit(toks, m))
            }
            if (t.text !in ONE) return null
            // « une heure », « an hour » : seulement les heures, en toutes lettres.
            val u = unitAt(toks, i + 1)
            if (u != Span.HOUR || toks[i + 1].text == "h") return null
            value = 1.0
            j = i + 1
        }
        val unit = unitAt(toks, j) ?: return null
        var seconds = Math.round(value * unit.seconds).toDouble()
        var next = afterUnit(toks, j)
        when (unit) {
            Span.HOUR -> {
                // « 1 h 30 », « 1h30 min », « 1 heure et demie », « 1 Std. 15 Min. ».
                var k = next
                if (toks.getOrNull(k)?.text in AND) k++
                if (toks.getOrNull(k)?.text == "a" || toks.getOrNull(k)?.text == "une") k++
                val n = toks.getOrNull(k)
                if (n != null && n.text in HALF && k > next) {
                    seconds += 1800
                    next = k + 1
                } else if (n?.number != null && n.number == Math.floor(n.number) && n.number < 60) {
                    val after = toks.getOrNull(k + 1)
                    when {
                        // « 1 hora y 30 minutos » : avec son unité, même après « et ».
                        unitAt(toks, k + 1) == Span.MINUTE -> { seconds += n.number * 60; next = afterUnit(toks, k + 1) }
                        // « 1 h 30 » : sans unité, seulement juste après l'heure, et pas « 1 h 30 g ».
                        k == next && (after == null || (after.number == null && after.text !in MEASURES && UNITS[after.text] == null)) -> {
                            seconds += n.number * 60
                            next = k + 1
                        }
                    }
                }
            }
            Span.MINUTE -> {
                // « 2 min 30 s » : les secondes seulement avec leur unité.
                val n = toks.getOrNull(next)
                if (n?.number != null && n.number < 60 && unitAt(toks, next + 1) == Span.SECOND) {
                    seconds += n.number
                    next = afterUnit(toks, next + 1)
                }
            }
            Span.SECOND -> Unit
        }
        return finish(toks, i, seconds.toInt(), next)
    }

    /** Écarte (0) la durée [start]..[end] si elle fait partie d'un réglage machine ou d'un intervalle. */
    private fun finish(toks: List<Tok>, start: Int, seconds: Int, end: Int): Pair<Int, Int> {
        val before = toks.getOrNull(start - 1)?.text
        val after = toks.getOrNull(end)?.text
        // Réglage machine écrit dans le texte : « 5 min/100 °C/vitesse 1 », « 10 Min./Stufe 2 ».
        if (before == "/" || after == "/" || before == "|" || after == "|") return 0 to end
        // Ou en toutes lettres près d'une vitesse : « mixer 30 s vitesse 10 », « 5 Min. Stufe 1 ».
        for (k in maxOf(0, start - 5) until minOf(toks.size, end + 5)) if (toks[k].text in SPEED) return 0 to end
        val prev = toks.getOrNull(start - 1)?.text
        val prev2 = toks.getOrNull(start - 2)?.text
        if (prev in EVERY || (prev == "les" && (prev2 == "toutes" || prev2 == "tous"))) return 0 to end
        return seconds to end
    }

    /** « 30 min », « 1 h 30 », « 2 h », « 1 min 30 s », « 45 s ». */
    fun label(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 && m > 0 -> String.format("%d h %02d", h, m)
            h > 0 -> "$h h"
            m > 0 && s > 0 -> "$m min $s s"
            m > 0 -> "$m min"
            else -> "$s s"
        }
    }
}
