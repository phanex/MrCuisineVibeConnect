package app.toque.core

import app.toque.core.companion.AdjustCommand
import app.toque.core.companion.ApiError
import app.toque.core.companion.CatalogPageWire
import app.toque.core.companion.CatalogQuery
import app.toque.core.companion.CommandResult
import app.toque.core.companion.CompanionApi
import app.toque.core.companion.CompanionHost
import app.toque.core.companion.CompanionHub
import app.toque.core.companion.CookSession
import app.toque.core.companion.EditGroupWire
import app.toque.core.companion.EditStepRequest
import app.toque.core.companion.EditableRecipe
import app.toque.core.companion.HttpRequest
import app.toque.core.companion.HttpResponse
import app.toque.core.companion.ImageData
import app.toque.core.companion.MemoryStore
import app.toque.core.companion.Permissions
import app.toque.core.companion.RecipeEditRequest
import app.toque.core.companion.RecipeEdits
import app.toque.core.companion.RecipeWrite
import app.toque.core.companion.StateWire
import app.toque.core.companion.WaitingFor
import app.toque.core.companion.client.CompanionClient
import app.toque.core.companion.client.CompanionException
import app.toque.core.json.Json
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.core.recipes.Weigh
import app.toque.core.recipes.forServings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Random

/** Recettes écrites sur le téléphone (lot A) : routes, règles, réglages machine gardés par le robot, client. */
class CompanionRecipeWriteTest {

    /** Réglage déjà enregistré sur le robot, à vérifier (importé). */
    private val reviewed = StepMachine(StepProgram.MANUAL, 1200, 1, 100)
    /** Réglage enregistré, refusé par les règles (vitesse 8 en chauffe) : bloqué à la cuisson, jamais jugé ici. */
    private val refused = StepMachine(StepProgram.MANUAL, 60, 8, 100)
    private val knead = StepMachine(StepProgram.KNEAD, 90, 4)

    private val mine = Recipe(
        id = "perso-soupe-abc123", title = "Soupe de légumes", servings = 4, totalMinutes = 40, difficulty = 1, source = RecipeSource.USER,
        ingredients = listOf(IngredientGroup("", listOf(Ingredient("2", "", "carottes"), Ingredient("500", "g", "eau"))),
            IngredientGroup("Pour servir", listOf(Ingredient("10", "cl", "crème")))),
        steps = listOf(
            Step("Couper les légumes."),
            Step("Cuire les légumes dans l'eau.", reviewed, needsReview = true),
            Step("Mixer la soupe.", refused),
            Step("Pétrir.", knead),
            Step("Ajouter la crème.", weigh = Weigh("Crème", 100)),
        ),
    )
    private val lidl = Recipe(id = "1234", title = "Tarte aux pommes", servings = 6, totalMinutes = 50, category = "Desserts",
        ingredients = listOf(IngredientGroup("Pâte", listOf(Ingredient("250", "g", "farine")))),
        steps = listOf(Step("Pétrir la pâte.", knead, needsReview = true), Step("Cuire.")))

    /**
     * Hôte d'essai : les recettes sont gardées encodées (RecipeCodec, comme la base du robot) ; [inEditor]
     * dit quelle recette l'éditeur du robot a ouverte ; [beforeWrite] joue ce qui arrive sur le fil de l'appli.
     */
    private class Host(val writes: Boolean = true) : CompanionHost {
        val stored = LinkedHashMap<String, String>()
        var inEditor: String? = null
        var beforeWrite: () -> Unit = {}
        var writesCalled = 0
        /** Ce que la fiche affiche (nombre de personnes choisi) : jamais ce que l'éditeur lit. */
        var shownServings = 0

        fun put(r: Recipe) { stored[r.id] = RecipeCodec.encode(r) }
        fun get(id: String): Recipe? = stored[id]?.let { RecipeCodec.decode(it) }

        override fun pause() = CommandResult.OK
        override fun resume(stillAllowed: () -> Boolean) = CommandResult.OK
        override fun stop() = CommandResult.OK
        override fun adjust(prepare: () -> AdjustCommand) = CommandResult.OK
        override fun tare() = CommandResult.OK
        override fun catalog(query: CatalogQuery): CatalogPageWire = CatalogPageWire()
        override fun recipe(id: String): Recipe? = get(id)?.let { if (shownServings > 0) it.forServings(shownServings) else it }
        override fun image(recipeId: String): ImageData? = null

