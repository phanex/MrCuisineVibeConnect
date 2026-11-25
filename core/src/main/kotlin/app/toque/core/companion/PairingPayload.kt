package app.toque.core.companion

/**
 * Texte du QR code d'appairage, lu par l'appli du téléphone :
 *
 *     toque://pair?v=1&h=192.168.0.12&p=8088&s=<32 hex>&r=<12 hex>[&n=<nom encodé>]
 *
 * v : version du protocole ; h : adresse IPv4 du robot ; p : port ; s : secret à usage unique (128 bits) ;
 * r : identifiant stable du robot ; n : nom du robot, facultatif (32 caractères au plus, encodage %XX
 * UTF-8), absent quand [name] est vide. Le robot l'omet (le téléphone reçoit le nom dans la réponse
 * d'appairage) : environ 90 octets, un QR code version 5 au niveau L. Jamais plus de [MAX_LENGTH].
 */
data class PairingPayload(
    val host: String,
    val port: Int,
    val secret: String,
    val robotId: String,
    val name: String,
    val version: Int = COMPANION_PROTOCOL,
) {
    fun format(): String {
        val n = name.take(MAX_NAME)
        val s = PREFIX + "v=$version&h=$host&p=$port&s=$secret&r=$robotId" + (if (n.isEmpty()) "" else "&n=" + encode(n))
        require(s.length <= MAX_LENGTH) { "QR code trop long" }
        return s
    }

    companion object {
        const val PREFIX = "toque://pair?"
        const val MAX_LENGTH = 200
        const val MAX_NAME = 32
        private val HEX = Regex("^[0-9a-f]+$")
        private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")

        /** Lit un texte scanné ; null s'il ne s'agit pas d'un QR code d'appairage Toque valide. */
        fun parse(text: String): PairingPayload? {
            val t = text.trim()
            if (t.length > MAX_LENGTH || !t.startsWith(PREFIX)) return null
            val q = try {
                HttpParser.parseQuery(t.substring(PREFIX.length))
            } catch (_: Exception) {
                return null
            }
            val v = q["v"]?.toIntOrNull() ?: return null
            if (v < 1) return null
            val host = q["h"] ?: return null
            if (!IPV4.matches(host)) return null
            val port = q["p"]?.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            val secret = q["s"] ?: return null
            if (secret.length != PairingManager.SECRET_BYTES * 2 || !HEX.matches(secret)) return null
            val robot = q["r"] ?: return null
            if (robot.length != PairingManager.ROBOT_ID_BYTES * 2 || !HEX.matches(robot)) return null
            val name = (q["n"] ?: "").take(MAX_NAME)
            return PairingPayload(host, port, secret, robot, name, v)
        }

        /** Encodage %XX de tout sauf les caractères non réservés (RFC 3986). */
        fun encode(s: String): String {
            val sb = StringBuilder()
            val bytes = s.toByteArray(Charsets.UTF_8)
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                val ch = c.toChar()
                if (c < 128 && (ch.isLetterOrDigit() || ch == '-' || ch == '.' || ch == '_' || ch == '~')) sb.append(ch)
                else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
            return sb.toString()
        }
    }
}
