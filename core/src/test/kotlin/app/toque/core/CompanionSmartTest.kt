package app.toque.core

import app.toque.core.companion.Alerts
import app.toque.core.companion.CompanionHub
import app.toque.core.companion.JournalPage
import app.toque.core.companion.MachineWire
import app.toque.core.companion.MemoryStore
import app.toque.core.companion.RobotWire
import app.toque.core.companion.StartRequest
import app.toque.core.companion.StateWire
import app.toque.core.companion.TemperatureWatch
import app.toque.core.companion.WhoAmI
import app.toque.core.companion.client.CompanionClient
import app.toque.core.robot.RobotProfiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Le Compagnon avec le profil du Smart : ce que le téléphone reçoit (modèle, bornes sans 40 °C), la
 * température inconnue hors chauffe, et « Température atteinte » à la vraie consigne de 40 °C.
 */
class CompanionSmartTest {
    @After fun connectAgain() {
        RobotProfiles.active = RobotProfiles.MC_CONNECT
    }

    private fun state(f: HubFixture, token: String) = StateWire.fromMap(f.json(f.req("GET", "/api/state", token)))

    @Suppress("UNCHECKED_CAST")
    private fun machineJson(f: HubFixture, token: String) = f.json(f.req("GET", "/api/state", token))["machine"] as Map<String, Any?>

    private fun smartFixture(): Pair<HubFixture, String> {
        RobotProfiles.active = RobotProfiles.MC_SMART
        val f = HubFixture()
        val token = f.pair()
        f.hub.updateMachine(Snaps.idle())
        return f to token
    }

    /** Cuisson manuelle qui chauffe au niveau [level] ; la base renvoie [boardC] comme consigne. */
    private fun heating(tempC: Int, level: Int, boardC: Int) =
        Snaps.running(tempC = tempC).copy(jobTempLevel = level, heaterOn = true, targetTempC = boardC)

    // ------------------------------------------------------------------ champs en plus

    @Test fun newFieldsAreWrittenOnlyWhenTheyTellSomething() {
        assertFalse("model" in RobotWire().toMap())
        assertEquals("mc-smart", RobotWire(model = "mc-smart").toMap()["model"])
        assertEquals("mc-smart", RobotWire.fromMap(RobotWire(model = "mc-smart").toMap()).model)
        // Robot plus ancien : pas de modèle, le téléphone le déduit.
        assertEquals("", RobotWire.fromMap(mapOf("id" to "r1")).model)

        assertFalse("tempKnown" in MachineWire().toMap())
        assertEquals(false, MachineWire(tempKnown = false).toMap()["tempKnown"])
        assertFalse(MachineWire.fromMap(MachineWire(tempKnown = false).toMap()).tempKnown)
        // Absent (Connect, robot plus ancien) : connue.
        assertTrue(MachineWire.fromMap(emptyMap()).tempKnown)
    }

    // ------------------------------------------------------------------ ce que le téléphone reçoit

    @Test fun theSmartSaysWhatItIsAndKeepsFortyDegreesForItself() {
        val (f, token) = smartFixture()
        val st = state(f, token)
        assertEquals("mc-smart", st.robot.model)
        assertFalse(st.limits.heatNoStir)
        assertFalse(40 in st.limits.temperaturesC)
        assertTrue(37 in st.limits.temperaturesC && 45 in st.limits.temperaturesC)
        assertEquals("mc-smart", WhoAmI.fromMap(f.json(f.req("GET", "/api/robot", token))).robot.model)
    }

    @Test fun theConnectSaysItTooAndItsMachineJsonKeepsItsShape() {
        val f = HubFixture()
        val token = f.pair()
        f.hub.updateMachine(Snaps.idle())
        val st = state(f, token)
        assertEquals("mc-connect", st.robot.model)
        assertTrue(st.limits.heatNoStir)
        assertTrue(st.machine.tempKnown)
        assertFalse("tempKnown" in machineJson(f, token))
    }

