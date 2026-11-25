package app.toque.core.companion

import app.toque.core.i18n.tr
import app.toque.core.json.Json
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.json.str
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Random

/** Horloges du compagnon : l'heure murale pour les dates affichées, une horloge monotone pour les délais. */
interface CompanionClock {
    /** Millisecondes depuis 1970 (dates affichées, journal). */
    fun wallMs(): Long
    /** Horloge monotone (validité des codes, attentes) : insensible aux changements d'heure. */
    fun monoMs(): Long
}

object SystemCompanionClock : CompanionClock {
    override fun wallMs(): Long = System.currentTimeMillis()
    override fun monoMs(): Long = System.nanoTime() / 1_000_000L
}

/** Où garder la liste des téléphones : l'appli l'écrit dans ses préférences, les tests en mémoire. */
interface CompanionStore {
    fun load(): String?
    fun save(text: String)
}

class MemoryStore(@Volatile var text: String? = null) : CompanionStore {
    override fun load(): String? = text
    override fun save(text: String) {
        this.text = text
    }
}

/** Un téléphone appairé. Seule l'empreinte SHA-256 de son jeton est gardée. */
data class Phone(
    val id: String,
    /** « Téléphone 1 », « Téléphone 2 »… : le plus petit numéro libre au moment de l'appairage. */
    val number: Int,
    val tokenHash: String,
    val pairedAt: Long,
    val lastSeenAt: Long,
    /** « qr » ou « code ». */
    val method: String,
) {
    val name: String get() = tr("Téléphone %d", number)
}

/** Code d'appairage affiché sur le robot. */
class PairingOffer(
    /** Six chiffres, à saisir sur le téléphone. */
    val code: String,
    /** Secret à usage unique porté par le QR code (fragment de l'adresse). */
    val secret: String,
    val createdMono: Long,
    val expiresMono: Long,
    /** Moment d'expiration en heure murale, pour l'affichage. */
    val expiresAtWall: Long,
) {
    @Volatile var failures = 0
        internal set
    @Volatile var dead = false
        internal set
}

/** État de l'appairage, pour l'écran du robot. */
enum class PairingStatus {
    /** Pas de code (jamais ouvert, ou écran fermé). */
    CLOSED,
    /** Un code est utilisable. */
    OPEN,
    /** Le code a dépassé ses deux minutes. */
    EXPIRED,
    /** Le code est mort après trop d'essais faux. */
    LOCKED,
    /** Le code a servi : un téléphone vient d'être appairé. */
    USED,
}

/** Résultat d'une tentative d'appairage. [token] n'est connu qu'ici : il n'est jamais enregistré. */
class PairResult(val phone: Phone?, val token: String?, val error: String?, val message: String) {
    val ok: Boolean get() = phone != null
}

/**
 * Appairage et liste des téléphones. Le robot affiche un code à six chiffres et un QR code (adresse
 * de la page + secret à usage unique), valables [validityMs] ; chaque code supporte [maxFailures]
 * essais faux puis meurt. Un appairage réussi consomme le code et le secret. Chaque téléphone reçoit
 * son propre jeton aléatoire de 256 bits ; seule son empreinte est enregistrée et la comparaison se
 * fait en temps constant.
 *
 * Toutes les méthodes sont sûres entre fils. Les écouteurs sont appelés hors verrou, sur le fil
 * qui a provoqué le changement (souvent un fil du serveur).
 */
