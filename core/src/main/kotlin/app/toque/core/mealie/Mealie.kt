package app.toque.core.mealie

import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.json.Json
import app.toque.core.json.bool
import app.toque.core.json.double
import app.toque.core.json.int
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.json.str
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.McOnline
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepDurations
import app.toque.core.recipes.StepText
import app.toque.core.recipes.UserRecipes
import app.toque.core.robot.RobotProfile
import app.toque.core.robot.RobotProfiles
import app.toque.core.site.ToqueSite
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.floor

/**
 * Recettes d'un serveur Mealie (gestionnaire de recettes auto-hébergé), reçues en lecture seule :
 * identifiant « mealie-<uuid> », photo « mealie-<uuid>-<clé>.webp ». Les réglages machine écrits
 * dans le texte des étapes sont proposés à vérifier ([StepText]), jamais lancés sans accord.
 */
object MealieRecipes {
    const val ID_PREFIX = "mealie-"
    /** Nom du jeton créé sur le serveur pour ce robot (visible dans Mealie, Profil › Jetons d'API). */
    const val TOKEN_NAME = "Toque (robot)"

    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    /** Photos des recettes Mealie dans le dossier des photos du robot. */
    val PHOTO_NAME = Regex("mealie-[0-9a-f-]{36}(?:-[A-Za-z0-9]{1,16})?\\.webp")

    fun isMealie(id: String): Boolean = id.startsWith(ID_PREFIX)

    fun toqueId(uuid: String): String = ID_PREFIX + uuid

    fun uuidOf(id: String): String? = id.removePrefix(ID_PREFIX).takeIf { isMealie(id) && UUID.matches(it) }

    /** Nom de la photo : la clé d'image de Mealie change avec la photo (une nouvelle photo est reprise). */
    fun photoName(uuid: String, imageKey: String?): String {
        val key = imageKey.orEmpty().filter { it.isLetterOrDigit() && it.code < 128 }.take(16)
        return if (key.isEmpty()) "mealie-$uuid.webp" else "mealie-$uuid-$key.webp"
    }

    /** L'uuid d'une photo Mealie ([PHOTO_NAME]), ou null. */
    fun photoUuid(name: String): String? = if (PHOTO_NAME.matches(name)) name.substring(ID_PREFIX.length, ID_PREFIX.length + 36) else null

    /**
     * Une recette Mealie (réponse de /api/recipes/{slug}) en recette Toque, ou null si elle n'a ni
     * identifiant ni nom. Les réglages proposés sont ceux que [robot] sait faire.
     */
    fun toRecipe(m: Map<String, Any?>, robot: RobotProfile = RobotProfiles.active): Recipe? {
        val uuid = m.str("id")?.trim()?.lowercase().orEmpty()
        val title = clean(m.str("name"))
        if (!UUID.matches(uuid) || title.isEmpty()) return null
        val amounts = m["settings"].obj().bool("disableAmount") != true
        val (servings, unit) = servings(m)
        val total = minutes(m, "totalTime")
        val prep = minutes(m, "prepTime")
        return Recipe(
            id = toqueId(uuid),
            title = title.take(200),
            category = m["recipeCategory"].list().firstNotNullOfOrNull { clean(it.obj().str("name")).ifEmpty { null } }.orEmpty(),
            tags = m["tags"].list().mapNotNull { clean(it.obj().str("name")).ifEmpty { null } }.distinct().take(20),
            servings = servings,
            servingUnit = unit,
            prepMinutes = prep,
            totalMinutes = if (total > 0) total else prep + minutes(m, "performTime"),
            ingredients = ingredients(m["recipeIngredient"].list(), amounts),
            steps = steps(m["recipeInstructions"].list(), robot),
            source = RecipeSource.MEALIE,
            image = if (m.str("image").isNullOrBlank()) "" else photoName(uuid, m.str("image")),
            notes = notes(m),
        )
    }

    private fun ingredients(raw: List<Any?>, amounts: Boolean): List<IngredientGroup> {
        val groups = ArrayList<IngredientGroup>()
        var title = ""
        var items = ArrayList<Ingredient>()
        for (any in raw) {
            val i = any.obj()
            val t = clean(i.str("title"))
            if (t.isNotEmpty()) {
                if (items.isNotEmpty()) groups += IngredientGroup(title, items)
                title = t
                items = ArrayList()
            }
            ingredient(i, amounts)?.let { items += it }
        }
        if (items.isNotEmpty()) groups += IngredientGroup(title, items)
        return groups
    }

