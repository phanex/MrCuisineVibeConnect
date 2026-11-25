package app.toque.core

import app.toque.core.companion.ApiError
import app.toque.core.companion.CookSession
import app.toque.core.companion.JournalPage
import app.toque.core.companion.RecipeDetail
import app.toque.core.companion.ServingsRequest
import app.toque.core.companion.StateWire
import app.toque.core.companion.Wires
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeAdjustments
import app.toque.core.recipes.Step
import app.toque.core.recipes.forServings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** POST /api/recipes/{id}/servings : le nombre de personnes choisi depuis le téléphone, enregistré sur le robot. */
class CompanionServingsTest {
    private val path = "/api/recipes/perso-veloute/servings"

    private fun detail(f: HubFixture, r: app.toque.core.companion.HttpResponse): RecipeDetail {
        assertEquals(String(r.body), 200, r.status)
        return RecipeDetail.fromMap(f.json(r))
    }

    private fun error(f: HubFixture, r: app.toque.core.companion.HttpResponse, status: Int): String {
        assertEquals(String(r.body), status, r.status)
        return ApiError.fromMap(f.json(r)).code
    }

    @Test fun changesTheNumberSavesItAndJournalsIt() {
        val f = HubFixture()
        val t = f.pair()
        // La fiche dit de quoi dessiner le réglage.
        val before = detail(f, f.req("GET", "/api/recipes/perso-veloute", t))
        assertEquals(4, before.servings)
        assertEquals(4, before.baseServings)
        assertEquals(2, before.minServings)
        assertEquals(8, before.maxServings)
        assertEquals(620, before.totalGrams)
        assertFalse(before.bowlTooFull)
        val rev = f.hub.currentRevision

        val six = detail(f, f.req("POST", path, t, mapOf("servings" to 6)))
        assertEquals(6, six.servings)
        assertEquals(4, six.baseServings)
        assertEquals("30", six.ingredients[0].items[0].qty)
        assertEquals("900", six.ingredients[0].items[1].qty)
        assertEquals(30, six.steps[1].weigh!!.grams)
        assertEquals(listOf("servings:perso-veloute:6"), f.host.calls)
        // Enregistré : la fiche et l'envoi au robot passent au nouveau nombre.
        assertEquals(6, detail(f, f.req("GET", "/api/recipes/perso-veloute", t)).servings)
        assertTrue(f.hub.currentRevision > rev)
        val journal = JournalPage.fromMap(f.json(f.req("GET", "/api/journal", t))).entries
        val line = journal.last()
        assertEquals("servings", line.kind)
        assertEquals("Nombre de personnes : Velouté de potimarron, 6 pers.", line.text)
        assertEquals("", line.alert)
        assertTrue(line.origin.self)

        // Retour au nombre de la recette.
        assertEquals(4, detail(f, f.req("POST", path, t, mapOf("servings" to 4))).servings)
        assertTrue(f.host.servings.isEmpty())
    }

    @Test fun refusals() {
        val f = HubFixture()
        val t = f.pair()
        // Sans jeton : 401, rien n'est fait.
        assertEquals(401, f.req("POST", path, null, mapOf("servings" to 6)).status)
        // Hors de la plage de la recette (moitié au double).
        assertEquals("servings_range", error(f, f.req("POST", path, t, mapOf("servings" to 9)), 400))
        assertEquals("servings_range", error(f, f.req("POST", path, t, mapOf("servings" to 1)), 400))
        // Corps strict.
        for (body in listOf(mapOf("servings" to "6"), mapOf("servings" to 6.5), mapOf("servings" to 0), mapOf("servings" to 100),
            mapOf("servings" to null), emptyMap<String, Any?>(), mapOf("servings" to 6, "other" to 1), mapOf("people" to 6),
            listOf(6), mapOf("servings" to true))) {
            val r = f.req("POST", path, t, body)
            assertEquals("$body", 400, r.status)
        }
        assertEquals(400, f.req("POST", path, t).status)
        // Recette inconnue, recette sans nombre de personnes.
        assertEquals("not_found", error(f, f.req("POST", "/api/recipes/inconnue/servings", t, mapOf("servings" to 2)), 404))
        assertEquals("no_servings", error(f, f.req("POST", "/api/recipes/r1/servings", t, mapOf("servings" to 2)), 409))
        assertEquals(0, detail(f, f.req("GET", "/api/recipes/r1", t)).baseServings)
        // GET sur la route : 405.
        assertEquals(405, f.req("GET", path, t).status)
        assertTrue(f.host.servings.isEmpty())

        // Recette en cours sur le robot : refusée, l'hôte n'est pas appelé.
        f.hub.updateSession(CookSession.of(CompanionData.veloute, 0))
        val calls = f.host.calls.size
        assertEquals("cooking", error(f, f.req("POST", path, t, mapOf("servings" to 6)), 409))
        assertEquals(calls, f.host.calls.size)
        // Une autre recette se règle toujours.
        f.hub.endSession(false)
        assertEquals(6, detail(f, f.req("POST", path, t, mapOf("servings" to 6))).servings)
    }

