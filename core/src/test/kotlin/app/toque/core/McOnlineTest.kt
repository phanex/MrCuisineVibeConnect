package app.toque.core

import app.toque.core.recipes.McCategories
import app.toque.core.recipes.McOnline
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.StepProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conversion des recettes de l'API monsieur-cuisine.com (données d'essai inventées, même structure). */
class McOnlineTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().use { it.readText() }

    @Test fun page() {
        val p = McOnline.parsePage(fixture("mc-online-page.json"))
        assertEquals(3, p.total)
        assertEquals(1, p.totalPages)
        assertEquals(listOf(900001L, 900002L, 900003L), p.entries.map { it.apiId })
        assertEquals("2026-04-19 09:00:02", p.entries[0].updated)
        assertEquals("", p.entries[2].updated)
        // Auteur et note, aussi dans la liste (pour garder les notes à jour sans tout retélécharger).
        assertEquals("Chef d'essai", p.entries[0].author)
        assertEquals(4.4, p.entries[0].rating, 0.001)
        assertEquals(500, p.entries[0].ratingCount)
        assertEquals(3.5, p.entries[1].rating, 0.001)
        assertEquals(0, p.entries[2].ratingCount)
        assertEquals("2026-04-19 09:00:02|4.40|500|Chef d'essai", p.entries[0].stamp)
        assertEquals("2026-04-19 09:00:02", McOnline.updatedOf(p.entries[0].stamp))
        assertEquals("2025-01-02 10:00:00", McOnline.updatedOf("2025-01-02 10:00:00"))
    }

    @Test fun recipe() {
        val r = McOnline.parseRecipe(fixture("mc-online-recipe.json"))!!
        assertEquals("web-900001", r.id)
        assertEquals("Soupe d'essai", r.title)
        assertEquals("Soupes", r.category)
        assertEquals(listOf("Végétarien"), r.tags)
        assertEquals(4, r.servings)
        assertEquals(15, r.prepMinutes)
        assertEquals(40, r.totalMinutes)
        assertEquals(2, r.difficulty)
        assertEquals("https://cdn.example.test/uploads/2021/11/123essai.jpg", r.image)
        assertEquals("Astuce : servir chaud.", r.notes)
        assertEquals("Chef d'essai", r.author)
        assertEquals(4.4, r.rating, 0.001)
        assertEquals(500, r.ratingCount)
        // Le format de stockage garde l'auteur et la note.
        val back = RecipeCodec.decode(RecipeCodec.encode(r))!!
        assertEquals(r.author, back.author)
        assertEquals(r.rating, back.rating, 0.001)
        assertEquals(r.ratingCount, back.ratingCount)

        // Ingrédients : sans groupe d'abord (dans l'ordre), puis les groupes ; sans nom = ignoré.
        assertEquals(listOf("", "POUR LA GARNITURE"), r.ingredients.map { it.title })
        assertEquals(listOf("1 oignon", "200 g carottes"), r.ingredients[0].items.map { it.display })
        assertEquals("10 g persil", r.ingredients[1].items.single().display)

        val s = r.steps
        assertEquals(9, s.size)
        // Le réglage « Mode » est rattaché à l'étape qui décrit le geste.
        assertEquals("Mettre l'oignon dans le bol mixeur.", s[0].text)
        assertEquals(StepProgram.MANUAL, s[0].machine!!.program)
        assertEquals(5, s[0].machine!!.seconds)
        assertEquals(5, s[0].machine!!.speed)
        assertTrue(s[0].needsReview)
        // Balance.
        assertEquals(200, s[1].weigh!!.grams)
        assertNull(s[1].machine)
        // Réglage après une pesée : étape à part, avec chauffe et sens inverse.
        with(s[2].machine!!) {
            assertEquals(100, tempC); assertEquals(1, speed); assertEquals(1200, seconds); assertTrue(reverse)
        }
        assertEquals(StepProgram.TURBO, s[3].machine!!.program)
        assertEquals(2, s[3].machine!!.pulses)
        assertEquals(StepProgram.STEAM, s[4].machine!!.program)
        assertEquals(600, s[4].machine!!.seconds)
        assertEquals(StepProgram.TOAST, s[5].machine!!.program)
        assertEquals(StepProgram.KNEAD, s[6].machine!!.program)
        // Programme du Monsieur Cuisine Smart : gardé avec sa température ; absent du Connect.
        with(s[7].machine!!) {
            assertEquals(StepProgram.SOUS_VIDE, program); assertEquals(60, tempC); assertEquals(3600, seconds)
        }
        assertEquals("Cuire sous vide.", s[7].text)
        assertEquals("Servir.", s[8].text)
        assertEquals(listOf("MC2.0", "MC3.0"), r.devices)

        // Sur le Connect, tous les réglages importés passent les règles de sécurité, sauf le sous-vide
        // (absent de ce robot : la recette ne lui est pas montrée) ; sur le Smart, tous.
        for (step in s) step.machine?.let { m ->
            if (m.program == StepProgram.SOUS_VIDE) assertFalse(m.valid) else assertTrue("${m.summary} : ${m.violations()}", m.valid)
        }
        assertFalse(app.toque.core.robot.RecipeCompat.supports(r, app.toque.core.robot.RobotProfiles.MC_CONNECT))
        assertTrue(app.toque.core.robot.RecipeCompat.reasons(r, app.toque.core.robot.RobotProfiles.MC_SMART).toString(),
            app.toque.core.robot.RecipeCompat.supports(r, app.toque.core.robot.RobotProfiles.MC_SMART))
        try {
            app.toque.core.robot.RobotProfiles.active = app.toque.core.robot.RobotProfiles.MC_SMART
            for (step in s) step.machine?.let { assertTrue("${it.summary} : ${it.violations()}", it.valid) }
        } finally {
            app.toque.core.robot.RobotProfiles.active = app.toque.core.robot.RobotProfiles.MC_CONNECT
        }
    }

    @Test fun rampBecomesOneManualStepPerStage() {
        val json = """{"data":{"recipe":{"id":900002,"name":"Purée d'essai","servingSizes":[{"id":1,"amount":2,
            "ingredients":[],"steps":[
              {"order":0,"name":"Étape","description":"Mixer finement.","deviceSetting":null},
              {"order":1,"name":"Rampe","description":null,"deviceSetting":{"mode":"ramp","reverse":false,"settings":[
                {"order":1,"speed":6,"speedFloat":6,"temperature":0,"time":10},
                {"order":0,"speed":5,"speedFloat":5,"temperature":0,"time":5},
                {"order":2,"speed":10,"speedFloat":10,"temperature":0,"time":20}]}}]}]}}}"""
        val s = McOnline.parseRecipe(json)!!.steps
        assertEquals(3, s.size)
        // Premier palier rattaché au texte, les suivants à part (enchaînés à la cuisson).
        assertEquals("Mixer finement.", s[0].text)
        assertEquals(listOf(5, 6, 10), s.map { it.machine!!.speed })
        assertEquals(listOf(5, 10, 20), s.map { it.machine!!.seconds })
        assertTrue(s.all { it.machine!!.program == StepProgram.MANUAL && it.machine!!.tempC == 0 && it.needsReview })
        assertEquals(s[1].machine!!.summary, s[1].text)
        for (step in s) assertTrue(step.machine!!.valid)
    }

    @Test fun reviews() {
        val p = McOnline.parseReviews(fixture("mc-online-reviews.json"))
        assertEquals(13, p.total)
        assertEquals(5, p.totalPages)
        // Les avis signalés ne sont pas affichés.
        assertEquals(2, p.reviews.size)
        with(p.reviews[0]) {
            assertEquals("Cuisinier d'essai", author); assertEquals(5, rating); assertEquals("2025-12-02", date)
            assertEquals("Très bonne recette, toute la famille a aimé.", message); assertEquals("", reply)
        }
        assertEquals("Bonjour,\nMerci, nous corrigeons la recette.\n\nMeilleures salutations", p.reviews[1].reply)
        assertEquals(900001L, McOnline.apiIdOf("web-900001"))
        assertNull(McOnline.apiIdOf("mc-26946"))
        assertTrue(McOnline.reviewsUrl(900001, 2).endsWith("/api/v1/recipe-reviews/recipes/900001/reviews?page=2&size=10"))
        assertEquals(36, McOnline.reviewHeaders("fr-FR")["X-Request-Id"]!!.length)
        val updated = McOnline.withScore(McOnline.parseRecipe(fixture("mc-online-recipe.json"))!!, McOnline.Entry(900001, "", "", "", 4.6, 510))
        assertEquals(4.6, updated.rating, 0.001); assertEquals(510, updated.ratingCount); assertEquals("Chef d'essai", updated.author)
    }

    @Test fun foreignCategoriesTakeTheNamesOfTheRecipeLanguage() {
        val es = McCategories.parse(
            """{"code":0,"data":{"categories":[{"id":313,"name":"Postres","order":11},{"id":487,"name":"Cocina francesa","order":18},""" +
                """{"id":265,"name":"Platos principales","order":6},{"id":"582","name":"Vegetariano","order":0}]}}""",
        )
        assertEquals(mapOf(313L to "Postres", 487L to "Cocina francesa", 265L to "Platos principales", 582L to "Vegetariano"), es)
        // Française : dessert (307), cuisine française (467) ; revue sans équivalent (497) ; anglaise : végétarien (581).
        assertEquals("Postres", McCategories.localName(307, es))
        assertEquals("Cocina francesa", McCategories.localName(467, es))
        assertEquals("Vegetariano", McCategories.localName(581, es))
        assertNull(McCategories.localName(497, es))
        assertNull(McCategories.localName(999999, es))
        // Même langue : son propre nom.
        assertEquals("Postres", McCategories.localName(313, es))

        val json = """{"data":{"recipe":{"id":1,"name":"Fondant","categories":[{"id":307,"name":"Dessert"},""" +
            """{"id":467,"name":"Cuisine française"},{"id":497,"name":"Mon Robot & Moi"},{"id":259,"name":"Plats principaux"}]}}}"""
        val r = McOnline.parseRecipe(json, es)!!
        assertEquals("Postres", r.category)
        assertEquals(listOf("Cocina francesa", "Mon Robot & Moi", "Platos principales"), r.tags)
        // Sans liste (recette dans la langue des recettes) : les noms de la fiche.
        val same = McOnline.parseRecipe(json)!!
        assertEquals("Dessert", same.category)
        assertEquals(listOf("Cuisine française", "Mon Robot & Moi", "Plats principaux"), same.tags)
    }

    @Test fun unusable() {
        assertNull(McOnline.parseRecipe("""{"data":{"recipe":{"id":1,"name":"  "}}}"""))
        assertNull(McOnline.parseRecipe("""{"code":404,"message":"Recipe not found"}"""))
        assertNotNull(McOnline.parseRecipe("""{"data":{"recipe":{"id":"2","name":"Vide"}}}"""))
    }

    @Test fun headersAndUrls() {
        assertEquals("web", McOnline.headers("fr-FR")["device-type"])
        assertEquals("fr-FR", McOnline.languageFor("fr"))
        assertEquals("en-US", McOnline.languageFor("en"))
        assertEquals("es-ES", McOnline.languageFor("ca"))
        assertEquals("es-ES", McOnline.languageFor("ca-ES"))
        assertEquals("es-ES", McOnline.recipeLanguage("ca", "auto"))
        assertEquals("fr-FR", McOnline.recipeLanguage("ca", "fr-FR"))
        assertTrue(McOnline.listUrl(2).contains("/page/2/size/100?"))
        assertTrue(McOnline.listUrl(1).contains("MC2.0"))
    }
}
