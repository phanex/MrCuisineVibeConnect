package app.toque.core.companion.demo

import app.toque.core.companion.AdjustCommand
import app.toque.core.companion.CatalogPageWire
import app.toque.core.companion.CatalogQuery
import app.toque.core.companion.CatalogSearch
import app.toque.core.companion.CommandResult
import app.toque.core.companion.CompanionHost
import app.toque.core.companion.CompanionHub
import app.toque.core.companion.CompanionStore
import app.toque.core.companion.CookSession
import app.toque.core.companion.HttpHandler
import app.toque.core.companion.HttpRequest
import app.toque.core.companion.HttpResponse
import app.toque.core.companion.HttpServer
import app.toque.core.companion.ImageData
import app.toque.core.companion.ServingsReply
import app.toque.core.companion.ServingsChange
import app.toque.core.companion.RobotInfo
import app.toque.core.companion.StartCommand
import app.toque.core.companion.WaitingEnd
import app.toque.core.companion.WaitingFor
import app.toque.core.i18n.I18n
import app.toque.core.machine.Clock
import app.toque.core.machine.ControllerConfig
import app.toque.core.machine.JobState
import app.toque.core.machine.MachineController
import app.toque.core.machine.MachineLoop
import app.toque.core.machine.Programs
import app.toque.core.machine.SafetyPolicy
import app.toque.core.machine.SimBoard
import app.toque.core.machine.SimTransport
import app.toque.core.machine.Snapshot
import app.toque.core.protocol.Direction
import app.toque.core.protocol.Mcc
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.forServings
import app.toque.core.recipes.SearchIndex
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress

/**
 * Serveur de démonstration du compagnon, sur la JVM : le vrai MachineController sur une base simulée,
 * un catalogue inventé, une recette guidée simulée. Ne fait pas partie de l'APK.
 *
 *     gradlew :core:companionDemo [-PdemoArgs="--port 8088 --fast 5 --lan"]
 *
 * Le rôle du robot se joue au clavier (tapez « help ») ou par POST http://127.0.0.1:<port+1>/demo
 * avec la commande dans le corps (texte brut), depuis la machine locale seulement.
 */
fun main(args: Array<String>) {
    var port = CompanionHub.DEFAULT_PORT
    var lan = false
    var fast = 1.0
    var store = File("build/companion-demo/store.json")
    var lang = "fr"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> port = args[++i].toInt()
            "--lan" -> lan = true
            "--fast" -> fast = args[++i].toDouble().coerceIn(1.0, 20.0)
            "--store" -> store = File(args[++i])
            "--lang" -> lang = args[++i]
        }
        i++
    }
    loadLanguages(lang)
    val demo = Demo(port, lan, fast, FileStore(store))
    demo.start()
    val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
    while (true) {
        val line = try {
            reader.readLine()
        } catch (_: Exception) {
            null
        }
        if (line == null) {
            // Sans console (lancé en arrière-plan) : on reste en service jusqu'à « quit » par /demo.
            while (demo.alive) Thread.sleep(500)
            break
        }
        if (line.trim() == "quit") break
        println(demo.command(line))
    }
    demo.shutdown()
    System.exit(0)
}

private fun loadLanguages(lang: String) {
    val dir = listOf(File("../app/src/main/assets/lang"), File("app/src/main/assets/lang")).firstOrNull { it.isDirectory }
    if (dir != null) {
        I18n.install(dir.listFiles()!!.filter { it.name.endsWith(".json") }
            .map { I18n.parse(it.name.removeSuffix(".json"), it.readText()) })
    }
    I18n.select(lang)
}

class FileStore(private val file: File) : CompanionStore {
    override fun load(): String? = if (file.isFile) file.readText() else null
    override fun save(text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }
}

/** Horloge accélérée pour la machine simulée (cuissons plus courtes). */
class FastClock(private val factor: Double) : Clock {
    private val start = System.nanoTime()
    override fun nowMs(): Long = ((System.nanoTime() - start) / 1_000_000.0 * factor).toLong() + 1_000_000L
}

