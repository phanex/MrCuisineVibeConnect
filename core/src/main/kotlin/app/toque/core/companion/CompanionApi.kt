package app.toque.core.companion

import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.Portions
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.Step
import app.toque.core.recipes.Weigh
import app.toque.core.recipes.forServings
import java.util.Locale

/** Conversion des modèles de recette vers ceux qui voyagent. */
object Wires {
    fun step(index: Int, s: Step, weighedG: Int? = null, hotBlendOnRobot: Boolean = false): StepWire = StepWire(
        index, s.text,
        s.machine?.let { m ->
            MachineStepWire(m.program.key, m.seconds, m.speed, m.tempC, m.reverse, m.accessory.name, m.pulses, m.summary)
        },
        s.weigh?.let { WeighWire(it.label, it.grams, weighedG) },
        s.needsReview, hotBlendOnRobot,
    )

    /**
     * [r] : la recette telle que le robot l'affiche ; [baseServings] : son nombre de personnes d'origine
     * (0 : pas de réglage du nombre de personnes).
     */
    fun recipe(r: Recipe, baseServings: Int = 0): RecipeDetail = RecipeDetail(
        r.id, r.title, r.category, r.servings, r.servingUnit, r.prepMinutes, r.totalMinutes, r.difficulty, r.favorite,
        r.source == RecipeSource.USER, r.author, r.rating, r.ratingCount, r.image.isNotBlank(),
        r.ingredients.map { g -> IngredientGroupWire(g.title, g.items.map { IngredientWire(it.qty, it.unit, it.name, it.note, it.display) }) },
        r.steps.mapIndexed { i, s -> step(i, s) },
        language = r.language.ifBlank { I18n.code },
    ).let { d ->
        if (baseServings <= 0 || r.servings <= 0) d else {
            val range = Portions.range(baseServings)
            val total = Portions.totalGrams(r)
            d.copy(baseServings = baseServings, minServings = range.first, maxServings = range.last, totalGrams = total,
                bowlTooFull = Portions.bowlTooFull(r.servings, baseServings, total))
        }
    }

    /**
     * La fiche [d] (à son propre nombre de personnes) pour [people] personnes, telle que le robot la
     * rendrait : le téléphone l'affiche sans attendre sa réponse. Même calcul que le robot
     * ([Recipe.forServings], [Portions.totalGrams], [recipe]) sur la partie de la recette que la fiche
     * porte ; ce que le changement ne touche pas (réglages machine, textes écrits par le robot dans sa
     * langue) est repris de [d]. Null quand le résultat ne serait pas sûr : [d] n'est pas à son nombre
     * d'origine, [people] hors de la plage, pas de réglage.
     */
    fun forServings(d: RecipeDetail, people: Int): RecipeDetail? {
        val base = d.baseServings
        if (base <= 0 || d.servings != base || people !in Portions.range(base)) return null
        if (people == base) return d
        // Seulement ce que lisent forServings et totalGrams ; la langue vide (robot plus ancien) : celle du téléphone.
        val r = Recipe(id = d.id, title = d.title, servings = base, language = d.language, servingUnit = d.servingUnit,
            ingredients = d.ingredients.map { g -> IngredientGroup(g.title, g.items.map { Ingredient(it.qty, it.unit, it.name, it.note) }) },
            steps = d.steps.map { s -> Step(s.text, weigh = s.weigh?.let { Weigh(it.label, it.grams) }) })
        val n = recipe(r.forServings(people), base)
        return d.copy(
            servings = n.servings, ingredients = n.ingredients, totalGrams = n.totalGrams, bowlTooFull = n.bowlTooFull,
            steps = d.steps.mapIndexed { i, s ->
                val w = n.steps[i].weigh
                s.copy(text = n.steps[i].text, weigh = s.weigh?.let { o -> if (w == null) o else o.copy(label = w.label, grams = w.grams) })
            },
        )
    }
}

/**
 * Routes de l'API. Sans jeton, seuls GET /api/hello et POST /api/pair répondent ; tout le reste répond
 * 401 sans rien dire du robot (pas même si la route existe). Aucune en-tête CORS, aucun cookie : une
 * page d'un autre site ne peut ni lire les réponses ni envoyer l'en-tête Authorization.
 */
class CompanionApi(private val hub: CompanionHub) : HttpHandler {

    private val host get() = hub.hostRef()

