package app.toque.core

import app.toque.core.companion.CookSession
import app.toque.core.companion.MachineWire
import app.toque.core.companion.PairingManager
import app.toque.core.companion.PhoneWire
import app.toque.core.companion.StateWire
import app.toque.core.companion.WaitingFor
import app.toque.core.machine.JobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ce qu'un tableau de bord (écran mural, domotique, #108) demandait à l'API du Compagnon : la recette
 * quittée encore lisible (`suspendedSession`), des mesures qu'on peut croire (`weightValid`, `heating`) et
 * un téléphone en lecture seule.
 */
class CompanionDashboardTest {

    private fun state(f: HubFixture, token: String) = StateWire.fromMap(f.json(f.req("GET", "/api/state", token)))

    @Test fun aRecipeLeftOnTheRobotStaysReadableUntilItIsResumedOrDropped() {
        val f = HubFixture()
        val t = f.pair()
        val r = CompanionData.veloute
        f.hub.updateSession(CookSession.of(r, 2, setOf(0, 1), waitingFor = WaitingFor.HAND))
        assertNull(state(f, t).suspendedSession)

        // Écran quitté, « Reprendre » sur l'accueil : la session devient la recette à reprendre, même forme.
        f.hub.endSession(completed = false, resumable = true)
        val s = state(f, t)
        assertNull(s.session)
        val left = s.suspendedSession
        assertNotNull(left)
        assertEquals(r.id, left!!.recipeId)
        assertEquals(2, left.current)
        assertEquals(listOf(0, 1), left.done)
        assertEquals(r.steps.size, left.steps.size)
        assertTrue("absent du JSON d'avant", f.json(f.req("GET", "/api/state", t)).containsKey("suspendedSession"))

        // Reprise : la session revient, la recette à reprendre disparaît.
        f.hub.updateSession(CookSession.of(r, 2, setOf(0, 1), waitingFor = WaitingFor.HAND))
        assertNotNull(state(f, t).session)
        assertNull(state(f, t).suspendedSession)

        // Arrêtée (pas « Reprendre ») ou finie : rien n'attend.
        f.hub.endSession(completed = false, resumable = false)
        assertNull(state(f, t).suspendedSession)
        f.hub.updateSession(CookSession.of(r, 0))
        f.hub.endSession(completed = true, resumable = true)
        assertNull(state(f, t).suspendedSession)

        // Après un redémarrage de Toque, puis l'abandon sur l'accueil.
        f.hub.suspendSession(CookSession.of(r, 3, setOf(0, 1, 2)))
        assertEquals(3, state(f, t).suspendedSession!!.current)
        f.hub.clearSuspended()
        assertNull(state(f, t).suspendedSession)
        assertFalse(f.json(f.req("GET", "/api/state", t)).containsKey("suspendedSession"))
    }

    @Test fun aRestoredRecipeNeverHidesARecipeBeingCooked() {
        val f = HubFixture()
        val t = f.pair()
        f.hub.updateSession(CookSession.of(CompanionData.veloute, 1, setOf(0)))
        f.hub.suspendSession(CookSession.of(CompanionData.veloute, 3, setOf(0, 1, 2)))
        assertEquals(1, state(f, t).session!!.current)
        assertNull(state(f, t).suspendedSession)
    }

