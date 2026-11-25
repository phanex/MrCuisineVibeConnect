package app.toque.core

import app.toque.core.companion.AdjustCommand
import app.toque.core.companion.ApiError
import app.toque.core.companion.CatalogPageWire
import app.toque.core.companion.CatalogQuery
import app.toque.core.companion.CommandResult
import app.toque.core.companion.CompanionApi
import app.toque.core.companion.CompanionHost
import app.toque.core.companion.CompanionHub
import app.toque.core.companion.EditStepRequest
import app.toque.core.companion.EditableRecipe
import app.toque.core.companion.EditorWire
import app.toque.core.companion.HttpRequest
import app.toque.core.companion.HttpResponse
import app.toque.core.companion.ImageData
import app.toque.core.companion.MemoryStore
import app.toque.core.companion.RecipeEditRequest
import app.toque.core.companion.RecipeWrite
import app.toque.core.companion.SettingEdit
import app.toque.core.companion.SettingKindWire
import app.toque.core.companion.SettingWire
import app.toque.core.companion.StepSettingsWire
import app.toque.core.companion.client.CompanionClient
import app.toque.core.companion.client.CompanionException
import app.toque.core.json.Json
import app.toque.core.machine.Accessory
import app.toque.core.machine.ManualSettings
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.core.recipes.StepSettings
import app.toque.core.recipes.Weigh
import app.toque.core.robot.RecipeCompat
import app.toque.core.robot.RobotProfiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.util.Random

/**
 * Recettes écrites sur le téléphone, lot B : le réglage du robot d'une étape tapé sur le téléphone. Le
 * robot annonce ce que son éditeur d'étape propose, lit strictement, juge ce qui a été tapé ou changé
 * (et rien d'autre), le fait vérifier sur le robot (needsReview) et dit à l'hôte quelles étapes oublier.
 */
class CompanionStepSettingsTest {
    private val reviewed = StepMachine(StepProgram.MANUAL, 1200, 1, 100)
    /** Enregistré et refusé par les règles (vitesse 8 en chauffe) : bloqué à la cuisson, jamais rejugé ici. */
    private val refused = StepMachine(StepProgram.MANUAL, 60, 8, 100)
    private val knead = StepMachine(StepProgram.KNEAD, 90, 4)

    private val mine = Recipe(
        id = "perso-soupe-abc123", title = "Soupe de légumes", servings = 4, totalMinutes = 40, difficulty = 1, source = RecipeSource.USER,
        ingredients = listOf(IngredientGroup("", listOf(Ingredient("2", "", "carottes")))),
        steps = listOf(
            Step("Couper les légumes."),
            Step("Cuire les légumes dans l'eau.", reviewed, needsReview = true),
            Step("Mixer la soupe.", refused),
            Step("Pétrir.", knead),
            Step("Ajouter la crème.", weigh = Weigh("Crème", 100)),
        ),
    )

    @After fun reset() {
        RobotProfiles.active = RobotProfiles.MC_CONNECT
        served?.stop()
    }

    /** Hôte d'essai : recettes encodées comme sur le robot ; garde la dernière écriture (étapes tapées, téléphone). */
    private class Host(val settings: Boolean = true) : CompanionHost {
        val stored = LinkedHashMap<String, String>()
        var last: RecipeWrite? = null
        var writesCalled = 0

        fun put(r: Recipe) { stored[r.id] = RecipeCodec.encode(r) }
        fun get(id: String): Recipe? = stored[id]?.let { RecipeCodec.decode(it) }

        override fun pause() = CommandResult.OK
        override fun resume(stillAllowed: () -> Boolean) = CommandResult.OK
        override fun stop() = CommandResult.OK
        override fun adjust(prepare: () -> AdjustCommand) = CommandResult.OK
        override fun tare() = CommandResult.OK
        override fun catalog(query: CatalogQuery): CatalogPageWire = CatalogPageWire()
        override fun recipe(id: String): Recipe? = get(id)
        override fun image(recipeId: String): ImageData? = null
        override val writesRecipes get() = true
        override val writesMachineSteps get() = settings
        override fun storedRecipe(id: String): Recipe? = get(id)

        @Synchronized override fun writeRecipe(id: String, decide: (stored: Recipe?, inEditor: Boolean) -> RecipeWrite): RecipeWrite {
            writesCalled++
            val w = decide(get(id), false)
            w.saved?.let { put(it) }
            last = w
            return w
        }
    }

