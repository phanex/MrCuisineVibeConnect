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
import app.toque.core.companion.HttpRequest
import app.toque.core.companion.HttpResponse
import app.toque.core.companion.ImageData
import app.toque.core.companion.MemoryStore
import app.toque.core.companion.OwnStepRequest
import app.toque.core.companion.OwnStepWrite
import app.toque.core.companion.OwnStepsRead
import app.toque.core.companion.OwnStepsWire
import app.toque.core.companion.Permissions
import app.toque.core.companion.RecipeDetail
import app.toque.core.companion.SettingWire
import app.toque.core.companion.StateWire
import app.toque.core.companion.client.CompanionClient
import app.toque.core.companion.client.CompanionException
import app.toque.core.json.Json
import app.toque.core.recipes.OwnStepSetting
import app.toque.core.recipes.OwnStepSettings
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeAdjustments
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.SettingAuthor
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.core.recipes.StepSettings
import app.toque.core.recipes.adjusted
import app.toque.core.robot.RobotProfiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.util.Random

/**
 * Le réglage du propriétaire d'une étape d'une recette Mealie, donné depuis le téléphone sans copier la
 * recette : annoncé (can.ownSettings, RecipeDetail.ownSettings, empreinte de chaque étape), jugé par la règle
 * des étapes perso et l'éditeur du robot, refusé sur une étape dont le texte a changé, enregistré comme venu
 * d'un téléphone (à vérifier sur le robot). Un robot qui ne le sait pas faire garde son JSON d'avant.
 */
class CompanionOwnStepsTest {
    /** Recette inventée, telle que la synchro Mealie l'écrit (réglages lus dans le texte, à vérifier). */
    private val tarte = Recipe(
        id = "mealie-aaaa0000-1111-4222-8333-444444444444", title = "Tarte d'essai", category = "Desserts", servings = 6,
        source = RecipeSource.MEALIE,
        steps = listOf(
            Step("Mettre la farine et le beurre dans le bol."),
            Step("Pétrir 2 min.", StepMachine(StepProgram.MANUAL, 120, 4), needsReview = true),
            Step("Cuire la compote 10 min à 90 °C.", StepMachine(StepProgram.MANUAL, 600, 1, 90), needsReview = true),
            Step("Mettre la farine et le beurre dans le bol."),
        ),
    )
    private val mine = Recipe(id = "perso-essai-1", title = "Recette à moi", source = RecipeSource.USER,
        steps = listOf(Step("Mélanger.", StepMachine(StepProgram.MANUAL, 30, 2))))
    private val knead = StepSettings.default(StepProgram.KNEAD)
    private val cook = StepMachine(StepProgram.MANUAL, 300, 1, 100)

    @After fun reset() {
        RobotProfiles.active = RobotProfiles.MC_CONNECT
        served?.stop()
    }

    /** Hôte d'essai : comme RobotHost, la recette de la source et ses adaptations, écrites d'un bloc. */
    private class Host(val takes: Boolean = true) : CompanionHost {
        val recipes = LinkedHashMap<String, Recipe>()
        val adjustments = HashMap<String, RecipeAdjustments>()
        val writes = ArrayList<String>()
        /** Rangs dont les vérifications gardées ont été oubliées (CookScreen.forgetConfirmations). */
        val forgotten = ArrayList<Int>()

        override fun pause() = CommandResult.OK
        override fun resume(stillAllowed: () -> Boolean) = CommandResult.OK
        override fun stop() = CommandResult.OK
        override fun adjust(prepare: () -> AdjustCommand) = CommandResult.OK
        override fun tare() = CommandResult.OK
        override fun catalog(query: CatalogQuery): CatalogPageWire = CatalogPageWire()
        override fun recipe(id: String): Recipe? = recipes[id]?.let { it.adjusted(adjustments[id] ?: RecipeAdjustments()) }
        override fun image(recipeId: String): ImageData? = null
        override val takesOwnSteps get() = takes
        override fun ownSteps(id: String): OwnStepsRead? {
            val r = recipes[id] ?: return null
            val a = adjustments[id] ?: RecipeAdjustments()
            return OwnStepsRead(r, r.adjusted(a), a.steps)
        }