class Demo(private val port: Int, private val lan: Boolean, fast: Double, private val store: CompanionStore) {
    private val clock = FastClock(fast)
    val board = SimBoard(clock)
    private val config = ControllerConfig(
        freshMs = (800 * fast).toLong(), staleMs = (1_500 * fast).toLong(), maxTickGapMs = (500 * fast).toLong(),
    )
    @Volatile private var lastSnap = Snapshot()
    private val index = SearchIndex(DemoCatalog.recipes)
    @Volatile var alive = true
        private set

    private lateinit var loop: MachineLoop
    lateinit var hub: CompanionHub
        private set
    private val cook = DemoCook(this)
    private var controls: HttpServer? = null

    val host = object : CompanionHost {
        override fun pause() = CommandResult.of(loop.call { it.pause() })
        override fun stop() = CommandResult.of(loop.call { it.stop() })
        override fun resume(stillAllowed: () -> Boolean): CommandResult = loop.call { c ->
            if (!stillAllowed()) CommandResult.refuse("changed", "État changé : reprenez sur le robot")
            else CommandResult.of(c.resume(false))
        }
        override fun adjust(prepare: () -> AdjustCommand): CommandResult = loop.call { c ->
            val a = prepare()
            a.refusal ?: CommandResult.of(c.adjust(a.speed, a.temperatureLevel,
                a.reverse?.let { if (it) Direction.COUNTER_CLOCKWISE else Direction.CLOCKWISE }, a.totalMs, false))
        }
        override fun tare(): CommandResult {
            loop.post { it.tare() }
            return CommandResult.OK
        }
        override fun clear(): CommandResult {
            loop.call { it.clearFinished() }
            return CommandResult.OK
        }
        override fun start(prepare: () -> StartCommand): CommandResult = loop.call { c ->
            val a = prepare()
            val spec = a.spec
            a.refusal ?: if (spec == null) CommandResult.refuse("refused", "refusé") else CommandResult.of(c.startJob(spec))
                .also { if (it.ok) println("[robot] Cuisson lancée depuis ${a.phoneName}") }
        }
        override fun catalog(query: CatalogQuery): CatalogPageWire =
            CatalogSearch.page(index, query, DemoCatalog.recent, DemoCatalog.frequent)
        override fun recipe(id: String): Recipe? = DemoCatalog.byId[id]?.let { r -> servings[id]?.let { r.forServings(it) } ?: r }
            ?.let { r -> favorites[id]?.let { r.copy(favorite = it) } ?: r }
        override fun image(recipeId: String): ImageData? = DemoCatalog.image(recipeId)?.let { ImageData(it, "image/png") }
        override fun baseServings(recipeId: String): Int = DemoCatalog.byId[recipeId]?.servings ?: 0
        // Pas de corrections dans la démo : la recette du catalogue.
        override fun baseRecipe(id: String): Recipe? = DemoCatalog.byId[id]
        // Nombre de personnes : gardé en mémoire, comme les adaptations du robot.
        override fun setServings(recipeId: String, people: Int, stillAllowed: () -> Boolean): ServingsReply {
            if (!stillAllowed()) return ServingsReply(refusal = ServingsChange.cooking())
            val r = DemoCatalog.byId[recipeId]
            ServingsChange.check(r, people)?.let { return ServingsReply(refusal = it) }
            if (people == r!!.servings) servings.remove(recipeId) else servings[recipeId] = people
            return ServingsReply(recipe(recipeId), r.servings)
        }
        // Favoris : gardés en mémoire, la liste et les fiches suivent (comme RecipeRepo.setFavorite).
        override val setsFavorites get() = true
        override fun setFavorite(recipeId: String, favorite: Boolean): CommandResult {
            if (DemoCatalog.byId[recipeId] == null) return CommandResult.refuse("not_found", "Recette introuvable")
            favorites[recipeId] = favorite
            index.entry(recipeId)?.favorite = favorite
            println("[robot] favori ${if (favorite) "ajouté" else "retiré"} : $recipeId")
            return CommandResult.OK
        }
        // « Arrêter la recette » depuis un téléphone : comme « abandon ».
        override val stopsRecipes get() = true
        override fun stopRecipe(): CommandResult =
            if (cook.abandon() == "aucune recette") CommandResult.refuse("no_recipe", "Aucune recette en cours sur le robot") else CommandResult.OK
    }
    private val servings = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val favorites = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun start() {
        hub = CompanionHub(host, store = store, log = { println("[hub] $it") })
        hub.updateRobot(RobotInfo("Monsieur Cuisine Connect (démo)", "0.7.0-demo", I18n.code))
        hub.setRemoteStart(true)
        loop = MachineLoop({ SimTransport(board) }, clock, config,
            onSnapshot = { s ->
                lastSnap = s
                hub.updateMachine(s)
                cook.offer(s)
            },
            onEvent = { e ->
                hub.onMachineEvent(e)
                println("[machine] ${e.type} ${e.message}")
            })
        loop.start()
        val bind = if (lan) null else InetAddress.getByName("127.0.0.1")
        hub.start(port, bind)
        controls = HttpServer(object : HttpHandler {
            override fun handle(request: HttpRequest): HttpResponse {
                if (request.path != "/demo" || request.method != "POST") return HttpResponse.text(404, "POST /demo\n")
                return HttpResponse.text(200, command(request.bodyText) + "\n")
            }
        }, port + 1, InetAddress.getByName("127.0.0.1")).also { it.start() }
        println("Toque Compagnon (démo) : API http://${if (lan) "0.0.0.0" else "127.0.0.1"}:${hub.port}/api/hello")
        println("Commandes du robot : tapez « help », ou POST http://127.0.0.1:${port + 1}/demo")
    }

