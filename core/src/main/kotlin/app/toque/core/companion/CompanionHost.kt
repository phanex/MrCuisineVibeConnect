package app.toque.core.companion

import app.toque.core.i18n.tr
import app.toque.core.machine.JobSpec
import app.toque.core.machine.Verdict
import app.toque.core.recipes.Portions
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeChip
import app.toque.core.recipes.SearchIndex
import app.toque.core.recipes.Step
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Résultat d'une commande transmise à la machine. [code] reprend celui du refus (Violation). */
class CommandResult(val ok: Boolean, val code: String = "", val message: String = "") {
    companion object {
        val OK = CommandResult(true)
        fun of(v: Verdict): CommandResult =
            if (v.ok) OK else CommandResult(false, v.violations.firstOrNull()?.code ?: "refused", v.message)
        fun refuse(code: String, message: String) = CommandResult(false, code, message)
        /** Rien n'a été fait : l'appli n'a pas pris la commande à temps (elle ne s'exécutera pas plus tard). */
        fun timeout() = CommandResult(false, "timeout", tr("Le robot ne répond pas"))
        /** La commande a été transmise mais sa réponse n'est pas arrivée : elle a peut-être été appliquée. */
        fun noAnswer() = CommandResult(false, "no_answer", tr("Le robot n'a pas confirmé la commande"))
    }
}

/** Ce que la page affiche du robot. */
data class RobotInfo(
    val name: String = "Monsieur Cuisine Connect",
    val appVersion: String = "",
    /** Code de la langue de l'interface du robot (« fr », « en »…). */
    val language: String = "fr",
    /**
     * Couleur choisie sur le robot (Réglages › Apparence), « #RRGGBB » ; vide : inconnue. Le téléphone
     * la reprend pour ressembler au robot.
     */
    val accent: String = "",
    /** Thème du robot, « dark » ou « light » ; vide : inconnu. */
    val theme: String = "",
)

enum class CatalogFilter(val key: String) {
    ALL("all"), RECENT("recent"), FREQUENT("frequent"), FAVORITES("favorites"),
    /** Les pastilles du robot (RecipeChip) : « Mes recettes », « Communauté » (toqueapp.org), « 30 min max », une catégorie. */
    USER("user"), COMMUNITY("community"), QUICK("quick"), CATEGORY("category");

    companion object {
        fun of(key: String?): CatalogFilter? = if (key.isNullOrEmpty()) ALL else values().firstOrNull { it.key == key }
        /** Filtre de la pastille [chip] (mêmes clés). */
        fun of(chip: RecipeChip): CatalogFilter = values().first { it.key == chip.kind.key }
    }
}

/** [category] : le nom de la catégorie pour [CatalogFilter.CATEGORY], vide sinon. */
data class CatalogQuery(val text: String = "", val filter: CatalogFilter = CatalogFilter.ALL, val offset: Int = 0, val limit: Int = 20,
                        val category: String = "") {
    /** La pastille du robot qui correspond. */
    val chip: RecipeChip get() =
        if (filter == CatalogFilter.CATEGORY) RecipeChip.category(category) else RecipeChip(RecipeChip.Kind.of(filter.key)!!)
}

/** Image d'une recette : JPEG, PNG ou WebP. */
class ImageData(val bytes: ByteArray, val contentType: String)

/**
 * Réglage à appliquer, calculé par le compagnon sur le fil de l'appli juste avant d'agir.
 * [refusal] non nul : ne rien faire et le renvoyer tel quel. Sinon MachineClient.adjust(speed,
 * temperatureLevel, reverse, totalMs, hot = false) ; null = inchangé.
 */
class AdjustCommand(
    val speed: Int? = null,
    val temperatureLevel: Int? = null,
    val reverse: Boolean? = null,
    val totalMs: Long? = null,
    val refusal: CommandResult? = null,
)

/**
 * Cuisson manuelle à lancer, calculée par le compagnon sur le fil de l'appli juste avant d'agir.
 * [refusal] non nul : ne rien faire et le renvoyer tel quel. Sinon MachineClient.start([spec]) ;
 * [phoneName] : le téléphone qui la lance (message affiché sur le robot).
 */