        @Synchronized override fun writeOwnStep(id: String, decide: (read: OwnStepsRead?) -> OwnStepWrite): OwnStepWrite {
            val w = decide(ownSteps(id))
            if (w.error != null || !w.changed) return w.done(ownSteps(id))
            val r = recipes[id]!!
            val a = adjustments[id] ?: RecipeAdjustments()
            adjustments[id] = if (w.clear) a.withoutStep(r, w.index) else a.withStep(r, w.index, w.machine, SettingAuthor.PHONE)
            forgotten += w.index
            writes += "${w.phone}:${w.index}:${if (w.clear) "clear" else w.machine?.summary}"
            return w.done(ownSteps(id))
        }
    }

    private inner class Fixture(val host: Host = Host()) {
        val hub = CompanionHub(host, FakeCompanionClock(), MemoryStore(), Random(7))
        val api = CompanionApi(hub)
        val token = hub.pairing.pairWithCode(hub.pairing.open().code).token!!

        init {
            host.recipes[tarte.id] = tarte
            host.recipes[mine.id] = mine
        }

        fun req(method: String, path: String, body: Any? = null, query: Map<String, String> = emptyMap()): HttpResponse {
            val headers = linkedMapOf("host" to "192.168.0.12:8088", "authorization" to "Bearer $token")
            val bytes = if (body == null) ByteArray(0) else Json.write(body).toByteArray()
            if (body != null) headers["content-type"] = "application/json"
            return api.handle(HttpRequest(method, path, query, headers, bytes, "192.168.0.20"))
        }

        @Suppress("UNCHECKED_CAST")
        fun json(r: HttpResponse) = Json.parse(String(r.body, Charsets.UTF_8)) as Map<String, Any?>
        fun error(r: HttpResponse) = ApiError.fromMap(json(r))
        fun detail(id: String = tarte.id) = RecipeDetail.fromMap(json(req("GET", "/api/recipes/$id")))
        fun steps(id: String = tarte.id) = OwnStepsWire.fromMap(json(req("GET", "/api/recipes/$id/steps")))
        fun set(index: Int, machine: StepMachine?, key: String = OwnStepSettings.keys(tarte)[index], id: String = tarte.id) =
            req("POST", "/api/recipes/$id/steps/$index", OwnStepRequest(key, machine).toMap())
        val own: Map<String, OwnStepSetting> get() = host.adjustments[tarte.id]?.steps ?: emptyMap()
    }

    @Test fun theRobotAnnouncesItAndAMealieSheetCarriesItsStepKeys() {
        val f = Fixture()
        val state = StateWire.fromMap(f.json(f.req("GET", "/api/state")))
        assertTrue(state.can.ownSettings.allowed)
        val d = f.detail()
        assertTrue(d.ownSettings)
        assertEquals(OwnStepSettings.keys(tarte), d.steps.map { it.key })
        // Deux étapes au même texte : leur ordre les distingue.
        assertTrue(d.steps[3].key.endsWith("-2"))
        assertTrue(d.steps.all { it.own.isEmpty() })
        // Une recette à soi ne prend pas de réglage du propriétaire : sa fiche n'en dit rien.
        val m = f.json(f.req("GET", "/api/recipes/${mine.id}"))
        assertFalse("ownSettings" in m)
        @Suppress("UNCHECKED_CAST")
        val step = (m["steps"] as List<Map<String, Any?>>)[0]
        assertFalse("key" in step)
        assertFalse("own" in step)
    }

