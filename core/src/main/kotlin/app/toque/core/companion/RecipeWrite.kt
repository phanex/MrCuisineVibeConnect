package app.toque.core.companion

import app.toque.core.i18n.tr
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeProblem
import app.toque.core.recipes.RecipeRules
import app.toque.core.recipes.SettingKind
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.core.recipes.StepSettings
import app.toque.core.recipes.UserRecipes
import app.toque.core.recipes.Weigh
import app.toque.core.machine.Accessory
import app.toque.core.machine.ManualLimits
import app.toque.core.robot.RobotProfile
import app.toque.core.robot.RobotProfiles
import java.security.MessageDigest

/*
 * Écrire ses recettes depuis le téléphone. Lot A : texte, ingrédients, pesées ; chaque étape envoyée dit
 * de quelle étape enregistrée elle vient (« from »), et le robot lui remet son réglage et sa vérification.
 * Lot B : le réglage du robot d'une étape, tapé sur le téléphone (« machine »), chez un robot qui le dit
 * ([EditableRecipe.settings]). Le téléphone sert de clavier, le robot juge : un réglage tapé ou changé
 * passe par les règles de son éditeur ([StepSettings.refusal]) et se vérifie une fois sur le robot avant
 * son premier lancement ; un réglage que le téléphone n'a pas touché n'est pas rejugé.
 */

/** Lecture d'un nombre entier d'un corps reçu, dans des bornes de lecture ; sinon [BadEdit] [field]. */
internal fun editNum(m: Map<String, Any?>, k: String, min: Long, max: Long, field: String = k, step: Int = -1): Long {
    val n = when (val v = m[k]) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong() else throw BadEdit(field, step)
        else -> throw BadEdit(field, step)
    }
    if (n < min || n > max) throw BadEdit(field, step)
    return n
}

/**
 * Le réglage du robot d'une étape tel que le téléphone l'écrit (membre « machine » d'une étape envoyée) :
 * `program` (StepProgram.key), `seconds`, `speed`, `tempC`, `reverse`, `accessory` (BLADE, WHISK), `pulses`.
 */
object SettingWire {
    private val KEYS = setOf("program", "seconds", "speed", "tempC", "reverse", "accessory", "pulses")

    fun toMap(m: StepMachine): Map<String, Any?> = linkedMapOf("program" to m.program.key, "seconds" to m.seconds, "speed" to m.speed,
        "tempC" to m.tempC, "reverse" to m.reverse, "accessory" to m.accessory.name, "pulses" to m.pulses)

    /**
     * Lecture stricte : chacun des sept champs, de son type, rien d'autre. Bornes de lecture seulement
     * (`seconds` 0–1000000, `speed` 0–100, `tempC` 0–1000, `pulses` 0–1000) : le robot juge ensuite.
     * Sinon [BadEdit] « machine_setting » à l'étape [step].
     */
    fun fromMap(m: Map<String, Any?>, step: Int = -1): StepMachine {
        val f = "machine_setting"
        for (k in m.keys) if (k !in KEYS) throw BadEdit(f, step)
        for (k in KEYS) if (!m.containsKey(k)) throw BadEdit(f, step)
        val program = StepProgram.values().firstOrNull { it.key == m["program"] } ?: throw BadEdit(f, step)
        val accessory = Accessory.values().firstOrNull { it.name == m["accessory"] } ?: throw BadEdit(f, step)
        val reverse = m["reverse"] as? Boolean ?: throw BadEdit(f, step)
        return StepMachine(program, editNum(m, "seconds", 0, 1_000_000, f, step).toInt(), editNum(m, "speed", 0, 100, f, step).toInt(),
            editNum(m, "tempC", 0, 1000, f, step).toInt(), reverse, accessory, editNum(m, "pulses", 0, 1000, f, step).toInt())
    }

    /** Lecture tolérante (brouillon du téléphone, réglage de départ annoncé par le robot) : null si illisible. */
    fun read(m: Map<String, Any?>?): StepMachine? = if (m == null) null else try {
        fromMap(m.filterKeys { it in KEYS })
    } catch (_: BadEdit) {
        null
    }

    /** Le réglage complet d'une étape montrée par le robot ([EditStepWire.machine]), ou null s'il ne se relit pas. */
    fun of(w: MachineStepWire?): StepMachine? {
        if (w == null) return null
        val program = StepProgram.values().firstOrNull { it.key == w.program } ?: return null
        val accessory = Accessory.values().firstOrNull { it.name == w.accessory } ?: return null
        return StepMachine(program, w.seconds, w.speed, w.tempC, w.reverse, accessory, w.pulses)
    }
}

