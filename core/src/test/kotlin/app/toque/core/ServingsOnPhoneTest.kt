package app.toque.core

import app.toque.core.companion.RecipeDetail
import app.toque.core.companion.Wires
import app.toque.core.companion.demo.DemoCatalog
import app.toque.core.i18n.I18n
import app.toque.core.i18n.Language
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.McOnline
import app.toque.core.recipes.Portions
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeAdjustments
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.Weigh
import app.toque.core.recipes.adjusted
import app.toque.core.recipes.corrected
import app.toque.core.recipes.forServings
import app.toque.core.recipes.unlistedWeighings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le nombre de personnes calculé sur le téléphone ([Wires.forServings]) donne exactement la fiche
 * que le robot répondrait, pour toutes les recettes d'essai, tous les nombres permis, en français
 * et en anglais, avec ou sans corrections de l'utilisateur.
 */
class ServingsOnPhoneTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().use { it.readText() }

    /** Cas difficiles : comptes et accords, « (60 g) », unités en mots, milliers, décimales, pesée hors liste, langue propre. */
    private val tricky = Recipe(
        id = "essai-portions", title = "Pâte d'essai", servings = 4,
        ingredients = listOf(
            IngredientGroup("Pâte", listOf(Ingredient("500", "g", "farine"), Ingredient("0,5", "l", "lait"), Ingredient("3", "", "œufs"),
                Ingredient("1", "c. à s.", "huile"), Ingredient(name = "sel"), Ingredient("2", "", "échalotes (60 g)"),
                Ingredient("7", "g", "levure boulangère"), Ingredient("1", "", "oignon", "émincé"), Ingredient("1", "pincée", "muscade"),
                Ingredient("1/2", "", "citron"), Ingredient("1 ½", "", "gousse d'ail"))),
            IngredientGroup("Garniture", listOf(Ingredient("1 000", "g", "tomates"), Ingredient("1.5", "kg", "pommes de terre"),
                Ingredient("", "", "100 g d'emmental"))),
        ),
        steps = listOf(
            Step("Mettre 300 g de farine et 7 g de levure dans le bol.", weigh = Weigh("farine", 300)),
            Step("Ajouter 200 g de farine, 500 ml de lait et 1 000 g d'eau.", weigh = Weigh("eau (1 000 g)", 1000)),
            Step("Cuire dans un moule de 24 cm, 2 gousses d'ail, 3 l'oignon.", StepMachine(seconds = 1200, speed = 1, tempC = 100)),
            Step("", weigh = Weigh("Bouillon 250 g", 250)),
            Step("Ajouter l'emmental.", weigh = Weigh("emmental", 100)),
        ),
    )

    private fun recipes(): List<Recipe> {
        val file = RecipeCodec.decodeFile(fixture("recettes-test.json"), RecipeSource.IMPORTED).first
        val online = listOfNotNull(McOnline.parseRecipe(fixture("mc-online-recipe.json")))
        val base = DemoCatalog.recipes + file + online + CompanionData.recipes + tricky +
            tricky.copy(id = "essai-anglais", language = "en") + tricky.copy(id = "essai-pots", servings = 3, servingUnit = "pots")
        // Corrections de l'utilisateur : la première quantité de chaque recette et les pesées hors liste.
        val corrected = base.filter { it.servings > 0 }.map { r ->
            var a = RecipeAdjustments()
            r.corrected(RecipeAdjustments()).allIngredients.firstOrNull { it.qty.isNotBlank() }?.let { i ->
                a = a.withQty(i, if (i.unit.isBlank()) "5" else "130")
            }
            for ((si, _) in r.unlistedWeighings()) r.steps[si].weigh?.let { w -> a = a.withWeigh(si, w, w.grams + 35) }
            r.copy(id = r.id + "-corrigee") to a
        }
        // Le robot rend ces recettes par adjusted : même résultat que corrected puis forServings.
        for ((r, a) in corrected) for (n in Portions.range(r.servings))
            assertEquals(r.adjusted(a.withServings(n, r.servings)), r.corrected(a).forServings(n))
        return base + corrected.map { (r, a) -> r.corrected(a) }
    }

    private fun check(language: String) {
        val before = I18n.current
        I18n.current = Language(language, language, emptyMap())
        try {
            var cases = 0
            var unlisted = 0
            for (r in recipes()) {
                if (r.servings <= 0) continue
                val base = Wires.recipe(r, r.servings)
                if (r.unlistedWeighings().isNotEmpty()) unlisted++
                for (n in Portions.range(r.servings)) {
                    val robot = Wires.recipe(r.forServings(n), r.servings)
                    assertEquals("${r.id} pour $n ($language)", robot, Wires.forServings(base, n))
                    // Et après le passage sur le réseau.
                    assertEquals(robot, Wires.forServings(RecipeDetail.fromMap(base.toMap()), n))
                    cases++
                }
            }
            assertTrue(cases > 200)
            assertTrue(unlisted > 3)
        } finally {
            I18n.current = before
        }
    }

    @Test fun sameSheetAsTheRobotInFrench() = check("fr")

    @Test fun sameSheetAsTheRobotInEnglish() = check("en")

    @Test fun nullWhenItCannotBeExact() {
        val base = Wires.recipe(tricky, 4)
        assertSame(base, Wires.forServings(base, 4))
        // Hors de la plage, fiche déjà adaptée, pas de réglage.
        assertNull(Wires.forServings(base, 9))
        assertNull(Wires.forServings(base, 1))
        assertNull(Wires.forServings(Wires.recipe(tricky.forServings(6), 4), 8))
        assertNull(Wires.forServings(Wires.recipe(tricky), 6))
        assertNull(Wires.forServings(Wires.recipe(tricky.copy(servings = 0), 0), 2))
    }

    /** Robot plus ancien : pas de langue sur la fiche, le téléphone prend la sienne. */
    @Test fun withoutLanguageThePhoneUsesItsOwn() {
        val base = Wires.recipe(tricky, 4)
        assertEquals("fr", base.language)
        assertEquals("en", Wires.recipe(tricky.copy(language = "en"), 4).language)
        val old = base.copy(language = "")
        assertEquals(Wires.recipe(tricky.forServings(6), 4).ingredients, Wires.forServings(old, 6)!!.ingredients)
        assertEquals("0,75", Wires.forServings(old, 6)!!.ingredients[0].items[1].qty)
        val before = I18n.current
        I18n.current = Language("en", "English", emptyMap())
        try {
            assertEquals("0,75", Wires.forServings(old, 6)!!.ingredients[0].items[1].qty) // écrit avec une virgule : elle reste
            assertEquals("oignon", Wires.forServings(old, 8)!!.ingredients[0].items[7].name) // pas d'accord hors du français
        } finally {
            I18n.current = before
        }
        assertEquals("oignons", Wires.forServings(old, 8)!!.ingredients[0].items[7].name)
    }

    @Test fun languageTravels() {
        val d = Wires.recipe(tricky.copy(language = "de-DE"), 4)
        assertEquals("de-DE", RecipeDetail.fromMap(d.toMap()).language)
        assertEquals("", RecipeDetail.fromMap(mapOf("servings" to 4)).language)
    }
}
