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
import app.toque.core.companion.FavoriteReply
import app.toque.core.companion.FavoriteRequest
import app.toque.core.companion.HttpRequest
import app.toque.core.companion.HttpResponse
import app.toque.core.companion.ImageData
import app.toque.core.companion.JournalPage
import app.toque.core.companion.MemoryStore
import app.toque.core.companion.Origin
import app.toque.core.companion.Permissions
import app.toque.core.companion.StateWire
import app.toque.core.companion.WaitingFor
import app.toque.core.companion.client.CompanionClient
import app.toque.core.companion.client.CompanionException
import app.toque.core.json.Json
import app.toque.core.machine.JobState
import app.toque.core.machine.MachineController
import app.toque.core.machine.Programs
import app.toque.core.protocol.Direction
import app.toque.core.recipes.Recipe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.util.Random

/** Favoris choisis depuis le téléphone et « Arrêter la recette » depuis le téléphone (routes, règles, journal, client). */
class CompanionFavoriteStopTest {

    /**
     * Hôte d'essai qui sait les deux nouvelles commandes ([features] faux : un hôte plus ancien).
     * [stopRecipe] fait ce que fait l'écran de la recette guidée : arrête le programme et clôt la session.
     */
    private class Host(val features: Boolean = true, var controller: MachineController? = null) : CompanionHost {
        lateinit var hub: CompanionHub
        val calls = ArrayList<String>()
        val favorites = HashMap<String, Boolean>()
        /** Une recette guidée est ouverte sur l'« écran » du robot. */
        var cookOpen = false

        override fun pause() = CommandResult.OK
        override fun resume(stillAllowed: () -> Boolean) = CommandResult.OK
        override fun stop(): CommandResult {
            calls += "stop"
            return controller?.let { CommandResult.of(it.stop()) } ?: CommandResult.OK
        }
        override fun adjust(prepare: () -> AdjustCommand) = CommandResult.OK
        override fun tare() = CommandResult.OK
        override fun catalog(query: CatalogQuery): CatalogPageWire = CatalogPageWire()
        override fun recipe(id: String): Recipe? = CompanionData.byId[id]
        override fun image(recipeId: String): ImageData? = null

        override val setsFavorites get() = features
        override fun setFavorite(recipeId: String, favorite: Boolean): CommandResult {
            calls += "favorite:$recipeId:$favorite"
            if (CompanionData.byId[recipeId] == null) return CommandResult.refuse("not_found", "Recette introuvable")
            favorites[recipeId] = favorite
            return CommandResult.OK
        }

        override val stopsRecipes get() = features
        override fun stopRecipe(): CommandResult {
            calls += "stopRecipe"
            if (!cookOpen) return CommandResult.refuse("no_recipe", "Aucune recette en cours sur le robot")
            cookOpen = false
            controller?.let { if (it.snapshot().jobActive) it.stop() }
            hub.endSession(false)
            return CommandResult.OK
        }
    }

    private class Fixture(val host: Host = Host()) {
        val clock = FakeCompanionClock()
        val hub = CompanionHub(host, clock, MemoryStore(), Random(7)).also { host.hub = it }
        val api = CompanionApi(hub)

        fun pair(): String = hub.pairing.pairWithCode(hub.pairing.open().code).token!!

        fun req(method: String, path: String, token: String?, body: Any? = null): HttpResponse {
            val headers = linkedMapOf("host" to "192.168.0.12:8088")
            if (token != null) headers["authorization"] = "Bearer $token"
            val bytes = if (body == null) ByteArray(0) else Json.write(body).toByteArray()
            if (body != null) headers["content-type"] = "application/json"
            return api.handle(HttpRequest(method, path, emptyMap(), headers, bytes, "192.168.0.20"))
        }

        @Suppress("UNCHECKED_CAST")
        fun json(r: HttpResponse) = Json.parse(String(r.body, Charsets.UTF_8)) as Map<String, Any?>
        fun state(t: String) = StateWire.fromMap(json(req("GET", "/api/state", t)))
        fun error(r: HttpResponse) = ApiError.fromMap(json(r))
    }

    // ------------------------------------------------------------------ favoris

