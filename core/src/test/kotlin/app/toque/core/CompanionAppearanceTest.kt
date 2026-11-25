package app.toque.core

import app.toque.core.companion.AdjustCommand
import app.toque.core.companion.ApiError
import app.toque.core.companion.AppearanceRequest
import app.toque.core.companion.CatalogPageWire
import app.toque.core.companion.CatalogQuery
import app.toque.core.companion.CommandResult
import app.toque.core.companion.CompanionApi
import app.toque.core.companion.CompanionHost
import app.toque.core.companion.CompanionHub
import app.toque.core.companion.HttpRequest
import app.toque.core.companion.HttpResponse
import app.toque.core.companion.ImageData
import app.toque.core.companion.JournalPage
import app.toque.core.companion.MemoryStore
import app.toque.core.companion.Permissions
import app.toque.core.companion.RobotInfo
import app.toque.core.companion.RobotLooks
import app.toque.core.companion.StateWire
import app.toque.core.json.Json
import app.toque.core.recipes.Recipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Couleur et thème du robot choisis depuis un téléphone : POST /api/robot/appearance, can.setAppearance, journal. */
class CompanionAppearanceTest {

    /**
     * Hôte d'essai : applique comme les Réglages du robot, ou refuse « busy » ; [features] faux : un hôte plus
     * ancien ; [anyAccent] faux : un hôte qui ne prend que les dix couleurs (avant la roue).
     */
    private class Host(val features: Boolean = true, val anyAccent: Boolean = true) : CompanionHost {
        lateinit var hub: CompanionHub
        val calls = ArrayList<String>()
        var busy = false
        var accent = "#FF8A3D"
        var theme = "dark"

        override fun pause() = CommandResult.OK
        override fun resume(stillAllowed: () -> Boolean) = CommandResult.OK
        override fun stop() = CommandResult.OK
        override fun adjust(prepare: () -> AdjustCommand) = CommandResult.OK
        override fun tare() = CommandResult.OK
        override fun catalog(query: CatalogQuery): CatalogPageWire = CatalogPageWire()
        override fun recipe(id: String): Recipe? = null
        override fun image(recipeId: String): ImageData? = null

        override val setsAppearance get() = features
        override val setsAnyAccent get() = anyAccent
        override fun setAppearance(accent: String?, theme: String?): CommandResult {
            calls += "appearance:$accent:$theme"
            if (busy) return CommandResult.refuse("busy", "Le robot n'est pas sur son écran d'accueil : revenez-y sur le robot, puis réessayez")
            if (accent != null) this.accent = accent
            if (theme != null) this.theme = theme
            // Comme l'appli après la reconstruction de l'interface (refreshRobot).
            hub.updateRobot(RobotInfo("Robot", "0.8.1", "fr", accent = this.accent, theme = this.theme))
            return CommandResult.OK
        }
    }

    private class Fixture(val host: Host = Host()) {
        val clock = FakeCompanionClock()
        val hub = CompanionHub(host, clock, MemoryStore(), Random(7)).also {
            host.hub = it
            it.updateRobot(RobotInfo("Robot", "0.8.1", "fr", accent = host.accent, theme = host.theme))
        }
        val api = CompanionApi(hub)

        fun pair(): String = hub.pairing.pairWithCode(hub.pairing.open().code).token!!

        fun req(method: String, path: String, token: String?, body: Any? = null, raw: String? = null): HttpResponse {
            val headers = linkedMapOf("host" to "192.168.0.12:8088")
            if (token != null) headers["authorization"] = "Bearer $token"
            val bytes = raw?.toByteArray() ?: if (body == null) ByteArray(0) else Json.write(body).toByteArray()
            if (body != null || raw != null) headers["content-type"] = "application/json"
            return api.handle(HttpRequest(method, path, emptyMap(), headers, bytes, "192.168.0.20"))
        }

        @Suppress("UNCHECKED_CAST")
        fun json(r: HttpResponse) = Json.parse(String(r.body, Charsets.UTF_8)) as Map<String, Any?>
        fun state(t: String) = StateWire.fromMap(json(req("GET", "/api/state", t)))
        fun error(r: HttpResponse) = ApiError.fromMap(json(r))
        fun journal(t: String) = JournalPage.fromMap(json(req("GET", "/api/journal", t)))
        fun set(t: String, body: Any?) = req("POST", "/api/robot/appearance", t, body)
    }

    @Test fun aPhoneSetsTheColourAndThemeOnHome() {
        val f = Fixture()
        val t = f.pair()
        assertTrue(f.state(t).can.setAppearance.allowed)
        val rev = f.state(t).revision
        val r = f.set(t, mapOf("accent" to "#4fc3f7", "theme" to "light"))
        assertEquals(200, r.status)
        assertEquals(listOf("appearance:#4FC3F7:light"), f.host.calls)
        val s = f.state(t)
        assertTrue(s.revision > rev)
        assertEquals("#4FC3F7", s.robot.accent)
        assertEquals("light", s.robot.theme)
        val line = f.journal(t).entries.last { it.kind == "appearance" }
        assertEquals("Apparence changée depuis Téléphone 1 : couleur #4FC3F7, mode clair", line.text)
        assertTrue(line.origin.self)

        // Une seule des deux : seule celle-là est demandée.
        assertEquals(200, f.set(t, mapOf("theme" to "dark")).status)
        assertEquals("appearance:null:dark", f.host.calls.last())
    }