    private inner class Fixture(val host: Host = Host()) {
        val hub = CompanionHub(host, FakeCompanionClock(), MemoryStore(), Random(5))
        val api = CompanionApi(hub)
        val token = hub.pairing.pairWithCode(hub.pairing.open().code).token!!

        init { host.put(mine) }

        fun req(method: String, path: String, body: Any? = null): HttpResponse {
            val headers = linkedMapOf("host" to "192.168.0.12:8088", "authorization" to "Bearer $token")
            val bytes = if (body == null) ByteArray(0) else Json.write(body).toByteArray()
            if (body != null) headers["content-type"] = "application/json"
            return api.handle(HttpRequest(method, path, emptyMap(), headers, bytes, "192.168.0.20"))
        }

        @Suppress("UNCHECKED_CAST")
        fun json(r: HttpResponse) = Json.parse(String(r.body, Charsets.UTF_8)) as Map<String, Any?>
        fun error(r: HttpResponse) = ApiError.fromMap(json(r))
        fun editable() = EditableRecipe.fromMap(json(req("GET", "/api/recipes/${mine.id}/edit")))
        fun save(req: RecipeEditRequest) = req("POST", "/api/recipes/${mine.id}/edit", req.toMap())
        fun saveRaw(body: Map<String, Any?>) = req("POST", "/api/recipes/${mine.id}/edit", body)
        val saved: Recipe get() = host.get(mine.id)!!
    }

    /** Ce qu'enverrait le téléphone sans rien changer (aucun membre « machine »). */
    private fun unchanged(e: EditableRecipe) = RecipeEditRequest(version = e.version, title = e.title, servings = e.servings,
        totalMinutes = e.totalMinutes, difficulty = e.difficulty, ingredients = e.ingredients,
        steps = e.steps.map { EditStepRequest(it.index, it.text, it.grams) })

    /** [unchanged] avec le réglage [m] envoyé à l'étape enregistrée [from] (null dans SettingEdit : aucun réglage). */
    private fun withSetting(e: EditableRecipe, from: Int, m: StepMachine?) =
        unchanged(e).let { u -> u.copy(steps = u.steps.map { if (it.from == from) it.copy(setting = SettingEdit(m)) else it }) }

    // ------------------------------------------------------------------ le drapeau et ce que le robot annonce

    @Test fun aRobotThatTakesSettingsSaysSoWithWhatItsEditorOffers() {
        val f = Fixture()
        val raw = f.json(f.req("GET", "/api/recipes/${mine.id}/edit"))
        assertTrue(raw.containsKey("settings"))
        val s = f.editable().settings!!
        // Connect : manuel, pétrir, vapeur, rissoler, Turbo, dans l'ordre de l'éditeur du robot ; pas les programmes du Smart.
        assertEquals(listOf("manuel", "petrir", "vapeur", "rissoler", "turbo"), s.kinds.map { it.program })
        val manual = s.kind(StepProgram.MANUAL)!!
        assertEquals("Cuisson manuelle", manual.label)
        assertEquals(listOf("seconds", "tempC", "speed", "reverse", "accessory"), manual.params)
        assertEquals(StepMachine(seconds = 60, speed = 2), manual.default)
        val k = s.kind(StepProgram.KNEAD)!!
        assertEquals(listOf(15, 120, 15), listOf(k.minSeconds, k.maxSeconds, k.secondsStep))
        assertEquals(StepMachine(StepProgram.KNEAD, 90, 4), k.default)
        assertTrue(k.hint.startsWith("Pâtes à pain"))
        assertEquals(listOf(60, 5940, 60), s.kind(StepProgram.STEAM)!!.let { listOf(it.minSeconds, it.maxSeconds, it.secondsStep) })
        assertEquals(listOf(30, 840, 30), s.kind(StepProgram.TOAST)!!.let { listOf(it.minSeconds, it.maxSeconds, it.secondsStep) })
        assertEquals(listOf(1, 10), s.kind(StepProgram.TURBO)!!.let { listOf(it.minPulses, it.maxPulses) })
        // Les bornes de la cuisson manuelle : celles de l'écran du robot, prêtes pour ManualSettings.
        assertEquals(StepSettings.manualLimits(RobotProfiles.MC_CONNECT), s.manual.limits())
        assertTrue(s.manual.heatNoStir)
        assertEquals(listOf("BLADE", "WHISK"), s.manual.accessories)
        assertEquals(3, s.manual.maxSpeedHeated)
        assertEquals(120, s.manual.maxTempNoStirC)
        // Une recette nouvelle : GET /api/editor.
        val ed = EditorWire.fromMap(f.json(f.req("GET", "/api/editor")))
        assertEquals(s, ed.settings)
        assertEquals(20, ed.limits.maxServings)
        // Chaque réponse d'écriture le redit.
        val back = EditableRecipe.fromMap(f.json(f.save(unchanged(f.editable()))))
        assertEquals(s, back.settings)
        assertEquals(405, f.req("POST", "/api/editor", emptyMap<String, Any>()).status)
    }