    @Test fun anOlderRobotKeepsItsJsonAndRefusesTheRoutes() {
        val f = Fixture(Host(takes = false))
        val state = StateWire.fromMap(f.json(f.req("GET", "/api/state")))
        assertFalse(state.can.ownSettings.allowed)
        assertEquals("unsupported", state.can.ownSettings.code)
        val m = f.json(f.req("GET", "/api/recipes/${tarte.id}"))
        assertFalse("ownSettings" in m)
        @Suppress("UNCHECKED_CAST")
        assertTrue((m["steps"] as List<Map<String, Any?>>).none { "key" in it || "own" in it })
        val r = f.req("GET", "/api/recipes/${tarte.id}/steps")
        assertEquals(409, r.status)
        assertEquals("unsupported", f.error(r).code)
        val w = f.set(2, cook)
        assertEquals(409, w.status)
        assertTrue(f.host.writes.isEmpty())
        // Un téléphone plus récent lit l'état d'un robot plus ancien (sans le champ) : refusé.
        val old = f.json(f.req("GET", "/api/state")).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        old["can"] = (old["can"] as Map<String, Any?>) - "ownSettings"
        assertFalse(StateWire.fromMap(old).can.ownSettings.allowed)
        assertFalse(Permissions.fromMap(emptyMap()).ownSettings.allowed)
    }

    @Test fun theStepsFormGivesKeysTheRecipesOwnSettingAndTheEditor() {
        val f = Fixture()
        val s = f.steps()
        assertEquals(tarte.id, s.id)
        assertEquals(OwnStepSettings.keys(tarte), s.steps.map { it.key })
        assertEquals("120", s.steps[1].recipe?.seconds?.toString())
        assertTrue(s.steps[1].needsReview)
        assertNull(s.steps[0].machine)
        assertTrue(s.settings.kinds.isNotEmpty())
        assertTrue(s.settings.kind(StepProgram.KNEAD) != null)
        // Une recette à soi : « faites-en une copie » n'a pas de sens, elle s'édite ; refus clair.
        val mineSteps = f.req("GET", "/api/recipes/${mine.id}/steps")
        assertEquals(409, mineSteps.status)
        assertEquals(OwnStepSettings.NOT_ALLOWED, f.error(mineSteps).code)
        assertEquals(404, f.req("GET", "/api/recipes/inconnue/steps").status)
    }

    @Test fun aSettingFromThePhoneIsStoredAsThePhonesAndStaysToCheck() {
        val f = Fixture()
        val r = f.set(1, knead)
        assertEquals(200, r.status)
        val reply = OwnStepsWire.fromMap(f.json(r))
        assertEquals("phone", reply.steps[1].own)
        assertTrue(reply.steps[1].needsReview)
        assertEquals(knead.summary, reply.steps[1].machine?.summary)
        // Enregistré par l'hôte comme venu d'un téléphone, au bon rang, vérifications de ce rang oubliées.
        val key = OwnStepSettings.keys(tarte)[1]
        assertEquals(OwnStepSetting(knead, SettingAuthor.PHONE), f.own[key])
        assertEquals(listOf(1), f.host.forgotten)
        assertTrue(f.host.writes.single().endsWith(":1:${knead.summary}"))
        // La recette telle que le robot la cuisine : à vérifier, sur sa propre carte, jamais enchaînée.
        val cooked = f.host.recipe(tarte.id)!!.steps[1]
        assertEquals(knead, cooked.machine)
        assertTrue(cooked.needsReview)
        assertTrue(cooked.reviewAlone)
        // La fiche du téléphone marque l'étape.
        val d = f.detail()
        assertEquals("phone", d.steps[1].own)
        assertTrue(d.steps[1].needsReview)
        assertEquals("", d.steps[2].own)
    }

