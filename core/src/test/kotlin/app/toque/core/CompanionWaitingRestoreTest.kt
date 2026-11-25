package app.toque.core

import app.toque.core.companion.Actor
import app.toque.core.companion.StateWire
import app.toque.core.companion.WaitingCodec
import app.toque.core.companion.WaitingEnd
import app.toque.core.companion.WaitingRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** La recette en attente survit à une relance de l'interface (gardée par l'appli, remise dans le hub). */
class CompanionWaitingRestoreTest {

    private val sample = WaitingRecipe("perso-veloute", "Velouté de potimarron", 4, 35, 4, "Soupes", true, true,
        1_795_000_000_000L, Actor("1eeddd36", "Téléphone 1"))

    @Test fun codecRoundTrip() {
        assertEquals(sample, WaitingCodec.decode(WaitingCodec.encode(sample)))
        val robot = sample.copy(sender = Actor.ROBOT)
        assertEquals(robot, WaitingCodec.decode(WaitingCodec.encode(robot)))
    }

    @Test fun codecRefusesGarbage() {
        assertNull(WaitingCodec.decode(null))
        assertNull(WaitingCodec.decode(""))
        assertNull(WaitingCodec.decode("{"))
        assertNull(WaitingCodec.decode("[1,2]"))
        assertNull(WaitingCodec.decode("""{"title":"sans id"}"""))
        assertNull(WaitingCodec.decode("""{"recipeId":""}"""))
        // Champs manquants : valeurs par défaut.
        val w = WaitingCodec.decode("""{"recipeId":"r1"}""")!!
        assertEquals("r1", w.recipeId)
        assertEquals(0, w.stepCount)
        assertEquals(Actor.ROBOT, w.sender)
    }

    @Test fun restoreShowsTheRecipeToPhonesWithoutJournalLine() {
        val f = HubFixture()
        val t = f.pair()
        var events = 0
        f.hub.onWaitingChanged { events++ }
        val before = f.hub.currentRevision
        val seq = StateWire.fromMap(f.json(f.req("GET", "/api/state", t))).journalSeq
        f.hub.restoreWaiting(sample)
        assertEquals(1, events)
        assertEquals(sample, f.hub.waitingRecipe)
        val s = StateWire.fromMap(f.json(f.req("GET", "/api/state", t)))
        assert(s.revision > before)
        assertNotNull(s.waiting)
        assertEquals("perso-veloute", s.waiting!!.recipeId)
        assertEquals("Téléphone 1", s.waiting!!.origin.name)
        assertEquals(seq, s.journalSeq)
        // Le robot la lance : elle quitte l'accueil comme une recette reçue normalement.
        f.hub.clearWaiting(WaitingEnd.LAUNCHED)
        assertNull(f.hub.waitingRecipe)
        assertEquals(2, events)
    }

    @Test fun restoreNeverReplacesANewerRecipe() {
        val f = HubFixture()
        val t = f.pair()
        assertEquals(200, f.req("POST", "/api/waiting", t, mapOf("recipeId" to "r1")).status)
        var events = 0
        f.hub.onWaitingChanged { events++ }
        f.hub.restoreWaiting(sample)
        assertEquals("r1", f.hub.waitingRecipe!!.recipeId)
        assertEquals(0, events)
    }
}