class PairingManager(
    private val clock: CompanionClock = SystemCompanionClock,
    private val random: Random = SecureRandom(),
    private val store: CompanionStore = MemoryStore(),
    val validityMs: Long = 120_000L,
    val maxFailures: Int = 5,
    val maxPhones: Int = 8,
    /** La date « vu » n'est réécrite au plus qu'une fois par… (évite d'écrire à chaque requête). */
    private val lastSeenSaveMs: Long = 10 * 60_000L,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private val phones = ArrayList<Phone>()
    private var offer: PairingOffer? = null
    private var ended = PairingStatus.CLOSED
    /** Clé tirée au démarrage : réponses de preuve des téléphones inconnus, impossibles à distinguer des vraies. */
    private val decoyKey = ByteArray(32).also { random.nextBytes(it) }
    private var lastSavedMono = Long.MIN_VALUE / 4
    private var seenDirty = false

    /**
     * Identifiant aléatoire du robot, tiré une fois puis gardé dans [store] : le téléphone s'en sert pour
     * reconnaître son robot quand l'adresse de celui-ci change.
     */
    @Volatile var robotId: String = ""
        private set

    private val phoneListeners = ArrayList<() -> Unit>()
    private val pairingListeners = ArrayList<() -> Unit>()

    init {
        load()
        if (robotId.isEmpty()) synchronized(lock) {
            robotId = randomHex(ROBOT_ID_BYTES)
            saveLocked()
        }
    }

    // ------------------------------------------------------------------ écouteurs

    /** Téléphone ajouté ou retiré (pas à chaque « vu »). Renvoie de quoi se désabonner. */
    fun onPhonesChanged(l: () -> Unit): () -> Unit {
        synchronized(lock) { phoneListeners += l }
        return { synchronized(lock) { phoneListeners -= l } }
    }

    /** Code ouvert, fermé, consommé ou mort. */
    fun onPairingChanged(l: () -> Unit): () -> Unit {
        synchronized(lock) { pairingListeners += l }
        return { synchronized(lock) { pairingListeners -= l } }
    }

    private fun fire(list: List<() -> Unit>) {
        val copy = synchronized(lock) { ArrayList(list) }
        for (l in copy) try {
            l()
        } catch (t: Throwable) {
            log("écouteur en échec : $t")
        }
    }

    // ------------------------------------------------------------------ téléphones

    val list: List<Phone> get() = synchronized(lock) { ArrayList(phones) }

    /** Au moins un téléphone appairé (sans copier la liste : lu à chaque Snapshot). */
    val hasPhones: Boolean get() = synchronized(lock) { phones.isNotEmpty() }

    fun remove(id: String): Boolean {
        val removed = synchronized(lock) {
            val ok = phones.removeAll { it.id == id }
            if (ok) saveLocked()
            ok
        }
        if (removed) fire(phoneListeners)
        return removed
    }

    /** Téléphone qui présente ce jeton, ou null. Met à jour sa date « vu ». */
    fun authenticate(token: String?): Phone? {
        if (token == null || token.length != TOKEN_HEX || !token.all { it in '0'..'9' || it in 'a'..'f' }) return null
        val hash = sha256Hex(token)
        synchronized(lock) {
            var found = -1
            // Pas de sortie anticipée : même durée quel que soit le téléphone trouvé.
            for (i in phones.indices) if (constantTimeEquals(phones[i].tokenHash, hash) && found < 0) found = i
            if (found < 0) return null
            val p = phones[found].copy(lastSeenAt = clock.wallMs())
            phones[found] = p
            seenDirty = true
            if (clock.monoMs() - lastSavedMono >= lastSeenSaveMs) saveLocked()
            return p
        }
    }

    /**
     * Preuve que ce robot connaît le téléphone [phoneId] (POST /api/prove, sans jeton) :
     * HMAC-SHA256, clé = empreinte du jeton de ce téléphone, sur [nonce], l'identifiant du robot et
     * l'adresse locale [local] où la connexion est arrivée. Pour un téléphone inconnu, une réponse de
     * même forme, stable, mais fausse.
     */
    fun prove(phoneId: String, nonce: String, local: String): String {
        // La clé leurre est toujours calculée : même travail que le téléphone soit connu ou non.
        val decoy = hex(CompanionProof.hmac(decoyKey, "decoy|$phoneId".toByteArray(Charsets.UTF_8)))
        var key = decoy
        synchronized(lock) {
            for (p in phones) if (constantTimeEquals(p.id, phoneId)) key = p.tokenHash
        }
        return CompanionProof.compute(key, nonce, robotId, local)
    }

    /** Écrit les dates « vu » en attente (à l'arrêt du serveur). */
    fun flush() = synchronized(lock) { if (seenDirty) saveLocked() }

    // ------------------------------------------------------------------ codes

    /** Code en cours, s'il est encore utilisable. */
    val current: PairingOffer?
        get() = synchronized(lock) { liveLocked() }

    val isOpen: Boolean get() = current != null

    /** Pourquoi il n'y a pas (ou plus) de code utilisable : expiré, tué par les essais faux, consommé… */
    val status: PairingStatus
        get() = synchronized(lock) {
            val o = offer
            when {
                o == null -> ended
                o.dead -> PairingStatus.LOCKED
                clock.monoMs() >= o.expiresMono -> PairingStatus.EXPIRED
                else -> PairingStatus.OPEN
            }
        }

    private fun liveLocked(): PairingOffer? {
        val o = offer ?: return null
        if (o.dead || clock.monoMs() >= o.expiresMono) return null
        return o
    }

    /** Ouvre l'appairage (écran du robot) ou remplace le code en cours (« Nouveau code »). */
    fun open(): PairingOffer {
        val o = synchronized(lock) {
            val now = clock.monoMs()
            val code = String.format(java.util.Locale.ROOT, "%06d", random.nextInt(1_000_000))
            val o = PairingOffer(code, randomHex(SECRET_BYTES), now, now + validityMs, clock.wallMs() + validityMs)
            offer = o
            o
        }
        fire(pairingListeners)
        return o
    }

    /** Ferme l'appairage (écran du robot quitté). */
    fun close() {
        val changed = synchronized(lock) {
            val had = offer != null
            offer = null
            ended = PairingStatus.CLOSED
            had
        }
        if (changed) fire(pairingListeners)
    }

    /** Appairage par le code à six chiffres. */
    fun pairWithCode(code: String): PairResult = pair(code.trim(), null)

    /** Appairage par le secret du QR code. */
    fun pairWithSecret(secret: String): PairResult = pair(null, secret.trim())

    private fun pair(code: String?, secret: String?): PairResult {
        var pairingChanged = false
        var phonesChanged = false
        val result = synchronized(lock) {
            val o = liveLocked()
            val last = offer
            if (o == null) {
                when {
                    last == null -> PairResult(null, null, "pairing_closed", tr("Aucun code d'appairage en cours : affichez-en un sur le robot"))
                    last.dead -> PairResult(null, null, "code_dead", tr("Trop d'essais : demandez un nouveau code sur le robot"))
                    else -> PairResult(null, null, "code_expired", tr("Code expiré : demandez un nouveau code sur le robot"))
                }
            } else {
                val match = if (code != null) constantTimeEquals(code, o.code) else constantTimeEquals(secret ?: "", o.secret)
                if (!match) {
                    o.failures++
                    if (o.failures >= maxFailures) {
                        o.dead = true
                        pairingChanged = true
                        PairResult(null, null, "code_dead", tr("Trop d'essais : demandez un nouveau code sur le robot"))
                    } else PairResult(null, null, "wrong_code", tr("Code incorrect"))
                } else if (phones.size >= maxPhones) {
                    PairResult(null, null, "too_many_phones", tr("Trop de téléphones appairés : retirez-en un sur le robot"))
                } else {
                    val token = randomHex(TOKEN_BYTES)
                    var n = 1
                    while (phones.any { it.number == n }) n++
                    var id = randomHex(4)
                    while (phones.any { it.id == id }) id = randomHex(4)
                    val now = clock.wallMs()
                    val p = Phone(id, n, sha256Hex(token), now, now, if (code != null) "code" else "qr")
                    phones += p
                    offer = null
                    ended = PairingStatus.USED
                    saveLocked()
                    pairingChanged = true
                    phonesChanged = true
                    PairResult(p, token, null, "")
                }
            }
        }
        if (pairingChanged) fire(pairingListeners)
        if (phonesChanged) fire(phoneListeners)
        return result
    }

    // ------------------------------------------------------------------ stockage

    private fun load() {
        val text = try {
            store.load()
        } catch (t: Throwable) {
            log("lecture des téléphones impossible : $t")
            null
        } ?: return
        try {
            val root = Json.parse(text).obj()
            val id = root.str("robotId").orEmpty()
            if (id.length == ROBOT_ID_BYTES * 2 && id.all { it in '0'..'9' || it in 'a'..'f' }) robotId = id
            for (e in root["phones"].list()) {
                val m = e.obj()
                val id = m.str("id") ?: continue
                val hash = m.str("tokenHash") ?: continue
                if (hash.length != 64) continue
                val number = (m["number"] as? Number)?.toInt() ?: continue
                phones += Phone(id, number, hash, (m["pairedAt"] as? Number)?.toLong() ?: 0L,
                    (m["lastSeenAt"] as? Number)?.toLong() ?: 0L, m.str("method") ?: "code")
            }
        } catch (t: Throwable) {
            log("liste des téléphones illisible : $t")
        }
    }

    private fun saveLocked() {
        val doc = linkedMapOf(
            "version" to 1,
            "robotId" to robotId,
            "phones" to phones.map {
                linkedMapOf("id" to it.id, "number" to it.number, "tokenHash" to it.tokenHash,
                    "pairedAt" to it.pairedAt, "lastSeenAt" to it.lastSeenAt, "method" to it.method)
            },
        )
        try {
            store.save(Json.write(doc))
            lastSavedMono = clock.monoMs()
            seenDirty = false
        } catch (t: Throwable) {
            log("écriture des téléphones impossible : $t")
        }
    }

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return hex(b)
    }

    companion object {
        const val TOKEN_BYTES = 32
        const val TOKEN_HEX = TOKEN_BYTES * 2
        const val SECRET_BYTES = 16
        const val ROBOT_ID_BYTES = 6

        fun hex(b: ByteArray): String {
            val digits = "0123456789abcdef"
            val sb = StringBuilder(b.size * 2)
            for (x in b) {
                val v = x.toInt() and 0xFF
                sb.append(digits[v shr 4]).append(digits[v and 15])
            }
            return sb.toString()
        }

        fun sha256Hex(s: String): String = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))

        /** Comparaison sans sortie anticipée (la durée ne dépend pas du premier caractère différent). */
        fun constantTimeEquals(a: String, b: String): Boolean {
            var diff = a.length xor b.length
            val n = maxOf(a.length, b.length)
            for (i in 0 until n) {
                val x = if (i < a.length) a[i].code else 0
                val y = if (i < b.length) b[i].code else 0
                diff = diff or (x xor y)
            }
            return diff == 0
        }
    }
}