    @Test fun theSameLookIsANoOpWithoutRebuilding() {
        val f = Fixture()
        val t = f.pair()
        val rev = f.state(t).revision
        assertEquals(200, f.set(t, mapOf("accent" to "#FF8A3D", "theme" to "dark")).status)
        assertEquals(200, f.set(t, mapOf("accent" to "#FF8A3D")).status)
        assertTrue(f.host.calls.isEmpty())
        assertEquals(rev, f.state(t).revision)
        assertFalse(f.journal(t).entries.any { it.kind == "appearance" })
        // Un seul changement réel : seule la différence est envoyée à l'hôte.
        assertEquals(200, f.set(t, mapOf("accent" to "#FF8A3D", "theme" to "light")).status)
        assertEquals(listOf("appearance:null:light"), f.host.calls)
    }

    @Test fun aBusyRobotRefusesAndNothingChanges() {
        val f = Fixture()
        val t = f.pair()
        f.host.busy = true
        val rev = f.state(t).revision
        val r = f.set(t, mapOf("theme" to "light"))
        assertEquals(409, r.status)
        assertEquals("busy", f.error(r).code)
        assertEquals("Le robot n'est pas sur son écran d'accueil : revenez-y sur le robot, puis réessayez", f.error(r).message)
        assertEquals("dark", f.state(t).robot.theme)
        assertEquals(rev, f.state(t).revision)
        assertFalse(f.journal(t).entries.any { it.kind == "appearance" })
    }

    @Test fun onlyTheRobotColoursAndThemesAreAccepted() {
        val f = Fixture()
        val t = f.pair()
        val bad = listOf<Any?>(
            emptyMap<String, Any?>(), mapOf("accent" to "#123456"), mapOf("accent" to "#1A237E"), mapOf("accent" to "4FC3F7"),
            mapOf("accent" to 0x4FC3F7), mapOf("theme" to "sepia"), mapOf("theme" to "Light"), mapOf("theme" to true),
            mapOf("accent" to "#4FC3F7", "extra" to 1), mapOf("accent" to null),
        )
        for (b in bad) {
            val r = f.set(t, b)
            assertEquals("$b", 400, r.status)
            assertEquals("bad_request", f.error(r).code)
        }
        assertEquals(400, f.req("POST", "/api/robot/appearance", t, raw = "{\"accent\":").status)
        assertEquals(400, f.set(t, listOf("#4FC3F7")).status)
        assertTrue(f.host.calls.isEmpty())
        // Sans jeton : 401, comme toute route.
        assertEquals(401, f.set("0".repeat(64), mapOf("theme" to "light")).status)
        assertEquals(405, f.req("GET", "/api/robot/appearance", t).status)
        for (a in RobotLooks.ACCENTS) assertEquals(a, AppearanceRequest.fromMap(mapOf("accent" to a.lowercase())).accent)
    }

    /** Une couleur de la roue (hors des dix) : prise par un robot qui le dit (robot.anyAccent), comme les dix. */
    @Test fun aWheelColourIsTakenByARobotThatSaysSo() {
        val f = Fixture()
        val t = f.pair()
        assertTrue(f.state(t).robot.anyAccent)
        assertTrue(f.json(f.req("GET", "/api/robot", t)).let { (it["robot"] as Map<*, *>)["anyAccent"] } == true)
        val rev = f.state(t).revision
        val r = f.set(t, mapOf("accent" to "#10ff4b"))
        assertEquals(200, r.status)
        assertEquals(listOf("appearance:#10FF4B:null"), f.host.calls)
        assertEquals("#10FF4B", f.state(t).robot.accent)
        assertTrue(f.state(t).revision > rev)
        assertEquals("Apparence changée depuis Téléphone 1 : couleur #10FF4B", f.journal(t).entries.last { it.kind == "appearance" }.text)
        // Une couleur sombre reste refusée (400), rien d'appliqué.
        for (dark in listOf("#800020", "#1A237E", "#000000")) {
            val no = f.set(t, mapOf("accent" to dark))
            assertEquals(dark, 400, no.status)
            assertEquals("bad_request", f.error(no).code)
        }
        assertEquals(1, f.host.calls.size)
        assertEquals("#10FF4B", f.state(t).robot.accent)
    }

    /**
     * Un hôte qui ne prend que les dix (robot d'avant la roue, côté hub d'aujourd'hui) : pas de
     * robot.anyAccent, et une couleur de la roue est refusée (400 « Couleur inconnue ») sans rien appliquer.
     */
    @Test fun aRobotThatTakesOnlyTheTenRefusesAWheelColour() {
        val f = Fixture(Host(anyAccent = false))
        val t = f.pair()
        assertFalse(f.state(t).robot.anyAccent)
        assertFalse((f.json(f.req("GET", "/api/state", t))["robot"] as Map<*, *>).containsKey("anyAccent"))
        val rev = f.state(t).revision
        val r = f.set(t, mapOf("accent" to "#10FF4B", "theme" to "light"))
        assertEquals(400, r.status)
        assertEquals("bad_request", f.error(r).code)
        assertEquals("Couleur inconnue", f.error(r).message)
        assertTrue(f.host.calls.isEmpty())
        assertEquals(rev, f.state(t).revision)
        assertEquals("dark", f.state(t).robot.theme)
        // Les dix passent toujours.
        assertEquals(200, f.set(t, mapOf("accent" to "#4FC3F7")).status)
        assertEquals(listOf("appearance:#4FC3F7:null"), f.host.calls)
    }

    @Test fun anOlderHostSaysUnsupported() {
        val f = Fixture(Host(features = false))
        val t = f.pair()
        val can = f.state(t).can.setAppearance
        assertFalse(can.allowed)
        assertEquals("unsupported", can.code)
        val r = f.set(t, mapOf("theme" to "light"))
        assertEquals(409, r.status)
        assertEquals("unsupported", f.error(r).code)
        assertTrue(f.host.calls.isEmpty())
        // Un robot plus ancien n'écrit pas la permission : lue comme refusée.
        assertFalse(Permissions.fromMap(emptyMap()).setAppearance.allowed)
    }
}