    @Test fun weightAndHeatingSayWhatCanBeTrusted() {
        val f = HubFixture()
        val t = f.pair()
        f.hub.updateMachine(Snaps.idle().copy(scaleState = "normal", weightG = 250))
        var m = state(f, t).machine
        assertTrue(m.weightValid)
        assertFalse(m.heating)
        val raw = f.json(f.req("GET", "/api/state", t))["machine"] as Map<*, *>
        assertFalse("écrit seulement quand il est faux", raw.containsKey("weightValid"))
        assertEquals(false, raw["heating"])

        // Lame en marche : la base du Connect envoie 0, ce n'est pas une pesée. Sans chauffe demandée, même si
        // la base dit sa résistance active.
        f.hub.updateMachine(Snaps.running().copy(scaleState = "normal", motorOn = true, heaterOn = true, jobTempC = 0))
        m = state(f, t).machine
        assertFalse(m.weightValid)
        assertFalse(m.heating)
        assertTrue(m.heaterOn)

        // Une cuisson à 100 °C qui tourne : Toque fait chauffer.
        f.hub.updateMachine(Snaps.running(tempC = 60).copy(scaleState = "normal", motorOn = true, jobTempLevel = 13, jobTempC = 100))
        assertTrue(state(f, t).machine.heating)
        // En pause, plus de chauffe demandée.
        f.hub.updateMachine(Snaps.running(tempC = 60).copy(jobState = JobState.PAUSED.name, pauseReason = "USER", jobTempLevel = 13, jobTempC = 100))
        assertFalse(state(f, t).machine.heating)

        // Un robot plus ancien n'écrit aucun des deux : lus comme « pesée valable » et « chauffe inconnue ».
        val old = MachineWire.fromMap(mapOf("link" to "READY", "weightG" to 10))
        assertTrue(old.weightValid)
        assertFalse(old.heating)
    }

    @Test fun aReadOnlyPhoneFollowsTheRobotButCommandsNothing() {
        val f = HubFixture()
        val ro = f.pair()
        val full = f.pair()
        f.hub.updateMachine(Snaps.running())
        val id = f.hub.pairing.authenticate(ro)!!.id
        assertTrue(state(f, ro).can.pause.allowed)

        assertNotNull(f.hub.setPhoneReadOnly(id, true))
        val s = state(f, ro)
        assertTrue(s.me.readOnly)
        for (p in listOf(s.can.pause, s.can.stop, s.can.tare, s.can.adjust, s.can.sendRecipe, s.can.start, s.can.favorite, s.can.editRecipes)) {
            assertFalse(p.allowed)
            assertEquals("read_only", p.code)
        }
        assertTrue("il peut encore se renommer", s.can.renamePhone.allowed)
        // Tout ce qui commande est refusé par le serveur, même sans regarder « can ».
        for ((method, path) in listOf("POST" to "/api/machine/pause", "POST" to "/api/machine/stop", "POST" to "/api/machine/tare",
                "DELETE" to "/api/waiting", "POST" to "/api/recipes/${CompanionData.veloute.id}/favorite")) {
            val r = f.req(method, path, ro, if (method == "POST") emptyMap<String, Any>() else null)
            assertEquals("$method $path", 403, r.status)
        }
        assertEquals(200, f.req("GET", "/api/recipes", ro).status)
        assertEquals(200, f.req("POST", "/api/phones/me", ro, mapOf("name" to "Écran mural")).status)
        assertEquals("Écran mural", state(f, ro).me.name)
        // L'autre téléphone garde ses commandes.
        assertTrue(state(f, full).can.pause.allowed)
        assertFalse(state(f, full).me.readOnly)
        // Gardé par le robot.
        assertTrue(PairingManager(store = f.store).list.first { it.id == id }.readOnly)

        // Le robot lui rend ses commandes.
        f.hub.setPhoneReadOnly(id, false)
        assertTrue(state(f, ro).can.pause.allowed)
        assertFalse(f.json(f.req("GET", "/api/state", ro)).let { (it["me"] as Map<*, *>).containsKey("readOnly") })
    }

    @Test fun aPhoneCanOnlyPutItselfInReadOnly() {
        val f = HubFixture()
        val t = f.pair()
        val r = f.req("POST", "/api/phones/me/read-only", t, emptyMap<String, Any>())
        assertEquals(200, r.status)
        assertTrue(PhoneWire.fromMap(f.json(r)).readOnly)
        assertTrue(state(f, t).me.readOnly)
        assertEquals(403, f.req("POST", "/api/machine/tare", t, emptyMap<String, Any>()).status)
        // Il n'en sort pas de lui-même : seul le robot lève la lecture seule.
        assertEquals(400, f.req("POST", "/api/phones/me", t, mapOf("name" to "x", "readOnly" to false)).status)
        assertTrue(state(f, t).me.readOnly)
        // Se désappairer reste possible.
        assertEquals(200, f.req("DELETE", "/api/phones/me", t).status)
        assertEquals(401, f.req("GET", "/api/state", t).status)
    }
}