    override fun handle(request: HttpRequest): HttpResponse {
        if (!hostAllowed(request.header("host"))) return ApiError.response(421, "bad_host", "Host must be an IP address")
        val method = if (request.method == "HEAD") "GET" else request.method
        // Les segments sont séparés avant décodage : « %2F » ne change jamais la route.
        val all = request.segments
        if (all.isEmpty() || all[0] != "api" || all.size < 2) return ApiError.response(404, "not_found", "Not found")
        val seg = all.drop(1)

        if (seg == listOf("hello") && method == "GET") return ok(Hello(pairing = hub.pairing.isOpen).toMap())
        if (seg == listOf("pair")) {
            if (method != "POST") return ApiError.response(405, "method_not_allowed", "Method not allowed")
            return pair(request)
        }
        if (seg == listOf("prove")) {
            if (method != "POST") return ApiError.response(405, "method_not_allowed", "Method not allowed")
            return prove(request)
        }

        val phone = hub.pairing.authenticate(bearer(request))
            ?: return ApiError.response(401, "unauthorized", tr("Ce téléphone n'est pas appairé avec le robot"))
        hub.noteAuthenticated(request.remote)

        return when {
            seg == listOf("robot") && method == "GET" -> ok(WhoAmI(hub.robotWire(), PhoneWire.of(phone)).toMap())
            seg == listOf("state") && method == "GET" -> state(request, phone)
            seg == listOf("journal") && method == "GET" -> journal(request, phone)
            seg == listOf("recipes") && method == "GET" -> catalog(request)
            seg.size == 2 && seg[0] == "recipes" && method == "GET" -> recipe(seg[1], request)
            seg.size == 3 && seg[0] == "recipes" && seg[2] == "image" && method == "GET" -> image(seg[1])
            seg.size == 3 && seg[0] == "recipes" && seg[2] == "servings" && method == "POST" -> servings(seg[1], request, phone)
            seg.size == 3 && seg[0] == "recipes" && seg[2] == "favorite" && method == "POST" -> favorite(seg[1], request, phone)
            seg == listOf("recipes") && method == "POST" -> createRecipe(request, phone)
            seg.size == 3 && seg[0] == "recipes" && seg[2] == "edit" && method == "GET" -> editable(seg[1])
            seg.size == 3 && seg[0] == "recipes" && seg[2] == "edit" && method == "POST" -> saveRecipe(seg[1], request, phone)
            seg.size == 3 && seg[0] == "recipes" && seg[2] == "copy" && method == "POST" -> copyRecipe(seg[1], phone)
            seg == listOf("editor") && method == "GET" -> editor()
            seg == listOf("session", "stop") && method == "POST" -> refusedOrDone(hub.stopRecipe(phone))
            seg == listOf("robot", "appearance") && method == "POST" -> appearance(request, phone)
            seg == listOf("waiting") && method == "POST" -> sendRecipe(request, phone)
            seg == listOf("waiting") && method == "DELETE" ->
                if (hub.removeWaiting(phone)) done() else ApiError.response(409, "no_waiting", tr("Aucune recette en attente"))
            seg == listOf("phones", "me") && method == "DELETE" -> {
                hub.pairing.remove(phone.id)
                done()
            }
            seg.size == 2 && seg[0] == "machine" && method == "POST" -> machine(seg[1], request, phone)
            known(seg) -> ApiError.response(405, "method_not_allowed", "Method not allowed")
            else -> ApiError.response(404, "not_found", "Not found")
        }
    }

    private fun known(seg: List<String>): Boolean = seg in listOf(listOf("robot"), listOf("state"), listOf("journal"),
        listOf("recipes"), listOf("waiting"), listOf("phones", "me"), listOf("session", "stop"), listOf("robot", "appearance"), listOf("editor")) || (seg.size == 2 && seg[0] == "machine" && seg[1] in MACHINE) ||
        (seg.size in 2..3 && seg[0] == "recipes")

    private fun ok(m: Map<String, Any?>) = HttpResponse.json(200, m)
    private fun done() = ok(CommandReply(true, hub.currentRevision).toMap())