    @Test fun aStepWhoseTextChangedOnTheRobotIsRefusedAsStale() {
        val f = Fixture()
        val oldKey = OwnStepSettings.keys(tarte)[2]
        // Le texte de l'étape 3 change à la source (synchro Mealie) après la lecture du téléphone.
        f.host.recipes[tarte.id] = tarte.copy(steps = tarte.steps.mapIndexed { i, s -> if (i == 2) s.copy(text = "Cuire la compote 12 min.") else s })
        val r = f.set(2, cook, key = oldKey)
        assertEquals(409, r.status)
        assertEquals("stale", f.error(r).code)
        assertEquals("La recette a changé sur le robot", f.error(r).message)
        assertTrue(f.own.isEmpty())
        // Une étape déplacée : la même empreinte à un autre rang est refusée aussi (le téléphone relit).
        f.host.recipes[tarte.id] = tarte
        assertEquals("stale", f.error(f.set(1, cook, key = OwnStepSettings.keys(tarte)[2])).code)
        // Un rang qui n'existe plus.
        assertEquals("stale", f.error(f.set(9, cook, key = OwnStepSettings.keys(tarte)[2])).code)
        // Retour au réglage de la recette : même règle.
        val d = f.req("DELETE", "/api/recipes/${tarte.id}/steps/1", query = mapOf("key" to OwnStepSettings.keys(tarte)[2]))
        assertEquals("stale", f.error(d).code)
        assertTrue(f.host.writes.isEmpty())
    }

    @Test fun aRefusedSettingComesBackWithTheRobotsReasonAndNothingIsStored() {
        val f = Fixture()
        // Vitesse 8 en chauffe : la règle des étapes perso (sécurité) refuse.
        val hot = f.set(2, StepMachine(StepProgram.MANUAL, 60, 8, 100))
        assertEquals(422, hot.status)
        val e = f.error(hot)
        assertEquals("step_machine", e.code)
        assertEquals("steps", e.field)
        assertEquals(2, e.step)
        assertTrue(e.message, e.message.startsWith("Étape 3 : "))
        // Un champ que l'éditeur du robot ne règle pas (vitesse d'un pétrissage) : refusé comme tout réglage tapé.
        val odd = f.set(1, knead.copy(seconds = 600))
        assertEquals(422, odd.status)
        assertEquals("step_machine", f.error(odd).code)
        assertTrue(f.error(odd).message, f.error(odd).message.startsWith("Étape 2 : "))
        // Un programme absent du Connect.
        val smart = f.set(1, StepSettings.default(StepProgram.SOUS_VIDE))
        assertEquals(422, smart.status)
        assertTrue(f.own.isEmpty())
        assertTrue(f.host.writes.isEmpty())
    }

    @Test fun nothingIsWrittenWhileTheRecipeCooksOnTheRobot() {
        val f = Fixture()
        f.hub.updateSession(CookSession.of(tarte, 0))
        val r = f.set(2, cook)
        assertEquals(409, r.status)
        assertEquals("cooking", f.error(r).code)
        assertTrue(f.host.writes.isEmpty())
        f.hub.endSession(false)
        assertEquals(200, f.set(2, cook).status)
    }

    @Test fun backToTheRecipesSettingAndNoRobotSetting() {
        val f = Fixture()
        val key = OwnStepSettings.keys(tarte)[2]
        // « Sans le robot » à cette étape : rien à vérifier.
        assertEquals(200, f.set(2, null).status)
        assertEquals(OwnStepSetting(null, SettingAuthor.PHONE), f.own[key])
        val cooked = f.host.recipe(tarte.id)!!.steps[2]
        assertNull(cooked.machine)
        assertFalse(cooked.needsReview)
        val d = f.req("DELETE", "/api/recipes/${tarte.id}/steps/2", query = mapOf("key" to key))
        assertEquals(200, d.status)
        assertTrue(f.own.isEmpty())
        assertEquals("", OwnStepsWire.fromMap(f.json(d)).steps[2].own)
        assertEquals(tarte.steps[2].machine, f.host.recipe(tarte.id)!!.steps[2].machine)
        // Rien à retirer : rien d'écrit.
        val n = f.host.writes.size
        assertEquals(200, f.req("DELETE", "/api/recipes/${tarte.id}/steps/2", query = mapOf("key" to key)).status)
        assertEquals(n, f.host.writes.size)
        assertEquals(400, f.req("DELETE", "/api/recipes/${tarte.id}/steps/2", query = mapOf("key" to "pas-une-cle")).status)
        assertEquals(400, f.req("DELETE", "/api/recipes/${tarte.id}/steps/2").status)
    }