    @Test fun anyPairedPhoneChangesAFavoriteAndAnUnknownRecipeIsACleanError() {
        val f = Fixture()
        val t = f.pair()
        val rev = f.hub.currentRevision
        assertTrue(f.state(t).can.favorite.allowed)

        val r = f.req("POST", "/api/recipes/r1/favorite", t, mapOf("favorite" to true))
        assertEquals(200, r.status)
        assertEquals(FavoriteReply("r1", true), FavoriteReply.fromMap(f.json(r)))
        assertEquals(true, f.host.favorites["r1"])
        assertEquals(200, f.req("POST", "/api/recipes/r1/favorite", f.pair(), mapOf("favorite" to false)).status)
        assertEquals(false, f.host.favorites["r1"])
        // Rien de l'état ne change : pas de nouvelle révision, pas de ligne de journal.
        assertEquals(rev, f.hub.currentRevision)
        assertEquals(0L, f.state(t).journalSeq)

        val unknown = f.req("POST", "/api/recipes/nope/favorite", t, mapOf("favorite" to true))
        assertEquals(404, unknown.status)
        assertEquals("not_found", f.error(unknown).code)
        // Corps strict : un booléen, rien d'autre.
        for (bad in listOf(mapOf("favorite" to "yes"), mapOf("favorite" to 1), emptyMap<String, Any?>(),
                mapOf("favorite" to true, "other" to 1)))
            assertEquals("$bad", 400, f.req("POST", "/api/recipes/r1/favorite", t, bad).status)
        assertEquals(405, f.req("GET", "/api/recipes/r1/favorite", t).status)
        // Sans jeton : 401, rien n'atteint l'hôte.
        val before = f.host.calls.size
        assertEquals(401, f.req("POST", "/api/recipes/r1/favorite", null, mapOf("favorite" to true)).status)
        assertEquals(before, f.host.calls.size)
    }

    @Test fun anOlderRobotHostOffersNoFavoriteAndNoStop() {
        val f = Fixture(Host(features = false))
        val t = f.pair()
        val s = f.state(t)
        assertFalse(s.can.favorite.allowed)
        assertFalse(s.can.stopRecipe.allowed)
        f.hub.updateSession(CookSession.of(CompanionData.veloute, 0, waitingFor = WaitingFor.HAND))
        assertFalse(f.state(t).can.stopRecipe.allowed)
        assertEquals(409, f.req("POST", "/api/recipes/r1/favorite", t, mapOf("favorite" to true)).status)
        assertEquals("unsupported", f.error(f.req("POST", "/api/session/stop", t)).code)
        assertTrue(f.host.calls.isEmpty())
    }

    @Test fun wireIsReadByOlderAndNewerSides() {
        // Un robot plus ancien n'envoie ni « favorite » ni « stopRecipe » : refusés.
        val old = Permissions.fromMap(mapOf("pause" to mapOf("allowed" to true)))
        assertTrue(old.pause.allowed)
        assertFalse(old.favorite.allowed)
        assertFalse(old.stopRecipe.allowed)
        // Un robot récent : les deux clés, relues à l'identique (un téléphone plus ancien les ignore).
        val p = Permissions(favorite = app.toque.core.companion.Permission.YES,
            stopRecipe = app.toque.core.companion.Permission.no("no_recipe", "Aucune recette"))
        @Suppress("UNCHECKED_CAST")
        val back = Permissions.fromMap(Json.parse(Json.write(p.toMap())) as Map<String, Any?>)
        assertEquals(p, back)
        assertEquals(FavoriteRequest(true), FavoriteRequest.fromMap(mapOf("favorite" to true)))
        assertEquals(FavoriteReply(), FavoriteReply.fromMap(emptyMap()))
    }

    // ------------------------------------------------------------------ arrêter la recette

