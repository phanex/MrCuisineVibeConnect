package app.toque.core.companion

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Preuve d'identité du robot, avant tout envoi du jeton (voir docs/COMPANION.md, « Proof ») :
 *
 *     proof = hex(HMAC-SHA256(key = UTF-8(sha256Hex(token)), "toque-prove-1|" + nonce + "|" + robotId + "|" + address))
 *
 * Le téléphone la calcule avec l'adresse qu'il a composée, le robot avec l'adresse locale où la
 * connexion est arrivée : un autre appareil, ou le bon robot joint par un relais, ne peut pas répondre juste.
 */
object CompanionProof {
    const val NONCE_HEX = 32
    private val HEX = Regex("^[0-9a-f]+$")

    fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    /** [keyHex] : empreinte SHA-256 du jeton, en hexadécimal (ce que le robot garde). */
    fun compute(keyHex: String, nonce: String, robotId: String, address: String): String =
        PairingManager.hex(hmac(keyHex.toByteArray(Charsets.UTF_8),
            "toque-prove-1|$nonce|$robotId|$address".toByteArray(Charsets.UTF_8)))

    /** Côté téléphone : la preuve reçue est-elle celle de son robot, à cette adresse ? */
    fun check(token: String, nonce: String, robotId: String, address: String, proof: String): Boolean =
        PairingManager.constantTimeEquals(compute(PairingManager.sha256Hex(token), nonce, robotId, address), proof)

    fun validNonce(n: String): Boolean = n.length == NONCE_HEX && HEX.matches(n)
    fun validPhoneId(id: String): Boolean = id.length == 8 && HEX.matches(id)
}