    private fun ingredient(i: Map<String, Any?>, amounts: Boolean): Ingredient? {
        val q = (i.double("quantity") ?: 0.0).takeIf { amounts && it > 0 && it < 100_000 } ?: 0.0
        val note = clean(i.str("note"))
        val food = i["food"].obj()
        val u = i["unit"].obj()
        val unit = if (q > 0) unitText(u, q) else ""
        val name = clean(food.str("name")).ifEmpty { clean(i["referencedRecipe"].obj().str("name")) }
        if (name.isNotEmpty()) {
            val plural = clean(food.str("pluralName"))
            return Ingredient(if (q > 0) qtyText(q) else "", unit, if (q > 1 && unit.isEmpty() && plural.isNotEmpty()) plural else name, note)
        }
        // Ingrédient non analysé par Mealie : tout est dans la note (« 500 g de farine »).
        var text = note.ifEmpty { clean(i.str("display")) }.ifEmpty { clean(i.str("originalText")) }
        if (text.isEmpty()) return null
        if (q > 0 && !text.first().isDigit()) text = qtyText(q) + (if (unit.isEmpty()) "" else " $unit") + " " + text
        return UserRecipes.ingredient(text) ?: Ingredient(name = text)
    }

    private fun unitText(u: Map<String, Any?>, q: Double): String {
        val plural = q > 1
        var v = ""
        if (u.bool("useAbbreviation") == true)
            v = clean(if (plural) u.str("pluralAbbreviation") else null).ifEmpty { clean(u.str("abbreviation")) }
        if (v.isEmpty()) v = clean(if (plural) u.str("pluralName") else null).ifEmpty { clean(u.str("name")) }
        return v
    }

    private val GLYPHS = listOf(0.5 to "½", 0.25 to "¼", 0.75 to "¾", 1 / 3.0 to "⅓", 2 / 3.0 to "⅔")

    /** Quantité lisible : « 2 », « 1 ½ », « 0,4 » (virgule sauf en anglais). */
    fun qtyText(q: Double): String {
        val whole = floor(q).toLong()
        val frac = q - whole
        if (frac < 1e-6) return whole.toString()
        GLYPHS.firstOrNull { abs(it.first - frac) < 0.01 }?.let { return if (whole == 0L) it.second else "$whole ${it.second}" }
        val r = Math.round(q * 100) / 100.0
        val s = if (r == floor(r)) r.toLong().toString() else r.toString()
        return if (I18n.code.startsWith("en")) s else s.replace('.', ',')
    }

    private fun steps(raw: List<Any?>, robot: RobotProfile): List<Step> = raw.mapNotNull { any ->
        val s = any.obj()
        val body = markdown(s.str("text")).ifEmpty { clean(s.str("summary")) }
        val head = clean(s.str("title"))
        val text = if (head.isEmpty()) body else if (body.isEmpty()) head else "$head\n$body"
        if (text.isEmpty()) return@mapNotNull null
        val machine = StepText.propose(body, robot)
        Step(text.take(4000), machine, needsReview = machine != null)
    }

    private fun notes(m: Map<String, Any?>): String {
        val parts = ArrayList<String>()
        markdown(m.str("description")).takeIf { it.isNotEmpty() }?.let { parts += it }
        for (n in m["notes"].list()) {
            val o = n.obj()
            val t = clean(o.str("title"))
            val x = markdown(o.str("text"))
            if (t.isNotEmpty() || x.isNotEmpty()) parts += if (t.isEmpty()) x else if (x.isEmpty()) t else "$t\n$x"
        }
        clean(m.str("orgURL")).takeIf { it.startsWith("http") }?.let { parts += tr("Source : %s", it) }
        return parts.joinToString("\n\n").take(8000)
    }

    /** Nombre de parts et ce qu'elles comptent quand ce ne sont pas des personnes (« 12 cookies »). */
    private fun servings(m: Map<String, Any?>): Pair<Int, String> {
        val people = m.double("recipeServings") ?: 0.0
        if (people >= 1) return people.toInt().coerceAtMost(MAX_SERVINGS) to ""
        val yieldText = clean(m.str("recipeYield"))
        val qty = m.double("recipeYieldQuantity") ?: 0.0
        if (qty >= 1) return qty.toInt().coerceAtMost(MAX_SERVINGS) to unitOf(yieldText)
        // Mealie d'avant 2.0 : « 4 servings », « 6 pots ».
        val n = Regex("^(\\d{1,3})\\s*(.*)$").find(yieldText) ?: return 0 to ""
        val v = n.groupValues[1].toInt()
        return if (v < 1) 0 to "" else v.coerceAtMost(MAX_SERVINGS) to unitOf(n.groupValues[2])
    }