    @Test fun stoppingTheRecipeNeedsAGuidedRecipeAndIsJournaledForThePhone() {
        val b = Bench().ready()
        val host = Host(controller = b.c)
        val f = Fixture(host)
        val t = f.pair()
        val other = f.pair()
        fun sync(ms: Long = 200) {
            b.step(ms)
            f.clock.advance(ms)
            f.hub.updateMachine(b.snap)
        }
        sync()
        // Aucune recette : refusé, rien n'est appelé.
        assertEquals("no_recipe", f.state(t).can.stopRecipe.code)
        val none = f.req("POST", "/api/session/stop", t)
        assertEquals(409, none.status)
        assertEquals("no_recipe", f.error(none).code)
        assertTrue(host.calls.isEmpty())

        // Recette guidée ouverte, une étape en cours.
        val r = CompanionData.veloute
        host.cookOpen = true
        f.hub.updateSession(CookSession.of(r, 2, setOf(0, 1), waitingFor = WaitingFor.LAUNCH))
        assertTrue(b.c.startJob(Programs.manual(1, 0, Direction.CLOCKWISE, 120_000, label = r.title)).ok)
        f.hub.updateSession(CookSession.of(r, 2, setOf(0, 1), listOf(2), WaitingFor.NOTHING))
        sync(1_000)
        assertTrue(f.state(t).can.stopRecipe.allowed)
        assertTrue(f.state(other).can.stopRecipe.allowed)

        assertEquals(200, f.req("POST", "/api/session/stop", t).status)
        assertEquals(listOf("stopRecipe"), host.calls)
        sync()
        sync()
        assertEquals(JobState.ABORTED.name, b.snap.jobState)
        val s = f.state(t)
        assertEquals(null, s.session)
        assertEquals("no_recipe", s.can.stopRecipe.code)

        val page = JournalPage.fromMap(f.json(f.req("GET", "/api/journal", other)))
        val end = page.entries.first { it.kind == "recipe_abandoned" }
        assertEquals("Recette arrêtée depuis Téléphone 1", end.text)
        assertEquals(Origin.PHONE, end.origin.kind)
        assertFalse(end.origin.self)
        val stop = page.entries.first { it.kind == "job_stopped" }
        assertEquals("Téléphone 1", stop.origin.name)
        assertTrue(stop.text, stop.text.startsWith("Arrêté depuis Téléphone 1"))
        val activity = page.activities.first { it.kind == "recipe" }
        assertEquals("abandoned", activity.outcome)
        assertEquals("perso-veloute", activity.recipeId)
        assertEquals("Téléphone 1", activity.endOrigin?.name)

        // Une seconde demande : plus de recette, refusée sans rien appeler.
        assertEquals(409, f.req("POST", "/api/session/stop", t).status)
        assertEquals(1, host.calls.size)
    }

    @Test fun aRecipeLeftOnTheRobotIsStillJournaledAsBefore() {
        val f = Fixture()
        val t = f.pair()
        f.hub.updateSession(CookSession.of(CompanionData.veloute, 0, waitingFor = WaitingFor.HAND))
        f.hub.endSession(false)
        val page = JournalPage.fromMap(f.json(f.req("GET", "/api/journal", t)))
        val end = page.entries.first { it.kind == "recipe_abandoned" }
        assertEquals("Recette quittée sur le robot", end.text)
        assertEquals(Origin.ROBOT, end.origin.kind)
    }

    @Test fun theHostRefusalComesBackAndNothingIsAttributed() {
        val f = Fixture()
        val t = f.pair()
        // La session existe pour le hub, mais l'écran n'a plus de recette (fermée entre-temps).
        f.hub.updateSession(CookSession.of(CompanionData.veloute, 0, waitingFor = WaitingFor.HAND))
        val r = f.req("POST", "/api/session/stop", t)
        assertEquals(409, r.status)
        assertEquals("no_recipe", f.error(r).code)
        // Quittée ensuite sur le robot : la ligne reste celle du robot.
        f.hub.endSession(false)
        val page = JournalPage.fromMap(f.json(f.req("GET", "/api/journal", t)))
        assertEquals("Recette quittée sur le robot", page.entries.first { it.kind == "recipe_abandoned" }.text)
    }

    // ------------------------------------------------------------------ client, vrai serveur

    private var served: CompanionHub? = null

    @After fun tearDown() {
        served?.stop()
    }

    @Test fun theClientCallsBothRoutesOverHttp() {
        val host = Host()
        val hub = CompanionHub(host, store = MemoryStore()).also { host.hub = it; served = it }
        hub.start(0, InetAddress.getByName("127.0.0.1"))
        val c = CompanionClient("127.0.0.1", hub.port)
        c.pairWithCode(hub.pairing.open().code)

        assertTrue(c.state().can.favorite.allowed)
        assertEquals(FavoriteReply("perso-veloute", false), c.setFavorite("perso-veloute", false))
        assertEquals(false, host.favorites["perso-veloute"])
        try {
            c.setFavorite("inconnue/avec une barre", true)
            fail()
        } catch (e: CompanionException) {
            assertEquals(404, e.status)
            assertEquals("not_found", e.error.code)
        }

        try {
            c.stopRecipe()
            fail()
        } catch (e: CompanionException) {
            assertEquals(409, e.status)
            assertEquals("no_recipe", e.error.code)
        }
        host.cookOpen = true
        hub.updateSession(CookSession.of(CompanionData.veloute, 0, waitingFor = WaitingFor.HAND))
        assertTrue(c.state().can.stopRecipe.allowed)
        val reply = c.stopRecipe()
        assertTrue(reply.ok)
        val st = c.state()
        assertTrue(st.revision >= reply.revision)
        assertEquals(null, st.session)
    }
}
