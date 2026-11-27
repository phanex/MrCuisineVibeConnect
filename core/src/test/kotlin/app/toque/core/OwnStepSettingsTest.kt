package app.toque.core

import app.toque.core.recipes.OwnStepSetting
import app.toque.core.recipes.OwnStepSettings
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeAdjustments
import app.toque.core.recipes.RecipeRules
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.SettingAuthor
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.core.recipes.StepText
import app.toque.core.recipes.adjusted
import app.toque.core.recipes.corrected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Réglages du propriétaire par étape d'une recette reçue (Mealie), gardés à côté de la recette. */
class OwnStepSettingsTest {
    /** Une recette inventée, telle que la synchro Mealie l'écrit : réglage lu dans le texte, à vérifier. */
    private fun step(text: String): Step {
        val m = StepText.parse(text)
        return Step(text, m, needsReview = m != null)
    }

    private val recipe = Recipe(
        id = "mealie-11111111-2222-4333-8444-555555555555",
        title = "Soupe des essais",
        source = RecipeSource.MEALIE,
        steps = listOf(
            step("Couper les légumes en morceaux."),
            step("Mixer 10 s/vitesse 5."),
            step("Cuire 20 min/100 °C/vitesse 1."),
            step("Servir chaud."),
        ),
    )
    private val mine = StepMachine(StepProgram.MANUAL, 25 * 60, 1, 100)
    /** Refusé par les règles des étapes perso : vitesse 8 en chauffe. */
    private val refused = StepMachine(StepProgram.MANUAL, 60, 8, 100)

    @Test fun theOwnersSettingIsTrustedLikeAnOwnRecipe() {
        assertTrue(recipe.steps[2].needsReview)
        val a = RecipeAdjustments().withStep(recipe, 2, mine)
        val r = recipe.adjusted(a)
        assertEquals(mine, r.steps[2].machine)
        assertFalse(r.steps[2].needsReview)
        assertFalse(r.steps[2].reviewAlone)
        // Les autres étapes ne bougent pas, la recette reste la même recette.
        assertEquals(recipe.steps[1], r.steps[1])
        assertEquals(recipe.id, r.id)
        assertEquals(mapOf(2 to OwnStepSetting(mine)), OwnStepSettings.owned(recipe, a))
    }

    @Test fun unchangedTextKeepsTheSettingAcrossASync() {
        val a = RecipeAdjustments().withStep(recipe, 2, mine)
        // La même recette reçue de nouveau (nouvel objet, même texte ; espaces et casse ne comptent pas).
        val again = recipe.copy(steps = recipe.steps.toMutableList().also { it[2] = step("  cuire 20 min/100 °C/vitesse 1. ") })
        assertEquals(a, a.keepingStepsOf(again))
        assertEquals(mine, again.adjusted(a).steps[2].machine)
        assertFalse(again.adjusted(a).steps[2].needsReview)
    }

    @Test fun changedTextDropsTheSettingAndTheStepFallsBackToItsText() {
        val a = RecipeAdjustments().withStep(recipe, 2, mine)
        val rewritten = recipe.copy(steps = recipe.steps.toMutableList().also { it[2] = step("Cuire 30 min/90 °C/vitesse 1.") })
        val r = rewritten.adjusted(a)
        // Le texte a changé : son propre réglage, à vérifier, jamais l'ancien réglage du propriétaire.
        assertEquals(StepText.parse("Cuire 30 min/90 °C/vitesse 1."), r.steps[2].machine)
        assertTrue(r.steps[2].needsReview)
        assertTrue(OwnStepSettings.owned(rewritten, a).isEmpty())
        // À la synchro, l'entrée qui ne servira plus part ; le reste des adaptations reste.
        val withNote = a.withNote("plus de sel")
        val kept = withNote.keepingStepsOf(rewritten)
        assertTrue(kept.steps.isEmpty())
        assertEquals("plus de sel", kept.note)
        // Une étape sans réglage dans son texte n'en reçoit aucun.
        val plain = recipe.copy(steps = recipe.steps.toMutableList().also { it[2] = step("Laisser cuire doucement.") })
        assertNull(plain.adjusted(a).steps[2].machine)
    }

    @Test fun reorderingStepsKeepsEachSettingOnItsText() {
        val a = RecipeAdjustments().withStep(recipe, 2, mine).withStep(recipe, 0, StepMachine(StepProgram.MANUAL, 5, 4, 0))
        val moved = recipe.copy(steps = listOf(recipe.steps[2], recipe.steps[3], recipe.steps[0], recipe.steps[1]))
        assertEquals(a, a.keepingStepsOf(moved))
        val r = moved.adjusted(a)
        assertEquals(mine, r.steps[0].machine)
        assertEquals(StepMachine(StepProgram.MANUAL, 5, 4, 0), r.steps[2].machine)
        assertEquals(setOf(0, 2), OwnStepSettings.owned(moved, a).keys)
    }

    @Test fun twoStepsWithTheSameTextAreTellApartByTheirOrder() {
        val twice = recipe.copy(steps = listOf(step("Mixer 10 s/vitesse 5."), step("Ajouter le lait."), step("Mixer 10 s/vitesse 5.")))
        val keys = OwnStepSettings.keys(twice)
        assertEquals(keys[0] + "-2", keys[2])
        val a = RecipeAdjustments().withStep(twice, 2, StepMachine(StepProgram.MANUAL, 20, 6, 0))
        val r = twice.adjusted(a)
        assertEquals(StepText.parse("Mixer 10 s/vitesse 5."), r.steps[0].machine)
        assertEquals(StepMachine(StepProgram.MANUAL, 20, 6, 0), r.steps[2].machine)
    }