    private fun bearer(r: HttpRequest): String? {
        val h = r.header("authorization") ?: return null
        if (!h.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
        return h.substring(7).trim()
    }

    /** Corps JSON (objet) ou réponse d'erreur. */
    private fun body(r: HttpRequest): Pair<Map<String, Any?>?, HttpResponse?> {
        val type = r.header("content-type")?.lowercase(Locale.ROOT) ?: ""
        if (r.body.isEmpty()) return emptyMap<String, Any?>() to null
        if (!type.startsWith("application/json")) return null to ApiError.response(415, "unsupported_media_type", "Content-Type must be application/json")
        return try {
            W.parse(r.bodyText) to null
        } catch (e: Exception) {
            null to ApiError.response(400, "bad_json", "Invalid JSON body")
        }
    }

    private fun intParam(r: HttpRequest, k: String, def: Long, min: Long, max: Long): Long? {
        val v = r.query[k] ?: return def
        val n = v.toLongOrNull() ?: return null
        return n.coerceIn(min, max)
    }

    private fun badParam(k: String) = ApiError.response(400, "bad_request", "Invalid parameter: $k")

    // ------------------------------------------------------------------ routes

    private fun pair(r: HttpRequest): HttpResponse {
        val (m, err) = body(r)
        if (err != null) return err
        val req = PairRequest.fromMap(m!!)
        val result = when {
            req.code != null && req.secret == null -> hub.pairing.pairWithCode(req.code)
            req.secret != null && req.code == null -> hub.pairing.pairWithSecret(req.secret)
            else -> return ApiError.response(400, "bad_request", "Give either code or secret")
        }
        val phone = result.phone
        if (phone == null || result.token == null) {
            val status = when (result.error) {
                "wrong_code" -> 403
                "code_dead", "code_expired", "pairing_closed" -> 410
                "too_many_phones" -> 409
                else -> 400
            }
            return ApiError.response(status, result.error ?: "refused", result.message)
        }
        return ok(PairReply(result.token, PhoneWire.of(phone), hub.robotWire()).toMap())
    }

    /** Preuve d'identité du robot, sans jeton : rien n'est révélé (réponse de même forme pour un inconnu). */
    private fun prove(r: HttpRequest): HttpResponse {
        val (m, err) = body(r)
        if (err != null) return err
        val req = ProveRequest.fromMap(m!!)
        if (!CompanionProof.validPhoneId(req.phoneId) || !CompanionProof.validNonce(req.nonce))
            return ApiError.response(400, "bad_request", "phoneId (8 hex) and nonce (32 hex) required")
        return ok(ProveReply(hub.prove(req.phoneId, req.nonce, r.local)).toMap())
    }

    private fun state(r: HttpRequest, phone: Phone): HttpResponse {
        val since = r.query["since"]
        if (since != null) {
            val n = since.toLongOrNull() ?: return badParam("since")
            val wait = intParam(r, "wait", 20, 0, hub.limits.maxLongPollMs / 1000) ?: return badParam("wait")
            // quiet=1 : la télémesure seule (température, poids) ne répond pas (téléphone en arrière-plan).
            // replace=1 : cette attente remplace celle que le même téléphone a peut-être laissée en cours.
            hub.awaitChange(n, wait * 1000, r.query["quiet"] == "1", if (r.query["replace"] == "1") phone.id else null)
        }
        return ok(hub.state(phone).toMap())
    }

    private fun journal(r: HttpRequest, phone: Phone): HttpResponse {
        val after = intParam(r, "after", 0, 0, Long.MAX_VALUE) ?: return badParam("after")
        val limit = intParam(r, "limit", 100, 1, 300) ?: return badParam("limit")
        return ok(hub.journalPage(phone, after, limit.toInt()).toMap())
    }

    private fun catalog(r: HttpRequest): HttpResponse {
        val q = r.query["q"] ?: ""
        if (q.length > 100) return badParam("q")
        val filter = CatalogFilter.of(r.query["filter"]) ?: return badParam("filter")
        // Catégorie : obligatoire avec filter=category, interdite sinon.
        val category = r.query["category"]
        if (filter == CatalogFilter.CATEGORY) {
            if (category == null || category.isBlank() || category.length > 100 || category.any { it < ' ' }) return badParam("category")
        } else if (category != null) return badParam("category")
        val offset = intParam(r, "offset", 0, 0, 100_000) ?: return badParam("offset")
        val limit = intParam(r, "limit", 20, 1, 50) ?: return badParam("limit")
        val page = host.catalog(CatalogQuery(q, filter, offset.toInt(), limit.toInt(), category ?: ""))
        return ok(page.toMap())
    }

    /** ?base=1 : la recette à son propre nombre de personnes (corrections comprises), rien n'est enregistré. */
    private fun recipe(id: String, r: HttpRequest): HttpResponse {
        val base = when (r.query["base"]) {
            null, "0" -> false
            "1" -> true
            else -> return badParam("base")
        }
        if (!validId(id)) return ApiError.response(404, "not_found", tr("Recette introuvable"))
        val rec = (if (base) host.baseRecipe(id) else host.recipe(id)) ?: return ApiError.response(404, "not_found", tr("Recette introuvable"))
        return ok(Wires.recipe(rec, host.baseServings(id)).toMap())
    }

    /** Nombre de personnes choisi depuis le téléphone ; réponse : la fiche à ce nombre. */
    private fun servings(id: String, r: HttpRequest, phone: Phone): HttpResponse {
        if (!validId(id)) return ApiError.response(404, "not_found", tr("Recette introuvable"))
        val (m, err) = body(r)
        if (err != null) return err
        val req = try {
            ServingsRequest.fromMap(m!!)
        } catch (e: Exception) {
            return ApiError.response(400, "bad_request", "Body must be {\"servings\": 1-99}")
        }
        val reply = hub.setServings(phone, id, req.servings)
        val refusal = reply.refusal
        val recipe = reply.recipe
        if (refusal != null || recipe == null) {
            val c = refusal ?: CommandResult.noAnswer()
            return ApiError.response(when (c.code) {
                "not_found" -> 404
                "servings_range", "bad_request" -> 400
                "timeout", "no_answer" -> 504
                else -> 409
            }, c.code, c.message)
        }
        return ok(Wires.recipe(recipe, reply.baseServings).toMap())
    }

    /** Couleur et/ou thème du robot choisis depuis le téléphone ; corps strict, rien n'est appliqué sur un refus. */
    private fun appearance(r: HttpRequest, phone: Phone): HttpResponse {
        val (m, err) = body(r)
        if (err != null) return err
        val req = try {
            AppearanceRequest.fromMap(m!!)
        } catch (e: Exception) {
            return ApiError.response(400, "bad_request", "Body must be {\"accent\": a colour the robot accepts (#RRGGBB), \"theme\": \"dark\"|\"light\"}")
        }
        return refusedOrDone(hub.setAppearance(phone, req))
    }

    /** Favori choisi depuis le téléphone ; réponse : la recette et son nouvel état. */
    private fun favorite(id: String, r: HttpRequest, phone: Phone): HttpResponse {
        if (!validId(id)) return ApiError.response(404, "not_found", tr("Recette introuvable"))
        val (m, err) = body(r)
        if (err != null) return err
        val req = try {
            FavoriteRequest.fromMap(m!!)
        } catch (e: Exception) {
            return ApiError.response(400, "bad_request", "Body must be {\"favorite\": true|false}")
        }
        val result = hub.setFavorite(phone, id, req.favorite)
        if (!result.ok) return error(result)
        return ok(FavoriteReply(id, req.favorite).toMap())
    }

    // ------------------------------------------------------------------ recettes écrites sur le téléphone

    /**
     * Corps plus grand (RecipeEdits.MAX_BODY_BYTES) pour les deux routes qui portent une recette entière,
     * et seulement avec un en-tête Authorization : le reste garde la borne du serveur.
     */
    override fun maxBodyBytes(method: String, segments: List<String>, headers: Map<String, String>): Int? {
        if (method != "POST" || headers["authorization"]?.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true) != true) return null
        val recipe = segments.size == 2 && segments[0] == "api" && segments[1] == "recipes"
        val edit = segments.size == 4 && segments[0] == "api" && segments[1] == "recipes" && segments[3] == "edit"
        return if (recipe || edit) RecipeEdits.MAX_BODY_BYTES else null
    }

