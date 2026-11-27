package app.toque.core.companion.client

import app.toque.core.companion.AdjustRequest
import app.toque.core.companion.AppearanceRequest
import app.toque.core.companion.ApiError
import app.toque.core.companion.CatalogFilter
import app.toque.core.companion.CatalogPageWire
import app.toque.core.companion.CommandReply
import app.toque.core.companion.CompanionProof
import app.toque.core.companion.EditableRecipe
import app.toque.core.companion.EditorWire
import app.toque.core.companion.RecipeEditRequest
import app.toque.core.companion.FavoriteReply
import app.toque.core.companion.FavoriteRequest
import app.toque.core.companion.Hello
import app.toque.core.companion.JournalPage
import app.toque.core.companion.OwnStepRequest
import app.toque.core.companion.OwnStepsWire
import app.toque.core.companion.PairReply
import app.toque.core.companion.PairRequest
import app.toque.core.companion.PairingManager
import app.toque.core.companion.PairingPayload
import app.toque.core.companion.PhoneWire
import app.toque.core.companion.ProveReply
import app.toque.core.companion.ProveRequest
import app.toque.core.companion.RecipeDetail
import app.toque.core.companion.RenamePhoneRequest
import app.toque.core.companion.SendRecipeRequest
import app.toque.core.companion.StartRequest
import app.toque.core.companion.ServingsRequest
import app.toque.core.companion.StateWire
import app.toque.core.companion.WhoAmI
import app.toque.core.json.Json
import app.toque.core.recipes.StepMachine
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.SecureRandom
import java.util.Collections

/** Réponse d'erreur de l'API ([status] HTTP et erreur décodée). */
class CompanionException(val status: Int, val error: ApiError) : IOException("HTTP $status ${error.code}: ${error.message}") {
    /** Jeton refusé : le téléphone a été retiré sur le robot (ou n'a jamais été appairé). */
    val unauthorized: Boolean get() = status == 401
}

/** Le robot qui répond n'a pas prouvé qu'il est celui de ce téléphone : le jeton n'a pas été envoyé. */
class RobotProofException(message: String) : IOException(message)

/**
 * Client de l'API du compagnon, en Kotlin pur (HttpURLConnection) : utilisable sur Android et sur la JVM.
 * Chaque appel est bloquant : ne jamais l'appeler sur le fil principal d'Android. Sûr entre fils.
 *
 * Règle du jeton : il ne part jamais vers une adresse qui n'a pas d'abord prouvé être le robot de ce
 * téléphone (POST /api/prove, voir [CompanionProof]). La preuve est refaite avant la première requête
 * authentifiée vers une adresse, et de nouveau après toute erreur réseau. Il faut donc connaître
 * [phoneId] et [robotId] (donnés par l'appairage).
 */
