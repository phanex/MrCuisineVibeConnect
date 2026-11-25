package app.toque.core

import app.toque.core.companion.ApiError
import app.toque.core.companion.CookSession
import app.toque.core.companion.Origin
import app.toque.core.companion.Permissions
import app.toque.core.companion.StartRequest
import app.toque.core.companion.StateWire
import app.toque.core.companion.WaitingFor
import app.toque.core.json.Json
import app.toque.core.machine.Accessory
import app.toque.core.machine.JobKind
import app.toque.core.machine.JobState
import app.toque.core.machine.LinkState
import app.toque.core.protocol.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** POST /api/machine/start : la règle du lancement d'une cuisson manuelle depuis un téléphone. */
class CompanionStartTest {
    private class Setup(enabled: Boolean = true) {
        val f = HubFixture()
        val token = f.pair()
        val phone = f.hub.pairing.authenticate(token)!!

        init {
            f.hub.setRemoteStart(enabled)
            f.hub.updateMachine(Snaps.idle())
            // Un lancement accepté : la machine publie l'instantané de la cuisson.
            f.host.afterStart = { f.hub.updateMachine(running()) }
        }

        fun running() = Snaps.idle().copy(jobActive = true, jobKind = "MANUAL", jobLabel = "Cuisson manuelle",
            jobState = JobState.STARTING.name, phaseCount = 1, totalMs = 120_000, remainingMs = 120_000, jobSpeed = 2)

        fun start(body: Any?) = f.req("POST", "/api/machine/start", token, body)
        fun state() = StateWire.fromMap(f.json(f.req("GET", "/api/state", token)))
        fun error(r: app.toque.core.companion.HttpResponse) = ApiError.fromMap(f.json(r))
    }

    private fun body(seconds: Int = 120, tempC: Int = 0, speed: Int = 2, reverse: Boolean = false, accessory: String = "BLADE") =
        StartRequest(seconds, tempC, speed, reverse, accessory).toMap()

    @Test fun happyPathStartsTheRequestedCookingAndAnswersWithTheJobAlreadyInTheState() {
        val t = Setup()
        assertTrue(t.state().can.start.allowed)
        val r = t.start(body(seconds = 600, tempC = 90, speed = 2, reverse = true, accessory = "WHISK"))
        assertEquals(200, r.status)
        val spec = t.f.host.lastStart!!.spec!!
        assertEquals(JobKind.MANUAL, spec.kind)
        assertEquals(600_000L, spec.totalMs)
        assertEquals(90, spec.phases[0].setting.temperatureC)
        assertEquals(2, spec.phases[0].setting.speed)
        assertEquals(Direction.COUNTER_CLOCKWISE, spec.phases[0].setting.direction)
        assertEquals(Accessory.WHISK, spec.accessory)
        // Jamais de confirmation de mixage à chaud depuis un téléphone.
        assertFalse(spec.hotBlendConfirmed)
        assertEquals("Téléphone 1", t.f.host.lastStart!!.phoneName)
        val revision = (t.f.json(r)["revision"] as Number).toLong()
        assertTrue(revision <= t.f.hub.currentRevision)
        assertEquals(listOf("start"), t.f.host.calls)
    }

    @Test fun theStartIsAttributedToThePhoneInTheJournal() {
        val t = Setup()
        assertEquals(200, t.start(body()).status)
        val page = t.f.hub.journalPage(t.phone, 0, 50)
        val e = page.entries.first { it.kind == "job_started" }
        assertEquals("Démarrage depuis Téléphone 1 : Cuisson manuelle", e.text)
        assertEquals(Origin.PHONE, e.origin.kind)
        assertTrue(e.origin.self)
        assertEquals("manual", page.activities.first().kind)
        // Un démarrage sur le robot, plus tard, reste au robot.
        t.f.hub.updateMachine(t.running().copy(jobState = JobState.FINISHED.name))
        t.f.clock.advance(10_000)
        t.f.hub.updateMachine(t.running().copy(jobLabel = "Cuisson manuelle", jobState = JobState.RUNNING.name, totalMs = 60_000))
        val again = t.f.hub.journalPage(t.phone, 0, 50).entries.first { it.kind == "job_started" }
        assertEquals("Démarrage sur le robot : Cuisson manuelle", again.text)
        assertEquals(Origin.ROBOT, again.origin.kind)
    }