    private fun writeError(e: EditError) = ApiError.response(e.status, e.code, e.message, e.field, e.step)

    private fun written(w: RecipeWrite): HttpResponse {
        val r = w.saved ?: return writeError(w.error ?: EditError.of(CommandResult.noAnswer()))
        return ok(hub.editableOf(r).toMap())
    }

    private fun editRequest(r: HttpRequest, create: Boolean): Pair<RecipeEditRequest?, HttpResponse?> {
        val (m, err) = body(r)
        if (err != null) return null to err
        return try {
            RecipeEditRequest.fromMap(m!!, create, settings = hub.takesSettings) to null
        } catch (e: BadEdit) {
            null to when (e.field) {
                "machine" -> ApiError.response(400, "bad_request", "Machine settings are set on the robot, never sent by the phone", "steps", e.step)
                "machine_setting" -> ApiError.response(400, "bad_request", "Invalid machine setting", "steps", e.step)
                else -> ApiError.response(400, "bad_request", "Invalid recipe body: ${e.field}", e.field, e.step)
            }
        }
    }

    private fun editor(): HttpResponse {
        val (e, err) = hub.editor()
        return if (e != null) ok(e.toMap()) else writeError(err!!)
    }

    private fun editable(id: String): HttpResponse {
        if (!validId(id)) return ApiError.response(404, "not_found", tr("Recette introuvable"))
        val (e, err) = hub.editable(id)
        return if (e != null) ok(e.toMap()) else writeError(err!!)
    }