        override val writesRecipes get() = writes
        override fun storedRecipe(id: String): Recipe? = if (writes) get(id) else null

        @Synchronized override fun writeRecipe(id: String, decide: (stored: Recipe?, inEditor: Boolean) -> RecipeWrite): RecipeWrite {
            if (!writes) return super.writeRecipe(id, decide)
            writesCalled++
            beforeWrite()
            val w = decide(get(id), inEditor == id)
            w.saved?.let { put(it) }
            return w
        }
    }

    private class Fixture(val host: Host = Host()) {
        val hub = CompanionHub(host, FakeCompanionClock(), MemoryStore(), Random(3))
        val api = CompanionApi(hub)
        val token = hub.pairing.pairWithCode(hub.pairing.open().code).token!!

        fun req(method: String, path: String, body: Any? = null, raw: String? = null): HttpResponse {
            val headers = linkedMapOf("host" to "192.168.0.12:8088", "authorization" to "Bearer $token")
            val bytes = raw?.toByteArray() ?: if (body == null) ByteArray(0) else Json.write(body).toByteArray()
            if (body != null || raw != null) headers["content-type"] = "application/json"
            return api.handle(HttpRequest(method, path, emptyMap(), headers, bytes, "192.168.0.20"))
        }

        @Suppress("UNCHECKED_CAST")
        fun json(r: HttpResponse) = Json.parse(String(r.body, Charsets.UTF_8)) as Map<String, Any?>
        fun error(r: HttpResponse) = ApiError.fromMap(json(r))
        fun editable(id: String) = EditableRecipe.fromMap(json(req("GET", "/api/recipes/$id/edit")))
        fun state() = StateWire.fromMap(json(req("GET", "/api/state")))
    }

    private fun fixture(): Fixture = Fixture().also { it.host.put(mine); it.host.put(lidl) }

    /** La demande que ferait le téléphone sans rien changer. */
    private fun unchanged(e: EditableRecipe) = RecipeEditRequest(version = e.version, title = e.title, servings = e.servings,
        totalMinutes = e.totalMinutes, difficulty = e.difficulty, ingredients = e.ingredients,
        steps = e.steps.map { EditStepRequest(it.index, it.text, it.grams) })

    // ------------------------------------------------------------------ lecture, drapeau

    @Test fun theRobotSaysItCanAndAnOlderRobotOrPhoneSeesNothingNew() {
        val f = fixture()
        assertTrue(f.state().can.editRecipes.allowed)
        assertEquals(true, (f.json(f.req("GET", "/api/state"))["can"] as Map<*, *>).containsKey("editRecipes"))
        // Robot plus ancien (hôte sans écriture) : pas de permission, chaque route refuse.
        val old = Fixture(Host(writes = false))
        assertFalse(old.state().can.editRecipes.allowed)
        assertEquals("unsupported", old.state().can.editRecipes.code)
        for ((m, p) in listOf("GET" to "/api/recipes/perso-x/edit", "POST" to "/api/recipes/1234/copy")) {
            val r = old.req(m, p)
            assertEquals(409, r.status)
            assertEquals("unsupported", old.error(r).code)
        }
        // Téléphone qui lit un état sans le champ (robot plus ancien) : refusé, rien de montré.
        assertFalse(Permissions.fromMap(mapOf("favorite" to mapOf("allowed" to true))).editRecipes.allowed)
        // Téléphone plus ancien : une erreur avec « field » et « step » se lit comme avant.
        val e = ApiError.fromMap(mapOf("error" to mapOf("code" to "weigh_range", "message" to "m", "field" to "steps", "step" to 2)))
        assertEquals(ApiError("weigh_range", "m", "steps", 2), e)
        assertEquals(ApiError("x", "y"), ApiError.fromMap(mapOf("error" to mapOf("code" to "x", "message" to "y"))))
        assertEquals(mapOf("error" to mapOf("code" to "x", "message" to "y")), ApiError("x", "y").toMap())
    }