class CompanionClient(
    @Volatile var host: String,
    @Volatile var port: Int,
    @Volatile var token: String? = null,
    @Volatile var phoneId: String? = null,
    @Volatile var robotId: String? = null,
    val connectTimeoutMs: Int = 3_000,
    val readTimeoutMs: Int = 10_000,
) {
    private val inflight: MutableSet<HttpURLConnection> = Collections.synchronizedSet(HashSet())
    /** « hôte:port » qui a passé la preuve, null après une erreur réseau. */
    @Volatile private var proven: String? = null
    private val random = SecureRandom()

    /** Une copie indépendante (mêmes adresse, jeton et identifiants), pour une boucle qu'on veut pouvoir couper seule. */
    fun copy(): CompanionClient = CompanionClient(host, port, token, phoneId, robotId, connectTimeoutMs, readTimeoutMs)

    /**
     * Demande à l'adresse actuelle de prouver qu'elle est le robot [robotId] qui connaît ce téléphone,
     * sans envoyer le jeton. Vrai si la preuve est bonne ; faux si un autre appareil répond.
     * IOException si personne ne répond.
     */
    fun verify(timeoutMs: Int = connectTimeoutMs): Boolean {
        val t = token ?: return false
        val pid = phoneId ?: return false
        val rid = robotId ?: return false
        val key = "$host:$port"
        val b = ByteArray(16)
        random.nextBytes(b)
        val nonce = PairingManager.hex(b)
        val reply = try {
            ProveReply.fromMap(json(call("POST", "/api/prove", ProveRequest(pid, nonce).toMap(), auth = false,
                readTimeout = timeoutMs, connectTimeout = minOf(timeoutMs, connectTimeoutMs))))
        } catch (e: CompanionException) {
            return false
        }
        // L'adresse composée, sous la forme que le robot voit (une IP littérale n'est jamais résolue par le réseau).
        val dialled = try {
            InetAddress.getByName(host).hostAddress
        } catch (_: Exception) {
            host
        }
        val ok = CompanionProof.check(t, nonce, rid, dialled, reply.proof)
        if (ok) proven = key
        return ok
    }

    private fun ensureProven() {
        if (proven == "$host:$port") return
        if (!verify()) throw RobotProofException("the robot at $host:$port did not prove its identity")
    }

    /**
     * Coupe les requêtes en cours (elles échouent avec une IOException). Même voulue (l'appli revient à
     * l'écran), une coupure fait refaire la preuve avant le prochain envoi du jeton : l'attente coupée
     * peut dater d'avant un long sommeil du téléphone, ou d'un autre Wi-Fi où cette adresse est à un
     * autre appareil.
     */
    fun cancelAll() {
        val list = synchronized(inflight) { ArrayList(inflight) }
        for (c in list) try {
            c.disconnect()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ sans jeton

    fun hello(timeoutMs: Int = connectTimeoutMs): Hello =
        Hello.fromMap(json(call("GET", "/api/hello", null, auth = false, readTimeout = timeoutMs, connectTimeout = timeoutMs)))

    /** Appairage par le code à six chiffres affiché sur le robot. Garde le jeton reçu. */
    fun pairWithCode(code: String): PairReply = pair(PairRequest(code = code))

    /**
     * Appairage par le texte du QR code. Prend l'adresse et le port du QR code, et vérifie que le robot
     * qui répond est bien celui du QR code.
     */
    fun pairWithPayload(payload: PairingPayload): PairReply {
        host = payload.host
        port = payload.port
        val reply = pair(PairRequest(secret = payload.secret))
        if (reply.robot.id != payload.robotId) {
            token = null
            phoneId = null
            robotId = null
            throw IOException("robot id mismatch")
        }
        return reply
    }

    /** Comme [pairWithPayload], depuis le texte brut scanné. */
    fun pairWithQr(text: String): PairReply =
        pairWithPayload(PairingPayload.parse(text) ?: throw IllegalArgumentException("not a Toque pairing QR code"))

    private fun pair(req: PairRequest): PairReply {
        val reply = PairReply.fromMap(json(call("POST", "/api/pair", req.toMap(), auth = false)))
        token = reply.token
        phoneId = reply.phone.id
        robotId = reply.robot.id
        proven = null
        return reply
    }

    // ------------------------------------------------------------------ avec jeton

    fun robot(timeoutMs: Int = readTimeoutMs): WhoAmI =
        WhoAmI.fromMap(json(call("GET", "/api/robot", null, readTimeout = timeoutMs, connectTimeout = minOf(timeoutMs, connectTimeoutMs))))

    /**
     * État ; avec [since], attend (au plus [waitSeconds]) que la révision change. [quiet] : la télémesure
     * seule (température, poids) ne met pas fin à l'attente (un ancien robot ignore ce paramètre).
     * [replace] : cette attente met fin à la précédente de ce téléphone sur le robot (un seul suiveur
     * par téléphone ; un ancien robot l'ignore, l'ancienne attente va alors à son terme).
     */
    fun state(since: Long? = null, waitSeconds: Int = 20, quiet: Boolean = false, replace: Boolean = false): StateWire {
        val path = if (since == null) "/api/state" else "/api/state?since=$since&wait=$waitSeconds" +
            (if (quiet) "&quiet=1" else "") + if (replace) "&replace=1" else ""
        val timeout = if (since == null) readTimeoutMs else readTimeoutMs + waitSeconds * 1000
        return StateWire.fromMap(json(call("GET", path, null, readTimeout = timeout)))
    }

    /** Lignes du journal de numéro > [after] (0 : les plus récentes). */
    fun journal(after: Long = 0, limit: Int = 100): JournalPage =
        JournalPage.fromMap(json(call("GET", "/api/journal?after=$after&limit=$limit", null)))

    /** Une page du catalogue ; [category] seulement avec [CatalogFilter.CATEGORY]. */
    fun recipes(text: String = "", filter: CatalogFilter = CatalogFilter.ALL, offset: Int = 0, limit: Int = 20, category: String = ""): CatalogPageWire =
        CatalogPageWire.fromMap(json(call("GET",
            "/api/recipes?q=${enc(text)}&filter=${filter.key}&offset=$offset&limit=$limit" +
                if (filter == CatalogFilter.CATEGORY) "&category=${enc(category)}" else "", null)))

    /**
     * Fiche d'une recette ; [base] : à son propre nombre de personnes (`?base=1`). Un robot plus
     * ancien l'ignore et répond au nombre choisi (`servings` ≠ `baseServings`).
     */
    fun recipe(id: String, base: Boolean = false): RecipeDetail =
        RecipeDetail.fromMap(json(call("GET", "/api/recipes/${enc(id)}" + if (base) "?base=1" else "", null)))

    /** Nombre de personnes de la recette, enregistré sur le robot ; renvoie la fiche à ce nombre. */
    fun setServings(id: String, people: Int): RecipeDetail =
        RecipeDetail.fromMap(json(call("POST", "/api/recipes/${enc(id)}/servings", ServingsRequest(people).toMap())))

    /** Image d'une recette, ou null si elle n'en a pas. */
    fun image(id: String): ByteArray? = try {
        call("GET", "/api/recipes/${enc(id)}/image", null)
    } catch (e: CompanionException) {
        if (e.status == 404) null else throw e
    }

    /** Favori du robot pour la recette [id] (voir state.can.favorite) ; renvoie l'état enregistré. */
    fun setFavorite(id: String, favorite: Boolean): FavoriteReply =
        FavoriteReply.fromMap(json(call("POST", "/api/recipes/${enc(id)}/favorite", FavoriteRequest(favorite).toMap())))

    /** Une recette perso telle qu'enregistrée, avec sa version (voir state.can.editRecipes). */
    fun editable(id: String): EditableRecipe = EditableRecipe.fromMap(json(call("GET", "/api/recipes/${enc(id)}/edit", null)))

    /** Enregistre la recette perso [id] écrite sur le téléphone ([req] avec la version lue) ; rend la recette enregistrée. */
    fun saveRecipe(id: String, req: RecipeEditRequest): EditableRecipe =
        EditableRecipe.fromMap(json(call("POST", "/api/recipes/${enc(id)}/edit", req.toMap())))

    /** Nouvelle recette perso ([req] avec l'identifiant du brouillon) ; le robot choisit son identifiant. */
    fun createRecipe(req: RecipeEditRequest): EditableRecipe = EditableRecipe.fromMap(json(call("POST", "/api/recipes", req.toMap())))

    /** Les bornes de l'éditeur pour une recette nouvelle, et ce que l'éditeur d'étape propose (robot qui prend les réglages). */
    fun editor(): EditorWire = EditorWire.fromMap(json(call("GET", "/api/editor", null)))

    /** « Ma version » de la recette Lidl [id] : la copie enregistrée dans « Mes recettes ». */
    fun copyRecipe(id: String): EditableRecipe = EditableRecipe.fromMap(json(call("POST", "/api/recipes/${enc(id)}/copy", null)))

    /** Les étapes d'une recette qui prend le réglage du propriétaire (Mealie), et l'éditeur d'étape du robot (voir state.can.ownSettings). */
    fun ownSteps(id: String): OwnStepsWire = OwnStepsWire.fromMap(json(call("GET", "/api/recipes/${enc(id)}/steps", null)))

    /**
     * Le réglage [machine] (null : sans le robot) pour l'étape [index] de la recette [id], désignée aussi par son
     * empreinte [key] (StepWire.key) ; rend les étapes relues. À vérifier ensuite sur le robot.
     */
    fun setOwnStep(id: String, index: Int, key: String, machine: StepMachine?): OwnStepsWire =
        OwnStepsWire.fromMap(json(call("POST", "/api/recipes/${enc(id)}/steps/$index", OwnStepRequest(key, machine).toMap())))

    /** L'étape [index] (empreinte [key]) de la recette [id] revient au réglage de la recette ; rend les étapes relues. */
    fun clearOwnStep(id: String, index: Int, key: String): OwnStepsWire =
        OwnStepsWire.fromMap(json(call("DELETE", "/api/recipes/${enc(id)}/steps/$index?key=${enc(key)}", null)))

    /** « Arrêter la recette » guidée ouverte sur le robot (voir state.can.stopRecipe). */
    fun stopRecipe(): CommandReply = command("POST", "/api/session/stop", null)

    /** Couleur et/ou thème du robot (voir state.can.setAppearance) ; null : inchangé. */
    fun setAppearance(accent: String?, theme: String?): CommandReply =
        command("POST", "/api/robot/appearance", AppearanceRequest(accent, theme).toMap())

    fun sendRecipe(id: String): CommandReply = command("POST", "/api/waiting", SendRecipeRequest(id).toMap())
    fun removeWaiting(): CommandReply = command("DELETE", "/api/waiting", null)
    fun pause(): CommandReply = command("POST", "/api/machine/pause", null)
    fun resume(): CommandReply = command("POST", "/api/machine/resume", null)
    fun stop(): CommandReply = command("POST", "/api/machine/stop", null)
    fun tare(): CommandReply = command("POST", "/api/machine/tare", null)
    /** « OK » : ferme le programme terminé ou arrêté affiché sur le robot (voir state.can.clear). */
    fun clear(): CommandReply = command("POST", "/api/machine/clear", null)
    fun adjust(req: AdjustRequest): CommandReply = command("POST", "/api/machine/adjust", req.toMap())
    /** Lance une cuisson manuelle (seule commande qui démarre quelque chose, voir state.can.start). */
    fun start(req: StartRequest): CommandReply = command("POST", "/api/machine/start", req.toMap())

    /**
     * Donne à ce téléphone le nom [name] sur le robot (voir state.can.renamePhone) ; vide : « Téléphone N ».
     * Réponse : ce téléphone tel que le robot le connaît désormais (nom nettoyé).
     */
    fun renameMe(name: String): PhoneWire =
        PhoneWire.fromMap(json(call("POST", "/api/phones/me", RenamePhoneRequest(name).toMap())))

    /** « Oublier » : retire ce téléphone de la liste du robot, puis oublie le jeton. */
    fun forget(): CommandReply {
        try {
            return command("DELETE", "/api/phones/me", null)
        } finally {
            // Oublié de ce côté même si la réponse se perd (le robot l'a peut-être déjà retiré).
            token = null
            proven = null
        }
    }

    private fun command(method: String, path: String, body: Map<String, Any?>?): CommandReply =
        CommandReply.fromMap(json(call(method, path, body)))

    // ------------------------------------------------------------------ transport

    private fun json(bytes: ByteArray): Map<String, Any?> {
        val v = try {
            Json.parse(String(bytes, Charsets.UTF_8))
        } catch (e: Json.ParseException) {
            proven = null
            throw IOException("invalid JSON response: ${e.message}")
        }
        @Suppress("UNCHECKED_CAST")
        return v as? Map<String, Any?> ?: throw IOException("JSON object expected")
    }

    @Throws(IOException::class)
    private fun call(method: String, path: String, body: Map<String, Any?>?, auth: Boolean = true,
                     readTimeout: Int = readTimeoutMs, connectTimeout: Int = connectTimeoutMs): ByteArray {
        if (auth) ensureProven()
        val h = host
        val urlHost = if (h.contains(':') && !h.startsWith("[")) "[$h]" else h
        val c = URL("http", urlHost, port, path).openConnection() as HttpURLConnection
        inflight += c
        try {
            c.requestMethod = method
            c.connectTimeout = connectTimeout
            c.readTimeout = readTimeout
            c.useCaches = false
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            if (auth) {
                val t = token ?: throw CompanionException(401, ApiError("unauthorized", "no token"))
                c.setRequestProperty("Authorization", "Bearer $t")
            }
            if (method == "POST") {
                val bytes = if (body == null) ByteArray(0) else Json.write(body).toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            val data = stream?.use { readLimited(it, MAX_RESPONSE_BYTES) } ?: ByteArray(0)
            if (status >= 400) {
                val err = try {
                    ApiError.fromMap(json(data))
                } catch (_: Exception) {
                    ApiError("http_$status", "")
                }
                throw CompanionException(status, err)
            }
            return data
        } catch (e: IOException) {
            // Erreur réseau (pas une réponse d'erreur de l'API) : refaire la preuve avant de renvoyer le jeton.
            if (e !is CompanionException) proven = null
            throw e
        } finally {
            inflight -= c
            c.disconnect()
        }
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

        fun enc(s: String): String = PairingPayload.encode(s)

        fun readLimited(input: InputStream, max: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > max) throw IOException("response too large")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