    @Test fun theSmartOffersItsProgramsWithTheirOwnRanges() {
        RobotProfiles.active = RobotProfiles.MC_SMART
        val f = Fixture()
        val s = f.editable().settings!!
        assertEquals(listOf("manuel", "petrir", "vapeur", "rissoler", "turbo", "mixer", "smoothie", "sous-vide", "mijoter", "fermenter"),
            s.kinds.map { it.program })
        val sv = s.kind(StepProgram.SOUS_VIDE)!!
        assertEquals(listOf("seconds", "tempC"), sv.params)
        assertEquals(listOf(15 * 60, 12 * 3600, 15 * 60, 40, 85), listOf(sv.minSeconds, sv.maxSeconds, sv.secondsStep, sv.minTempC, sv.maxTempC))
        assertEquals(listOf(30, 120, 10), s.kind(StepProgram.MIXER)!!.let { listOf(it.minSeconds, it.maxSeconds, it.secondsStep) })
        assertFalse(s.manual.heatNoStir)
        assertTrue(40 in s.manual.temperaturesC)
    }

    @Test fun anOlderRobotAndAnOlderPhoneSeeExactlyWhatTheySawBefore() {
        val old = Fixture(Host(settings = false))
        // Robot de lot A : ni « settings », ni réglage accepté (400 « machine », comme avant).
        assertFalse(old.json(old.req("GET", "/api/recipes/${mine.id}/edit")).containsKey("settings"))
        assertFalse(old.json(old.req("GET", "/api/editor")).containsKey("settings"))
        val r = old.save(withSetting(old.editable(), 0, StepMachine(seconds = 60, speed = 2)))
        assertEquals(400, r.status)
        assertEquals("Machine settings are set on the robot, never sent by the phone", old.error(r).message)
        assertEquals(0, old.host.writesCalled)
        // Un téléphone lit une réponse sans « settings » : rien à proposer.
        assertNull(EditableRecipe.fromMap(mapOf("id" to "perso-x")).settings)
        assertNull(EditorWire.fromMap(mapOf("limits" to emptyMap<String, Any>())).settings)

        // Téléphone plus ancien chez un robot de lot B : son corps (aucun « machine ») garde chaque réglage par « from ».
        val f = Fixture()
        val u = unchanged(f.editable())
        assertFalse(Json.write(u.toMap()).contains("machine"))
        assertEquals(200, f.save(u.copy(steps = u.steps.reversed())).status)
        assertEquals(mine.steps.reversed(), f.saved.steps)
        assertTrue(f.host.last!!.typed.isEmpty())
    }

    // ------------------------------------------------------------------ lecture stricte

