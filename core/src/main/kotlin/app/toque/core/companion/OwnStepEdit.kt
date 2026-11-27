package app.toque.core.companion

import app.toque.core.i18n.tr
import app.toque.core.recipes.OwnStepSetting
import app.toque.core.recipes.OwnStepSettings
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeAdjustments
import app.toque.core.recipes.RecipeRules
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepSettings
import app.toque.core.robot.RobotProfiles

/*
 * Le réglage du propriétaire d'une étape d'une recette reçue (Mealie), donné depuis le téléphone sans copier la
 * recette, comme sur la page de la recette du robot (OwnStepSettings). Le téléphone sert de clavier, le robot
 * juge : la même règle que l'éditeur d'étape des recettes perso (OwnStepSettings.problem), puis ce que l'éditeur
 * du robot propose (StepSettings.refusal, comme tout réglage tapé sur un téléphone). Un réglage venu d'un
 * téléphone n'est pas sûr : il reste « à vérifier » sur le robot (SettingAuthor.PHONE) jusqu'à ce que le
 * propriétaire le confirme là-bas. L'étape est désignée par son rang ET l'empreinte de son texte : un réglage ne
 * part jamais sur une étape dont le texte a changé sur le robot depuis que le téléphone l'a lue.
 */

/**
 * Ce que l'hôte lit d'une recette qui prend les réglages du propriétaire : la recette de la source ([source],
 * sans adaptation : c'est elle qui donne les empreintes), telle que le robot l'affiche ([shown] : réglages du
 * propriétaire, corrections et nombre de personnes compris), et les réglages du propriétaire ([own], par empreinte).
 */
class OwnStepsRead(val source: Recipe, val shown: Recipe, val own: Map<String, OwnStepSetting>) {
    /** Rang → réglage du propriétaire. */
    val owned: Map<Int, OwnStepSetting> get() = OwnStepSettings.owned(source, RecipeAdjustments(steps = own))
}

/**
 * Une étape de [OwnStepsWire] : [key] l'empreinte à renvoyer, [text] tel que le robot l'affiche, [machine] le
 * réglage qui partira (celui du propriétaire s'il y en a un), [recipe] celui de la recette elle-même (ce que
 * « Réglage de la recette » remet), [own] l'auteur du réglage du propriétaire (« robot », « phone », vide sans
 * réglage à soi), [needsReview] à vérifier sur le robot avant son lancement.
 */
data class OwnStepWire(
    val index: Int = 0,
    val key: String = "",
    val text: String = "",
    val machine: MachineStepWire? = null,
    val recipe: MachineStepWire? = null,
    val own: String = "",
    val needsReview: Boolean = false,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("index" to index, "key" to key, "text" to text, "machine" to machine?.toMap(),
        "recipe" to recipe?.toMap(), "own" to own, "needsReview" to needsReview)

    companion object {
        fun fromMap(m: Map<String, Any?>) = OwnStepWire(W.i(m, "index"), W.s(m, "key"), W.s(m, "text"),
            W.o(m, "machine")?.let { MachineStepWire.fromMap(it) }, W.o(m, "recipe")?.let { MachineStepWire.fromMap(it) },
            W.s(m, "own"), W.b(m, "needsReview"))
    }
}

/**
 * Réponse de GET /api/recipes/{id}/steps et de chaque écriture : les étapes d'une recette qui prend les réglages
 * du propriétaire, et ce que l'éditeur d'étape du robot propose ([settings], comme pour une recette perso).
 */
data class OwnStepsWire(
    val id: String = "",
    val title: String = "",
    val steps: List<OwnStepWire> = emptyList(),
    val settings: StepSettingsWire = StepSettingsWire(),
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("id" to id, "title" to title, "steps" to steps.map { it.toMap() },
        "settings" to settings.toMap())

    companion object {
        fun of(r: OwnStepsRead, settings: StepSettingsWire): OwnStepsWire {
            val keys = OwnStepSettings.keys(r.source)
            val owned = r.owned
            return OwnStepsWire(r.source.id, r.shown.title, r.source.steps.indices.map { i ->
                val shown = r.shown.steps.getOrNull(i) ?: r.source.steps[i]
                val src = r.source.steps[i]
                OwnStepWire(i, keys[i], shown.text, Wires.step(i, shown).machine, Wires.step(i, src).machine,
                    owned[i]?.by?.key.orEmpty(), shown.needsReview)
            }, settings)
        }

        fun fromMap(m: Map<String, Any?>) = OwnStepsWire(W.s(m, "id"), W.s(m, "title"), W.objs(m, "steps").map { OwnStepWire.fromMap(it) },
            W.o(m, "settings")?.let { StepSettingsWire.fromMap(it) } ?: StepSettingsWire())
    }
}