    @Test fun theEditorReadsTheRecipeAsStoredNotAsShown() {
        val f = fixture()
        f.host.shownServings = 8
        val e = f.editable(mine.id)
        assertEquals(mine.id, e.id)
        assertEquals(4, e.servings)
        assertEquals(listOf(EditGroupWire("", listOf("2 carottes", "500 g eau")), EditGroupWire("Pour servir", listOf("10 cl crème"))), e.ingredients)
        assertEquals(100, e.steps[4].grams)
        assertEquals("20 min · 100 °C · vitesse 1", e.steps[1].machine!!.summary)
        assertTrue(e.steps[1].needsReview)
        assertNull(e.steps[0].machine)
        assertEquals(RecipeEdits.version(mine), e.version)
        assertEquals(16, e.version.length)
        assertEquals(20, e.limits.maxServings)
        // Le favori change à part : il ne change pas la version.
        assertEquals(RecipeEdits.version(mine), RecipeEdits.version(mine.copy(favorite = true)))
        assertNotEquals(RecipeEdits.version(mine), RecipeEdits.version(mine.copy(title = "Autre")))

        assertEquals(409, f.req("GET", "/api/recipes/1234/edit").status)
        assertEquals("not_user", f.error(f.req("GET", "/api/recipes/1234/edit")).code)
        assertEquals(404, f.req("GET", "/api/recipes/perso-inconnue/edit").status)
    }

    // ------------------------------------------------------------------ les réglages restent au robot

    @Test fun storedMachineSettingsAndReviewFlagsComeBackUntouchedAfterMovingRemovingAndEditing() {
        val f = fixture()
        val e = f.editable(mine.id)
        // Mixer (refusé par les règles) monte en tête et change de texte ; la cuisson à vérifier suit ;
        // « Couper » et la crème s'en vont ; une étape nouvelle avec une pesée.
        val req = unchanged(e).copy(steps = listOf(
            EditStepRequest(2, "Mixer finement la soupe.", 0),
            EditStepRequest(1, "Cuire les légumes dans l'eau.", 0),
            EditStepRequest(3, "Pétrir.", 250),
            EditStepRequest(null, "Servir bien chaud.", 120),
        ))
        val r = f.req("POST", "/api/recipes/${mine.id}/edit", req.toMap())
        assertEquals(String(r.body), 200, r.status)
        val saved = f.host.get(mine.id)!!
        assertEquals(listOf("Mixer finement la soupe.", "Cuire les légumes dans l'eau.", "Pétrir.", "Servir bien chaud."), saved.steps.map { it.text })
        assertEquals(listOf(refused, reviewed, knead, null), saved.steps.map { it.machine })
        assertEquals(listOf(false, true, false, false), saved.steps.map { it.needsReview })
        assertEquals(listOf(null, null, 250, 120), saved.steps.map { it.weigh?.grams })
        // La cuisson à vérifier est l'étape enregistrée telle quelle (texte, réglage, vérification).
        assertEquals(mine.steps[1], saved.steps[1])
        // La réponse est la recette enregistrée, avec sa nouvelle version.
        val back = EditableRecipe.fromMap(f.json(r))
        assertEquals(RecipeEdits.version(saved), back.version)
        assertNotEquals(e.version, back.version)
        assertEquals("1 min · 100 °C · vitesse 8", back.steps[0].machine!!.summary)
    }

    @Test fun anUnchangedRecipeIsSavedIdenticalAndAPesageKeepsItsLabel() {
        val f = fixture()
        val e = f.editable(mine.id)
        assertEquals(200, f.req("POST", "/api/recipes/${mine.id}/edit", unchanged(e).toMap()).status)
        assertEquals(mine, f.host.get(mine.id))
        // Seul le texte change : pesée, libellé et réglages restent.
        val text = unchanged(f.editable(mine.id)).let { it.copy(steps = it.steps.map { s -> if (s.from == 4) s.copy(text = "Ajouter la crème et servir.") else s }) }
        assertEquals(200, f.req("POST", "/api/recipes/${mine.id}/edit", text.toMap()).status)
        assertEquals(Weigh("Crème", 100), f.host.get(mine.id)!!.steps[4].weigh)
        assertEquals(mine.ingredients, f.host.get(mine.id)!!.ingredients)
    }