    @Test fun theSettingMemberIsReadStrictly() {
        val f = Fixture()
        val ok = unchanged(f.editable()).toMap()
        val good = SettingWire.toMap(StepMachine(seconds = 60, speed = 2))
        @Suppress("UNCHECKED_CAST")
        fun at(i: Int, extra: Map<String, Any?>): Map<String, Any?> =
            ok + ("steps" to (ok["steps"] as List<Map<String, Any?>>).mapIndexed { k, s -> if (k == i) s + extra else s })
        val bad = listOf(
            "machine" to "manuel",
            "machine" to listOf(good),
            "machine" to good - "pulses",
            "machine" to good + ("summary" to "1 min · vitesse 2"),
            "machine" to good + ("needsReview" to false),
            "machine" to good + ("program" to "inconnu"),
            "machine" to good + ("program" to "MANUEL"),
            "machine" to good + ("accessory" to "whisk"),
            "machine" to good + ("reverse" to "false"),
            "machine" to good + ("seconds" to 2.5),
            "machine" to good + ("seconds" to "60"),
            "machine" to good + ("speed" to -1),
            "machine" to good + ("tempC" to 5000),
        )
        for ((k, v) in bad) {
            val r = f.saveRaw(at(2, mapOf(k to v)))
            assertEquals("$v", 400, r.status)
            assertEquals(ApiError("bad_request", "Invalid machine setting", "steps", 2), f.error(r))
        }
        // La vérification et les champs à plat restent au robot, même chez un robot qui prend les réglages.
        for (extra in listOf(mapOf("needsReview" to false), mapOf("speed" to 4), mapOf("weigh" to mapOf("grams" to 3)), mapOf("hotBlendOnRobot" to true))) {
            val r = f.saveRaw(at(1, extra))
            assertEquals("$extra", 400, r.status)
            assertEquals(ApiError("bad_request", "Machine settings are set on the robot, never sent by the phone", "steps", 1), f.error(r))
        }
        assertEquals(0, f.host.writesCalled)
        assertEquals(mine, f.saved)
        // Lu, 4.0 est un entier ; « machine »: null se lit « aucun réglage ».
        val parsed = RecipeEditRequest.fromMap(Json.parse(Json.write(at(0, mapOf("machine" to good + ("seconds" to 60.0))))) as Map<String, Any?>,
            create = false, settings = true)
        assertEquals(SettingEdit(StepMachine(seconds = 60, speed = 2)), parsed.steps[0].setting)
        @Suppress("UNCHECKED_CAST")
        val none = RecipeEditRequest.fromMap(Json.parse(Json.write(at(0, mapOf("machine" to null)))) as Map<String, Any?>, create = false, settings = true)
        assertEquals(SettingEdit(null), none.steps[0].setting)
        assertNull(none.steps[1].setting)
        // Sans le drapeau (robot de lot A), le même corps est refusé à la lecture.
        try {
            RecipeEditRequest.fromMap(Json.parse(Json.write(at(0, mapOf("machine" to null)))) as Map<String, Any?>, create = false)
            fail()
        } catch (e: app.toque.core.companion.BadEdit) {
            assertEquals("machine", e.field)
        }
        // Le corps écrit par le téléphone : absent, null ou l'objet, rien d'autre.
        assertFalse(EditStepRequest(1, "x", 0).toMap().containsKey("machine"))
        assertTrue(EditStepRequest(1, "x", 0, SettingEdit(null)).toMap().let { it.containsKey("machine") && it["machine"] == null })
        assertEquals(good, EditStepRequest(1, "x", 0, SettingEdit(StepMachine(seconds = 60, speed = 2))).toMap()["machine"])
    }

    // ------------------------------------------------------------------ poser, retirer, garder

    @Test fun setRemoveKeepAndAnObjectEqualToTheStoredOne() {
        val f = Fixture()
        val e = f.editable()
        val typed = StepMachine(seconds = 300, speed = 1, tempC = 90)
        val req = unchanged(e).copy(steps = listOf(
            EditStepRequest(0, "Couper les légumes.", 0, SettingEdit(typed)),                 // posé
            EditStepRequest(1, "Cuire les légumes dans l'eau.", 0, SettingEdit(reviewed)),     // renvoyé tel quel (à vérifier)
            EditStepRequest(3, "Pétrir.", 0, SettingEdit(knead)),                              // renvoyé tel quel (vérifié)
            EditStepRequest(2, "Mixer la soupe.", 0, SettingEdit(null)),                       // retiré
            EditStepRequest(4, "Ajouter la crème.", 100),                                     // pas touché
            EditStepRequest(null, "Servir.", 0, SettingEdit(StepMachine(StepProgram.TURBO, pulses = 2))), // nouvelle, avec réglage
            EditStepRequest(null, "Décorer.", 0, SettingEdit(null)),                          // nouvelle, sans réglage
        ))
        val r = f.save(req)
        assertEquals(String(r.body), 200, r.status)
        val s = f.saved.steps
        assertEquals(listOf(typed, reviewed, knead, null, null, StepMachine(StepProgram.TURBO, pulses = 2), null), s.map { it.machine })
        // Tapé : à vérifier sur le robot ; renvoyé tel quel : sa vérification d'avant, exactement.
        assertEquals(listOf(true, true, false, false, false, true, false), s.map { it.needsReview })
        assertEquals(listOf(0, 5), f.host.last!!.typed)
        // Tapé : sa propre carte sur le robot ; le pétrissage, pas touché mais venu d'un autre rang, y est oublié aussi.
        assertEquals(listOf(true, false, false, false, false, true, false), s.map { it.reviewAlone })
        assertEquals(listOf(2), f.host.last!!.moved)
        assertEquals(listOf(0, 5, 2), f.host.last!!.forget)
        assertEquals(Weigh("Crème", 100), s[4].weigh)
        val back = EditableRecipe.fromMap(f.json(r))
        assertEquals("5 min · 90 °C · vitesse 1", back.steps[0].machine!!.summary)
        assertTrue(back.steps[0].needsReview)
    }