/**
 * Corps de POST /api/recipes/{id}/steps/{rang} : [key] l'empreinte de l'étape lue par le téléphone, [machine] le
 * réglage (null : « sans le robot » à cette étape).
 */
data class OwnStepRequest(val key: String = "", val machine: StepMachine? = null) {
    fun toMap(): Map<String, Any?> = linkedMapOf("key" to key, "machine" to machine?.let { SettingWire.toMap(it) })

    companion object {
        /**
         * Lecture stricte : exactement « key » (une empreinte, [OwnStepSettings.isKey]) et « machine » (null, ou les
         * sept champs de [SettingWire.fromMap]) ; sinon [BadEdit] (« key », « machine_setting ») à l'étape [step].
         */
        fun fromMap(m: Map<String, Any?>, step: Int): OwnStepRequest {
            if (m.keys != setOf("key", "machine")) throw BadEdit("steps", step)
            val key = m["key"] as? String ?: throw BadEdit("key", step)
            if (!OwnStepSettings.isKey(key)) throw BadEdit("key", step)
            @Suppress("UNCHECKED_CAST")
            val raw = m["machine"]?.let { it as? Map<String, Any?> ?: throw BadEdit("machine_setting", step) }
            return OwnStepRequest(key, raw?.let { SettingWire.fromMap(it, step) })
        }
    }
}

/**
 * Ce qu'une écriture du réglage d'une étape donne. Avant l'écriture (réponse de la décision) : [error], ou le
 * changement à faire à l'étape [index] ([clear] : retour au réglage de la recette ; sinon [machine], enregistré
 * comme venu du téléphone [phone]) ; [changed] faux : rien à écrire (le réglage est déjà celui-là). Après :
 * [after], la recette relue dans le même bloc, ou [error] si l'enregistrement a été refusé.
 */
class OwnStepWrite(
    val index: Int = -1,
    val machine: StepMachine? = null,
    val clear: Boolean = false,
    val changed: Boolean = false,
    val error: EditError? = null,
    val phone: String = "",
    val after: OwnStepsRead? = null,
) {
    fun from(phone: String) = OwnStepWrite(index, machine, clear, changed, error, phone, after)
    fun done(after: OwnStepsRead?) = OwnStepWrite(index, machine, clear, changed, error, phone, after)
    fun failed(e: EditError) = OwnStepWrite(index, machine, clear, false, e, phone, null)
}

/** La décision d'une écriture du réglage d'une étape depuis le téléphone. Pure. */
object OwnStepEdits {
    fun stale() = EditError(409, "stale", tr("La recette a changé sur le robot"))
    fun notAllowed() = EditError(409, OwnStepSettings.NOT_ALLOWED, tr("Cette recette ne prend pas votre réglage du robot"))

    /**
     * Pour la recette lue [read] (null : introuvable), à l'étape [index] que le téléphone désigne par [key] :
     * [clear] (retour au réglage de la recette) ou le réglage [machine]. Refus : recette inconnue (404), qui n'en
     * prend pas (409), étape qui n'est plus celle que le téléphone a lue (409 « stale »), réglage refusé (422,
     * « Étape N : … », par [OwnStepSettings.problem] puis [StepSettings.refusal] pour le robot choisi). Un réglage
     * déjà celui de l'étape (le même réglage du propriétaire, ou, sans réglage à soi, celui de la recette quand il
     * n'est pas à vérifier) n'est pas récrit : il garde son auteur.
     */
    fun decide(read: OwnStepsRead?, index: Int, key: String, machine: StepMachine?, clear: Boolean): OwnStepWrite {
        if (read == null) return OwnStepWrite(error = EditError(404, "not_found", tr("Recette introuvable")))
        if (!OwnStepSettings.appliesTo(read.source)) return OwnStepWrite(error = notAllowed())
        val keys = OwnStepSettings.keys(read.source)
        if (keys.getOrNull(index) != key) return OwnStepWrite(error = stale())
        if (clear) return OwnStepWrite(index, clear = true, changed = key in read.own)
        val cur = read.own[key]
        val src = read.source.steps[index]
        if (cur != null && cur.machine == machine) return OwnStepWrite(index, machine)
        if (cur == null && machine == src.machine && !src.needsReview) return OwnStepWrite(index, machine)
        OwnStepSettings.problem(read.source, index, machine)?.let { p ->
            return OwnStepWrite(error = EditError(422, p.code, tr("Étape %d : %s", index + 1, p.message), "steps", index))
        }
        if (machine != null) StepSettings.refusal(machine, RobotProfiles.active)?.let { why ->
            return OwnStepWrite(error = EditError(422, RecipeRules.STEP_MACHINE, tr("Étape %d : %s", index + 1, why), "steps", index))
        }
        return OwnStepWrite(index, machine, changed = true)
    }
}