    fun shutdown() {
        alive = false
        controls?.stop()
        hub.stop()
        loop.shutdown()
    }

    fun call(block: (MachineController) -> app.toque.core.machine.Verdict) = loop.call(block)
    fun post(block: (MachineController) -> Unit) = loop.post(block)
    val snapshot: Snapshot get() = lastSnap

    private fun address(): String = if (lan) (try {
        InetAddress.getLocalHost().hostAddress
    } catch (_: Exception) {
        "127.0.0.1"
    }) else "127.0.0.1"

    @Synchronized
    fun command(line: String): String {
        val w = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (w.isEmpty()) return ""
        return try {
            when (w[0]) {
                "help" -> HELP
                "pair" -> {
                    val o = hub.pairing.open()
                    "code ${o.code} (valable ${hub.pairing.validityMs / 1000} s)\nQR : ${hub.pairingPayload(address())}"
                }
                "close-pairing" -> { hub.pairing.close(); "appairage fermé" }
                "phones" -> hub.pairing.list.joinToString("\n") { "${it.name} id=${it.id} ${it.method}" }.ifEmpty { "aucun téléphone" }
                "unpair" -> {
                    val target = w.getOrNull(1) ?: return "unpair <numéro>|all"
                    val list = hub.pairing.list.filter { target == "all" || it.number.toString() == target }
                    for (p in list) hub.pairing.remove(p.id)
                    "${list.size} téléphone(s) retiré(s)"
                }
                "launch" -> cook.launch()
                "next" -> cook.next(false)
                "confirm" -> cook.next(true)
                "finish" -> cook.finish()
                "abandon" -> cook.abandon()
                "remove" -> { hub.clearWaiting(WaitingEnd.REMOVED); "recette en attente retirée" }
                "lid" -> {
                    board.lidOpen = w.getOrNull(1) != "close"
                    if (!board.lidOpen) board.bowlPresent = true
                    "couvercle ${if (board.lidOpen) "ouvert" else "fermé"}"
                }
                "bowl" -> {
                    val out = w.getOrNull(1) != "in"
                    board.bowlPresent = !out
                    if (out) board.lidOpen = true
                    if (out) "bol retiré (couvercle ouvert)" else "bol remis (fermez le couvercle : lid close)"
                }
                "weight" -> { board.contentWeightG = w.getOrNull(1)?.toIntOrNull() ?: 0; "poids ${board.contentWeightG} g" }
                "tare" -> { loop.post { it.tare() }; "tare" }
                "manual" -> {
                    val speed = w.getOrNull(1)?.toIntOrNull() ?: 2
                    val temp = w.getOrNull(2)?.toIntOrNull() ?: 0
                    val secs = w.getOrNull(3)?.toIntOrNull() ?: 480
                    val level = if (temp == 0) 0 else Mcc.levelAtMost(temp)
                    verdict(loop.call { it.startJob(Programs.manual(speed, level, Direction.CLOCKWISE, secs * 1000L)) })
                }
                "program" -> {
                    val spec = when (w.getOrNull(1)) {
                        "knead" -> Programs.knead()
                        "steam" -> Programs.steam()
                        "toast" -> Programs.toast()
                        "clean" -> Programs.clean()
                        else -> return "program knead|steam|toast|clean"
                    }
                    verdict(loop.call { it.startJob(spec) })
                }
                "pause" -> verdict(loop.call { it.pause() })
                "resume" -> verdict(loop.call { it.resume(false) })
                "stop" -> verdict(loop.call { it.stop() })
                "clear" -> { loop.post { it.clearFinished() }; "programme terminé effacé" }
                "phone-start" -> {
                    hub.setRemoteStart(w.getOrNull(1) != "off")
                    "lancement depuis le téléphone : ${if (hub.remoteStartEnabled) "oui" else "non"}"
                }
                "status" -> status()
                "quit" -> { alive = false; "au revoir" }
                else -> "commande inconnue : ${w[0]} (help)"
            }
        } catch (e: Exception) {
            "erreur : $e"
        }
    }