    /** Un réglage tapé sur le téléphone a sa propre carte ; une étape déplacée garde ce qu'elle était, et son nouveau rang est oublié. */
    @Test fun aTypedSettingIsReviewedAloneAndAMovedStepForgetsWhatItsNewRankKept() {
        val f = Fixture()
        val u = unchanged(f.editable()).steps
        val typed = StepMachine(seconds = 30, speed = 4)
        // « Pétrir » et « Cuire » échangent leurs rangs sans qu'on touche à leurs réglages ; « Mixer » reçoit un réglage tapé.
        val req = unchanged(f.editable()).copy(steps = listOf(u[0], u[3], u[2].copy(setting = SettingEdit(typed)), u[1], u[4]))
        val r = f.save(req)
        assertEquals(String(r.body), 200, r.status)
        val s = f.saved.steps
        assertEquals(listOf(null, knead, typed, reviewed, null), s.map { it.machine })
        assertEquals(listOf(false, false, true, true, false), s.map { it.needsReview })
        assertEquals(listOf(false, false, true, false, false), s.map { it.reviewAlone })
        assertEquals(listOf(2), f.host.last!!.typed)
        assertEquals(listOf(1, 3), f.host.last!!.moved)
        assertEquals(listOf(2, 1, 3), f.host.last!!.forget)
        // Gardé tel quel dans le fichier de la recette, et à l'enregistrement suivant sans y toucher.
        assertTrue(RecipeCodec.encode(f.saved).contains("\"reviewAlone\""))
        assertFalse(RecipeCodec.encode(mine).contains("reviewAlone"))
        assertEquals(200, f.save(unchanged(f.editable())).status)
        assertEquals(listOf(false, false, true, false, false), f.saved.steps.map { it.reviewAlone })
        assertEquals(emptyList<Int>(), f.host.last!!.forget)
    }

    /** Une étape dont le texte n'est que le résumé de son réglage (réglage enchaîné d'une recette Lidl) suit le nouveau réglage. */
    @Test fun aStepLabelledByItsSettingFollowsTheNewSetting() {
        val chained = StepMachine(seconds = 300, speed = 1, tempC = 100)
        val f = Fixture()
        f.host.put(mine.copy(steps = listOf(Step("Cuire.", reviewed, needsReview = true), Step(chained.summary, chained, needsReview = true))))
        val longer = chained.copy(seconds = 360)
        assertEquals(200, f.save(withSetting(f.editable(), 1, longer)).status)
        assertEquals(Step("6 min · 100 °C · vitesse 1", longer, needsReview = true, reviewAlone = true), f.saved.steps[1])
        // Un texte écrit à la main reste, et un texte changé sur le téléphone en même temps que le réglage aussi.
        assertEquals(200, f.save(withSetting(f.editable(), 0, reviewed.copy(seconds = 600))).status)
        assertEquals("Cuire.", f.saved.steps[0].text)
        val req = withSetting(f.editable(), 1, chained).let { q -> q.copy(steps = q.steps.map { if (it.from == 1) it.copy(text = "Laisser mijoter.") else it }) }
        assertEquals(200, f.save(req).status)
        assertEquals("Laisser mijoter.", f.saved.steps[1].text)
    }