    private const val MAX_SERVINGS = 50
    private val PEOPLE = Regex("(?i)^(?:servings?|serves|portions?|personnes?|pers\\.?|people|persons?|portionen|personen|raciones|personas|racions|persone|porzioni)$")

    private fun unitOf(text: String): String {
        val t = text.trim().trimEnd('.').take(24)
        return if (t.isEmpty() || PEOPLE.matches(t)) "" else t
    }

    /** Durée en minutes : les secondes de Mealie récent, sinon le texte (« PT1H30M », « 1 hour 30 minutes »). */
    private fun minutes(m: Map<String, Any?>, key: String): Int {
        m.int(key + "Seconds")?.takeIf { it > 0 }?.let { return ((it + 59) / 60).coerceAtMost(MAX_MINUTES) }
        val t = clean(m.str(key))
        if (t.isEmpty()) return 0
        ISO.matchEntire(t.uppercase())?.let { iso ->
            val (d, h, min) = (1..3).map { iso.groupValues[it].toIntOrNull() ?: 0 }
            return (d * 1440 + h * 60 + min).coerceAtMost(MAX_MINUTES)
        }
        t.toIntOrNull()?.let { return it.coerceIn(0, MAX_MINUTES) }
        val s = StepDurations.all(t).firstOrNull() ?: return 0
        return ((s + 59) / 60).coerceAtMost(MAX_MINUTES)
    }

    private const val MAX_MINUTES = 7 * 24 * 60
    private val ISO = Regex("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:\\d+(?:\\.\\d+)?S)?")

    private fun clean(s: String?): String = s?.replace(' ', ' ')?.trim().orEmpty()

    /** Texte simple à partir du Markdown (ou du HTML léger) de Mealie. */
    fun markdown(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val t = s
            .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
            .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
            .replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
            .replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\w)"), "$1")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("(?m)^\\s{0,3}>\\s?"), "")
        return McOnline.plain(t).orEmpty()
    }
}

/**
 * Client de l'API d'un serveur Mealie. Le transport HTTP est fourni (celui du robot, ou un faux dans
 * les tests). Le jeton n'est jamais écrit dans les messages ; le mot de passe ne sert qu'à créer le jeton.
 */
class MealieClient(val base: String, private val token: String, private val transport: ToqueSite.Transport) {

    /** Refus ou réponse illisible du serveur ; [status] : code HTTP (0 : pas un serveur Mealie). */
    class MealieException(val status: Int, message: String) : IOException(message)

    /** Une recette de la liste : [stamp] change quand la recette ou sa photo changent. */
    class Summary(val uuid: String, val slug: String, val stamp: String)

    class Page(val items: List<Summary>, val total: Int, val totalPages: Int)

    /** Version du serveur (vide si inconnue) ; MealieException si ce n'est pas un serveur Mealie. */
    fun version(): String {
        val r = transport.send("GET", "$base/api/app/about", headers(null), null)
        val m = parse(r)
        if (r.code !in 200..299 || !m.containsKey("version")) throw MealieException(0, tr("Pas de serveur Mealie à cette adresse"))
        return m.str("version").orEmpty()
    }

    /** Une page de la liste des recettes, par nom (ordre stable). */
    fun page(n: Int, perPage: Int = PER_PAGE): Page {
        val m = get("/api/recipes?page=$n&perPage=$perPage&orderBy=name&orderDirection=asc")
        if (m["items"] !is List<*>) throw MealieException(0, tr("Réponse illisible du serveur Mealie"))
        val items = m["items"].list().mapNotNull { any ->
            val o = any.obj()
            val uuid = o.str("id")?.trim()?.lowercase().orEmpty()
            val slug = o.str("slug")?.trim().orEmpty()
            if (MealieRecipes.uuidOf(MealieRecipes.toqueId(uuid)) == null || slug.isEmpty()) return@mapNotNull null
            val updated = o.str("updatedAt") ?: o.str("dateUpdated") ?: ""
            Summary(uuid, slug, updated + "|" + o.str("image").orEmpty())
        }
        return Page(items, m.int("total") ?: items.size, m.int("total_pages") ?: m.int("totalPages") ?: 1)
    }

    /** Le détail d'une recette (JSON de Mealie, converti par [MealieRecipes.toRecipe]). */
    fun recipe(slug: String): Map<String, Any?> = get("/api/recipes/" + URLEncoder.encode(slug, "UTF-8").replace("+", "%20"))

    /** Adresse de la petite photo d'une recette (servie sans jeton). */
    fun photoUrl(uuid: String): String = "$base/api/media/recipes/$uuid/images/min-original.webp"