    fun verdict(v: app.toque.core.machine.Verdict) = if (v.ok) "ok" else "refusé : ${v.message}"

    private fun status(): String {
        val s = lastSnap
        return "lien=${s.link} couvercle=${if (s.lidOpen) "ouvert" else "fermé"} bol=${if (s.bowlRemoved) "retiré" else "en place"} " +
            "temp=${s.actualTempC}°C poids=${s.weightG} g programme=${s.jobLabel.ifEmpty { "-" }} ${s.jobState} ${s.pauseReason} " +
            "reste=${SafetyPolicy.formatDuration(s.remainingMs)} révision=${hub.currentRevision} " +
            "en attente=${hub.waitingRecipe?.title ?: "-"} recette=${cook.describe()}"
    }

    companion object {
        val HELP = """
            Rôle du robot :
              pair                 ouvrir l'appairage : affiche le code et le texte du QR code
              close-pairing        fermer l'appairage
              phones / unpair N|all  téléphones appairés
              launch               lancer la recette reçue (ou le velouté s'il n'y en a pas)
              next                 étape suivante : pesée relevée, étape à la main faite, étape machine lancée
              confirm              lancer l'étape en confirmant le mixage à chaud
              finish               finir tout de suite l'étape machine en cours
              abandon              quitter la recette
              remove               retirer la recette en attente
              lid open|close       couvercle ; bowl out|in : bol
              weight G             poser G grammes sur la balance ; tare
              manual V T S         cuisson manuelle vitesse V, T °C, S secondes
              program knead|steam|toast|clean
              pause / resume / stop / clear   comme sur l'écran du robot
              phone-start on|off   réglage « Lancement depuis le téléphone »
              status               état ; quit : quitter
        """.trimIndent()
    }
}

/** Recette guidée simulée (ce que fait CookScreen sur le robot). */
class DemoCook(private val demo: Demo) {
    private var recipe: Recipe? = null
    private var current = 0
    private val done = HashSet<Int>()
    private var running = emptyList<Int>()
    private val weighed = HashMap<Int, Int>()
    private val hotBlend = HashSet<Int>()
    private var finishing = false
    private var lastState = ""

    @Synchronized
    fun describe(): String = recipe?.let { "${it.title} étape ${current + 1}/${it.steps.size} faites=${done.sorted().map { i -> i + 1 }}" } ?: "-"