    /** Un réglage venu d'une recette Lidl, touché sur le téléphone, est ramené à ce que l'éditeur du robot propose. */
    @Test fun aKindFitsASettingFromElsewhereToWhatTheEditorOffers() {
        val s = StepSettingsWire.of(RobotProfiles.MC_CONNECT)
        val kind = s.kind(StepProgram.KNEAD)!!
        // Pétrissage Lidl : pas de vitesse. Tel quel, le robot le refuserait ; ajusté, il passe.
        val lidl = StepMachine(StepProgram.KNEAD, seconds = 60)
        assertNotNull(StepSettings.refusal(lidl.copy(seconds = 75), RobotProfiles.MC_CONNECT))
        val fitted = kind.fit(lidl.copy(seconds = 75))
        assertEquals(StepMachine(StepProgram.KNEAD, 75, 4), fitted)
        assertNull(StepSettings.refusal(fitted, RobotProfiles.MC_CONNECT))
        assertTrue(kind.same(kind.fit(lidl), lidl))
        assertFalse(kind.same(fitted, lidl))
        assertFalse(kind.same(lidl, StepMachine(StepProgram.STEAM, 60)))
        // Turbo Lidl : compté en secondes, parfois plus de dix.
        val turbo = s.kind(StepProgram.TURBO)!!
        assertEquals(StepMachine(StepProgram.TURBO, pulses = 10), turbo.fit(StepMachine(StepProgram.TURBO, seconds = 19, pulses = 19)))
        assertEquals(StepMachine(StepProgram.TURBO, pulses = 1), turbo.fit(StepMachine(StepProgram.TURBO, pulses = -1)))
        // Durée ramenée dans les bornes du genre.
        val steam = s.kind(StepProgram.STEAM)!!
        assertEquals(steam.maxSeconds, steam.fit(StepMachine(StepProgram.STEAM, 100_000)).seconds)
        assertEquals(steam.minSeconds, steam.fit(StepMachine(StepProgram.STEAM, -60)).seconds)
        // Tout ce qu'un genre ajuste est un réglage que l'éditeur du robot propose : le juge le prend.
        for (k in s.kinds) {
            if (k.stepProgram == StepProgram.MANUAL) continue
            val odd = StepMachine(k.stepProgram!!, seconds = 7, speed = 9, tempC = 3, reverse = true, accessory = Accessory.WHISK, pulses = 77)
            assertNull(k.program, StepSettings.refusal(k.fit(odd), RobotProfiles.MC_CONNECT))
        }
    }

    @Test fun aNewRecipeWithSettingsIsJudgedAndReviewed() {
        val f = Fixture()
        val body = RecipeEditRequest(draft = "0123456789abcdef0123456789abcdef", title = "Pâte", servings = 4, steps = listOf(
            EditStepRequest(null, "Pétrir.", 0, SettingEdit(StepMachine(StepProgram.KNEAD, 105, 4))),
            EditStepRequest(null, "Repos.", 0)))
        val r = f.req("POST", "/api/recipes", body.toMap())
        assertEquals(String(r.body), 200, r.status)
        val id = EditableRecipe.fromMap(f.json(r)).id
        val saved = f.host.get(id)!!
        assertEquals(StepMachine(StepProgram.KNEAD, 105, 4), saved.steps[0].machine)
        assertEquals(listOf(true, false), saved.steps.map { it.needsReview })
        assertEquals(listOf(0), f.host.last!!.typed)
        assertTrue(f.host.last!!.phone.isNotEmpty())
        // Refusé à la création : rien d'enregistré.
        val bad = body.copy(draft = "1123456789abcdef0123456789abcdef", steps = listOf(EditStepRequest(null, "Turbo.", 0,
            SettingEdit(StepMachine(StepProgram.TURBO, pulses = 3, accessory = Accessory.WHISK)))))
        val n = f.host.stored.size
        val x = f.req("POST", "/api/recipes", bad.toMap())
        assertEquals(422, x.status)
        assertEquals(0, f.error(x).step)
        assertEquals(n, f.host.stored.size)
    }

    // ------------------------------------------------------------------ le robot juge ce qui est tapé, et rien d'autre