    @Test fun aMachineSettingFromThePhoneIsABadRequest() {
        val f = fixture()
        val base = unchanged(f.editable(mine.id)).toMap()
        for (extra in listOf(
            mapOf("machine" to mapOf("program" to "manuel", "seconds" to 60, "speed" to 2)),
            mapOf("needsReview" to false),
            mapOf("weigh" to mapOf("label" to "", "grams" to 20)),
            mapOf("speed" to 4),
            mapOf("hotBlendOnRobot" to true),
        )) {
            @Suppress("UNCHECKED_CAST")
            val steps = (base["steps"] as List<Map<String, Any?>>).mapIndexed { i, s -> if (i == 0) s + extra else s }
            val r = f.req("POST", "/api/recipes/${mine.id}/edit", base + ("steps" to steps))
            assertEquals(extra.toString(), 400, r.status)
            assertEquals("bad_request", f.error(r).code)
            assertEquals("steps", f.error(r).field)
            assertTrue(f.error(r).message.contains("robot"))
        }
        // Une nouvelle étape avec un réglage : même refus ; rien n'a été écrit.
        val r = f.req("POST", "/api/recipes", mapOf("draft" to "0123456789abcdef0123456789abcdef", "title" to "Soupe", "servings" to 4,
            "totalMinutes" to 0, "difficulty" to 1, "ingredients" to emptyList<Any>(),
            "steps" to listOf(mapOf("text" to "Mixer.", "grams" to 0, "machine" to mapOf("program" to "turbo")))))
        assertEquals(400, r.status)
        assertEquals(0, f.host.writesCalled)
        assertEquals(mine, f.host.get(mine.id))
    }

    @Test fun parsingIsStrict() {
        val f = fixture()
        val ok = unchanged(f.editable(mine.id)).toMap()
        val bad = listOf(
            ok + ("couleur" to "rouge"),
            ok - "title",
            ok - "version",
            ok + ("draft" to "0123456789abcdef0123456789abcdef"),
            ok + ("servings" to 2.5),
            ok + ("servings" to "4"),
            ok + ("difficulty" to 11),
            ok + ("ingredients" to listOf("2 carottes")),
            ok + ("ingredients" to listOf(mapOf("title" to "", "items" to listOf(3)))),
            ok + ("ingredients" to listOf(mapOf("title" to "Inventé", "items" to listOf("sel")))),
            ok + ("steps" to listOf(mapOf("from" to 9, "text" to "x", "grams" to 0))),
            ok + ("steps" to listOf(mapOf("from" to 0, "text" to "x", "grams" to 0), mapOf("from" to 0, "text" to "y", "grams" to 0))),
            ok + ("steps" to listOf(mapOf("from" to -1, "text" to "x", "grams" to 0))),
            ok + ("steps" to listOf(mapOf("text" to "x"))),
            ok + ("steps" to listOf(mapOf("text" to "x", "grams" to -3))),
        )
        for (b in bad) {
            val r = f.req("POST", "/api/recipes/${mine.id}/edit", b)
            assertEquals(b.toString(), 400, r.status)
        }
        assertEquals(400, f.req("POST", "/api/recipes/${mine.id}/edit", raw = "{nope").status)
        assertEquals(mine, f.host.get(mine.id))
        // Création : un identifiant de brouillon de 32 hex, jamais de version.
        val create = ok - "version" + ("draft" to "0123456789abcdef0123456789abcdef")
        assertEquals(400, f.req("POST", "/api/recipes", create + ("version" to "abc")).status)
        assertEquals(400, f.req("POST", "/api/recipes", create + ("draft" to "court")).status)
    }

    // ------------------------------------------------------------------ limites de l'éditeur (422)

