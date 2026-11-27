package app.toque.core.recipes

import app.toque.core.json.obj
import app.toque.core.json.str
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * Qui a tapé un réglage d'étape du propriétaire. Un réglage tapé sur l'écran du robot vaut celui d'une
 * recette perso ; un réglage venu d'ailleurs (un téléphone) reste à vérifier une fois sur le robot.
 */
enum class SettingAuthor(val key: String) {
    ROBOT("robot"),
    PHONE("phone");

    companion object {
        /** Auteur inconnu (version plus récente, fichier abîmé) : traité comme un téléphone, donc à vérifier. */
        fun of(key: String?): SettingAuthor = values().firstOrNull { it.key == key } ?: PHONE
    }
}

/**
 * Le réglage que le propriétaire a donné à une étape d'une recette reçue (Mealie) sans en faire une
 * copie : [machine] null veut dire « pas de réglage du robot à cette étape » (un réglage lu dans le
 * texte, écarté).
 */
data class OwnStepSetting(val machine: StepMachine?, val by: SettingAuthor = SettingAuthor.ROBOT) {
    /** Tapé sur le robot : pas d'étape « à vérifier », comme dans une recette perso. */
    val trusted: Boolean get() = by == SettingAuthor.ROBOT

    fun toMap(): Map<String, Any?> = linkedMapOf("by" to by.key, "machine" to machine?.let { RecipeCodec.machineMap(it) })

    companion object {
        /** Null si l'entrée est illisible (programme inconnu, « machine » absent) : elle est ignorée. */
        fun fromMap(any: Any?): OwnStepSetting? {
            val m = any as? Map<*, *> ?: return null
            if (!m.containsKey("machine")) return null
            val raw = m["machine"]
            val machine = if (raw == null) null else RecipeCodec.parseMachine(raw.obj()) ?: return null
            return OwnStepSetting(machine, SettingAuthor.of(m.obj().str("by")))
        }
    }
}

/**
 * Réglages du propriétaire pour les étapes d'une recette reçue, rangés avec ses adaptations
 * ([RecipeAdjustments.steps]) et appliqués par-dessus la recette à chaque affichage : la recette reste
 * une seule recette, mise à jour par chaque synchronisation.
 *
 * Une étape est reconnue à l'empreinte de son texte normalisé (casse, espaces, apostrophes), pas à
 * son rang : déplacer les étapes dans la source garde les réglages, mais réécrire une étape fait
 * tomber le sien (l'étape revient à ce que son texte propose, à vérifier, ou à rien), pour qu'un
 * ancien réglage ne parte jamais sur une étape qui dit autre chose. Deux étapes au texte identique
 * se distinguent par leur ordre (« -2 » pour la deuxième).
 */
object OwnStepSettings {
    /** Le seul endroit qui dit quelles recettes en acceptent : celles de Mealie pour l'instant. */
    fun appliesTo(r: Recipe): Boolean = r.source == RecipeSource.MEALIE

    private val SPACES = Regex("\\s+")
    private val KEY = Regex("[0-9a-f]{16}(?:-[1-9][0-9]{0,3})?")

    /** Le texte tel qu'il est comparé : les différences d'écriture qui ne changent pas le sens disparaissent. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).replace('’', '\'').lowercase(Locale.ROOT).replace(SPACES, " ").trim()

    /** Empreinte d'un texte d'étape : 16 chiffres hexadécimaux (64 bits du SHA-256 du texte normalisé). */
    fun fingerprint(text: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(normalize(text).toByteArray(Charsets.UTF_8))
        return d.take(8).joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xFF) }
    }

    fun isKey(k: String): Boolean = KEY.matches(k)

    /** La clé de chaque étape de [r] (la recette de la source, sans adaptation), dans l'ordre. */
    fun keys(r: Recipe): List<String> {
        val seen = HashMap<String, Int>()
        return r.steps.map { s ->
            val f = fingerprint(s.text)
            val n = (seen[f] ?: 0) + 1
            seen[f] = n
            if (n == 1) f else "$f-$n"
        }
    }

    /**
     * Ce qui refuse [machine] pour l'étape [index] de [r], ou null : la même règle que l'éditeur
     * d'étape d'une recette perso ([RecipeRules.step], réglages compris). Le texte de l'étape ne change pas.
     */
    fun problem(r: Recipe, index: Int, machine: StepMachine?): RecipeProblem? {
        val s = r.steps.getOrNull(index)
        if (!appliesTo(r) || s == null) return RecipeProblem(NOT_ALLOWED, "steps", index, NOT_ALLOWED)
        val p = RecipeRules.step(s.copy(machine = machine, needsReview = false, reviewAlone = false)) ?: return null
        return p.copy(step = index)
    }

    /** Refus de [problem] quand la recette n'en accepte pas ou que l'étape n'existe pas (pas montré à l'écran). */
    const val NOT_ALLOWED = "own_setting_not_allowed"

    /** Les étapes de [r] qui portent un réglage du propriétaire : rang → réglage. */
    fun owned(r: Recipe, a: RecipeAdjustments): Map<Int, OwnStepSetting> {
        if (a.steps.isEmpty() || !appliesTo(r)) return emptyMap()
        val out = LinkedHashMap<Int, OwnStepSetting>()
        keys(r).forEachIndexed { i, k -> a.steps[k]?.let { out[i] = it } }
        return out
    }
}

/**
 * [r] avec les réglages du propriétaire à la place de ceux de la source. Tapé sur le robot : plus « à
 * vérifier », comme une étape de recette perso ; venu d'un téléphone : à vérifier sur sa propre carte.
 * Les réglages passent ensuite par les mêmes contrôles que tout réglage (cuisson guidée, MachineController).
 */
fun Recipe.withOwnSettings(own: Map<String, OwnStepSetting>): Recipe {
    if (own.isEmpty() || !OwnStepSettings.appliesTo(this)) return this
    val keys = OwnStepSettings.keys(this)
    var changed = false
    val out = steps.mapIndexed { i, s ->
        val o = own[keys[i]] ?: return@mapIndexed s
        changed = true
        val m = o.machine
        val review = m != null && !o.trusted
        s.copy(machine = m, needsReview = review, reviewAlone = review)
    }
    return if (changed) copy(steps = out) else this
}