    @Synchronized
    fun launch(): String {
        val w = demo.hub.waitingRecipe
        val r = demo.host.recipe(w?.recipeId ?: DemoCatalog.VELOUTE_ID) ?: return "recette introuvable"
        if (w != null) demo.hub.clearWaiting(WaitingEnd.LAUNCHED)
        recipe = r
        current = 0
        done.clear(); weighed.clear(); hotBlend.clear()
        running = emptyList()
        push()
        return "recette lancée : ${r.title}"
    }

    @Synchronized
    fun next(hot: Boolean): String {
        val r = recipe ?: return "aucune recette (launch)"
        if (running.isNotEmpty()) return "une étape tourne (finish, ou attendez)"
        val step = r.steps[current]
        if (step.weigh != null && current !in weighed) {
            weighed[current] = demo.snapshot.weightG
            if (step.machine == null) return advance()
            push()
            return "pesée relevée : ${weighed[current]} g"
        }
        val m = step.machine
        if (m != null && current !in done) {
            val spec = m.toJob(r.title, hotBlendConfirmed = hot) ?: return advance()
            val v = demo.call { it.startJob(spec) }
            if (!v.ok) {
                if (v.has(SafetyPolicy.HOT_BLEND)) hotBlend += current
                push()
                return demo.verdict(v)
            }
            hotBlend -= current
            running = listOf(current)
            push()
            return "étape ${current + 1} lancée"
        }
        return advance()
    }

    private fun advance(): String {
        val r = recipe!!
        done += current
        if (current >= r.steps.size - 1) {
            demo.hub.endSession(true)
            recipe = null
            return "recette terminée"
        }
        current++
        push()
        return "étape ${current + 1}"
    }

    @Synchronized
    fun finish(): String {
        if (running.isEmpty()) return "aucune étape en cours"
        finishing = true
        demo.post { it.stop() }
        return "étape finie"
    }

    @Synchronized
    fun abandon(): String {
        if (recipe == null) return "aucune recette"
        if (running.isNotEmpty()) demo.post { it.stop() }
        recipe = null
        running = emptyList()
        demo.hub.endSession(false)
        return "recette abandonnée"
    }

    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "demo-cook").apply { isDaemon = true } }
    @Volatile private var offeredKey = ""

    /** Depuis le fil machine : ne bloque jamais (traitement sur un fil à part). */
    fun offer(s: Snapshot) {
        val key = s.jobState + "|" + s.pauseReason + "|" + s.jobActive
        if (key == offeredKey) return
        offeredKey = key
        exec.execute { onSnapshot(s) }
    }

    @Synchronized
    private fun onSnapshot(s: Snapshot) {
        val r = recipe ?: return
        val key = s.jobState + s.pauseReason
        if (key == lastState) return
        lastState = key
        if (running.isNotEmpty() && (s.state == JobState.FINISHED || (s.state == JobState.ABORTED && finishing))) {
            done += running
            running = emptyList()
            finishing = false
            demo.post { it.clearFinished() }
            if (current < r.steps.size - 1) current++
            push()
        } else if (running.isNotEmpty() && (s.state == JobState.ABORTED || !s.jobActive)) {
            running = emptyList()
            demo.post { it.clearFinished() }
            push()
        } else push()
    }

    private fun push() {
        val r = recipe ?: return
        val s = demo.snapshot
        val step = r.steps[current]
        val waitingFor = when {
            running.isNotEmpty() -> if (s.state == JobState.PAUSED) WaitingFor.RESUME else WaitingFor.NOTHING
            current == r.steps.size - 1 && current in done -> WaitingFor.FINISH
            step.weigh != null && current !in weighed -> WaitingFor.WEIGH
            step.machine != null && current !in done -> if (current in hotBlend) WaitingFor.HOT_BLEND else WaitingFor.LAUNCH
            else -> WaitingFor.HAND
        }
        demo.hub.updateSession(CookSession.of(r, current, HashSet(done), running, waitingFor, HashMap(weighed), HashSet(hotBlend)))
    }
}