    @Test fun theSameSettingAsTheRobotOwnersKeepsItsAuthor() {
        val f = Fixture()
        val key = OwnStepSettings.keys(tarte)[1]
        f.host.adjustments[tarte.id] = RecipeAdjustments().withStep(tarte, 1, knead, SettingAuthor.ROBOT)
        assertEquals(200, f.set(1, knead).status)
        assertEquals(SettingAuthor.ROBOT, f.own[key]!!.by)
        assertTrue(f.host.writes.isEmpty())
        // Un autre réglage, lui, vient du téléphone.
        assertEquals(200, f.set(1, StepMachine(StepProgram.KNEAD, 105, 4)).status)
        assertEquals(SettingAuthor.PHONE, f.own[key]!!.by)
    }

    @Test fun theBodyIsReadStrictly() {
        val f = Fixture()
        val key = OwnStepSettings.keys(tarte)[2]
        val good = SettingWire.toMap(cook)
        fun post(body: Any?, index: String = "2") = f.req("POST", "/api/recipes/${tarte.id}/steps/$index", body)
        assertEquals(400, post(mapOf("key" to key)).status)
        assertEquals(400, post(mapOf("key" to key, "machine" to good, "needsReview" to false)).status)
        assertEquals(400, post(mapOf("key" to "1234", "machine" to good)).status)
        val bad = post(mapOf("key" to key, "machine" to good + ("summary" to "x")))
        assertEquals(400, bad.status)
        assertEquals(2, f.error(bad).step)
        assertEquals(400, post(mapOf("key" to key, "machine" to "manuel")).status)
        assertEquals(400, post(mapOf("key" to key, "machine" to good), index = "deux").status)
        assertEquals(400, post(mapOf("key" to key, "machine" to good), index = "-1").status)
        // GET sur un rang, PUT : la route existe, pas cette méthode.
        assertEquals(405, f.req("GET", "/api/recipes/${tarte.id}/steps/2").status)
        assertEquals(405, f.req("POST", "/api/recipes/${tarte.id}/steps").status)
        assertTrue(f.host.writes.isEmpty())
        assertEquals(200, post(mapOf("key" to key, "machine" to good)).status)
    }

    @Test fun wireRoundTripsAndOlderJson() {
        val d = RecipeDetail(id = "x", ownSettings = true, steps = listOf(app.toque.core.companion.StepWire(0, "a", key = "0123456789abcdef", own = "phone")))
        assertEquals(d, RecipeDetail.fromMap(Json.parse(Json.write(d.toMap())).let {
            @Suppress("UNCHECKED_CAST")
            it as Map<String, Any?>
        }))
        assertFalse(RecipeDetail.fromMap(mapOf("id" to "x")).ownSettings)
        val s = Fixture().steps()
        assertEquals(s, OwnStepsWire.fromMap(Json.parse(Json.write(s.toMap())).let {
            @Suppress("UNCHECKED_CAST")
            it as Map<String, Any?>
        }))
    }

    private var served: CompanionHub? = null

    @Test fun theClientLibraryWritesAndReadsBack() {
        val host = Host().also { it.recipes[tarte.id] = tarte }
        val hub = CompanionHub(host, store = MemoryStore()).also { served = it }
        hub.start(0, InetAddress.getByName("127.0.0.1"))
        val c = CompanionClient("127.0.0.1", hub.port)
        c.pairWithCode(hub.pairing.open().code)
        val steps = c.ownSteps(tarte.id)
        val after = c.setOwnStep(tarte.id, 2, steps.steps[2].key, cook)
        assertEquals("phone", after.steps[2].own)
        assertEquals("phone", c.recipe(tarte.id).steps[2].own)
        try {
            c.setOwnStep(tarte.id, 1, steps.steps[2].key, cook)
            fail("étape désignée par une autre empreinte")
        } catch (e: CompanionException) {
            assertEquals(409, e.status)
            assertEquals("stale", e.error.code)
        }
        val back = c.clearOwnStep(tarte.id, 2, steps.steps[2].key)
        assertEquals("", back.steps[2].own)
        assertTrue(host.adjustments[tarte.id]!!.steps.isEmpty())
    }
}