    @Test fun smartTemperatureIsUnknownOutsideAHeating() {
        val (f, token) = smartFixture()
        // Au repos : la base ne mesure pas, le téléphone ne doit pas afficher 22 °C.
        assertFalse(state(f, token).machine.tempKnown)
        assertEquals(false, machineJson(f, token)["tempKnown"])
        // Mélange sans chauffe : toujours inconnue.
        f.hub.updateMachine(Snaps.running())
        assertFalse(state(f, token).machine.tempKnown)
        // En chauffe : mesurée.
        f.hub.updateMachine(heating(30, level = 6, boardC = 60))
        assertTrue(state(f, token).machine.tempKnown)
        // Cuisson chauffée, résistance au repos (thermostat) : la base a mesuré, elle mesure encore.
        f.hub.updateMachine(heating(58, level = 6, boardC = 60).copy(heaterOn = false))
        assertTrue(state(f, token).machine.tempKnown)
        // Fini : de nouveau inconnue.
        f.hub.updateMachine(Snaps.idle())
        assertFalse(state(f, token).machine.tempKnown)
    }

    @Test fun phoneSettingsOnTheSmartFollowItsRules() {
        val limits = CompanionHub.limitsFor(RobotProfiles.MC_SMART).manual()
        // Pas de chauffe sans mélange.
        val noStir = StartRequest(seconds = 60, tempC = 50, speed = 0).settings(limits)!!
        assertEquals("speed_min", noStir.outOfBounds()?.code)
        // 40 °C reste sur le robot : refusé depuis le téléphone.
        assertNull(StartRequest(seconds = 60, tempC = 40, speed = 1).settings(limits))
        // Le reste passe comme sur l'écran du robot.
        assertNull(StartRequest(seconds = 60, tempC = 37, speed = 1).settings(limits)!!.outOfBounds())
        // Le Connect, lui, chauffe sans mélanger.
        assertNull(StartRequest(seconds = 60, tempC = 50, speed = 0).settings(CompanionHub.limitsFor(RobotProfiles.MC_CONNECT).manual())!!.outOfBounds())
    }

    // ------------------------------------------------------------------ « Température atteinte »

    @Test fun watchUsesTheExactTargetWhenGiven() {
        val w = TemperatureWatch()
        // 40 °C du Smart (niveau 37 °C) : rien à 35 ni à 37, annoncée à 38 comme 40 °C.
        assertEquals(listOf(null, null, null, 40), listOf(30, 35, 37, 38).map { w.update(1, true, true, 1, true, it, 40) })
        // Sans consigne exacte : celle du niveau, comme avant.
        val v = TemperatureWatch()
        assertEquals(listOf(null, 37), listOf(30, 35).map { v.update(1, true, true, 1, true, it) })
    }

    @Test fun smartFortyDegreesAnnouncedAsFortyNotAtThirtyFive() {
        val (f, token) = smartFixture()
        for (t in listOf(30, 35, 36, 37)) f.hub.updateMachine(heating(t, level = 1, boardC = 40))
        fun lines() = JournalPage.fromMap(f.json(f.req("GET", "/api/journal", token))).entries.filter { it.alert == Alerts.TEMP_REACHED }
        assertTrue(lines().isEmpty())
        f.hub.updateMachine(heating(38, level = 1, boardC = 40))
        assertEquals(1, lines().size)
        assertTrue(lines()[0].text, lines()[0].text.contains("40 °C"))
    }

    @Test fun connectTargetsStayThoseOfTheTable() {
        val f = HubFixture()
        val token = f.pair()
        f.hub.updateMachine(Snaps.idle())
        for (t in listOf(30, 60, 78, 80)) f.hub.updateMachine(heating(t, level = 9, boardC = 80))
        val lines = JournalPage.fromMap(f.json(f.req("GET", "/api/journal", token))).entries.filter { it.alert == Alerts.TEMP_REACHED }
        assertEquals(1, lines.size)
        assertTrue(lines[0].text, lines[0].text.contains("80 °C"))
    }

    // ------------------------------------------------------------------ par le réseau

    @Test fun overHttpWithTheSmartProfile() {
        RobotProfiles.active = RobotProfiles.MC_SMART
        val hub = CompanionHub(FakeHost(), store = MemoryStore())
        hub.updateMachine(Snaps.idle())
        hub.start(0, InetAddress.getByName("127.0.0.1"))
        try {
            val c = CompanionClient("127.0.0.1", hub.port)
            val reply = c.pairWithCode(hub.pairing.open().code)
            assertEquals("mc-smart", reply.robot.model)
            val st = c.state()
            assertEquals("mc-smart", st.robot.model)
            assertFalse(st.machine.tempKnown)
            assertFalse(st.limits.heatNoStir)
            assertNull(st.job)
        } finally {
            hub.stop()
        }
    }
}