    @Test fun aRefusedSettingGives422AtItsStepWithTheRobotsReasonAndNothingIsSaved() {
        val f = Fixture()
        val e = f.editable()
        fun refusal(from: Int, m: StepMachine): ApiError {
            val r = f.save(withSetting(e, from, m))
            assertEquals(String(r.body), 422, r.status)
            return f.error(r)
        }
        assertEquals(ApiError("step_machine", "Étape 1 : En chauffe, vitesse 3 maximum", "steps", 0),
            refusal(0, StepMachine(seconds = 60, speed = 5, tempC = 100)))
        assertEquals("Étape 1 : Température 92 °C absente de la machine", refusal(0, StepMachine(seconds = 60, speed = 1, tempC = 92)).message)
        assertEquals("Étape 1 : Durée trop courte", refusal(0, StepMachine(seconds = 2, speed = 2)).message)
        assertEquals("Étape 1 : Vitesse minimale : 1", refusal(0, StepMachine(seconds = 60, speed = 0)).message)
        assertEquals("Étape 1 : Sans mélange : sens normal uniquement", refusal(0, StepMachine(seconds = 60, speed = 0, tempC = 90, reverse = true)).message)
        assertEquals("Étape 1 : En sens inverse, vitesse 3 maximum", refusal(0, StepMachine(seconds = 60, speed = 5, reverse = true)).message)
        assertEquals("Étape 1 : Fouet : vitesse 4 maximum", refusal(0, StepMachine(seconds = 60, speed = 6, accessory = Accessory.WHISK)).message)
        assertEquals("Étape 1 : Durée maximale : 15 min", refusal(0, StepMachine(seconds = 1200, speed = 8)).message)
        assertEquals("Étape 1 : Réglage non proposé par l'éditeur du robot", refusal(0, StepMachine(seconds = 60, speed = 2, pulses = 3)).message)
        // Programmes : leurs bornes, et rien d'autre que ce que l'éditeur règle.
        assertEquals("Étape 4 : Durée : de 15 s à 2 min", refusal(3, StepMachine(StepProgram.KNEAD, 200, 4)).message)
        assertEquals("Étape 4 : Réglage non proposé par l'éditeur du robot", refusal(3, StepMachine(StepProgram.KNEAD, 90, 2)).message)
        assertEquals("Étape 4 : Impulsions : de 1 à 10", refusal(3, StepMachine(StepProgram.TURBO, pulses = 0)).message)
        assertEquals("Étape 4 : Réglage non proposé par l'éditeur du robot", refusal(3, StepMachine(StepProgram.TURBO, pulses = 3, accessory = Accessory.WHISK)).message)
        // Un programme que ce robot n'a pas.
        assertEquals("Étape 4 : Mixer : absent de ce robot", refusal(3, StepMachine(StepProgram.MIXER, 30)).message)
        // Changer en un réglage refusé un réglage déjà là : refusé aussi.
        assertEquals(ApiError("step_machine", "Étape 2 : En chauffe, vitesse 3 maximum", "steps", 1),
            refusal(1, reviewed.copy(speed = 4)))
        assertEquals(0, f.host.stored.values.count { it != RecipeCodec.encode(mine) })
        assertEquals(mine, f.saved)
    }

    @Test fun anUnchangedStoredSettingThatBreaksARuleIsKeptAndTheTextEditSaves() {
        val f = Fixture()
        val e = f.editable()
        // Texte seul de l'étape « Mixer » (réglage refusé enregistré) : enregistré, réglage et vérification inchangés.
        val text = unchanged(e).let { u -> u.copy(steps = u.steps.map { if (it.from == 2) it.copy(text = "Mixer finement.") else it }) }
        assertEquals(200, f.save(text).status)
        assertEquals(Step("Mixer finement.", refused), f.saved.steps[2])
        // Le même réglage renvoyé tel quel : inchangé, pas rejugé.
        val same = withSetting(f.editable(), 2, refused)
        assertEquals(200, f.save(same).status)
        assertEquals(refused, f.saved.steps[2].machine)
        assertFalse(f.saved.steps[2].needsReview)
        assertTrue(f.host.last!!.typed.isEmpty())
        // Retiré : rien à juger.
        assertEquals(200, f.save(withSetting(f.editable(), 2, null)).status)
        assertNull(f.saved.steps[2].machine)
    }

    @Test fun theSmartJudgesItsProgramsAndRefusesWhatItCannotDo() {
        RobotProfiles.active = RobotProfiles.MC_SMART
        val f = Fixture()
        val e = f.editable()
        assertEquals(200, f.save(withSetting(e, 0, StepMachine(StepProgram.SOUS_VIDE, 3600, tempC = 62))).status)
        assertEquals(StepMachine(StepProgram.SOUS_VIDE, 3600, tempC = 62), f.saved.steps[0].machine)
        val e2 = f.editable()
        fun refusal(m: StepMachine): String {
            val r = f.save(withSetting(e2, 0, m))
            assertEquals(String(r.body), 422, r.status)
            return f.error(r).message
        }
        assertEquals("Étape 1 : Sous-vide : de 40 à 85 °C", refusal(StepMachine(StepProgram.SOUS_VIDE, 3600, tempC = 90)))
        assertEquals("Étape 1 : Durée : de 15 min à 720 min", refusal(StepMachine(StepProgram.SOUS_VIDE, 60, tempC = 60)))
        // Chauffe sans mélange : le Smart ne la fait pas.
        assertEquals("Étape 1 : Vitesse minimale : 1", refusal(StepMachine(seconds = 60, speed = 0, tempC = 90)))
        // 40 °C : dans sa table.
        assertEquals(200, f.save(withSetting(e2, 0, StepMachine(seconds = 600, speed = 1, tempC = 40))).status)
    }