    @Test fun theRobotJudgesWithItsEditorsLimitsAndSaysWhere() {
        val f = fixture()
        val e = f.editable(mine.id)
        fun refusal(req: RecipeEditRequest): ApiError {
            val r = f.req("POST", "/api/recipes/${mine.id}/edit", req.toMap())
            assertEquals(String(r.body), 422, r.status)
            return f.error(r)
        }
        val u = unchanged(e)
        assertEquals(ApiError("title_missing", "Donnez un titre à la recette", "title", -1), refusal(u.copy(title = "   ")))
        assertEquals(ApiError("servings_range", "De 1 à 20 personnes", "servings", -1), refusal(u.copy(servings = 21)))
        assertEquals("total_time_range", refusal(u.copy(totalMinutes = 605)).code)
        assertEquals("difficulty_range", refusal(u.copy(difficulty = 0)).code)
        assertEquals(ApiError("content_empty", "Ajoutez au moins un ingrédient ou une étape", "content", -1),
            refusal(u.copy(ingredients = emptyList(), steps = emptyList())))
        val heavy = u.copy(steps = u.steps.map { if (it.from == 3) it.copy(grams = 9000) else it })
        assertEquals(ApiError("weigh_range", "Étape 4 : Entrez un poids entre 1 et 5000 g", "steps", 3), refusal(heavy))
        assertEquals(ApiError("step_empty", "Étape 6 : Écrivez ce qu'il faut faire", "steps", 5),
            refusal(u.copy(steps = u.steps + EditStepRequest(null, "  ", 0))))
        // Tailles.
        assertEquals("too_long", refusal(u.copy(title = "x".repeat(RecipeEdits.MAX_TITLE + 1))).code)
        refusal(u.copy(steps = u.steps + EditStepRequest(null, "y".repeat(RecipeEdits.MAX_STEP_TEXT + 1), 0))).let {
            assertEquals(listOf("too_long", "steps", "5"), listOf(it.code, it.field, it.step.toString()))
        }
        assertEquals("too_many_steps", refusal(u.copy(steps = (0..RecipeEdits.MAX_STEPS).map { EditStepRequest(null, "Étape $it", 0) })).code)
        assertEquals("too_many_ingredients", refusal(u.copy(ingredients = listOf(EditGroupWire("", (0..RecipeEdits.MAX_INGREDIENTS).map { "$it g de sel" })))).code)
        assertEquals("too_long", refusal(u.copy(ingredients = listOf(EditGroupWire("", listOf("z".repeat(RecipeEdits.MAX_LINE + 1)))))).code)
        assertEquals(mine, f.host.get(mine.id))
        // Un réglage déjà enregistré et refusé par les règles (Mixer) ne bloque pas un texte corrigé :
        // il reste bloqué à la cuisson, comme une recette Lidl.
        assertEquals(200, f.req("POST", "/api/recipes/${mine.id}/edit", u.copy(title = "Soupe de mamie").toMap()).status)
        assertEquals(refused, f.host.get(mine.id)!!.steps[2].machine)
    }

    // ------------------------------------------------------------------ changements concurrents

    @Test fun aStaleVersionIsRefusedAndTheRobotsChangeStays() {
        val f = fixture()
        val e = f.editable(mine.id)
        // L'éditeur du robot enregistre entre-temps.
        f.host.put(mine.copy(title = "Soupe du robot"))
        val r = f.req("POST", "/api/recipes/${mine.id}/edit", unchanged(e).copy(title = "Soupe du téléphone").toMap())
        assertEquals(409, r.status)
        assertEquals("stale", f.error(r).code)
        assertEquals("Soupe du robot", f.host.get(mine.id)!!.title)
        // Relue, elle s'enregistre.
        assertEquals(200, f.req("POST", "/api/recipes/${mine.id}/edit", unchanged(f.editable(mine.id)).copy(title = "Soupe du téléphone").toMap()).status)
        assertEquals("Soupe du téléphone", f.host.get(mine.id)!!.title)
    }

    @Test fun refusedWhileCookedOrOpenInTheRobotsEditorCheckedAgainOnTheAppThread() {
        val f = fixture()
        val req = unchanged(f.editable(mine.id)).copy(title = "Autre").toMap()
        f.hub.updateSession(CookSession.of(mine, 0, waitingFor = WaitingFor.HAND))
        var r = f.req("POST", "/api/recipes/${mine.id}/edit", req)
        assertEquals(409, r.status)
        assertEquals("cooking", f.error(r).code)
        assertEquals(0, f.host.writesCalled)
        f.hub.endSession(false)

        // La recette s'ouvre sur le robot entre la requête et l'écriture : revérifié sur le fil de l'appli.
        f.host.beforeWrite = { f.hub.updateSession(CookSession.of(mine, 0, waitingFor = WaitingFor.HAND)) }
        r = f.req("POST", "/api/recipes/${mine.id}/edit", req)
        assertEquals("cooking", f.error(r).code)
        f.hub.endSession(false)
        f.host.beforeWrite = {}

        f.host.inEditor = mine.id
        r = f.req("POST", "/api/recipes/${mine.id}/edit", req)
        assertEquals(409, r.status)
        assertEquals("editing", f.error(r).code)
        assertEquals("Recette ouverte dans l'éditeur du robot", f.error(r).message)
        f.host.inEditor = "perso-une-autre"
        assertEquals(200, f.req("POST", "/api/recipes/${mine.id}/edit", req).status)
        assertEquals("Autre", f.host.get(mine.id)!!.title)
    }

