package app.toque.companion

import android.os.Handler
import app.toque.ToqueApp
import app.toque.core.companion.AdjustCommand
import app.toque.core.companion.CatalogPageWire
import app.toque.core.companion.CatalogQuery
import app.toque.core.companion.CatalogSearch
import app.toque.core.companion.CommandResult
import app.toque.core.companion.CompanionHost
import app.toque.core.companion.CompanionHub
import app.toque.core.companion.HostCalls
import app.toque.core.companion.EditError
import app.toque.core.companion.ImageData
import app.toque.core.companion.RecipeWrite
import app.toque.core.companion.StartCommand
import app.toque.core.companion.ServingsChange
import app.toque.core.companion.ServingsReply
import app.toque.core.i18n.tr
import app.toque.core.machine.Verdict
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.adjusted
import app.toque.core.recipes.corrected
import app.toque.core.robot.RecipeCompat
import app.toque.core.robot.RobotProfiles
import app.toque.ui.Ui
import app.toque.ui.screens.CookScreen
import app.toque.ui.screens.RecipeEditScreen

/**
 * Ce que le compagnon peut demander au robot, un pour un vers MachineClient : pause, reprise sans
 * confirmation de mixage à chaud, arrêt, réglage sans confirmation à chaud, tare, et le lancement
 * d'une cuisson manuelle sous la règle du compagnon (jamais de mixage à chaud). Rien d'autre ne démarre.
 *
 * Les commandes machine passent sur le fil principal (MachineClient n'est utilisable que là) et
 * rendent la main en [CompanionHub.HOST_TIMEOUT_MS] au plus. Le catalogue, la recette et l'image
 * sont lus sur le fil du serveur : l'index de recherche est un instantané immuable et la base
 * SQLite est sûre entre fils, ce qui évite de ralentir l'écran pendant une recherche.
 */