    /** Aucun réglage, même absurde, ne fait lever le juge ; ce qu'il laisse passer passe aussi la sécurité et le robot. */
    @Test fun theJudgeNeverThrowsAndWhatItLetsThroughIsSafe() {
        for (profile in listOf(RobotProfiles.MC_CONNECT, RobotProfiles.MC_SMART)) {
            RobotProfiles.active = profile
            var passed = 0
            for (p in StepProgram.values()) for (sec in listOf(0, 1, 30, 90, 900, 3600, 50_000, 999_999)) for (speed in listOf(0, 1, 3, 4, 8, 11, 100))
                for (t in listOf(0, 37, 40, 62, 90, 95, 100, 130, 1000)) for (rev in listOf(false, true)) for (acc in Accessory.values()) for (pulses in listOf(0, 3, 11)) {
                    val m = StepMachine(p, sec, speed, t, rev, acc, pulses)
                    val why = StepSettings.refusal(m, profile)
                    if (why == null) {
                        passed++
                        assertTrue("$m", m.violations().isEmpty())
                        assertNull("$m", RecipeCompat.stepReason(m, profile))
                    }
                }
            assertTrue(passed > 50)
        }
    }

    // ------------------------------------------------------------------ lecture côté téléphone

    @Test fun theDescriptionReadsBackAndANewerKindIsIgnored() {
        val s = StepSettingsWire.of(RobotProfiles.MC_SMART)
        assertEquals(s, StepSettingsWire.fromMap(Json.parse(Json.write(s.toMap())) as Map<String, Any?>))
        val m = s.toMap() + ("kinds" to (s.kinds.map { it.toMap() } + mapOf("program" to "flamber", "default" to mapOf("program" to "flamber"))))
        assertEquals(s.kinds, StepSettingsWire.fromMap(m).kinds)
        assertNull(SettingKindWire.fromMap(mapOf("program" to "manuel")))
        // Les réglages de départ de l'éditeur du robot, tels quels.
        for (k in s.kinds) assertEquals(StepSettings.default(k.stepProgram!!), k.default)
        // Le départ de la cuisson manuelle, le même que sur le robot.
        val start = StepSettings.manualStart(StepMachine(seconds = 0, speed = 5, tempC = 93), s.manual.limits())
        assertEquals(ManualSettings(seconds = 60, speed = 5, limits = s.manual.limits()).withCelsius(90), start)
        assertEquals(StepMachine(seconds = 60, speed = 5, tempC = 90), StepSettings.manualPicked(start))
    }

    // ------------------------------------------------------------------ vrai serveur

    private var served: CompanionHub? = null

    @Test fun theClientReadsTheEditorAndSavesASetting() {
        val host = Host().also { it.put(mine) }
        val hub = CompanionHub(host, store = MemoryStore()).also { served = it }
        hub.start(0, InetAddress.getByName("127.0.0.1"))
        val c = CompanionClient("127.0.0.1", hub.port)
        c.pairWithCode(hub.pairing.open().code)
        assertNotNull(c.editor().settings)
        val e = c.editable(mine.id)
        val saved = c.saveRecipe(mine.id, withSetting(e, 4, StepMachine(StepProgram.STEAM, 1200)))
        assertTrue(saved.steps[4].needsReview)
        assertEquals("Vapeur 20 min", saved.steps[4].machine!!.summary)
        try {
            c.saveRecipe(mine.id, withSetting(saved, 4, StepMachine(StepProgram.STEAM, 7200)))
            fail()
        } catch (x: CompanionException) {
            assertEquals(422, x.status)
            assertEquals(ApiError("step_machine", "Étape 5 : Durée : de 1 min à 99 min", "steps", 4), x.error)
        }
    }
}