    @Test fun removingTheSettingGoesBackToTheRecipe() {
        val a = RecipeAdjustments().withStep(recipe, 2, mine)
        val back = a.withoutStep(recipe, 2)
        assertTrue(back.isEmpty)
        assertEquals(recipe.corrected(RecipeAdjustments()), recipe.adjusted(back))
        // Rien à retirer : inchangé.
        assertEquals(back, back.withoutStep(recipe, 1))
        assertEquals(back, back.withoutStep(recipe, 99))
    }

    @Test fun noSettingIsAChoiceToo() {
        val a = RecipeAdjustments().withStep(recipe, 1, null)
        val s = recipe.adjusted(a).steps[1]
        assertNull(s.machine)
        assertFalse(s.needsReview)
        assertEquals(mapOf(1 to OwnStepSetting(null)), OwnStepSettings.owned(recipe, a))
    }

    @Test fun theOwnRecipeStepRuleRefusesAndNothingIsSaved() {
        val p = OwnStepSettings.problem(recipe, 2, refused)
        assertNotNull(p)
        assertEquals(RecipeRules.STEP_MACHINE, p!!.code)
        assertEquals(2, p.step)
        // La même règle que l'éditeur d'étape des recettes perso.
        assertEquals(RecipeRules.step(Step(recipe.steps[2].text, refused))!!.message, p.message)
        try {
            RecipeAdjustments().withStep(recipe, 2, refused)
            fail("réglage refusé enregistré")
        } catch (_: IllegalArgumentException) {
        }
        assertNull(OwnStepSettings.problem(recipe, 2, mine))
        // Hors des recettes qui l'acceptent, ou hors des étapes : refusé.
        val lidl = recipe.copy(id = "web-1", source = RecipeSource.IMPORTED)
        assertEquals(OwnStepSettings.NOT_ALLOWED, OwnStepSettings.problem(lidl, 2, mine)!!.code)
        assertEquals(OwnStepSettings.NOT_ALLOWED, OwnStepSettings.problem(recipe, 9, mine)!!.code)
        assertEquals(OwnStepSettings.NOT_ALLOWED, OwnStepSettings.problem(recipe, -1, mine)!!.code)
        // Une recette qui ne l'accepte pas ignore aussi des réglages qui y seraient rangés.
        val a = RecipeAdjustments().withStep(recipe, 2, mine)
        assertEquals(lidl.steps, lidl.adjusted(a).steps)
    }

    @Test fun aSettingFromAPhoneStaysToBeCheckedOnItsOwnCard() {
        val a = RecipeAdjustments().withStep(recipe, 3, mine, SettingAuthor.PHONE)
        val s = recipe.adjusted(a).steps[3]
        assertEquals(mine, s.machine)
        assertTrue(s.needsReview)
        assertTrue(s.reviewAlone)
    }

    @Test fun storageRoundTrip() {
        val a = RecipeAdjustments(note = "à refaire", servings = 6)
            .withStep(recipe, 1, StepMachine(StepProgram.TURBO, pulses = 3))
            .withStep(recipe, 2, mine, SettingAuthor.PHONE)
            .withStep(recipe, 3, null)
        val back = RecipeAdjustments.decode(a.encode())
        assertEquals(a, back)
        // Sans réglage, le format reste celui d'avant (une version plus ancienne le relit à l'identique).
        val plain = RecipeAdjustments(note = "x")
        assertFalse(plain.encode().contains("steps"))
        assertEquals(plain, RecipeAdjustments.decode("""{"note":"x","qty":{},"weigh":{},"servings":0}"""))
    }

    @Test fun garbageInStorageIsIgnored() {
        val good = OwnStepSettings.keys(recipe)[2]
        val json = """{"note":"n","steps":{
            "$good":{"by":"robot","machine":{"program":"manuel","seconds":1500,"speed":1,"tempC":100}},
            "pas-une-cle":{"by":"robot","machine":null},
            "0123456789abcdef":{"by":"robot","machine":{"program":"inconnu","seconds":10}},
            "0123456789abcde0":{"by":"robot"},
            "0123456789abcde1":"texte",
            "0123456789abcde2":{"by":"martien","machine":null}
        }}"""
        val a = RecipeAdjustments.decode(json)
        assertEquals("n", a.note)
        assertEquals(setOf(good, "0123456789abcde2"), a.steps.keys)
        assertEquals(StepMachine(StepProgram.MANUAL, 1500, 1, 100), a.steps[good]!!.machine)
        // Auteur inconnu : traité comme un téléphone, donc à vérifier.
        assertEquals(SettingAuthor.PHONE, a.steps["0123456789abcde2"]!!.by)
        assertEquals(RecipeAdjustments(note = "n"), RecipeAdjustments.decode("""{"note":"n","steps":[1,2]}"""))
        assertTrue(RecipeAdjustments.decode("pas du json").isEmpty)
    }

    @Test fun fingerprintsIgnoreOnlyHowTheTextIsWritten() {
        val f = OwnStepSettings.fingerprint("Cuire 20 min/100 °C/vitesse 1.")
        assertEquals(16, f.length)
        assertEquals(f, OwnStepSettings.fingerprint("cuire  20 min/100 °C/vitesse 1.\n"))
        assertNotEquals(f, OwnStepSettings.fingerprint("Cuire 20 min/100 °C/vitesse 2."))
        // Écritures non latines : elles comptent aussi (pas de lettres retirées).
        assertNotEquals(OwnStepSettings.fingerprint("Варити 20 хв"), OwnStepSettings.fingerprint("Варити 30 хв"))
        assertEquals(OwnStepSettings.fingerprint("L’eau"), OwnStepSettings.fingerprint("l'eau"))
    }
}
