package app.toque.core

import app.toque.core.companion.RobotWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La couleur et le thème du robot, tels qu'ils voyagent vers le téléphone. */
class RobotLookWireTest {
    @Test fun colourAndThemeTravelWithTheRobot() {
        val w = RobotWire("a1b2c3d4e5f6", "Monsieur Cuisine Connect", "0.8.0", "fr", accent = "#4FC3F7", theme = "light")
        val back = RobotWire.fromMap(w.toMap())
        assertEquals(w, back)
        assertEquals("#4FC3F7", back.accent)
        assertEquals("light", back.theme)
    }

    @Test fun anOlderRobotKeepsItsJsonAndThePhoneKeepsItsLook() {
        // Robot sans couleur connue : rien de plus dans son JSON.
        val old = RobotWire("a1b2c3d4e5f6", "Monsieur Cuisine Connect", "0.7.1", "fr")
        assertFalse(old.toMap().containsKey("accent"))
        assertFalse(old.toMap().containsKey("theme"))
        assertEquals(listOf("id", "name", "appVersion", "language", "protocol"), old.toMap().keys.toList())
        val back = RobotWire.fromMap(old.toMap())
        assertEquals("", back.accent)
        assertEquals("", back.theme)
    }

    @Test fun anythingElseThanAColourOrAThemeIsIgnored() {
        fun read(accent: Any?, theme: Any?) = RobotWire.fromMap(mapOf("id" to "x", "accent" to accent, "theme" to theme))
        assertEquals("#FF8A3D", read("#ff8a3d", "dark").accent)
        assertEquals("dark", read("#ff8a3d", "dark").theme)
        for (bad in listOf("FF8A3D", "#FFF", "#GGGGGG", "#FF8A3D00", "red", "", 12, null, "javascript:alert(1)")) {
            assertEquals("couleur $bad", "", read(bad, "light").accent)
        }
        for (bad in listOf("Dark", "clair", "", 1, null)) assertEquals("thème $bad", "", read("#FF8A3D", bad).theme)
        // Ce que le robot écrit passe par le même filtre.
        assertEquals("", RobotWire.accent("orange"))
        assertEquals("", RobotWire.theme("sepia"))
    }

    /** robot.anyAccent : écrit seulement quand il est vrai ; absent (robot plus ancien) : faux. */
    @Test fun theWheelFlagTravelsOnlyWhenTrue() {
        val w = RobotWire("a1b2c3d4e5f6", "Monsieur Cuisine Connect", "0.8.1", "fr", accent = "#10FF4B", theme = "dark", anyAccent = true)
        assertEquals(true, w.toMap()["anyAccent"])
        assertEquals(w, RobotWire.fromMap(w.toMap()))
        val before = RobotWire("a1b2c3d4e5f6", "Monsieur Cuisine Connect", "0.8.0", "fr", accent = "#FF8A3D", theme = "dark")
        assertFalse(before.toMap().containsKey("anyAccent"))
        assertFalse(RobotWire.fromMap(before.toMap()).anyAccent)
        // Seul le vrai booléen compte.
        for (v in listOf<Any?>("true", 1, null, false)) assertFalse("$v", RobotWire.fromMap(mapOf("anyAccent" to v)).anyAccent)
    }

    /** Ce que le téléphone peut proposer au robot : les dix partout, une couleur de la roue seulement avec le drapeau. */
    @Test fun whatTheRobotTakes() {
        val old = RobotWire(accent = "#FF8A3D")
        val now = old.copy(anyAccent = true)
        for (c in listOf("#4FC3F7", "#4fc3f7")) {
            assertTrue(c, old.takesAccent(c))
            assertTrue(c, now.takesAccent(c))
        }
        assertFalse(old.takesAccent("#10FF4B"))
        assertTrue(now.takesAccent("#10FF4B"))
        for (bad in listOf("#800020", "#000000", "red", "")) assertFalse(bad, now.takesAccent(bad))
    }
}