class StartCommand(
    val spec: JobSpec? = null,
    val phoneName: String = "",
    val refusal: CommandResult? = null,
)

/**
 * Ce que l'appli fournit au compagnon. Les méthodes sont appelées depuis les fils du serveur et
 * doivent rendre la main en moins de [CompanionHub.HOST_TIMEOUT_MS].
 *
 * Seules les commandes (pause, resume, stop, adjust, tare, start, clear, setServings, setFavorite, stopRecipe) passent par le fil principal
 * d'Android (voir [HostCalls.command]). catalog, recipe et image s'exécutent sur le fil du serveur,
 * HORS du fil principal (lecture de la base de recettes, des fichiers d'images).
 *
 * Une seule lance quelque chose : [start], une cuisson manuelle, sous la règle du compagnon
 * (interrupteur du robot, couvercle fermé, bol en place, rien en cours, jamais de mixage à chaud).
 * Aucune ne peut lancer une recette, une étape, un programme, le Turbo ou confirmer un mixage à
 * chaud : ces commandes n'existent pas ici, exprès.
 */
interface CompanionHost {
    /** MachineClient.pause. */
    fun pause(): CommandResult

    /**
     * MachineClient.resume(hot = false). [stillAllowed] refait, sur le fil de l'appelant, la vérification
     * de la règle de reprise juste avant d'agir : l'appeler sur le fil principal, refuser s'il répond faux.
     */
    fun resume(stillAllowed: () -> Boolean): CommandResult

    /** MachineClient.stop. */
    fun stop(): CommandResult

    /**
     * Sur le fil principal : appeler [prepare] (le compagnon revérifie la règle, le programme et calcule
     * la durée à partir de l'état le plus frais), puis, sans refus, MachineClient.adjust(c.speed,
     * c.temperatureLevel, c.reverse, c.totalMs, hot = false). Jamais de confirmation à chaud.
     */
    fun adjust(prepare: () -> AdjustCommand): CommandResult

    /** MachineClient.tare. */
    fun tare(): CommandResult

    /**
     * Sur le fil principal : appeler [prepare] (le compagnon revérifie toute la règle contre l'état le
     * plus frais), puis, sans refus, MachineClient.start(c.spec) et rendre CommandResult.of(verdict) :
     * tout refus de la machine (mixage à chaud compris) en est un, rien ne démarre. Jamais de
     * confirmation à chaud. Par défaut : refusé (hôte sans lancement).
     */
    fun start(prepare: () -> StartCommand): CommandResult =
        CommandResult.refuse("unsupported", tr("Lancement depuis le téléphone indisponible"))

    /**
     * Sur le fil principal : MachineClient.clearFinished, comme le bouton OK du robot (retire de
     * l'affichage un programme terminé ou arrêté, rien d'autre). Par défaut : refusé (hôte plus ancien).
     */
    fun clear(): CommandResult =
        CommandResult.refuse("unsupported", tr("Le robot ne peut pas fermer le programme terminé"))

    fun catalog(query: CatalogQuery): CatalogPageWire

    /** Recette telle qu'elle s'afficherait sur le robot (quantités adaptées comprises), ou null. */
    fun recipe(id: String): Recipe?

    fun image(recipeId: String): ImageData?

    /**
     * La recette avec les corrections de l'utilisateur mais à son propre nombre de personnes
     * (RecipeAdjustments.servings ignoré, rien d'enregistré), ou null (GET /api/recipes/{id}?base=1).
     * Par défaut, [recipe] : le téléphone reconnaît alors à `servings` ≠ `baseServings` qu'il ne l'a pas.
     */
    fun baseRecipe(id: String): Recipe? = recipe(id)

    /**
     * Nombre de personnes de la recette [recipeId] elle-même, avant le choix enregistré sur le robot ;
     * 0 si elle n'en indique pas ou est inconnue (le téléphone n'affiche alors pas de réglage).
     */
    fun baseServings(recipeId: String): Int = 0