    /** Retire de Mealie le jeton [id] de ce robot (déliaison). */
    fun deleteToken(id: Long) {
        val r = transport.send("DELETE", "$base/api/users/api-tokens/$id", headers(token), null)
        if (r.code !in 200..299 && r.code != 404) throw error(r)
    }

    private fun get(path: String): Map<String, Any?> {
        val r = transport.send("GET", base + path, headers(token), null)
        if (r.code !in 200..299) throw error(r)
        return parse(r).ifEmpty { throw MealieException(r.code, tr("Réponse illisible du serveur Mealie")) }
    }

    private fun headers(bearer: String?): Map<String, String> {
        val h = linkedMapOf("Accept" to "application/json", "Accept-Language" to I18n.code)
        if (bearer != null) h["Authorization"] = "Bearer $bearer"
        return h
    }

    companion object {
        const val PER_PAGE = 100

        /**
         * Adresse du serveur telle qu'écrite (« mealie.maison.lan:9000/ », « https://…/g/home ») :
         * schéma (http par défaut sur le réseau local), sans « / » final ni page de l'interface. Null si illisible.
         */
        fun normalize(url: String): String? {
            var u = url.trim().trimEnd('/')
            if (u.isEmpty() || u.any { it.isWhitespace() }) return null
            if (!SCHEME.containsMatchIn(u)) u = "http://$u"
            val m = ADDRESS.find(u) ?: return null
            val host = m.groupValues[2].lowercase()
            if (host.startsWith(":") || host.startsWith(".")) return null
            var path = m.groupValues[3].trimEnd('/')
            // Une page de l'interface collée telle quelle : on garde seulement le chemin d'installation.
            for (cut in listOf("/g/", "/api/", "/login", "/user/", "/recipe/", "/r/")) {
                val i = "$path/".indexOf(cut)
                if (i >= 0) path = path.substring(0, minOf(i, path.length))
            }
            if (path == "/g" || path == "/api") path = ""
            return m.groupValues[1].lowercase() + "://" + host + path
        }

        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
        private val ADDRESS = Regex("^(?i)(https?)://([A-Za-z0-9.\\-]+(?::\\d{1,5})?|\\[[0-9A-Fa-f:.]+](?::\\d{1,5})?)(/[^?#]*)?(?:[?#].*)?$")

        /** Jeton de session pour [user] et [password] (sert seulement à créer le jeton du robot). */
        fun login(base: String, user: String, password: String, transport: ToqueSite.Transport): String {
            val body = "username=" + enc(user.trim()) + "&password=" + enc(password) + "&remember_me=false"
            val r = transport.send("POST", "$base/api/auth/token",
                mapOf("Accept" to "application/json", "Content-Type" to "application/x-www-form-urlencoded"), body)
            if (r.code == 401) throw MealieException(401, tr("Identifiant ou mot de passe incorrect"))
            if (r.code == 423) throw MealieException(423, tr("Compte Mealie bloqué : réessayez plus tard"))
            if (r.code !in 200..299) throw error(r)
            return parse(r).str("access_token")?.takeIf { it.isNotBlank() } ?: throw MealieException(r.code, tr("Réponse illisible du serveur Mealie"))
        }

        /** Crée le jeton longue durée de ce robot avec la session [session] : (jeton, numéro). */
        fun createToken(base: String, session: String, transport: ToqueSite.Transport): Pair<String, Long> {
            val r = transport.send("POST", "$base/api/users/api-tokens",
                mapOf("Accept" to "application/json", "Content-Type" to "application/json", "Authorization" to "Bearer $session"),
                Json.write(mapOf("name" to MealieRecipes.TOKEN_NAME)))
            if (r.code !in 200..299) throw error(r)
            val m = parse(r)
            val t = m.str("token")?.takeIf { it.isNotBlank() } ?: throw MealieException(r.code, tr("Réponse illisible du serveur Mealie"))
            return t to ((m["id"] as? Number)?.toLong() ?: 0L)
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private fun parse(r: ToqueSite.Reply): Map<String, Any?> = try {
            Json.parse(r.body).obj()
        } catch (_: Exception) {
            emptyMap()
        }

        private fun error(r: ToqueSite.Reply): MealieException {
            val detail = parse(r)["detail"]
            val text = (detail as? String) ?: detail.obj().str("message")
            return when (r.code) {
                401, 403 -> MealieException(r.code, tr("Jeton refusé par Mealie : reliez à nouveau le robot"))
                in 300..399 -> MealieException(r.code, tr("Le serveur redirige ailleurs : essayez l'adresse en https://"))
                else -> MealieException(r.code, if (text.isNullOrBlank()) tr("Réponse %d du serveur Mealie", r.code) else text.take(200))
            }
        }
    }
}

/**
 * Synchro en lecture seule avec Mealie : toutes les recettes du serveur arrivent sur le robot ;
 * seules celles changées (date ou photo) sont redemandées ; celles retirées de Mealie partent du
 * robot, mais seulement après une liste complète (une liste coupée ne retire rien).
 */
class MealieSync(private val client: MealieClient, private val store: Store, private val robot: () -> RobotProfile = { RobotProfiles.active }) {