    private fun refused(t: Setup, body: Any?, code: String) {
        val r = t.start(body)
        assertEquals("$code : ${String(r.body)}", 409, r.status)
        assertEquals(code, t.error(r).code)
        assertTrue(t.f.host.calls.isEmpty())
    }

    @Test fun switchOffOnTheRobotRefusesAndTheStateTells() {
        val t = Setup(enabled = false)
        assertEquals("disabled", t.state().can.start.code)
        refused(t, body(), "disabled")
        val before = t.f.hub.currentRevision
        t.f.hub.setRemoteStart(true)
        // La permission change aussitôt (nouvelle révision).
        assertTrue(t.f.hub.currentRevision > before)
        assertTrue(t.state().can.start.allowed)
    }

    @Test fun theBaseMustBeReady() {
        val t = Setup()
        t.f.hub.updateMachine(Snaps.idle().copy(link = LinkState.ARMING))
        assertEquals("link", t.state().can.start.code)
        refused(t, body(), "link")
        t.f.hub.updateMachine(Snaps.idle().copy(hasSample = false))
        refused(t, body(), "link")
        t.f.hub.updateMachine(Snaps.idle().copy(faultCode = "BOARD_ERROR", faultMessage = "x"))
        refused(t, body(), "fault")
    }

    @Test fun nothingMayBeInProgress() {
        val t = Setup()
        for (st in listOf(JobState.STARTING, JobState.RUNNING, JobState.STOPPING, JobState.PAUSED)) {
            t.f.hub.updateMachine(t.running().copy(jobState = st.name))
            assertEquals(st.name, "busy", t.state().can.start.code)
            refused(t, body(), "busy")
        }
        t.f.hub.updateMachine(Snaps.idle().copy(turboActive = true))
        refused(t, body(), "busy")
        // Un programme terminé encore affiché ne gêne pas (le contrôleur accepte alors un démarrage).
        t.f.hub.updateMachine(t.running().copy(jobState = JobState.FINISHED.name))
        assertTrue(t.state().can.start.allowed)
        assertEquals(200, t.start(body()).status)
    }

    @Test fun anOpenRecipeOnTheRobotRefuses() {
        val t = Setup()
        t.f.hub.updateSession(CookSession.of(CompanionData.veloute, 0, waitingFor = WaitingFor.HAND))
        assertEquals("recipe", t.state().can.start.code)
        assertEquals("Une recette est en cours sur le robot", t.state().can.start.reason)
        refused(t, body(), "recipe")
    }

    @Test fun lidClosedAndBowlInPlace() {
        val t = Setup()
        t.f.hub.updateMachine(Snaps.idle().copy(lidOpen = true))
        assertEquals("Fermez le couvercle sur le robot", t.state().can.start.reason)
        refused(t, body(), "lid")
        t.f.hub.updateMachine(Snaps.idle().copy(lidOpen = true, bowlRemoved = true))
        refused(t, body(), "bowl")
        t.f.hub.updateMachine(Snaps.idle().copy(bowlDetected = false))
        refused(t, body(), "bowl")
    }

    @Test fun settingsOutsideTheRobotScreenBoundsAreRefusedNeverClamped() {
        val t = Setup()
        refused(t, body(seconds = 4), "too_short")
        refused(t, body(seconds = 99 * 60 + 1), "too_long")
        refused(t, body(seconds = 16 * 60, speed = 7), "too_long")
        refused(t, body(tempC = 92), "temp_range")
        refused(t, body(speed = 0), "speed_min")
        refused(t, body(speed = 0, tempC = 125), "no_stir_temp")
        refused(t, body(speed = 0, tempC = 100, reverse = true), "no_stir_dir")
        refused(t, body(speed = 4, tempC = 37), "heated_speed")
        refused(t, body(speed = 4, reverse = true), "reverse_speed")
        refused(t, body(speed = 5, accessory = "WHISK"), "accessory_speed")
        // Aux bornes exactes : accepté.
        assertEquals(200, t.start(body(seconds = 15 * 60, speed = 10)).status)
    }

    @Test fun aHotBlendIsLeftToTheRobot() {
        val t = Setup()
        t.f.hub.updateMachine(Snaps.idle().copy(actualTempC = 72))
        // Le lancement reste permis (vitesse basse) ; à vitesse 4 ou plus, refusé, sans fenêtre sur le robot.
        assertTrue(t.state().can.start.allowed)
        refused(t, body(speed = 4), "hot_blend")
        assertEquals("Contenu chaud : lancez sur le robot", t.error(t.start(body(speed = 6))).message)
        assertEquals(200, t.start(body(speed = 3)).status)
    }