    /**
     * Sur le fil principal : si [stillAllowed] (la recette n'est pas en cours sur le robot), vérifier
     * avec [ServingsChange.check], enregistrer RecipeAdjustments.withServings comme la page de la
     * recette du robot, et rendre la recette adaptée. Sans réglage du nombre de personnes : refus.
     */
    fun setServings(recipeId: String, people: Int, stillAllowed: () -> Boolean): ServingsReply =
        ServingsReply(refusal = CommandResult.refuse("unsupported", tr("Le robot ne règle pas le nombre de personnes")))

    /** L'hôte sait enregistrer un favori ([setFavorite]) : `can.favorite` est alors permis. */
    val setsFavorites: Boolean get() = false

    /**
     * Sur le fil principal : comme l'étoile de la page de la recette du robot (RecipeRepo.setFavorite),
     * pour la recette [recipeId] ; refus « not_found » si elle n'existe pas. Par défaut : refusé.
     */
    fun setFavorite(recipeId: String, favorite: Boolean): CommandResult =
        CommandResult.refuse("unsupported", tr("Le robot ne change pas les favoris depuis le téléphone"))

    /** L'hôte sait arrêter la recette guidée ([stopRecipe]) : `can.stopRecipe` est alors permis pendant une recette. */
    val stopsRecipes: Boolean get() = false

    /**
     * Sur le fil principal : « Arrêter la recette » de l'écran de la recette guidée, sans sa
     * confirmation (faite sur le téléphone) : le programme lancé par la recette s'arrête, la session se
     * clôt ([CompanionHub.endSession] false) et l'écran se ferme. Refus « no_recipe » si aucune recette
     * guidée n'est ouverte. Par défaut : refusé.
     */
    fun stopRecipe(): CommandResult =
        CommandResult.refuse("unsupported", tr("Le robot n'arrête pas la recette depuis le téléphone"))

    /** L'hôte sait changer sa couleur et son thème depuis un téléphone ([setAppearance]) : `can.setAppearance`. */
    val setsAppearance: Boolean get() = false

    /**
     * L'hôte prend toute couleur qui passe la garde de la roue (ColourWheel.accepts), pas seulement les
     * dix de [RobotLooks.ACCENTS] : `robot.anyAccent`. Par défaut : non (seulement les dix).
     */
    val setsAnyAccent: Boolean get() = false

    /**
     * Sur le fil principal : appliquer [accent] (une de [RobotLooks.ACCENTS], ou toute couleur gardée si
     * [setsAnyAccent]) et/ou [theme] (« dark »,
     * « light ») comme les Réglages du robot, sans les ouvrir ; null : inchangé. Refus « busy » quand
     * reconstruire l'interface ferait perdre quelque chose (programme, recette, saisie, fenêtre ouverte).
     * Par défaut : refusé.
     */
    fun setAppearance(accent: String?, theme: String?): CommandResult =
        CommandResult.refuse("unsupported", tr("Le robot ne change pas son apparence depuis le téléphone"))

    /** L'hôte sait écrire les recettes perso venues du téléphone ([storedRecipe], [writeRecipe]) : `can.editRecipes`. */
    val writesRecipes: Boolean get() = false

    /**
     * La recette [id] telle qu'elle est enregistrée (ni adaptée au nombre de personnes, ni corrigée),
     * ou null ; lue sur le fil du serveur, comme [recipe].
     */
    fun storedRecipe(id: String): Recipe? = null

    /**
     * Sur le fil principal, d'un bloc : lire la recette [id] telle qu'enregistrée (null si absente),
     * savoir si elle est ouverte dans l'éditeur de recettes du robot, appeler [decide] avec les deux,
     * et si sa réponse porte une recette ([RecipeWrite.saved]), l'enregistrer comme l'éditeur du robot
     * (RecipeRepo.saveUser, nombre de personnes choisi remis à celui de la recette). Rendre la réponse.
     * Un bloc qui n'a pas pu commencer à temps n'est jamais exécuté (« timeout »). Par défaut : refusé.
     */
    fun writeRecipe(id: String, decide: (stored: Recipe?, inEditor: Boolean) -> RecipeWrite): RecipeWrite =
        RecipeWrite(error = EditError(409, "unsupported", tr("Le robot n'enregistre pas les recettes du téléphone")))

