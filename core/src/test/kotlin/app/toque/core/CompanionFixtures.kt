package app.toque.core

import app.toque.core.companion.AdjustCommand
import app.toque.core.companion.CatalogPageWire
import app.toque.core.companion.CatalogQuery
import app.toque.core.companion.CatalogSearch
import app.toque.core.companion.CommandResult
import app.toque.core.companion.CompanionApi
import app.toque.core.companion.CompanionClock
import app.toque.core.companion.CompanionHost
import app.toque.core.companion.CompanionHub
import app.toque.core.companion.HttpRequest
import app.toque.core.companion.HttpResponse
import app.toque.core.companion.ImageData
import app.toque.core.companion.MemoryStore
import app.toque.core.companion.StartCommand
import app.toque.core.companion.ServingsChange
import app.toque.core.companion.ServingsReply
import app.toque.core.json.Json
import app.toque.core.machine.JobState
import app.toque.core.machine.LinkState
import app.toque.core.machine.MachineController
import app.toque.core.machine.Snapshot
import app.toque.core.protocol.Direction
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.SearchIndex
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.core.recipes.Weigh
import app.toque.core.recipes.forServings
import app.toque.core.recipes.RecipeAdjustments
import app.toque.core.recipes.corrected
import java.util.Random

class FakeCompanionClock(var wall: Long = 1_795_000_000_000L, var mono: Long = 5_000_000L) : CompanionClock {
    override fun wallMs() = wall
    override fun monoMs() = mono
    fun advance(ms: Long) {
        wall += ms
        mono += ms
    }
}

object CompanionData {
    val veloute = Recipe(
        id = "perso-veloute", title = "Velouté de potimarron", category = "Soupes", servings = 4, totalMinutes = 35,
        image = "v.png", favorite = true,
        ingredients = listOf(IngredientGroup(items = listOf(Ingredient("20", "g", "Beurre"), Ingredient("600", "g", "Potimarron")))),
        steps = listOf(
            Step("Couper le potimarron."),
            Step("Mettre le beurre.", weigh = Weigh("Beurre", 20)),
            Step("Faire revenir.", StepMachine(StepProgram.MANUAL, 180, 1, 100)),
            Step("Mixer.", StepMachine(StepProgram.MANUAL, 60, 8, 0)),
        ),
    )
    val recipes: List<Recipe> = listOf(veloute) + (1..24).map { i ->
        Recipe(id = "r$i", title = "Recette d'essai numéro $i", category = if (i % 2 == 0) "Soupes" else "Desserts",
            totalMinutes = 10 + i, favorite = i % 5 == 0, steps = listOf(Step("Mélanger.", StepMachine(StepProgram.MANUAL, 30, 2))))
    }
    val index = SearchIndex(recipes)
    val byId = recipes.associateBy { it.id }
}

/**
 * Hôte d'essai : enregistre chaque appel ; s'il a un contrôleur, il lui transmet les commandes
 * (comme MachineClient), sinon il répond [result].
 */
class FakeHost(var controller: MachineController? = null) : CompanionHost {
    val calls = ArrayList<String>()
    var result: CommandResult = CommandResult.OK
    /** Ce que la vérification de reprise a répondu au dernier appel. */
    var lastStillAllowed: Boolean? = null

    @Synchronized override fun pause(): CommandResult {
        calls += "pause"
        return controller?.let { CommandResult.of(it.pause()) } ?: result
    }

    @Synchronized override fun resume(stillAllowed: () -> Boolean): CommandResult {
        calls += "resume"
        val ok = stillAllowed()
        lastStillAllowed = ok
        if (!ok) return CommandResult.refuse("changed", "changed")
        return controller?.let { CommandResult.of(it.resume(false)) } ?: result
    }

    @Synchronized override fun stop(): CommandResult {
        calls += "stop"
        return controller?.let { CommandResult.of(it.stop()) } ?: result
    }

    @Synchronized override fun adjust(prepare: () -> AdjustCommand): CommandResult {
        val a = prepare()
        calls += "adjust:${a.speed}:${a.temperatureLevel}:${a.reverse}:${a.totalMs}"
        if (a.refusal != null) return a.refusal
        return controller?.let {
            CommandResult.of(it.adjust(a.speed, a.temperatureLevel,
                a.reverse?.let { r -> if (r) Direction.COUNTER_CLOCKWISE else Direction.CLOCKWISE }, a.totalMs, false))
        } ?: result
    }

    /** Juste avant la revérification (fil de l'appli) : de quoi changer l'état entre la requête et l'action. */
    var beforeStart: () -> Unit = {}
    /** Après un lancement accepté (la machine publierait son instantané). */
    var afterStart: () -> Unit = {}
    var lastStart: StartCommand? = null

