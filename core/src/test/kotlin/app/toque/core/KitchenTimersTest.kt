package app.toque.core

import app.toque.core.time.KitchenTimerList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KitchenTimersTest {
    @Test fun atMostThreeRunningSoonestFirst() {
        val l = KitchenTimerList()
        val a = l.add("Pâte", 30 * 60_000L, 1_000)!!
        l.add("Four", 25 * 60_000L, 1_000)!!
        l.add("Minuteur", 5 * 60_000L, 1_000)!!
        assertNull(l.add("Quatrième", 60_000L, 1_000))
        assertEquals(listOf("Minuteur", "Four", "Pâte"), l.running(1_000).map { it.label })
        assertEquals(29 * 60_000L, a.remaining(61_000))
        // Le premier terminé ne compte plus parmi les trois : un autre peut partir.
        val later = 1_000 + 5 * 60_000L
        assertEquals(listOf("Minuteur"), l.ringing(later).map { it.label })
        assertTrue(l.canAdd(later))
        // Durées hors limites.
        assertNull(l.add("court", 1_000L, later))
        assertNull(l.add("long", KitchenTimerList.MAX_MS + 1, later))
    }

    @Test fun extendAndRemove() {
        val l = KitchenTimerList()
        val t = l.add("Pâte", 60_000L, 0)!!
        assertTrue(l.extend(t.id, 60_000L, 30_000))
        assertEquals(90_000L, l.all[0].remaining(30_000))
        assertTrue(l.remove(t.id))
        assertTrue(l.all.isEmpty())
        // Un minuteur terminé ne se prolonge pas.
        val u = l.add("Four", 60_000L, 0)!!
        assertFalse(l.extend(u.id, 60_000L, 70_000))
    }

    @Test fun survivesARestartOfTheInterfaceButNotOfTheTablet() {
        val l = KitchenTimerList()
        l.add("Pâte", 30 * 60_000L, 10_000)
        l.add("Four", 60_000L, 10_000)
        val saved = l.encode("boot-1", 20_000)
        // Même démarrage : tout revient, l'heure de fin ne bouge pas, les numéros continuent.
        val same = KitchenTimerList.restore(saved, "boot-1", 50_000)
        assertTrue(same.lost.isEmpty())
        assertEquals(listOf("Four", "Pâte"), same.list.all.sortedBy { it.endsAt }.map { it.label })
        assertEquals(listOf("Four"), same.list.ringing(80_000).map { it.label })
        val next = same.list.add("Nouveau", 60_000L, 50_000)!!
        assertEquals(3L, next.id)
        // Autre démarrage (coupure de courant) : perdus, et annoncés comme tels.
        val other = KitchenTimerList.restore(saved, "boot-2", 50_000)
        assertTrue(other.list.all.isEmpty())
        assertEquals(2, other.lost.size)
        // Identifiant de démarrage illisible : une horloge revenue en arrière dit le redémarrage.
        assertEquals(2, KitchenTimerList.restore(saved, "", 5_000).lost.size)
        assertEquals(0, KitchenTimerList.restore(saved, "", 25_000).lost.size)
        // Texte illisible : rien.
        assertTrue(KitchenTimerList.restore("{pas du json", "boot-1", 0).list.all.isEmpty())
        assertTrue(KitchenTimerList.restore("", "boot-1", 0).list.all.isEmpty())
    }
}