/**
 * Un genre de réglage que l'éditeur du robot propose ([SettingKind]) : [label] dans la langue du robot,
 * [params] les champs réglables, [default] le réglage de départ (et la valeur des autres champs), les
 * bornes de l'éditeur (0 : sans objet ; [secondsStep] 0 : pas de la cuisson manuelle), [hint] son rappel.
 */
data class SettingKindWire(
    val program: String = "",
    val label: String = "",
    val params: List<String> = emptyList(),
    val default: StepMachine = StepMachine(),
    val minSeconds: Int = 0,
    val maxSeconds: Int = 0,
    val secondsStep: Int = 0,
    val minTempC: Int = 0,
    val maxTempC: Int = 0,
    val minPulses: Int = 0,
    val maxPulses: Int = 0,
    val hint: String = "",
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("program" to program, "label" to label, "params" to params,
        "default" to SettingWire.toMap(default), "minSeconds" to minSeconds, "maxSeconds" to maxSeconds, "secondsStep" to secondsStep,
        "minTempC" to minTempC, "maxTempC" to maxTempC, "minPulses" to minPulses, "maxPulses" to maxPulses, "hint" to hint)

    val stepProgram: StepProgram? get() = StepProgram.values().firstOrNull { it.key == program }

    /**
     * [m] ramené à ce que l'éditeur du robot propose pour ce genre : les champs qu'il ne règle pas reprennent
     * ceux de [default], durée, température et impulsions rentrent dans ses bornes. Le téléphone s'en sert dès
     * qu'on y touche un réglage venu d'ailleurs (recette Lidl : pétrissage sans vitesse, Turbo compté en
     * secondes), que le robot refuserait tel quel.
     */
    fun fit(m: StepMachine): StepMachine {
        fun has(f: String) = f in params
        fun within(v: Int, min: Int, max: Int) = if (max > 0 && max >= min) v.coerceIn(min, max) else maxOf(v, 0)
        return StepMachine(default.program,
            if (has(StepSettings.SECONDS)) within(m.seconds, minSeconds, maxSeconds) else default.seconds,
            if (has(StepSettings.SPEED)) m.speed else default.speed,
            if (has(StepSettings.TEMP)) within(m.tempC, minTempC, maxTempC) else default.tempC,
            if (has(StepSettings.REVERSE)) m.reverse else default.reverse,
            if (has(StepSettings.ACCESSORY)) m.accessory else default.accessory,
            if (has(StepSettings.PULSES)) within(m.pulses, minPulses, maxPulses) else default.pulses)
    }

    /** [a] et [b] sont le même réglage de ce genre : ce que l'éditeur y règle a la même valeur (le reste ne compte pas). */
    fun same(a: StepMachine, b: StepMachine): Boolean =
        a.program.key == program && b.program.key == program && params.all { StepSettings.value(a, it) == StepSettings.value(b, it) }

    companion object {
        fun of(k: SettingKind) = SettingKindWire(k.program.key, k.program.label, k.params, k.default, k.minSeconds, k.maxSeconds,
            k.secondsStep, k.minTempC, k.maxTempC, k.minPulses, k.maxPulses, k.hint)

        /** Null : un genre que ce téléphone ne connaît pas (robot plus récent), ou illisible. */
        fun fromMap(m: Map<String, Any?>): SettingKindWire? {
            val program = W.s(m, "program")
            if (StepProgram.values().none { it.key == program }) return null
            @Suppress("UNCHECKED_CAST")
            val default = SettingWire.read(W.o(m, "default")) ?: return null
            if (default.program.key != program) return null
            return SettingKindWire(program, W.s(m, "label"), (m["params"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(), default,
                W.i(m, "minSeconds"), W.i(m, "maxSeconds"), W.i(m, "secondsStep"), W.i(m, "minTempC"), W.i(m, "maxTempC"),
                W.i(m, "minPulses"), W.i(m, "maxPulses"), W.s(m, "hint"))
        }
    }
}

/**
 * Bornes de la cuisson manuelle de l'éditeur d'étape du robot (celles de son écran Cuisson manuelle) :
 * [temperaturesC] du robot choisi (40 °C sur le Smart), vitesses maximales en chauffe, en sens inverse et
 * au fouet, chauffe sans mélange ([heatNoStir], jusqu'à [maxTempNoStirC]), durées, et ce que le robot a
 * ([reverse], [accessories], [maxSpeed]).
 */
data class ManualRangesWire(
    val temperaturesC: List<Int> = emptyList(),
    val maxSpeed: Int = 10,
    val maxSpeedHeated: Int = 0,
    val maxSpeedReverse: Int = 0,
    val whiskMaxSpeed: Int = 0,
    val maxTempNoStirC: Int = 0,
    val heatNoStir: Boolean = false,
    val reverse: Boolean = true,
    val accessories: List<String> = listOf(Accessory.BLADE.name),
    val minSeconds: Int = 0,
    val maxSeconds: Int = 0,
    val maxSecondsHighSpeed: Int = 0,
    val highSpeedAbove: Int = 0,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("temperaturesC" to temperaturesC, "maxSpeed" to maxSpeed, "maxSpeedHeated" to maxSpeedHeated,
        "maxSpeedReverse" to maxSpeedReverse, "whiskMaxSpeed" to whiskMaxSpeed, "maxTempNoStirC" to maxTempNoStirC, "heatNoStir" to heatNoStir,
        "reverse" to reverse, "accessories" to accessories, "minSeconds" to minSeconds, "maxSeconds" to maxSeconds,
        "maxSecondsHighSpeed" to maxSecondsHighSpeed, "highSpeedAbove" to highSpeedAbove)

    /** Les bornes de saisie de [app.toque.core.machine.ManualSettings] ; un champ absent ou nul garde la valeur de Toque. */
    fun limits(): ManualLimits {
        val d = ManualLimits()
        fun pos(v: Int, def: Int) = if (v > 0) v else def
        val noStir = app.toque.core.protocol.Mcc.levelForCelsius(maxTempNoStirC)
        return ManualLimits(pos(maxSpeedHeated, d.maxSpeedHeated), pos(maxSpeedReverse, d.maxSpeedReverse), pos(whiskMaxSpeed, d.whiskMaxSpeed),
            if (noStir > 0) noStir else d.maxTempLevelNoStir, pos(minSeconds, d.minSeconds), pos(maxSeconds, d.maxSeconds),
            pos(maxSecondsHighSpeed, d.maxSecondsHighSpeed), pos(highSpeedAbove, d.highSpeedAbove), temperaturesC.ifEmpty { d.temperaturesC }, heatNoStir)
    }

    companion object {
        fun of(robot: RobotProfile): ManualRangesWire {
            val l = StepSettings.manualLimits(robot)
            val caps = robot.capabilities
            return ManualRangesWire(l.temperaturesC, caps.maxSpeed, l.maxSpeedHeated, l.maxSpeedReverse, l.whiskMaxSpeed,
                app.toque.core.protocol.Mcc.celsiusForLevel(l.maxTempLevelNoStir), l.heatNoStir, caps.reverse,
                Accessory.values().filter { it in caps.accessories }.map { it.name }, l.minSeconds, l.maxSeconds, l.maxSecondsHighSpeed, l.highSpeedAbove)
        }

        fun fromMap(m: Map<String, Any?>) = ManualRangesWire(W.ints(m, "temperaturesC"), W.i(m, "maxSpeed").takeIf { it > 0 } ?: 10,
            W.i(m, "maxSpeedHeated"), W.i(m, "maxSpeedReverse"), W.i(m, "whiskMaxSpeed"), W.i(m, "maxTempNoStirC"), W.b(m, "heatNoStir"),
            m["reverse"] as? Boolean ?: true, (m["accessories"] as? List<*>)?.mapNotNull { it as? String }?.ifEmpty { null } ?: listOf(Accessory.BLADE.name),
            W.i(m, "minSeconds"), W.i(m, "maxSeconds"), W.i(m, "maxSecondsHighSpeed"), W.i(m, "highSpeedAbove"))
    }
}

/**
 * Ce que l'éditeur d'étape du robot propose, annoncé au téléphone : présent seulement chez un robot qui
 * prend les réglages tapés sur le téléphone (absent : il n'en prend aucun, le téléphone n'en propose pas).
 */
data class StepSettingsWire(val kinds: List<SettingKindWire> = emptyList(), val manual: ManualRangesWire = ManualRangesWire()) {
    fun toMap(): Map<String, Any?> = linkedMapOf("kinds" to kinds.map { it.toMap() }, "manual" to manual.toMap())

    fun kind(program: StepProgram?): SettingKindWire? = kinds.firstOrNull { it.program == program?.key }

    companion object {
        fun of(robot: RobotProfile) = StepSettingsWire(StepSettings.kinds(robot).map { SettingKindWire.of(it) }, ManualRangesWire.of(robot))

        fun fromMap(m: Map<String, Any?>) = StepSettingsWire(W.objs(m, "kinds").mapNotNull { SettingKindWire.fromMap(it) },
            W.o(m, "manual")?.let { ManualRangesWire.fromMap(it) } ?: ManualRangesWire())
    }
}

/** Réponse de GET /api/editor : les bornes de l'éditeur pour une recette nouvelle, et ses réglages s'il en prend. */
data class EditorWire(val limits: EditLimitsWire = EditLimitsWire(), val settings: StepSettingsWire? = null) {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>("limits" to limits.toMap()).apply {
        if (settings != null) put("settings", settings.toMap())
    }

    companion object {
        fun fromMap(m: Map<String, Any?>) = EditorWire(W.o(m, "limits")?.let { EditLimitsWire.fromMap(it) } ?: EditLimitsWire(),
            W.o(m, "settings")?.let { StepSettingsWire.fromMap(it) })
    }
}

/** Un groupe d'ingrédients tel que le téléphone l'écrit : son titre (vide, ou un titre déjà là) et une ligne par ingrédient. */
data class EditGroupWire(val title: String = "", val items: List<String> = emptyList()) {
    fun toMap(): Map<String, Any?> = linkedMapOf("title" to title, "items" to items)

    companion object {
        fun fromMap(m: Map<String, Any?>) = EditGroupWire(W.s(m, "title"),
            (m["items"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList())
    }
}

/**
 * Une étape de la recette enregistrée, pour l'éditeur du téléphone : [index] à renvoyer dans « from »,
 * [grams] 0 sans pesée, [machine] le réglage du robot (lecture seule ici), [needsReview] à vérifier sur le robot.
 */
data class EditStepWire(
    val index: Int = 0,
    val text: String = "",
    val grams: Int = 0,
    val machine: MachineStepWire? = null,
    val needsReview: Boolean = false,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("index" to index, "text" to text, "grams" to grams,
        "machine" to machine?.toMap(), "needsReview" to needsReview)

    companion object {
        fun fromMap(m: Map<String, Any?>) = EditStepWire(W.i(m, "index"), W.s(m, "text"), W.i(m, "grams"),
            W.o(m, "machine")?.let { MachineStepWire.fromMap(it) }, W.b(m, "needsReview"))
    }
}

/** Bornes que le robot applique à une recette écrite sur le téléphone (le téléphone les montre ; le robot juge). */
data class EditLimitsWire(
    val minServings: Int = RecipeRules.MIN_SERVINGS,
    val maxServings: Int = RecipeRules.MAX_SERVINGS,
    val maxTotalMinutes: Int = RecipeRules.MAX_TOTAL_MINUTES,
    val maxGrams: Int = app.toque.core.recipes.RecipeAdjustments.MAX_GRAMS,
    val maxTitle: Int = RecipeEdits.MAX_TITLE,
    val maxLine: Int = RecipeEdits.MAX_LINE,
    val maxStepText: Int = RecipeEdits.MAX_STEP_TEXT,
    val maxIngredients: Int = RecipeEdits.MAX_INGREDIENTS,
    val maxSteps: Int = RecipeEdits.MAX_STEPS,
    val maxBodyBytes: Int = RecipeEdits.MAX_BODY_BYTES,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("minServings" to minServings, "maxServings" to maxServings,
        "maxTotalMinutes" to maxTotalMinutes, "maxGrams" to maxGrams, "maxTitle" to maxTitle, "maxLine" to maxLine,
        "maxStepText" to maxStepText, "maxIngredients" to maxIngredients, "maxSteps" to maxSteps, "maxBodyBytes" to maxBodyBytes)

    companion object {
        fun fromMap(m: Map<String, Any?>): EditLimitsWire {
            fun v(k: String, def: Int) = W.i(m, k).takeIf { it > 0 } ?: def
            val d = EditLimitsWire()
            return EditLimitsWire(v("minServings", d.minServings), v("maxServings", d.maxServings), v("maxTotalMinutes", d.maxTotalMinutes),
                v("maxGrams", d.maxGrams), v("maxTitle", d.maxTitle), v("maxLine", d.maxLine), v("maxStepText", d.maxStepText),
                v("maxIngredients", d.maxIngredients), v("maxSteps", d.maxSteps), v("maxBodyBytes", d.maxBodyBytes))
        }
    }
}

/**
 * Une recette perso telle qu'enregistrée sur le robot (ni adaptée au nombre de personnes, ni corrigée),
 * avec sa [version] : GET /api/recipes/{id}/edit, et la réponse de chaque écriture.
 */
data class EditableRecipe(
    val id: String = "",
    val version: String = "",
    val title: String = "",
    val servings: Int = 0,
    val totalMinutes: Int = 0,
    val difficulty: Int = 1,
    val ingredients: List<EditGroupWire> = emptyList(),
    val steps: List<EditStepWire> = emptyList(),
    val limits: EditLimitsWire = EditLimitsWire(),
    /**
     * Ce que l'éditeur d'étape du robot propose : écrit seulement par un robot qui prend les réglages tapés
     * sur le téléphone (lot B) ; absent (null) chez un robot plus ancien, qui refuse tout réglage.
     */
    val settings: StepSettingsWire? = null,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("id" to id, "version" to version, "title" to title, "servings" to servings,
        "totalMinutes" to totalMinutes, "difficulty" to difficulty, "ingredients" to ingredients.map { it.toMap() },
        "steps" to steps.map { it.toMap() }, "limits" to limits.toMap()).apply {
        if (settings != null) put("settings", settings.toMap())
    }

    companion object {
        fun of(r: Recipe, settings: StepSettingsWire? = null) = EditableRecipe(r.id, RecipeEdits.version(r), r.title, r.servings, r.totalMinutes,
            r.difficulty, r.ingredients.map { g -> EditGroupWire(g.title, g.items.map { it.display }) },
            r.steps.mapIndexed { i, s ->
                EditStepWire(i, s.text, s.weigh?.grams ?: 0, s.machine?.let { Wires.step(i, s).machine }, s.needsReview)
            }, settings = settings)

        fun fromMap(m: Map<String, Any?>) = EditableRecipe(W.s(m, "id"), W.s(m, "version"), W.s(m, "title"), W.i(m, "servings"),
            W.i(m, "totalMinutes"), W.i(m, "difficulty").coerceAtLeast(1), W.objs(m, "ingredients").map { EditGroupWire.fromMap(it) },
            W.objs(m, "steps").map { EditStepWire.fromMap(it) }, W.o(m, "limits")?.let { EditLimitsWire.fromMap(it) } ?: EditLimitsWire(),
            W.o(m, "settings")?.let { StepSettingsWire.fromMap(it) })
    }
}

/** Le réglage d'une étape écrit sur le téléphone : [machine] ce réglage, ou null pour « aucun réglage ». */
data class SettingEdit(val machine: StepMachine?)

/**
 * Une étape envoyée par le téléphone : [from] l'étape enregistrée d'où elle vient (null : nouvelle), son
 * texte, sa pesée (0 : aucune), et [setting] son réglage écrit sur le téléphone : null, le membre
 * « machine » est absent (le réglage de l'étape d'origine reste, une étape nouvelle n'en a pas) ;
 * [SettingEdit] de null, « machine » vaut null (aucun réglage) ; sinon ce réglage.
 */
data class EditStepRequest(val from: Int? = null, val text: String = "", val grams: Int = 0, val setting: SettingEdit? = null) {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        if (from != null) put("from", from)
        put("text", text)
        put("grams", grams)
        if (setting != null) put("machine", setting.machine?.let { SettingWire.toMap(it) })
    }
}

/**
 * Corps de POST /api/recipes (création : [draft], pas de [version]) et de POST /api/recipes/{id}/edit
 * (enregistrement : [version], pas de [draft]).
 */
data class RecipeEditRequest(
    val version: String? = null,
    /** Identifiant du brouillon du téléphone (32 hex) : une création répétée après une réponse perdue ne crée pas deux recettes. */
    val draft: String? = null,
    val title: String = "",
    val servings: Int = 4,
    val totalMinutes: Int = 0,
    val difficulty: Int = 1,
    val ingredients: List<EditGroupWire> = emptyList(),
    val steps: List<EditStepRequest> = emptyList(),
) {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        if (version != null) put("version", version)
        if (draft != null) put("draft", draft)
        put("title", title)
        put("servings", servings)
        put("totalMinutes", totalMinutes)
        put("difficulty", difficulty)
        put("ingredients", ingredients.map { it.toMap() })
        put("steps", steps.map { it.toMap() })
    }

    companion object {
        private val FIELDS = setOf("version", "draft", "title", "servings", "totalMinutes", "difficulty", "ingredients", "steps")
        private val STEP_FIELDS = setOf("from", "text", "grams")
        /**
         * Ce que le téléphone n'envoie jamais à plat dans une étape (vérification, pesée du robot, champs du
         * réglage) ; « machine » seulement chez un robot qui prend les réglages ([settings] de [fromMap]).
         */
        private val MACHINE_FIELDS = setOf("machine", "needsReview", "hotBlendOnRobot", "weigh", "program", "seconds", "speed",
            "tempC", "reverse", "accessory", "pulses")
        private val HEX32 = Regex("^[0-9a-f]{32}$")

        /**
         * Lecture stricte. Champ inconnu, manquant ou d'un autre type, nombre non entier ou hors des bornes de
         * lecture : [BadEdit]. Sans [settings] (robot qui ne prend pas les réglages), un réglage machine dans
         * une étape : [BadEdit] « machine ». Avec [settings], le membre « machine » d'une étape est lu par
         * [SettingWire.fromMap] (null : aucun réglage) ; « needsReview » et les champs à plat restent
         * « machine ». Les limites de la recette et les réglages sont jugés ensuite par le robot (422).
         */
        fun fromMap(m: Map<String, Any?>, create: Boolean, settings: Boolean = false): RecipeEditRequest {
            for (k in m.keys) if (k !in FIELDS) throw BadEdit(k)
            val version = m["version"]
            val draft = m["draft"]
            if (create) {
                if (version != null) throw BadEdit("version")
                if (draft !is String || !HEX32.matches(draft)) throw BadEdit("draft")
            } else {
                if (draft != null) throw BadEdit("draft")
                if (version !is String || version.isEmpty() || version.length > 64) throw BadEdit("version")
            }
            val title = m["title"] as? String ?: throw BadEdit("title")
            val groups = (m["ingredients"] as? List<*> ?: throw BadEdit("ingredients")).map { g ->
                @Suppress("UNCHECKED_CAST")
                val gm = g as? Map<String, Any?> ?: throw BadEdit("ingredients")
                for (k in gm.keys) if (k != "title" && k != "items") throw BadEdit("ingredients")
                val items = (gm["items"] as? List<*> ?: throw BadEdit("ingredients")).map { it as? String ?: throw BadEdit("ingredients") }
                EditGroupWire(gm["title"] as? String ?: throw BadEdit("ingredients"), items)
            }
            val steps = (m["steps"] as? List<*> ?: throw BadEdit("steps")).mapIndexed { i, s ->
                @Suppress("UNCHECKED_CAST")
                val sm = s as? Map<String, Any?> ?: throw BadEdit("steps", i)
                for (k in sm.keys) {
                    if (settings && k == "machine") continue
                    if (k in MACHINE_FIELDS) throw BadEdit("machine", i)
                    if (k !in STEP_FIELDS) throw BadEdit("steps", i)
                }
                val from = if (sm["from"] == null) null else num(sm, "from", 0, 10_000).toInt()
                val setting = if (settings && sm.containsKey("machine")) {
                    @Suppress("UNCHECKED_CAST")
                    val v = sm["machine"]?.let { it as? Map<String, Any?> ?: throw BadEdit("machine_setting", i) }
                    SettingEdit(v?.let { SettingWire.fromMap(it, i) })
                } else null
                EditStepRequest(from, sm["text"] as? String ?: throw BadEdit("steps", i), num(sm, "grams", 0, 1_000_000).toInt(), setting)
            }
            return RecipeEditRequest(version as String?, draft as String?, title, num(m, "servings", 0, 1000).toInt(),
                num(m, "totalMinutes", 0, 100_000).toInt(), num(m, "difficulty", 0, 10).toInt(), groups, steps)
        }

        private fun num(m: Map<String, Any?>, k: String, min: Long, max: Long): Long = editNum(m, k, min, max)
    }
}

/**
 * Corps refusé à la lecture ([field] : où ; « machine » : un réglage machine que ce robot ne prend pas, ou
 * envoyé à plat ; « machine_setting » : un membre « machine » mal formé), [step] l'étape (-1 : hors étape).
 */
class BadEdit(val field: String, val step: Int = -1) : IllegalArgumentException(field)

/**
 * Une écriture refusée : statut HTTP, code stable, message affichable, et où ([field], [step]).
 * 400 corps mal formé, 404 recette inconnue, 409 règle (recette en cours, ouverte sur le robot, version
 * dépassée, pas une recette perso), 422 contenu refusé par les limites de l'éditeur, 504 robot sans réponse.
 */
class EditError(val status: Int, val code: String, val message: String, val field: String = "", val step: Int = -1) {
    companion object {
        fun of(p: RecipeProblem) = EditError(422, p.code, p.message, p.field, p.step)
        fun of(c: CommandResult) = EditError(when (c.code) {
            "timeout", "no_answer" -> 504
            "not_found" -> 404
            else -> 409
        }, c.code, c.message)
    }
}

/**
 * Ce qu'une écriture a donné : la recette enregistrée, ou [error]. [typed] : les étapes (rangs dans la
 * recette enregistrée) dont le réglage du robot vient d'être tapé ou changé sur le téléphone ; [moved] :
 * celles dont le réglage, pas touché, a changé de rang (étape déplacée, étape retirée avant elle). L'hôte
 * oublie, en écrivant, ce qu'il gardait à ces rangs ([forget]) : une vérification se garde par rang, et
 * celle d'une autre étape ne doit pas passer pour la leur. [phone] : le nom du téléphone (journal).
 */
class RecipeWrite(
    val saved: Recipe? = null,
    val error: EditError? = null,
    val typed: List<Int> = emptyList(),
    val phone: String = "",
    val moved: List<Int> = emptyList(),
) {
    /** Les rangs dont l'hôte oublie les vérifications et adaptations gardées. */
    val forget: List<Int> get() = typed + moved

    fun from(phone: String) = RecipeWrite(saved, error, typed, phone, moved)
}

/** Construction et jugement d'une recette écrite sur le téléphone. Pur. */
object RecipeEdits {
    /** Titre, ligne d'ingrédient, texte d'une étape (caractères). */
    const val MAX_TITLE = 200
    const val MAX_LINE = 300
    const val MAX_STEP_TEXT = 1500
    /** Lignes d'ingrédients, groupes, étapes. */
    const val MAX_INGREDIENTS = 100
    const val MAX_GROUPS = 20
    const val MAX_STEPS = 60

    /**
     * Corps de ces routes : 192 Kio. Les plafonds ci-dessus font au plus environ 121 000 caractères
     * (60 × 1500 + 100 × 300 + titres) ; 192 Kio les laisse passer avec des accents (deux octets en
     * UTF-8) et l'enveloppe JSON. Côté robot : 16 connexions × 192 Kio = 3 Mio au pire, lus seulement
     * avec un en-tête Authorization, analysés seulement après le jeton vérifié.
     */
    const val MAX_BODY_BYTES = 192 * 1024

    /** Version d'une recette enregistrée : empreinte de son contenu (le favori, changé à part, n'en fait pas partie). */
    fun version(r: Recipe): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(RecipeCodec.encode(r.copy(favorite = false)).toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (i in 0 until 8) sb.append(String.format(java.util.Locale.ROOT, "%02x", bytes[i].toInt() and 0xFF))
        return sb.toString()
    }

    private fun clean(t: String) = t.trim().replace(Regex("\\s+"), " ")

    /**
     * La recette [base] (enregistrée, ou la recette vide d'une création) récrite d'après [req], ou son
     * refus. [stored] : la recette telle qu'elle était (null pour une création) ; ce qui n'a pas changé
     * n'est pas rejugé. Une étape sans réglage envoyé garde celui de son étape d'origine et sa vérification
     * (une étape nouvelle n'en a pas) ; un réglage envoyé égal à celui de l'étape d'origine compte comme
     * inchangé. Un réglage tapé ou changé est jugé comme dans l'éditeur du robot [robot]
     * ([StepSettings.refusal], 422 « step_machine » à son étape) et sera vérifié sur le robot avant son
     * premier lancement, sur sa propre carte (needsReview, reviewAlone) ; retirer un réglage n'a rien à juger.
     */
    fun apply(base: Recipe, stored: Recipe?, req: RecipeEditRequest, robot: RobotProfile = RobotProfiles.active): RecipeWrite {
        fun bad(field: String, message: String) = RecipeWrite(error = EditError(400, "bad_request", message, field))
        fun limit(code: String, field: String, message: String, step: Int = -1) = RecipeWrite(error = EditError(422, code, message, field, step))

        val title = clean(req.title)
        if (title.length > MAX_TITLE && title != stored?.title) return limit("too_long", "title", tr("Titre trop long : %d caractères au plus", MAX_TITLE))

        // Ingrédients : une ligne inchangée reste l'ingrédient enregistré ; une ligne écrite est lue comme sur le robot.
        val known = base.ingredients.flatMap { it.items }.toMutableList()
        val titles = base.ingredients.map { it.title }.toSet()
        var lines = 0
        val groups = ArrayList<IngredientGroup>()
        if (req.ingredients.size > maxOf(MAX_GROUPS, base.ingredients.size)) return bad("ingredients", "too many groups")
        for (g in req.ingredients) {
            if (g.title.isNotEmpty() && g.title !in titles) return bad("ingredients", "groups are created on the robot")
            val items = ArrayList<Ingredient>()
            for (raw in g.items) {
                val t = clean(raw)
                if (t.isEmpty()) continue
                lines++
                val same = known.firstOrNull { it.display == t }
                if (same != null) {
                    known.remove(same)
                    items += same
                    continue
                }
                if (t.length > MAX_LINE) return limit("too_long", "ingredients", tr("Ingrédient trop long : %d caractères au plus", MAX_LINE))
                items += UserRecipes.ingredient(t) ?: continue
            }
            if (items.isNotEmpty()) groups += IngredientGroup(g.title, items)
        }
        if (lines > maxOf(MAX_INGREDIENTS, base.allIngredients.size))
            return limit("too_many_ingredients", "ingredients", tr("Trop d'ingrédients : %d au plus", MAX_INGREDIENTS))

        // Étapes : réglage et vérification repris de l'étape d'origine, sauf réglage écrit sur le téléphone.
        if (req.steps.size > maxOf(MAX_STEPS, base.steps.size)) return limit("too_many_steps", "steps", tr("Trop d'étapes : %d au plus", MAX_STEPS))
        val used = HashSet<Int>()
        val steps = ArrayList<Step>()
        val typed = ArrayList<Int>()
        val moved = ArrayList<Int>()
        for ((i, s) in req.steps.withIndex()) {
            val text = s.text.trim()
            val from = s.from
            val step = if (from != null) {
                val src = base.steps.getOrNull(from) ?: return bad("steps", "unknown step $from")
                if (!used.add(from)) return bad("steps", "step $from sent twice")
                val keepText = text == src.text.trim()
                if (!keepText && text.length > MAX_STEP_TEXT)
                    return limit("too_long", "steps", tr("Étape %d : %d caractères au plus", i + 1, MAX_STEP_TEXT), i)
                val weigh = if (s.grams == (src.weigh?.grams ?: 0)) src.weigh else if (s.grams > 0) Weigh("", s.grams) else null
                val edit = s.setting
                when {
                    // Pas touché (ou renvoyé tel quel) : le réglage enregistré et sa vérification, sans les rejuger.
                    edit == null || edit.machine == src.machine -> {
                        if (from != i && src.machine != null) moved += i
                        Step(if (keepText) src.text else text, src.machine, weigh, src.needsReview, src.reviewAlone)
                    }
                    edit.machine == null -> Step(if (keepText) src.text else text, null, weigh, false)
                    else -> {
                        typed += i
                        // Une étape dont le texte n'est que le résumé de son réglage (les réglages enchaînés d'une
                        // recette Lidl) suit le nouveau réglage : elle ne dit pas « 5 min » au-dessus de « 6 min ».
                        val label = if (keepText && src.machine != null && src.text == summaryOf(src.machine)) summaryOf(edit.machine) else null
                        Step(label ?: if (keepText) src.text else text, edit.machine, weigh, needsReview = true, reviewAlone = true)
                    }
                }
            } else {
                if (text.length > MAX_STEP_TEXT) return limit("too_long", "steps", tr("Étape %d : %d caractères au plus", i + 1, MAX_STEP_TEXT), i)
                val m = s.setting?.machine
                if (m != null) typed += i
                Step(text, m, if (s.grams > 0) Weigh("", s.grams) else null, needsReview = m != null, reviewAlone = m != null)
            }
            steps += step
        }
        val r = base.copy(title = title, servings = req.servings, totalMinutes = req.totalMinutes, difficulty = req.difficulty,
            ingredients = groups, steps = steps)
        // Les limites de l'éditeur du robot, sans rejuger les réglages : seuls ceux tapés ou changés sur le
        // téléphone passent ensuite par les règles de l'éditeur d'étape (un réglage enregistré refusé par la
        // sécurité reste bloqué à la cuisson, comme une recette Lidl).
        RecipeRules.check(r, stored, machines = false)?.let { return RecipeWrite(error = EditError.of(it)) }
        for (i in typed) {
            val why = StepSettings.refusal(steps[i].machine!!, robot) ?: continue
            return limit(RecipeRules.STEP_MACHINE, "steps", tr("Étape %d : %s", i + 1, why), i)
        }
        return RecipeWrite(saved = r, typed = typed, moved = moved)
    }

    /** Le résumé d'un réglage, ou null si le cœur ne sait pas le décrire (programme d'un autre robot hors de sa plage). */
    private fun summaryOf(m: StepMachine): String? = try {
        m.summary
    } catch (_: RuntimeException) {
        null
    }
}