    /**
     * L'hôte prend aussi le réglage du robot d'une étape tapé sur le téléphone (lot B, avec [writesRecipes]) :
     * `settings` dans les réponses de l'éditeur. Il s'engage alors, dans le bloc de [writeRecipe] qui
     * enregistre, à oublier pour chaque rang de [RecipeWrite.forget] (réglage tapé ou changé, étape à réglage
     * venue d'un autre rang) toute vérification ou adaptation gardée qui la ferait passer pour déjà vérifiée
     * (CookScreen.forgetConfirmations sur le robot), et à ne jamais enchaîner à l'étape précédente un réglage
     * tapé sur un téléphone tant qu'il n'est pas vérifié (Step.reviewAlone). Par défaut : non (un réglage reçu
     * est refusé, 400 « machine », comme avant).
     */
    val writesMachineSteps: Boolean get() = false
}

/** Réponse de [CompanionHost.setServings] : la recette adaptée et son nombre de personnes d'origine, ou [refusal]. */
class ServingsReply(val recipe: Recipe? = null, val baseServings: Int = 0, val refusal: CommandResult? = null)

/** Règles d'un changement du nombre de personnes, les mêmes que sur la page de la recette du robot. */
object ServingsChange {
    /** Refus, ou null si [recipe] (telle qu'enregistrée, avant adaptation) peut passer à [people] personnes. */
    fun check(recipe: Recipe?, people: Int): CommandResult? {
        if (recipe == null) return CommandResult.refuse("not_found", tr("Recette introuvable"))
        if (recipe.servings <= 0) return CommandResult.refuse("no_servings", tr("Cette recette n'indique pas de nombre de personnes"))
        val range = Portions.range(recipe.servings)
        if (people !in range) return CommandResult.refuse("servings_range", tr("De %s à %s pour cette recette",
            Portions.label(range.first, recipe.servingUnit), Portions.label(range.last, recipe.servingUnit)))
        return null
    }

    /** Refus quand la recette est en cours sur le robot. */
    fun cooking() = CommandResult.refuse("cooking", tr("Recette en cours sur le robot"))
}

/**
 * Aides pour passer d'un fil du serveur au fil principal de l'appli et revenir, avec un délai maximal.
 * Un bloc qui n'a pas commencé quand le délai expire n'est jamais exécuté : « délai dépassé » veut
 * dire « rien n'a été fait ». Un bloc déjà commencé est attendu encore [timeoutMs] au plus.
 */
object HostCalls {
    /** Issue d'un appel : [value] si [done], sinon [skipped] (jamais exécuté) ou sans réponse (commencé). */
    class Outcome<T>(val done: Boolean, val value: T?, val skipped: Boolean)

    /** Exécute [block] via [post] (Handler.post) et attend son résultat ; null s'il n'a pas pu s'exécuter à temps. */
    fun <T> call(post: (Runnable) -> Unit, timeoutMs: Long, block: () -> T): T? = await(post, timeoutMs) { done -> done(block()) }

    /**
     * Pour les commandes à rappel (MachineClient.pause(cb)…) : [start] est exécuté via [post] et reçoit
     * de quoi rendre le résultat. Null si rien n'arrive à temps.
     */
    fun <T> await(post: (Runnable) -> Unit, timeoutMs: Long, start: (done: (T) -> Unit) -> Unit): T? =
        attempt(post, timeoutMs, start).value

    /**
     * Commande de la machine : le résultat, [CommandResult.timeout] si elle n'a pas pu commencer à temps
     * (elle ne s'exécutera pas), [CommandResult.noAnswer] si elle a commencé sans réponse.
     */
    fun command(post: (Runnable) -> Unit, timeoutMs: Long, start: (done: (CommandResult) -> Unit) -> Unit): CommandResult {
        val o = attempt(post, timeoutMs, start)
        return when {
            o.done -> o.value ?: CommandResult.noAnswer()
            o.skipped -> CommandResult.timeout()
            else -> CommandResult.noAnswer()
        }
    }

