package app.toque.core

import app.toque.core.json.Json
import app.toque.core.json.obj
import app.toque.core.mealie.MealieClient
import app.toque.core.mealie.MealieRecipes
import app.toque.core.mealie.MealieSync
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeChip
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.SearchIndex
import app.toque.core.recipes.StepProgram
import app.toque.core.robot.RobotProfiles
import app.toque.core.site.ToqueSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MealieTest {
    private class Fake : ToqueSite.Transport {
        class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String?)
        val calls = ArrayList<Call>()
        val replies = LinkedHashMap<String, (Call) -> ToqueSite.Reply>()
        var down = false

        override fun send(method: String, url: String, headers: Map<String, String>, body: String?): ToqueSite.Reply {
            if (down) throw IOException("hors ligne")
            val c = Call(method, url, headers, body)
            calls += c
            val path = url.removePrefix(BASE)
            val key = replies.keys.firstOrNull { path == it || path.startsWith("$it?") } ?: return ToqueSite.Reply(404, """{"detail":"Not Found"}""")
            return replies.getValue(key)(c)
        }
    }

    private class Store : MealieSync.Store {
        override var index: Map<String, String> = emptyMap()
        val recipes = LinkedHashMap<String, Recipe>()
        override fun mealieIds() = recipes.keys.toSet()
        override fun save(recipes: List<Recipe>) { for (r in recipes) this.recipes[r.id] = r }
        override fun removeMealieExcept(keep: Set<String>) { recipes.keys.retainAll(keep) }
    }

    companion object {
        const val BASE = "http://mealie.lan:9000"
        const val A = "6f1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        const val B = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4e"
    }

    private fun summary(id: String, slug: String, updated: String, image: String? = "Ab3x") =
        """{"id":"$id","slug":"$slug","name":"$slug","image":${image?.let { "\"$it\"" } ?: "null"},"updatedAt":"$updated"}"""

    private fun list(vararg items: String, total: Int = items.size, pages: Int = 1) =
        ToqueSite.Reply(200, """{"page":1,"per_page":100,"total":$total,"total_pages":$pages,"items":[${items.joinToString(",")}],"next":null,"previous":null}""")

    private val soup = """{
      "id":"$A","slug":"veloute","name":"Velouté de potiron","image":"Ab3x",
      "recipeServings":4,"recipeYieldQuantity":0,"recipeYield":"",
      "totalTimeSeconds":2700,"prepTimeSeconds":600,
      "description":"Un **velouté** tout doux.",
      "recipeCategory":[{"name":"Soupes"}],"tags":[{"name":"Hiver"},{"name":"Végé"}],
      "recipeIngredient":[
        {"title":"Légumes","quantity":800,"unit":{"name":"gramme","abbreviation":"g","useAbbreviation":true},"food":{"name":"potiron"},"note":"en cubes"},
        {"quantity":1.5,"unit":null,"food":{"name":"oignon","pluralName":"oignons"},"note":""},
        {"title":"Liquide","quantity":0,"unit":null,"food":null,"note":"500 g d'eau"},
        {"quantity":0,"food":null,"note":"sel"}
      ],
      "recipeInstructions":[
        {"title":"","text":"Mixer l'oignon 5 sec/vitesse 5."},
        {"title":"Cuisson","text":"Ajouter le potiron et l'eau, cuire **25 min/100°C/vitesse 1**."},
        {"text":"Servir avec de la [crème](https://exemple.org)."}
      ],
      "notes":[{"title":"Astuce","text":"Ajouter du lait de coco."}],
      "orgURL":"https://exemple.org/veloute",
      "settings":{"disableAmount":false}
    }"""

    @Test fun recipeConversion() {
        val r = MealieRecipes.toRecipe(Json.parse(soup).obj(), RobotProfiles.MC_CONNECT)!!
        assertEquals("mealie-$A", r.id)
        assertEquals(RecipeSource.MEALIE, r.source)
        assertEquals("Velouté de potiron", r.title)
        assertEquals("Soupes", r.category)
        assertEquals(listOf("Hiver", "Végé"), r.tags)
        assertEquals(4, r.servings)
        assertEquals(45, r.totalMinutes)
        assertEquals(10, r.prepMinutes)
        assertEquals("mealie-$A-Ab3x.webp", r.image)
        assertEquals(listOf("Légumes", "Liquide"), r.ingredients.map { it.title })
        val veg = r.ingredients[0].items
        assertEquals(listOf("800", "g", "potiron", "en cubes"), veg[0].let { listOf(it.qty, it.unit, it.name, it.note) })
        assertEquals("1 ½", veg[1].qty)
        assertEquals("oignons", veg[1].name)
        val water = r.ingredients[1].items[0]
        assertEquals(listOf("500", "g", "d'eau"), listOf(water.qty, water.unit, water.name))
        assertEquals("sel", r.ingredients[1].items[1].name)
        assertEquals(3, r.steps.size)
        val s0 = r.steps[0]
        assertEquals(5, s0.machine!!.speed)
        assertTrue(s0.needsReview)
        assertEquals("Cuisson\nAjouter le potiron et l'eau, cuire 25 min/100°C/vitesse 1.", r.steps[1].text)
        assertEquals(1500, r.steps[1].machine!!.seconds)
        assertEquals("Servir avec de la crème.", r.steps[2].text)
        assertNull(r.steps[2].machine)
        assertFalse(r.steps[2].needsReview)
        assertTrue(r.notes.startsWith("Un velouté tout doux.\n\nAstuce\nAjouter du lait de coco."))
        assertTrue(r.notes.endsWith("https://exemple.org/veloute"))
    }

    @Test fun olderMealieAndOddValues() {
        val m = Json.parse("""{"id":"$B","slug":"cookies","name":"  Cookies ","image":null,"recipeYield":"12 cookies",
            "totalTime":"PT1H15M","prepTime":"20 minutes","settings":{"disableAmount":true},
            "recipeIngredient":[{"quantity":1,"note":"2 eggs","food":null,"unit":null},{"quantity":1,"note":"salt"}],
            "recipeInstructions":[{"text":"Bake 12 min."}]}""").obj()
        val r = MealieRecipes.toRecipe(m, RobotProfiles.MC_CONNECT)!!
        assertEquals("Cookies", r.title)
        assertEquals(12, r.servings)
        assertEquals("cookies", r.servingUnit)
        assertEquals(75, r.totalMinutes)
        assertEquals(20, r.prepMinutes)
        assertEquals("", r.image)
        // Quantités désactivées dans Mealie : la note telle quelle, pas de « 1 » devant.
        assertEquals("salt", r.ingredients[0].items[1].name)
        assertEquals("", r.ingredients[0].items[1].qty)
        assertEquals(4, MealieRecipes.toRecipe(Json.parse("""{"id":"$B","name":"x","recipeYield":"4 servings"}""").obj())!!.servings)
        assertEquals("", MealieRecipes.toRecipe(Json.parse("""{"id":"$B","name":"x","recipeYield":"4 servings"}""").obj())!!.servingUnit)
        assertNull(MealieRecipes.toRecipe(Json.parse("""{"id":"pas-un-uuid","name":"x"}""").obj()))
        assertNull(MealieRecipes.toRecipe(Json.parse("""{"id":"$B","name":"  "}""").obj()))
    }

    @Test fun addressesAsTyped() {
        assertEquals("http://mealie.lan:9000", MealieClient.normalize(" mealie.lan:9000/ "))
        assertEquals("https://recettes.exemple.org", MealieClient.normalize("HTTPS://Recettes.exemple.org/g/home/r/veloute"))
        assertEquals("http://192.168.1.20:9925", MealieClient.normalize("http://192.168.1.20:9925/login?redirect=/"))
        assertEquals("https://exemple.org/mealie", MealieClient.normalize("https://exemple.org/mealie/g/home"))
        assertEquals("http://[fd00::2]:9000", MealieClient.normalize("http://[fd00::2]:9000/api/app/about"))
        assertNull(MealieClient.normalize(""))
        assertNull(MealieClient.normalize("ftp://exemple.org"))
        assertNull(MealieClient.normalize("mealie maison"))
        assertNull(MealieClient.normalize("http://user@exemple.org"))
    }

    @Test fun linkCreatesARobotTokenWithoutKeepingThePassword() {
        val t = Fake()
        t.replies["/api/app/about"] = { ToqueSite.Reply(200, """{"production":true,"version":"v3.4.0"}""") }
        t.replies["/api/auth/token"] = { ToqueSite.Reply(200, """{"access_token":"session","token_type":"bearer"}""") }
        t.replies["/api/users/api-tokens"] = { ToqueSite.Reply(201, """{"name":"Toque (robot)","id":7,"token":"longue"}""") }
        assertEquals("v3.4.0", MealieClient(BASE, "", t).version())
        val session = MealieClient.login(BASE, " maxime ", "p&ss w", t)
        assertEquals("session", session)
        val login = t.calls[1]
        assertEquals("application/x-www-form-urlencoded", login.headers["Content-Type"])
        assertEquals("username=maxime&password=p%26ss+w&remember_me=false", login.body)
        val (token, id) = MealieClient.createToken(BASE, session, t)
        assertEquals("longue" to 7L, token to id)
        val create = t.calls[2]
        assertEquals("Bearer session", create.headers["Authorization"])
        assertEquals("Toque (robot)", Json.parse(create.body!!).obj()["name"])
        // Mauvais mot de passe ; pas un serveur Mealie.
        t.replies["/api/auth/token"] = { ToqueSite.Reply(401, """{"detail":"Unauthorized"}""") }
        val e = runCatching { MealieClient.login(BASE, "maxime", "faux", t) }.exceptionOrNull() as MealieClient.MealieException
        assertEquals(401, e.status)
        t.replies["/api/app/about"] = { ToqueSite.Reply(200, "<html>routeur</html>") }
        assertEquals(0, (runCatching { MealieClient(BASE, "", t).version() }.exceptionOrNull() as MealieClient.MealieException).status)
    }

    @Test fun syncFetchesOnlyChangesAndRemovesWhatIsGone() {
        val t = Fake()
        val store = Store()
        var items = listOf(summary(A, "veloute", "2026-11-01T10:00:00"), summary(B, "cookies", "2026-11-02T10:00:00"))
        t.replies["/api/recipes"] = { list(*items.toTypedArray()) }
        t.replies["/api/recipes/veloute"] = { ToqueSite.Reply(200, soup) }
        t.replies["/api/recipes/cookies"] = { ToqueSite.Reply(200, """{"id":"$B","slug":"cookies","name":"Cookies","image":"Ab3x"}""") }
        val client = MealieClient(BASE, "robot", t)
        val first = MealieSync(client, store).run()
        assertTrue(first.message, first.ok)
        assertEquals(2, first.added)
        assertEquals(setOf("mealie-$A", "mealie-$B"), store.mealieIds())
        assertEquals("Bearer robot", t.calls[0].headers["Authorization"])
        assertTrue(t.calls[0].url.contains("perPage=100") && t.calls[0].url.contains("orderBy=name"))

        // Rien n'a changé : seule la liste est demandée.
        t.calls.clear()
        val same = MealieSync(client, store).run()
        assertEquals(0, same.added + same.updated + same.removed)
        assertEquals(1, t.calls.size)

        // Photo changée sur une recette, l'autre retirée de Mealie.
        items = listOf(summary(A, "veloute", "2026-11-01T10:00:00", image = "Zz9q"))
        t.calls.clear()
        val next = MealieSync(client, store).run()
        assertEquals(1, next.updated)
        assertEquals(1, next.removed)
        assertEquals(setOf("mealie-$A"), store.mealieIds())
        assertEquals(setOf(A), store.index.keys)
        assertEquals(listOf("/api/recipes?", "/api/recipes/veloute"), t.calls.map { it.url.removePrefix(BASE).substringBefore('?') + if ('?' in it.url) "?" else "" })
    }

    @Test fun partialListRemovesNothingAndErrorsKeepRecipes() {
        val t = Fake()
        val store = Store()
        store.recipes["mealie-$B"] = Recipe("mealie-$B", "Cookies", source = RecipeSource.MEALIE)
        // Le serveur annonce 2 recettes mais n'en liste qu'une : rien n'est retiré.
        t.replies["/api/recipes"] = { list(summary(A, "veloute", "x"), total = 2) }
        t.replies["/api/recipes/veloute"] = { ToqueSite.Reply(200, soup) }
        val r = MealieSync(MealieClient(BASE, "robot", t), store).run()
        assertTrue(r.ok)
        assertEquals(0, r.removed)
        assertTrue("mealie-$B" in store.mealieIds())

        // Jeton révoqué : message clair, recettes gardées.
        t.replies["/api/recipes"] = { ToqueSite.Reply(401, """{"detail":"Unauthorized"}""") }
        val denied = MealieSync(MealieClient(BASE, "robot", t), store).run()
        assertFalse(denied.ok)
        assertEquals(2, store.recipes.size)
        t.down = true
        assertFalse(MealieSync(MealieClient(BASE, "robot", t), store).run().ok)

        // Une recette illisible est sautée, les autres arrivent.
        t.down = false
        val fresh = Store()
        t.replies["/api/recipes"] = { list(summary(A, "veloute", "x"), summary(B, "cookies", "y")) }
        t.replies["/api/recipes/cookies"] = { ToqueSite.Reply(500, """{"detail":"boom"}""") }
        val partial = MealieSync(MealieClient(BASE, "robot", t), fresh).run()
        assertTrue(partial.ok)
        assertEquals(setOf("mealie-$A"), fresh.mealieIds())
        assertFalse(B in fresh.index)
    }

    @Test fun photosAndChips() {
        assertEquals("mealie-$A-Ab3x.webp", MealieRecipes.photoName(A, "Ab3x"))
        assertEquals("mealie-$A.webp", MealieRecipes.photoName(A, "../"))
        assertEquals(A, MealieRecipes.photoUuid("mealie-$A-Ab3x.webp"))
        assertNull(MealieRecipes.photoUuid("r3-0123456789abcdef.webp"))
        assertEquals("$BASE/api/media/recipes/$A/images/min-original.webp", MealieClient(BASE, "", Fake()).photoUrl(A))
        val index = SearchIndex(listOf(Recipe("mealie-$A", "Velouté", source = RecipeSource.MEALIE), Recipe("1", "Riz")))
        assertTrue(RecipeChip.MEALIE in RecipeChip.list(index))
        assertEquals("Mealie", RecipeChip.MEALIE.label)
        assertEquals(listOf("mealie-$A"), RecipeChip.select(index, RecipeChip.MEALIE, "").map { it.entry.id })
        assertFalse(RecipeChip.MEALIE in RecipeChip.list(SearchIndex(listOf(Recipe("1", "Riz")))))
        assertEquals(StepProgram.MANUAL, StepProgram.MANUAL)
    }
}