    @Synchronized override fun start(prepare: () -> StartCommand): CommandResult {
        beforeStart()
        val c = prepare()
        lastStart = c
        calls += "start"
        if (c.refusal != null) return c.refusal
        val r = controller?.let { CommandResult.of(it.startJob(c.spec!!)) } ?: result
        if (r.ok) afterStart()
        return r
    }

    /** Après un « OK » accepté (la machine publierait l'instantané sans le programme). */
    var afterClear: () -> Unit = {}

    @Synchronized override fun clear(): CommandResult {
        calls += "clear"
        controller?.clearFinished()
        val r = if (controller != null) CommandResult.OK else result
        if (r.ok) afterClear()
        return r
    }

    @Synchronized override fun tare(): CommandResult {
        calls += "tare"
        controller?.tare()
        return CommandResult.OK
    }

    override fun catalog(query: CatalogQuery): CatalogPageWire =
        CatalogSearch.page(CompanionData.index, query, listOf("r3", "perso-veloute", "r1"), listOf("r2", "r3"))

    /** Nombre de personnes choisi par recette (comme RecipeAdjustments.servings sur le robot). */
    val servings = HashMap<String, Int>()

    /** Corrections de l'utilisateur par recette (RecipeAdjustments sur le robot, sans le nombre de personnes). */
    val corrections = HashMap<String, RecipeAdjustments>()

    override fun baseRecipe(id: String): Recipe? = CompanionData.byId[id]?.let { r -> corrections[id]?.let { r.corrected(it) } ?: r }

    override fun recipe(id: String): Recipe? = baseRecipe(id)?.let { r -> servings[id]?.let { r.forServings(it) } ?: r }

    override fun baseServings(recipeId: String): Int = CompanionData.byId[recipeId]?.servings ?: 0

    @Synchronized override fun setServings(recipeId: String, people: Int, stillAllowed: () -> Boolean): ServingsReply {
        calls += "servings:$recipeId:$people"
        if (!stillAllowed()) return ServingsReply(refusal = ServingsChange.cooking())
        val r = CompanionData.byId[recipeId]
        ServingsChange.check(r, people)?.let { return ServingsReply(refusal = it) }
        if (people == r!!.servings) servings.remove(recipeId) else servings[recipeId] = people
        return ServingsReply(recipe(recipeId), r.servings)
    }

    override fun image(recipeId: String): ImageData? =
        if (recipeId == "perso-veloute") ImageData(byteArrayOf(1, 2, 3), "image/png") else null
}

/** Hub d'essai avec horloge et hasard fixés, et de quoi faire des requêtes sans socket. */
class HubFixture(val host: FakeHost = FakeHost(), val clock: FakeCompanionClock = FakeCompanionClock()) {
    val store = MemoryStore()
    val hub = CompanionHub(host, clock, store, Random(42))
    val api = CompanionApi(hub)

    /** Appaire un téléphone et renvoie son jeton. */
    fun pair(): String {
        val o = hub.pairing.open()
        val r = hub.pairing.pairWithCode(o.code)
        return r.token!!
    }

    fun req(method: String, path: String, token: String? = null, body: Any? = null, query: Map<String, String> = emptyMap()): HttpResponse {
        val headers = LinkedHashMap<String, String>()
        headers["host"] = "192.168.0.12:8088"
        if (token != null) headers["authorization"] = "Bearer $token"
        val bytes = if (body == null) ByteArray(0) else Json.write(body).toByteArray()
        if (body != null) headers["content-type"] = "application/json"
        return api.handle(HttpRequest(method, path, query, headers, bytes, "192.168.0.20"))
    }

    fun json(r: HttpResponse): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return Json.parse(String(r.body, Charsets.UTF_8)) as Map<String, Any?>
    }
}

/** Instantanés synthétiques (la règle de reprise ne lit que le Snapshot). */
object Snaps {
    fun idle() = Snapshot(link = LinkState.READY, hasSample = true, actualTempC = 22, bowlDetected = true)
    fun running(kind: String = "MANUAL", speed: Int = 2, tempC: Int = 22, phases: Int = 1) = idle().copy(
        actualTempC = tempC, jobActive = true, jobKind = kind, jobLabel = "Cuisson manuelle", jobState = JobState.RUNNING.name,
        phaseCount = phases, totalMs = 480_000, remainingMs = 400_000, elapsedMs = 80_000, jobSpeed = speed,
    )
    fun paused(reason: String, kind: String = "MANUAL") = running(kind).copy(jobState = JobState.PAUSED.name, pauseReason = reason)
}