    @Test fun onlyOwnRecipesAreWrittenAndLidlOnesNever() {
        val f = fixture()
        val asIfMine = unchanged(f.editable(mine.id)).toMap()
        var r = f.req("POST", "/api/recipes/1234/edit", asIfMine)
        assertEquals(409, r.status)
        assertEquals("not_user", f.error(r).code)
        r = f.req("POST", "/api/recipes/perso-inconnue/edit", asIfMine)
        assertEquals(404, r.status)
        assertEquals(lidl, f.host.get("1234"))
    }

    // ------------------------------------------------------------------ création, « Ma version »

    @Test fun theRobotNamesANewRecipeAndARepeatedCreationIsNotADuplicate() {
        val f = fixture()
        val req = RecipeEditRequest(draft = "00112233445566778899aabbccddeeff", title = "Pâte à crêpes", servings = 6, totalMinutes = 15,
            difficulty = 1, ingredients = listOf(EditGroupWire("", listOf("250 g de farine", "4 œufs", ""))),
            steps = listOf(EditStepRequest(null, "Tout mixer.", 0), EditStepRequest(null, "Peser le lait.", 500)))
        val r = f.req("POST", "/api/recipes", req.toMap())
        assertEquals(String(r.body), 200, r.status)
        val e = EditableRecipe.fromMap(f.json(r))
        assertTrue(e.id, e.id.startsWith("perso-pate-a-crepes-"))
        val saved = f.host.get(e.id)!!
        assertEquals(RecipeSource.USER, saved.source)
        // Lignes lues comme sur le robot (quantité à part) ; la ligne vide ne compte pas.
        assertEquals(listOf("250", "4"), saved.allIngredients.map { it.qty })
        assertTrue(saved.allIngredients[0].display, saved.allIngredients[0].display.contains("farine"))
        assertTrue(saved.steps.all { it.machine == null && !it.needsReview })
        assertEquals(500, saved.steps[1].weigh!!.grams)
        // La réponse perdue, le téléphone renvoie le même brouillon : la même recette, pas une deuxième.
        val again = EditableRecipe.fromMap(f.json(f.req("POST", "/api/recipes", req.copy(title = "Pâte à crêpes légère").toMap())))
        assertEquals(e.id, again.id)
        assertEquals("Pâte à crêpes légère", f.host.get(e.id)!!.title)
        assertEquals(3, f.host.stored.size)
        // Une création est jugée entière : sans nombre de personnes, refusée.
        val r2 = f.req("POST", "/api/recipes", req.copy(draft = "ffeeddccbbaa99887766554433221100", servings = 0).toMap())
        assertEquals(422, r2.status)
        assertEquals("servings_range", f.error(r2).code)
    }

    @Test fun myVersionCopiesWhatTheRobotShowsAndLeavesTheLidlRecipe() {
        val f = fixture()
        f.host.shownServings = 3
        val r = f.req("POST", "/api/recipes/1234/copy")
        assertEquals(String(r.body), 200, r.status)
        val e = EditableRecipe.fromMap(f.json(r))
        assertTrue(e.id.startsWith("perso-tarte-aux-pommes-"))
        assertEquals(3, e.servings)
        val copy = f.host.get(e.id)!!
        assertEquals(RecipeSource.USER, copy.source)
        assertEquals(knead, copy.steps[0].machine)
        assertTrue(copy.steps[0].needsReview)
        assertEquals("Pâte", copy.ingredients[0].title)
        assertEquals(lidl, f.host.get("1234"))
        // La copie se modifie ensuite comme une recette à soi, avec ses groupes.
        assertEquals(200, f.req("POST", "/api/recipes/${e.id}/edit", unchanged(e).copy(title = "Ma tarte").toMap()).status)
        assertEquals(409, f.req("POST", "/api/recipes/${mine.id}/copy").status)
        assertEquals("already_user", f.error(f.req("POST", "/api/recipes/${mine.id}/copy")).code)
        assertEquals(404, f.req("POST", "/api/recipes/inconnue/copy").status)
    }