class RobotHost(
    private val app: ToqueApp,
    private val main: Handler,
    /** Cuisson lancée depuis un téléphone (nom du téléphone), sur le fil principal. */
    private val onRemoteStart: (phoneName: String) -> Unit = {},
    /**
     * « Arrêter la recette » demandé par un téléphone, sur le fil principal : l'écran de la recette
     * guidée l'arrête comme son propre bouton ; false s'il n'y en a pas d'ouverte.
     */
    private val stopGuidedRecipe: () -> Boolean = { false },
    /**
     * Couleur (null : inchangée) et thème (null : inchangé) demandés par un téléphone, sur le fil
     * principal : appliqués comme les Réglages, ou refusés si le robot n'est pas libre.
     */
    private val appearance: ((accent: Int?, light: Boolean?) -> CommandResult)? = null,
) : CompanionHost {
    private val post: (Runnable) -> Unit = { r -> main.post(r) }
    private val timeout = CompanionHub.HOST_TIMEOUT_MS

    /** Commande machine sur le fil principal ; jamais exécutée si elle n'a pas pu commencer à temps. */
    private fun command(start: (done: (CommandResult) -> Unit) -> Unit): CommandResult =
        HostCalls.command(post, timeout, start)

    private fun verdict(send: (cb: (Verdict) -> Unit) -> Unit): CommandResult =
        command { done -> send { v -> done(CommandResult.of(v)) } }

    override fun pause(): CommandResult = verdict { cb -> app.machine.pause(cb) }

    override fun resume(stillAllowed: () -> Boolean): CommandResult = command { done ->
        // Dernière vérification sur le fil principal, juste avant d'agir.
        if (!stillAllowed()) done(CommandResult.refuse("changed", tr("La situation a changé : reprenez sur le robot")))
        else app.machine.resume(hot = false) { v -> done(CommandResult.of(v)) }
    }

    override fun stop(): CommandResult = verdict { cb -> app.machine.stop(cb) }

    override fun adjust(prepare: () -> AdjustCommand): CommandResult = command { done ->
        // Le compagnon revérifie et calcule sur l'état le plus frais, juste avant d'agir.
        val c = prepare()
        val refusal = c.refusal
        if (refusal != null) done(refusal)
        else app.machine.adjust(speed = c.speed, temp = c.temperatureLevel, reverse = c.reverse, totalMs = c.totalMs, hot = false) { v ->
            done(CommandResult.of(v))
        }
    }

    override fun start(prepare: () -> StartCommand): CommandResult = command { done ->
        // Le compagnon revérifie toute la règle sur l'état le plus frais, juste avant d'agir.
        val c = prepare()
        val refusal = c.refusal
        val spec = c.spec
        if (refusal != null) done(refusal)
        else if (spec == null) done(CommandResult.refuse("refused", tr("Le robot a refusé la demande")))
        // Tout refus de la machine (mixage à chaud compris) en est un : aucune confirmation ici.
        else app.machine.start(spec) { v ->
            if (v.ok) onRemoteStart(c.phoneName)
            done(CommandResult.of(v))
        }
    }

    /** Le bouton OK du robot : retire le programme terminé ou arrêté de l'affichage, rien d'autre. */
    override fun clear(): CommandResult = verdict { cb -> app.machine.clearFinished(cb) }

    override fun tare(): CommandResult = command { done ->
        if (!app.machine.connected) done(CommandResult.refuse("ipc", tr("Service machine indisponible, nouvelle tentative…")))
        else {
            app.machine.tare()
            done(CommandResult.OK)
        }
    }

    override fun catalog(query: CatalogQuery): CatalogPageWire {
        val repo = app.recipes
        return CatalogSearch.page(repo.index, query, repo.recentIds(), repo.mostCookedIds())
    }

    /**
     * Une recette que le robot choisi ne sait pas faire n'existe pas pour le téléphone, même demandée par
     * son identifiant (le catalogue ne la liste déjà pas) : ni lue, ni envoyée sur l'accueil.
     */
    override fun recipe(id: String): Recipe? =
        if (id.length > MAX_ID) null else app.recipes.getAdjusted(id)?.takeIf { cookable(it) }

    /** Les corrections de l'utilisateur, sans son nombre de personnes : le téléphone recalcule de là. */
    override fun baseRecipe(id: String): Recipe? =
        if (id.length > MAX_ID) null
        else app.recipes.get(id)?.takeIf { cookable(it) }?.let { it.corrected(app.recipes.adjustmentsOf(it.id)) }

    private fun cookable(r: Recipe) = RecipeCompat.supports(r, RobotProfiles.active)

    override fun baseServings(recipeId: String): Int = if (recipeId.length > MAX_ID) 0 else app.recipes.get(recipeId)?.servings ?: 0

    /**
     * Comme le réglage de la page de la recette, sur le fil principal (les adaptations s'y enregistrent
     * et la page affichée suit par RecipeRepo.onAdjusted). Jamais exécuté s'il n'a pas pu commencer à temps.
     */
    override fun setServings(recipeId: String, people: Int, stillAllowed: () -> Boolean): ServingsReply {
        val o = HostCalls.attempt<ServingsReply>(post, timeout) { done ->
            val repo = app.recipes
            val r = if (recipeId.length > MAX_ID) null else repo.get(recipeId)
            val refusal = if (!stillAllowed()) ServingsChange.cooking() else ServingsChange.check(r, people)
            if (refusal != null || r == null) done(ServingsReply(refusal = refusal))
            else {
                val next = repo.adjustmentsOf(r.id).withServings(people, r.servings)
                repo.setAdjustments(r.id, next)
                done(ServingsReply(r.adjusted(next), r.servings))
            }
        }
        return when {
            o.done -> o.value ?: ServingsReply(refusal = CommandResult.noAnswer())
            o.skipped -> ServingsReply(refusal = CommandResult.timeout())
            else -> ServingsReply(refusal = CommandResult.noAnswer())
        }
    }

    override val setsFavorites: Boolean get() = true

    /** Comme l'étoile de la page de la recette, sur le fil principal (la liste et les tuiles du robot suivent). */
    override fun setFavorite(recipeId: String, favorite: Boolean): CommandResult = command { done ->
        val repo = app.recipes
        if (recipeId.length > MAX_ID || repo.get(recipeId) == null) done(CommandResult.refuse("not_found", tr("Recette introuvable")))
        else {
            repo.setFavorite(recipeId, favorite)
            // Recette de la communauté, compte toqueapp.org relié : l'étoile part aussi sur le site, comme sur le robot.
            app.site.favoriteChanged(recipeId, favorite)
            done(CommandResult.OK)
        }
    }

    override val stopsRecipes: Boolean get() = true

    /** Le bouton « Arrêter la recette » de l'écran de la recette guidée, sans sa confirmation (faite sur le téléphone). */
    override fun stopRecipe(): CommandResult = command { done ->
        done(if (stopGuidedRecipe()) CommandResult.OK else CommandResult.refuse("no_recipe", tr("Aucune recette en cours sur le robot")))
    }

    override val setsAppearance: Boolean get() = appearance != null

    /** Toute couleur que la garde de la roue accepte, comme Réglages › Apparence › Roue des couleurs. */
    override val setsAnyAccent: Boolean get() = true

    /** Les valeurs sont déjà vérifiées par le hub (couleur gardée, « dark » ou « light ») ; revérifiées ici. */
    override fun setAppearance(accent: String?, theme: String?): CommandResult {
        val f = appearance ?: return super.setAppearance(accent, theme)
        val color = accent?.let { (0xFF shl 24) or it.substring(1).toInt(16) }
        if (color != null && !Ui.accepts(color)) return CommandResult.refuse("bad_request", tr("Couleur inconnue"))
        val light = theme?.let { it == "light" }
        return command { done -> done(f(color, light)) }
    }

    override val writesRecipes: Boolean get() = true

    /** Les réglages d'étape tapés sur le téléphone, jugés comme l'éditeur d'étape du robot ; leurs vérifications gardées sont oubliées en écrivant. */
    override val writesMachineSteps: Boolean get() = true

    /** Telle qu'enregistrée : ni adaptée au nombre de personnes, ni corrigée (le téléphone l'édite et la renvoie). */
    override fun storedRecipe(id: String): Recipe? = if (id.length > MAX_ID) null else app.recipes.get(id)

    /**
     * Sur le fil principal, d'un bloc, comme l'éditeur du robot : relire la recette, savoir si l'éditeur
     * du robot l'a ouverte, décider, enregistrer. Jamais exécuté s'il n'a pas pu commencer à temps.
     */
    override fun writeRecipe(id: String, decide: (stored: Recipe?, inEditor: Boolean) -> RecipeWrite): RecipeWrite {
        val o = HostCalls.attempt<RecipeWrite>(post, timeout) { done ->
            val repo = app.recipes
            val stored = if (id.length > MAX_ID) null else repo.get(id)
            val w = decide(stored, RecipeEditScreen.isEditing(id))
            val r = w.saved
            if (r != null) {
                // Comme l'éditeur du robot : la fiche s'ouvre ensuite au nombre de personnes de la recette.
                val adj = repo.adjustmentsOf(r.id)
                if (adj.servings != 0) repo.setAdjustments(r.id, adj.copy(servings = 0))
                repo.saveUser(r)
                // Déjà sur toqueapp.org (compte relié) : mise à jour en ligne, comme après l'éditeur du robot. Une
                // recette qui n'y est pas n'y part jamais d'ici : ce choix se fait sur le robot.
                app.site.updateIfOnline(r) { error -> if (error != null) app.log.write("site", "recette ${r.id} pas mise à jour sur toqueapp.org : $error") }
                // Réglages tapés sur le téléphone : à vérifier sur le robot avant leur premier lancement, même
                // si un réglage au même résumé avait été vérifié à ce rang ; une étape déplacée ne reprend pas non
                // plus la vérification de celle qui tenait son rang (dans ce même bloc, avant toute cuisson).
                CookScreen.forgetConfirmations(app.prefs, r.id, w.forget)
                val by = w.phone.ifBlank { "un téléphone" }
                val typed = if (w.typed.isEmpty()) "" else ", réglage écrit sur le téléphone à l'étape " + w.typed.joinToString(", ") { "${it + 1}" }
                app.log.write("compagnon", "recette enregistrée depuis $by : ${r.id}$typed")
            }
            done(w)
        }
        return when {
            o.done -> o.value ?: RecipeWrite(error = EditError.of(CommandResult.noAnswer()))
            o.skipped -> RecipeWrite(error = EditError.of(CommandResult.timeout()))
            else -> RecipeWrite(error = EditError.of(CommandResult.noAnswer()))
        }
    }

    /** L'image de la recette [recipeId], trouvée par la recette elle-même : jamais un chemin construit depuis l'id reçu. */
    override fun image(recipeId: String): ImageData? {
        if (recipeId.length > MAX_ID) return null
        val repo = app.recipes
        val r = repo.get(recipeId) ?: return null
        val f = repo.imageFile(r.image)?.canonicalFile ?: return null
        val dir = repo.imagesDir.canonicalFile
        if (!f.isFile || f.parentFile != dir || f.length() <= 0 || f.length() > MAX_IMAGE) return null
        val bytes = f.readBytes()
        val type = contentType(bytes) ?: return null
        return ImageData(bytes, type)
    }

    companion object {
        const val MAX_IMAGE = 4L * 1024 * 1024
        const val MAX_ID = 200

        /** Type d'après les premiers octets (le nom du fichier ne fait pas foi). */
        fun contentType(b: ByteArray): String? = when {
            b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
            b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
            b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> null
        }
    }
}