    private fun saveRecipe(id: String, r: HttpRequest, phone: Phone): HttpResponse {
        if (!validId(id)) return ApiError.response(404, "not_found", tr("Recette introuvable"))
        val (req, err) = editRequest(r, create = false)
        if (err != null) return err
        return written(hub.saveRecipe(phone, id, req!!))
    }

    private fun createRecipe(r: HttpRequest, phone: Phone): HttpResponse {
        val (req, err) = editRequest(r, create = true)
        if (err != null) return err
        return written(hub.createRecipe(phone, req!!))
    }

    private fun copyRecipe(id: String, phone: Phone): HttpResponse {
        if (!validId(id)) return ApiError.response(404, "not_found", tr("Recette introuvable"))
        return written(hub.copyRecipe(phone, id))
    }

    private fun image(id: String): HttpResponse {
        if (!validId(id)) return ApiError.response(404, "not_found", "No image")
        val img = host.image(id) ?: return ApiError.response(404, "not_found", "No image")
        if (img.bytes.size > MAX_IMAGE_BYTES) return ApiError.response(404, "not_found", "No image")
        val type = if (img.contentType in IMAGE_TYPES) img.contentType else "application/octet-stream"
        return HttpResponse(200, type, img.bytes, mapOf("Cache-Control" to "private, max-age=3600"))
    }

    private fun sendRecipe(r: HttpRequest, phone: Phone): HttpResponse {
        val (m, err) = body(r)
        if (err != null) return err
        val id = SendRecipeRequest.fromMap(m!!).recipeId
        if (!validId(id)) return ApiError.response(400, "bad_request", "recipeId required")
        val rec = host.recipe(id) ?: return ApiError.response(404, "not_found", tr("Recette introuvable"))
        hub.sendRecipe(phone, rec)
        return done()
    }

    private fun machine(cmd: String, r: HttpRequest, phone: Phone): HttpResponse {
        val result = when (cmd) {
            "pause" -> hub.pause(phone)
            "resume" -> hub.resume(phone)
            "stop" -> hub.stop(phone)
            "tare" -> hub.tare(phone)
            "clear" -> hub.clear(phone)
            "adjust" -> {
                val (m, err) = body(r)
                if (err != null) return err
                val req = try {
                    AdjustRequest.fromMap(m!!)
                } catch (e: Exception) {
                    return ApiError.response(400, "bad_request", "Invalid adjust body")
                }
                hub.adjust(phone, req)
            }
            "start" -> {
                val (m, err) = body(r)
                if (err != null) return err
                val req = try {
                    StartRequest.fromMap(m!!)
                } catch (e: Exception) {
                    return ApiError.response(400, "bad_request", "Invalid start body")
                }
                hub.start(phone, req)
            }
            else -> return ApiError.response(404, "not_found", "Not found")
        }
        return refusedOrDone(result)
    }

    private fun refusedOrDone(result: CommandResult): HttpResponse = if (result.ok) done() else error(result)

    /** Refus d'une commande : 404 (recette inconnue), 400, 504 (robot sans réponse), 409 (règle). */
    private fun error(result: CommandResult): HttpResponse = ApiError.response(when (result.code) {
        "not_found" -> 404
        "timeout", "no_answer" -> 504
        "bad_request" -> 400
        else -> 409
    }, result.code, result.message)

    companion object {
        val MACHINE = setOf("pause", "resume", "stop", "tare", "adjust", "start", "clear")
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        fun validId(id: String): Boolean = id.isNotEmpty() && id.length <= 200 && id.none { it < ' ' }

        /**
         * Contre le « DNS rebinding » : l'en-tête Host doit être une adresse IP (v4 ou [v6]) ou localhost,
         * jamais un nom de domaine. Sans en-tête Host (HTTP/1.0), accepté.
         */
        fun hostAllowed(h: String?): Boolean {
            if (h == null) return true
            val v = h.trim().lowercase(Locale.ROOT)
            if (v.isEmpty()) return false
            val name = when {
                v.startsWith("[") -> {
                    val end = v.indexOf(']')
                    if (end < 0) return false
                    val rest = v.substring(end + 1)
                    if (rest.isNotEmpty() && !(rest.startsWith(":") && rest.length > 1 && rest.substring(1).all { it.isDigit() })) return false
                    return v.substring(1, end).all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' || it == '%' }
                }
                v.contains(':') -> {
                    val port = v.substringAfterLast(':')
                    if (port.isEmpty() || !port.all { it.isDigit() }) return false
                    v.substringBeforeLast(':')
                }
                else -> v
            }
            return name == "localhost" || IPV4.matches(name)
        }
    }
}