    fun <T> attempt(post: (Runnable) -> Unit, timeoutMs: Long, start: (done: (T) -> Unit) -> Unit): Outcome<T> {
        val latch = CountDownLatch(1)
        val out = AtomicReference<Any?>(NONE)
        // 0 : en attente, 1 : commencé, 2 : abandonné (ne doit plus commencer).
        val phase = AtomicInteger(0)
        post(Runnable {
            if (!phase.compareAndSet(0, 1)) return@Runnable
            try {
                start { v -> if (out.compareAndSet(NONE, v)) latch.countDown() }
            } catch (t: Throwable) {
                if (out.compareAndSet(NONE, t)) latch.countDown()
            }
        })
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            if (phase.compareAndSet(0, 2)) return Outcome(false, null, true)
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return Outcome(false, null, false)
        }
        val v = out.get()
        if (v is Throwable) throw v
        @Suppress("UNCHECKED_CAST")
        return Outcome(true, v as T, false)
    }

    private val NONE = Any()
}

/**
 * Recherche du catalogue pour le compagnon, partagée par l'appli et la démo : la même sélection
 * que la liste du robot ([RecipeChip.select]), sans sa limite de 500 recettes (le téléphone
 * charge page par page). La première page (offset 0) porte aussi les pastilles du robot.
 */
object CatalogSearch {
    fun page(
        index: SearchIndex,
        query: CatalogQuery,
        recentIds: List<String> = emptyList(),
        frequentIds: List<String> = emptyList(),
    ): CatalogPageWire {
        val chip = query.chip
        val list = RecipeChip.select(index, chip, query.text.trim(), { recentIds }, { frequentIds }, limit = Int.MAX_VALUE).map { it.entry }
        val from = query.offset.coerceIn(0, list.size)
        val items = list.subList(from, minOf(list.size, from + query.limit.coerceAtLeast(0))).map { e ->
            RecipeSummary(e.id, e.title, e.category, e.totalMinutes, e.favorite, e.source == app.toque.core.recipes.RecipeSource.USER,
                e.image.isNotBlank(), e.machineSteps, e.rating)
        }
        val filters = if (query.offset == 0) RecipeChip.list(index, chip).map { CatalogChipWire.of(it) } else null
        return CatalogPageWire(list.size, from, items, filters)
    }
}

/** Ce que le robot attend de la personne pendant une recette guidée. */
enum class WaitingFor(val key: String) {
    /** Un programme tourne : rien à faire. */
    NOTHING("nothing"),
    /** Étape de pesée : ajouter l'ingrédient sur la balance. */
    WEIGH("weigh"),
    /** Étape à la main : la faire puis toucher « Suivant » sur le robot. */
    HAND("hand"),
    /** Étape machine prête : la lancer sur le robot. */
    LAUNCH("launch"),
    /** Mixage à chaud à confirmer sur le robot. */
    HOT_BLEND("hot_blend"),
    /** En pause : reprendre. */
    RESUME("resume"),
    /** Dernière étape faite : terminer sur le robot. */
    FINISH("finish"),
}

/** Une étape de la recette suivie, avec ce qui en a été fait. */
data class SessionStep(
    val step: Step,
    /** Poids relevé à la fin de la pesée (g), ou null. */
    val weighedG: Int? = null,
    /** Contenu chaud et vitesse ≥ 4 : la confirmation se fait sur le robot. */
    val hotBlendOnRobot: Boolean = false,
)

/** Recette guidée en cours sur le robot (écran CookScreen), poussée par l'appli à chaque changement. */
data class CookSession(
    val recipeId: String,
    val title: String,
    val servings: Int,
    val steps: List<SessionStep>,
    val current: Int,
    val done: Set<Int>,
    /** Étapes couvertes par le programme lancé (plusieurs quand elles sont enchaînées) ; vide sinon. */
    val running: List<Int>,
    val waitingFor: WaitingFor,
) {
    companion object {
        fun of(recipe: Recipe, current: Int, done: Set<Int> = emptySet(), running: List<Int> = emptyList(),
               waitingFor: WaitingFor = WaitingFor.NOTHING, weighed: Map<Int, Int> = emptyMap(), hotBlend: Set<Int> = emptySet()) =
            CookSession(recipe.id, recipe.title, recipe.servings,
                recipe.steps.mapIndexed { i, s -> SessionStep(s, weighed[i], i in hotBlend) },
                current, done, running, waitingFor)
    }
}