    @Test fun theRuleIsCheckedAgainJustBeforeActing() {
        // Couvercle ouvert entre la requête et l'action.
        var t = Setup()
        t.f.host.beforeStart = { t.f.hub.updateMachine(Snaps.idle().copy(lidOpen = true)) }
        var r = t.start(body())
        assertEquals(409, r.status)
        assertEquals("lid", t.error(r).code)
        assertEquals("lid", t.f.host.lastStart!!.refusal!!.code)
        assertNull(t.f.host.lastStart!!.spec)
        // Une cuisson lancée sur le robot entre-temps.
        t = Setup()
        t.f.host.beforeStart = { t.f.hub.updateMachine(t.running().copy(jobState = JobState.RUNNING.name)) }
        r = t.start(body())
        assertEquals("busy", t.error(r).code)
        // Une recette ouverte entre-temps.
        t = Setup()
        t.f.host.beforeStart = { t.f.hub.updateSession(CookSession.of(CompanionData.veloute, 0, waitingFor = WaitingFor.HAND)) }
        r = t.start(body())
        assertEquals("recipe", t.error(r).code)
        // Le bol a chauffé entre-temps : mixage à chaud.
        t = Setup()
        t.f.host.beforeStart = { t.f.hub.updateMachine(Snaps.idle().copy(actualTempC = 80)) }
        assertEquals("hot_blend", t.error(t.start(body(speed = 5))).code)
        // L'interrupteur coupé entre-temps.
        t = Setup()
        t.f.host.beforeStart = { t.f.hub.setRemoteStart(false) }
        assertEquals("disabled", t.error(t.start(body())).code)
    }

    @Test fun aMachineRefusalIsARefusalAndUnmarksTheAttribution() {
        val t = Setup()
        t.f.host.result = app.toque.core.companion.CommandResult.refuse("lid", "Fermez et verrouillez le couvercle")
        val r = t.start(body())
        assertEquals(409, r.status)
        assertEquals("lid", t.error(r).code)
        // Lancée ensuite sur le robot : au robot, pas au téléphone.
        t.f.hub.updateMachine(t.running())
        assertEquals(Origin.ROBOT, t.f.hub.journalPage(t.phone, 0, 50).entries.first { it.kind == "job_started" }.origin.kind)
    }

    @Test fun oneStartAtATime() {
        val t = Setup()
        var second: app.toque.core.companion.HttpResponse? = null
        t.f.host.beforeStart = { if (second == null) second = t.start(body()) }
        assertEquals(200, t.start(body()).status)
        assertEquals(409, second!!.status)
        assertEquals("busy", t.error(second!!).code)
    }

    @Test fun strictParsing() {
        val t = Setup()
        fun bad(m: Map<String, Any?>) {
            val r = t.start(m)
            assertEquals(m.toString(), 400, r.status)
            assertEquals("bad_request", t.error(r).code)
        }
        val ok = body()
        bad(ok + ("hotBlendConfirmed" to true))
        bad(ok + ("seconds" to 12.5))
        bad(ok + ("seconds" to "60"))
        bad(ok + ("speed" to 11))
        bad(ok + ("reverse" to "true"))
        bad(ok + ("accessory" to "SPATULA"))
        bad(ok - "tempC")
        bad(emptyMap())
        // JSON illisible.
        val r = t.f.api.handle(app.toque.core.companion.HttpRequest("POST", "/api/machine/start", emptyMap(),
            mapOf("host" to "192.168.0.12:8088", "authorization" to "Bearer ${t.token}", "content-type" to "application/json"),
            "{".toByteArray(), "192.168.0.20"))
        assertEquals(400, r.status)
        assertTrue(t.f.host.calls.isEmpty())
        // Un nombre écrit 120.0 est un entier.
        assertEquals(200, t.start(Json.parse("""{"seconds":120.0,"tempC":0,"speed":2,"reverse":false,"accessory":"BLADE"}""")).status)
    }

    @Test fun withoutATokenNothingStarts() {
        val t = Setup()
        val r = t.f.req("POST", "/api/machine/start", null, body())
        assertEquals(401, r.status)
        assertTrue(t.f.host.calls.isEmpty())
    }

    @Test fun anOlderRobotSaysNothingAboutStart() {
        val old = Permissions.fromMap(mapOf("pause" to mapOf("allowed" to true)))
        assertFalse(old.start.allowed)
        assertEquals("", old.start.code)
    }
}