    @Test fun theWaitingRecipeTakesTheNewTitle() {
        val f = fixture()
        assertEquals(200, f.req("POST", "/api/waiting", mapOf("recipeId" to mine.id)).status)
        val rev = f.state().revision
        assertEquals(200, f.req("POST", "/api/recipes/${mine.id}/edit", unchanged(f.editable(mine.id)).copy(title = "Soupe d'hiver").toMap()).status)
        val s = f.state()
        assertEquals("Soupe d'hiver", s.waiting!!.title)
        assertTrue(s.revision > rev)
    }

    // ------------------------------------------------------------------ vrai serveur, client

    private var served: CompanionHub? = null

    @After fun tearDown() {
        served?.stop()
    }

    private fun serve(): Pair<Host, CompanionClient> {
        val host = Host().also { it.put(mine); it.put(lidl) }
        val hub = CompanionHub(host, store = MemoryStore()).also { served = it }
        hub.start(0, InetAddress.getByName("127.0.0.1"))
        val c = CompanionClient("127.0.0.1", hub.port)
        c.pairWithCode(hub.pairing.open().code)
        return host to c
    }

    @Test fun theClientWritesOverHttpAndLargeRecipesPassOnlyOnTheseRoutes() {
        val (host, c) = serve()
        assertTrue(c.state().can.editRecipes.allowed)
        val e = c.editable(mine.id)
        // 50 étapes de 1000 caractères accentués (environ 90 Kio) : au-delà des 16 Kio des autres routes.
        val long = unchanged(e).copy(steps = (0 until 50).map { EditStepRequest(null, "é".repeat(500) + "a".repeat(500), 0) })
        assertTrue(Json.write(long.toMap()).toByteArray().size > 64 * 1024)
        val saved = c.saveRecipe(mine.id, long)
        assertEquals(50, saved.steps.size)
        assertEquals(50, host.get(mine.id)!!.steps.size)
        val created = c.createRecipe(RecipeEditRequest(draft = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", title = "Salade", servings = 2, steps = listOf(EditStepRequest(null, "Mélanger.", 0))))
        assertTrue(created.id.startsWith("perso-salade-"))
        val copy = c.copyRecipe("1234")
        assertEquals("Tarte aux pommes", copy.title)
        try {
            c.saveRecipe(mine.id, unchanged(e))
            fail()
        } catch (x: CompanionException) {
            assertEquals(409, x.status)
            assertEquals("stale", x.error.code)
        }
        try {
            c.saveRecipe(mine.id, unchanged(c.editable(mine.id)).copy(servings = 30))
            fail()
        } catch (x: CompanionException) {
            assertEquals(422, x.status)
            assertEquals(ApiError("servings_range", "De 1 à 20 personnes", "servings", -1), x.error)
        }
    }

    @Test fun bodyLimitsAreRaisedOnlyForRecipesWithAToken() {
        val (_, c) = serve()
        val hub = served!!
        val token = c.token!!
        fun post(path: String, bytes: Int, auth: Boolean): Int {
            val conn = URL("http://127.0.0.1:${hub.port}$path").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (auth) conn.setRequestProperty("Authorization", "Bearer $token")
            val body = ("{\"x\":\"" + "a".repeat(bytes - 8) + "\"}").toByteArray()
            conn.setFixedLengthStreamingMode(body.size)
            return try {
                conn.outputStream.use { it.write(body) }
                conn.responseCode
            } catch (e: java.io.IOException) {
                // Le serveur a pu répondre et fermer avant la fin de l'envoi.
                try { conn.responseCode } catch (_: Exception) { 413 }
            } finally {
                conn.disconnect()
            }
        }
        assertEquals(400, post("/api/recipes/${mine.id}/edit", 100 * 1024, auth = true))
        assertEquals(413, post("/api/recipes/${mine.id}/edit", RecipeEdits.MAX_BODY_BYTES + 10, auth = true))
        assertEquals(400, post("/api/recipes", 100 * 1024, auth = true))
        // Sans jeton, ou sur une autre route : la borne de 16 Kio reste.
        assertEquals(413, post("/api/recipes", 20 * 1024, auth = false))
        assertEquals(413, post("/api/recipes/${mine.id}/favorite", 20 * 1024, auth = true))
        assertEquals(413, post("/api/recipes/1234/copy", 20 * 1024, auth = true))
    }
}