    interface Store {
        /** uuid → empreinte ([MealieClient.Summary.stamp]) des recettes déjà reçues. */
        var index: Map<String, String>
        fun mealieIds(): Set<String>
        /** Ajoute ou met à jour des recettes (sans recharger le catalogue). */
        fun save(recipes: List<Recipe>)
        /** Retire les recettes Mealie absentes de [keep] (identifiants Toque). */
        fun removeMealieExcept(keep: Set<String>)
        /**
         * Liste complète du serveur relié reçue : [keep] (identifiants Toque) sont toutes ses recettes. Ce qui
         * reste au robot pour une autre recette Mealie (réglages d'étapes du propriétaire gardés en déliant,
         * puis serveur changé ou recette supprimée) ne pourra plus servir.
         */
        fun listed(keep: Set<String>) {}
    }

    class Result(val ok: Boolean, val added: Int, val updated: Int, val removed: Int, val message: String)

    /** Arrêt demandé (ce qui est reçu est gardé ; la prochaine synchro reprend). */
    class Stopped : Exception()

    fun run(stop: () -> Boolean = { false }): Result {
        val present = store.mealieIds()
        val index = HashMap(store.index)
        val listed = LinkedHashMap<String, MealieClient.Summary>()
        var added = 0
        var updated = 0
        var removed = 0
        var skipped = 0
        try {
            var total = 0
            var n = 1
            while (true) {
                if (stop()) throw Stopped()
                val p = client.page(n)
                for (s in p.items) listed[s.uuid] = s
                total = p.total
                if (p.items.isEmpty() || n >= p.totalPages || n >= MAX_PAGES) break
                n++
            }
            val todo = listed.values.filter { s -> MealieRecipes.toqueId(s.uuid) !in present || index[s.uuid] != s.stamp }
            val batch = ArrayList<Recipe>(BATCH)
            fun flush() {
                if (batch.isEmpty()) return
                store.save(batch)
                store.index = HashMap(index)
                batch.clear()
            }
            for (s in todo) {
                if (stop()) {
                    flush()
                    throw Stopped()
                }
                val r = try {
                    MealieRecipes.toRecipe(client.recipe(s.slug), robot())
                } catch (e: MealieClient.MealieException) {
                    if (e.status == 401 || e.status == 403) throw e
                    null
                }
                if (r == null) {
                    skipped++
                    continue
                }
                if (r.id in present) updated++ else added++
                index[s.uuid] = s.stamp
                batch += r
                if (batch.size >= BATCH) flush()
            }
            flush()
            // Liste complète seulement : ce qui n'y est plus a été retiré de Mealie.
            if (listed.size >= total) {
                val keep = listed.keys.mapTo(HashSet()) { MealieRecipes.toqueId(it) }
                removed = present.count { it !in keep }
                if (removed > 0) store.removeMealieExcept(keep)
                store.listed(keep)
                index.keys.retainAll(listed.keys)
                store.index = HashMap(index)
            }
        } catch (e: Stopped) {
            return Result(false, added, updated, removed, tr("Synchro arrêtée"))
        } catch (e: MealieClient.MealieException) {
            return Result(false, added, updated, removed, e.message.orEmpty())
        } catch (e: IOException) {
            return Result(false, added, updated, removed, tr("Serveur Mealie injoignable"))
        }
        val parts = ArrayList<String>()
        if (added > 0) parts += tr("%d ajoutée(s)", added)
        if (updated > 0) parts += tr("%d mise(s) à jour", updated)
        if (removed > 0) parts += tr("%d retirée(s)", removed)
        if (skipped > 0) parts += tr("%d illisible(s)", skipped)
        return Result(true, added, updated, removed, if (parts.isEmpty()) tr("Déjà à jour") else parts.joinToString(", "))
    }

    companion object {
        private const val BATCH = 20
        /** 50 pages de 100 : 5 000 recettes au plus. */
        private const val MAX_PAGES = 50
    }
}
