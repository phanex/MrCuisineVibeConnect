package app.toque.core

import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeAdjustments
import app.toque.core.recipes.RecipeRules
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.core.recipes.UserRecipes
import app.toque.core.recipes.Weigh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Les limites de l'éditeur de recettes du robot, en un seul endroit (écran du robot et téléphone). */
class RecipeRulesTest {
    private val ok = Recipe(id = "perso-x", title = "Soupe", servings = 4, totalMinutes = 30, steps = listOf(Step("Mixer.")))
    /** Réglage refusé par les règles : vitesse 8 en chauffe. */
    private val hot = StepMachine(StepProgram.MANUAL, 60, 8, 100)

    @Test fun aValidRecipePasses() {
        assertNull(RecipeRules.check(ok))
        assertNull(RecipeRules.check(ok.copy(steps = emptyList(), ingredients = listOf(IngredientGroup(items = listOf(Ingredient(name = "Sel")))))))
    }

    @Test fun everyRecipeLimitHasItsCodeFieldAndTheEditorsMessage() {
        fun p(r: Recipe) = RecipeRules.check(r)!!
        p(ok.copy(title = "  ")).let { assertEquals(listOf("title_missing", "title", -1, "Donnez un titre à la recette"), listOf(it.code, it.field, it.step, it.message)) }
        for (n in listOf(0, 21, -1)) p(ok.copy(servings = n)).let {
            assertEquals(listOf("servings_range", "servings", "De 1 à 20 personnes"), listOf(it.code, it.field, it.message))
        }
        assertNull(RecipeRules.check(ok.copy(servings = 1)))
        assertNull(RecipeRules.check(ok.copy(servings = 20)))
        p(ok.copy(totalMinutes = 601)).let { assertEquals(listOf("total_time_range", "totalMinutes", "Durée totale de 0 à 600 min"), listOf(it.code, it.field, it.message)) }
        p(ok.copy(totalMinutes = -5)).let { assertEquals("total_time_range", it.code) }
        assertNull(RecipeRules.check(ok.copy(totalMinutes = 0)))
        assertNull(RecipeRules.check(ok.copy(totalMinutes = 600)))
        p(ok.copy(difficulty = 4)).let { assertEquals(listOf("difficulty_range", "difficulty"), listOf(it.code, it.field)) }
        p(ok.copy(steps = emptyList())).let {
            assertEquals(listOf("content_empty", "content", "Ajoutez au moins un ingrédient ou une étape"), listOf(it.code, it.field, it.message))
        }
    }

    @Test fun stepsAreJudgedLikeTheStepEditorWithTheirRank() {
        val r = ok.copy(steps = listOf(Step("Couper."), Step(" ")))
        RecipeRules.check(r)!!.let {
            assertEquals(listOf("step_empty", "steps", 1), listOf(it.code, it.field, it.step))
            assertEquals("Étape 2 : Écrivez ce qu'il faut faire", it.message)
            assertEquals("Écrivez ce qu'il faut faire", it.detail)
        }
        val heavy = ok.copy(steps = listOf(Step("Peser.", weigh = Weigh("", RecipeAdjustments.MAX_GRAMS + 1))))
        RecipeRules.check(heavy)!!.let {
            assertEquals("weigh_range", it.code)
            assertEquals("Étape 1 : Entrez un poids entre 1 et 5000 g", it.message)
        }
        assertNull(RecipeRules.check(ok.copy(steps = listOf(Step("Peser.", weigh = Weigh("", 5000))))))
        assertEquals("weigh_range", RecipeRules.check(ok.copy(steps = listOf(Step("Peser.", weigh = Weigh("", 0)))))!!.code)
        // Un réglage seul, ou une pesée seule, fait une étape.
        assertNull(RecipeRules.check(ok.copy(steps = listOf(Step("", StepMachine(StepProgram.MANUAL, 60, 2))))))
        assertNull(RecipeRules.check(ok.copy(steps = listOf(Step("", weigh = Weigh("", 20))))))
        val machine = RecipeRules.check(ok.copy(steps = listOf(Step("Mixer.", hot))))!!
        assertEquals(listOf("step_machine", 0), listOf(machine.code, machine.step))
        assertEquals("Étape 1 : " + hot.violations().first().message, machine.message)
    }

    @Test fun theStepEditorCheckKeepsItsOrderAndMessages() {
        assertEquals("Écrivez ce qu'il faut faire", RecipeRules.step(Step(""))!!.message)
        assertEquals("Entrez un poids entre 1 et 5000 g", RecipeRules.step(Step("", weigh = Weigh("", 9000)))!!.message)
        assertEquals(hot.violations().first().message, RecipeRules.step(Step("Mixer.", hot, Weigh("", 20)))!!.message)
        // Le poids passe avant le réglage, comme dans l'écran d'étape.
        assertEquals("weigh_range", RecipeRules.step(Step("Mixer.", hot, Weigh("", 9000)))!!.code)
        assertNull(RecipeRules.step(Step("Mixer.", hot), machines = false))
    }

    @Test fun whatWasAlreadyStoredIsNotJudgedAgain() {
        val stored = ok.copy(servings = 24, totalMinutes = 720, steps = listOf(Step("Peser.", weigh = Weigh("", 6000)), Step("Mixer.")))
        // Inchangé : enregistrable, comme sur le robot (une copie Lidl à 24 personnes reste à 24).
        assertNull(RecipeRules.check(stored, stored))
        assertNull(RecipeRules.check(stored.copy(steps = stored.steps.reversed()), stored))
        // Changé : jugé.
        assertEquals("servings_range", RecipeRules.check(stored.copy(servings = 25), stored)!!.code)
        assertNull(RecipeRules.check(stored.copy(servings = 20), stored))
        assertEquals("total_time_range", RecipeRules.check(stored.copy(totalMinutes = 700), stored)!!.code)
        val edited = stored.copy(steps = listOf(Step("Peser la farine.", weigh = Weigh("", 6000)), Step("Mixer.")))
        assertEquals("weigh_range", RecipeRules.check(edited, stored)!!.code)
        // Sans recette d'avant (création) : tout est jugé.
        assertEquals("servings_range", RecipeRules.check(stored)!!.code)
    }

    @Test fun machineSettingsCanBeLeftToTheCooking() {
        val r = ok.copy(steps = listOf(Step("Mixer.", hot)))
        assertEquals("step_machine", RecipeRules.check(r, r)!!.code)
        assertNull(RecipeRules.check(r, r, machines = false))
        assertNull(RecipeRules.check(r, null, machines = false))
    }

    @Test fun problemStaysWhatTheEditorAlwaysSaid() {
        assertEquals("Donnez un titre à la recette", UserRecipes.problem(UserRecipes.blank()))
        assertEquals("Ajoutez au moins un ingrédient ou une étape", UserRecipes.problem(Recipe(id = "perso-x", title = "Soupe")))
        assertNull(UserRecipes.problem(Recipe(id = "perso-x", title = "Soupe", steps = listOf(Step("Mixer.")))))
        // Sans nombre de personnes (servings 0), une recette reste enregistrable comme avant.
        assertNull(UserRecipes.problem(Recipe(id = "perso-x", title = "Soupe", servings = 0, totalMinutes = 900, steps = listOf(Step("Mixer.")))))
        assertEquals("Étape 1 : " + hot.violations().first().message, UserRecipes.problem(ok.copy(steps = listOf(Step("Mixer.", hot)))))
    }
}