    @Test fun theWaitingRecipeFollows() {
        val f = HubFixture()
        val t = f.pair()
        fun state() = StateWire.fromMap(f.json(f.req("GET", "/api/state", t)))
        f.req("POST", "/api/waiting", t, mapOf("recipeId" to "perso-veloute"))
        assertEquals(4, state().waiting!!.servings)
        var fired = 0
        f.hub.onWaitingChanged { fired++ }
        val rev = state().revision
        detail(f, f.req("POST", path, t, mapOf("servings" to 8)))
        val s = state()
        assertEquals(8, s.waiting!!.servings)
        assertTrue(s.revision > rev)
        assertEquals(1, fired)
        assertEquals(8, f.hub.waitingRecipe!!.servings)
        // Changé sur la page de la recette du robot : l'appli le dit au hub.
        f.hub.recipeServingsChanged("perso-veloute", 3)
        assertEquals(3, state().waiting!!.servings)
        assertEquals(2, fired)
        // Une autre recette, ou le même nombre : rien.
        f.hub.recipeServingsChanged("r2", 5)
        f.hub.recipeServingsChanged("perso-veloute", 3)
        assertEquals(2, fired)
    }

    /** GET /api/recipes/{id}?base=1 : corrections gardées, nombre choisi ignoré, rien d'enregistré. */
    @Test fun baseSheet() {
        val f = HubFixture()
        val t = f.pair()
        val beurre = CompanionData.veloute.allIngredients[0]
        f.host.corrections["perso-veloute"] = RecipeAdjustments().withQty(beurre, "40")
        detail(f, f.req("POST", path, t, mapOf("servings" to 6)))
        val rev = f.hub.currentRevision
        val calls = f.host.calls.size
        val journal = JournalPage.fromMap(f.json(f.req("GET", "/api/journal", t))).entries.size

        val base = detail(f, f.req("GET", "/api/recipes/perso-veloute", t, query = mapOf("base" to "1")))
        assertEquals(4, base.servings)
        assertEquals(4, base.baseServings)
        assertEquals("40", base.ingredients[0].items[0].qty) // la correction, pour 4
        assertEquals(40, base.steps[1].weigh!!.grams)
        assertEquals("fr", base.language)
        // De là, le téléphone retrouve la fiche du robot pour 6.
        val six = detail(f, f.req("GET", "/api/recipes/perso-veloute", t))
        assertEquals(6, six.servings)
        assertEquals("60", six.ingredients[0].items[0].qty)
        assertEquals(six, Wires.forServings(base, 6))
        assertEquals(six, detail(f, f.req("GET", "/api/recipes/perso-veloute", t, query = mapOf("base" to "0"))))
        // Rien d'enregistré ni de journalisé.
        assertEquals(6, f.host.servings["perso-veloute"])
        assertEquals(rev, f.hub.currentRevision)
        assertEquals(calls, f.host.calls.size)
        assertEquals(journal, JournalPage.fromMap(f.json(f.req("GET", "/api/journal", t))).entries.size)

        // Autre valeur : refusée ; recette inconnue : 404 ; sans jeton : 401.
        for (v in listOf("2", "true", "", "yes", "01")) {
            val r = f.req("GET", "/api/recipes/perso-veloute", t, query = mapOf("base" to v))
            assertEquals(v, "bad_request", error(f, r, 400))
        }
        assertEquals("not_found", error(f, f.req("GET", "/api/recipes/inconnue", t, query = mapOf("base" to "1")), 404))
        assertEquals(401, f.req("GET", "/api/recipes/perso-veloute", null, query = mapOf("base" to "1")).status)
        // Recette sans nombre de personnes : la fiche telle quelle, sans réglage.
        assertEquals(0, detail(f, f.req("GET", "/api/recipes/r1", t, query = mapOf("base" to "1"))).baseServings)
    }

    @Test fun bowlTooFullAndStrictBody() {
        val big = Recipe(id = "x", title = "Grande soupe", servings = 2,
            ingredients = listOf(IngredientGroup(items = listOf(Ingredient("1500", "g", "eau"), Ingredient("2", "", "oignons")))),
            steps = listOf(Step("Mixer.")))
        val base = Wires.recipe(big, 2)
        assertFalse(base.bowlTooFull)
        assertEquals(1500, base.totalGrams)
        val four = Wires.recipe(big.forServings(4), 2)
        assertTrue(four.bowlTooFull)
        assertEquals(3000, four.totalGrams)
        assertEquals(1, four.minServings)
        assertEquals(4, four.maxServings)
        // Sans nombre d'origine (robot plus ancien, ou recette sans personnes) : pas de réglage.
        assertEquals(0, Wires.recipe(big).baseServings)
        assertEquals(0, RecipeDetail.fromMap(mapOf("servings" to 4)).baseServings)
        // Lecture stricte du corps.
        assertEquals(3, ServingsRequest.fromMap(mapOf("servings" to 3L)).servings)
        assertEquals(3, ServingsRequest.fromMap(mapOf("servings" to 3.0)).servings)
        try {
            ServingsRequest.fromMap(mapOf("servings" to 3.5))
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }
}
