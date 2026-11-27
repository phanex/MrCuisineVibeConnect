package app.toque.core.json

/** JSON minimal sans dépendance : objets -> LinkedHashMap, tableaux -> List, nombres -> Long/Double. */
object Json {
    class ParseException(message: String, val position: Int) : Exception("$message (position $position)")

    /** Imbrication maximale d'objets et de tableaux : au-delà, ParseException (jamais de débordement de pile). */
    const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw ParseException("contenu en trop", p.i)
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        var depth = 0

        fun enter() {
            if (++depth > MAX_DEPTH) throw ParseException("imbrication trop profonde", i)
        }

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(): Any? {
            if (i >= s.length) throw ParseException("fin inattendue", i)
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw ParseException("caractère inattendu '$c'", i)
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw ParseException("littéral invalide", i)
            i += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            enter()
            try {
                return objBody()
            } finally {
                depth--
            }
        }

        fun arr(): List<Any?> {
            enter()
            try {
                return arrBody()
            } finally {
                depth--
            }
        }

        fun objBody(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(8)
            i++
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw ParseException("clé attendue", i)
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw ParseException("':' attendu", i)
                i++
                ws()
                m[k] = value()
                ws()
                if (i >= s.length) throw ParseException("objet non fermé", i)
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                throw ParseException("',' ou '}' attendu", i)
            }
        }

        fun arrBody(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws()
                l.add(value())
                ws()
                if (i >= s.length) throw ParseException("tableau non fermé", i)
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                throw ParseException("',' ou ']' attendu", i)
            }
        }

        fun str(): String {
            i++
            val start = i
            val len = s.length
            // Voie rapide sans échappement (99 % des chaînes) : aucune allocation de StringBuilder.
            while (i < len) {
                val c = s[i]
                if (c == '"') return s.substring(start, i++)
                if (c == '\\') break
                i++
            }
            if (i >= len) throw ParseException("chaîne non fermée", i)
            val sb = StringBuilder(i - start + 16)
            sb.append(s, start, i)
            while (true) {
                if (i >= len) throw ParseException("chaîne non fermée", i)
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= len) throw ParseException("échappement incomplet", i)
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > len) throw ParseException("\\u incomplet", i)
                                var code = 0
                                for (k in 0 until 4) {
                                    val ch = s[i + k]
                                    val d = when (ch) {
                                        in '0'..'9' -> ch - '0'
                                        in 'a'..'f' -> ch - 'a' + 10
                                        in 'A'..'F' -> ch - 'A' + 10
                                        else -> throw ParseException("\\u invalide", i + k)
                                    }
                                    code = (code shl 4) or d
                                }
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw ParseException("échappement \\$e invalide", i)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any {
            val start = i
            val len = s.length
            val neg = s[i] == '-'
            if (neg) i++
            val digitStart = i
            var acc = 0L
            while (i < len && s[i] in '0'..'9') {
                acc = acc * 10L + (s[i] - '0')
                i++
            }
            val digits = i - digitStart
            var real = false
            if (i < len && s[i] == '.') { real = true; i++; while (i < len && s[i] in '0'..'9') i++ }
            if (i < len && (s[i] == 'e' || s[i] == 'E')) {
                real = true; i++
                if (i < len && (s[i] == '+' || s[i] == '-')) i++
                while (i < len && s[i] in '0'..'9') i++
            }
            // Voie rapide entier sans débordement (≤ 18 chiffres) : aucune allocation de substring.
            if (!real && digits in 1..18) return if (neg) -acc else acc
            val t = s.substring(start, i)
            return try {
                if (real) t.toDouble() else t.toLong()
            } catch (e: NumberFormatException) {
                throw ParseException("nombre invalide", start)
            }
        }
    }

    fun write(v: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder(512)
        write(sb, v, if (pretty) 0 else -1)
        return sb.toString()
    }

    private fun indent(sb: StringBuilder, level: Int) {
        if (level < 0) return
        sb.append('\n')
        repeat(level) { sb.append("  ") }
    }

    private fun write(sb: StringBuilder, v: Any?, level: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int -> sb.append(v)
            is Long -> sb.append(v)
            is Short, is Byte -> sb.append(v.toInt())
            is Double -> if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) sb.append(v.toLong()) else sb.append(v)
            is Float -> write(sb, v.toDouble(), level)
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    indent(sb, if (level < 0) -1 else level + 1)
                    quote(sb, k.toString())
                    sb.append(if (level < 0) ":" else ": ")
                    write(sb, value, if (level < 0) -1 else level + 1)
                }
                if (!first) indent(sb, level)
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (value in v) {
                    if (!first) sb.append(',')
                    first = false
                    indent(sb, if (level < 0) -1 else level + 1)
                    write(sb, value, if (level < 0) -1 else level + 1)
                }
                if (!first) indent(sb, level)
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        var start = 0
        val len = s.length
        for (i in 0 until len) {
            val c = s[i]
            if (c == '"' || c == '\\' || c < ' ') {
                if (i > start) sb.append(s, start, i)
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> {
                        val code = c.code
                        sb.append("\\u00")
                        sb.append(HEX[(code ushr 4) and 0xF])
                        sb.append(HEX[code and 0xF])
                    }
                }
                start = i + 1
            }
        }
        if (start < len) sb.append(s, start, len)
        sb.append('"')
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/** Accès confortables et tolérants aux données JSON importées. */
@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()
fun Any?.list(): List<Any?> = this as? List<Any?> ?: emptyList()
fun Map<String, Any?>.str(key: String): String? = when (val v = this[key]) {
    null -> null
    is String -> v
    is Number, is Boolean -> v.toString()
    else -> null
}
fun Map<String, Any?>.int(key: String): Int? = when (val v = this[key]) {
    is Number -> v.toInt()
    is String -> v.trim().toIntOrNull() ?: v.trim().replace(',', '.').toDoubleOrNull()?.toInt()
    else -> null
}
fun Map<String, Any?>.double(key: String): Double? = when (val v = this[key]) {
    is Number -> v.toDouble()
    is String -> v.trim().replace(',', '.').toDoubleOrNull()
    else -> null
}

fun Map<String, Any?>.bool(key: String): Boolean? = when (val v = this[key]) {
    is Boolean -> v
    is Number -> v.toInt() != 0
    is String -> v.equals("true", true) || v == "1"
    else -> null
}
