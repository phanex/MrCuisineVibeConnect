package app.toque.core

import app.toque.core.look.ColourWheel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La roue des couleurs d'accent : ses calculs, sa garde, ses raccourcis. */
class ColourWheelTest {
    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)
    private fun parse(s: String) = (0xFF shl 24) or s.substring(1).toInt(16)

    /** Les dix couleurs d'origine (Ui.ACCENTS, dans app), dans le même ordre. */
    private val ten = listOf(
        "#FF8A3D", "#FFB547", "#E8D44D", "#8BD85F", "#3DDCB0",
        "#4FC3F7", "#6E9BFF", "#B48CFF", "#F2639A", "#FF6F61",
    )

    /**
     * Les mêmes couleurs que la maquette validée : chaque point a été calculé en refaisant les formules
     * de la maquette pas à pas (doubles, tables en Float32Array, Math.round de JavaScript).
     */
    @Test fun theWheelGivesTheColoursOfTheValidatedMockUp() {
        val samples = listOf(
            Triple(0.0, 0.0, "#EFCDD6"), Triple(0.0, 1.0, "#FF67A0"), Triple(30.0, 0.5, "#FBA394"),
            Triple(55.0, 1.0, "#FF8505"), Triple(55.0, 0.0, "#EDD1BF"), Triple(90.0, 1.0, "#FECA00"),
            Triple(120.0, 0.5, "#CDE483"), Triple(145.0, 1.0, "#10FF4B"), Triple(200.0, 1.0, "#00F4FE"),
            Triple(237.3, 0.62, "#6CC4FA"), Triple(270.0, 0.75, "#94ADFD"), Triple(300.0, 1.0, "#B688FF"),
            Triple(330.4, 0.3, "#F3ADEB"), Triple(359.6, 1.0, "#FF68A1"), Triple(17.5, 0.9, "#FF7A83"),
        )
        for ((h, t, want) in samples) assertEquals("teinte $h, intensité $t", want, hex(ColourWheel.colour(h, t)))
        // Hors bornes : la teinte fait le tour, l'intensité est ramenée entre 0 et 1.
        assertEquals(hex(ColourWheel.colour(10.0, 1.0)), hex(ColourWheel.colour(370.0, 1.4)))
        assertEquals(hex(ColourWheel.colour(350.0, 0.0)), hex(ColourWheel.colour(-10.0, -0.5)))
    }

    /** Les dix couleurs d'origine se placent où la maquette les plaçait (raccourcis, poignée). */
    @Test fun theTenColoursArePlacedLikeInTheMockUp() {
        val want = mapOf(
            "#FF8A3D" to (50.5355 to 0.8565), "#FFB547" to (73.3464 to 0.8317), "#E8D44D" to (100.7785 to 0.7801),
            "#8BD85F" to (135.5863 to 0.6005), "#3DDCB0" to (169.8048 to 0.7754), "#4FC3F7" to (231.1149 to 0.7782),
            "#6E9BFF" to (264.4725 to 1.0), "#B48CFF" to (298.2157 to 0.9763), "#F2639A" to (359.0244 to 0.9364),
            "#FF6F61" to (27.9687 to 1.0),
        )
        assertEquals(ten.toSet(), want.keys)
        for ((c, ht) in want) {
            val s = ColourWheel.place(parse(c))
            assertEquals("teinte de $c", ht.first, s.hue, 1e-3)
            assertEquals("intensité de $c", ht.second, s.intensity, 1e-3)
        }
    }

    /** Toute la roue passe la garde, sur une grille fine (un dixième de degré sur 3 degrés, 2 % d'intensité). */
    @Test fun everyColourOfTheWheelPassesTheGuard() {
        var worst = 99.0
        var h = 0.0
        while (h < 360.0) {
            for (k in 0..50) {
                val c = ColourWheel.colour(h, k / 50.0)
                assertTrue("teinte $h, intensité ${k / 50.0} : ${hex(c)}", ColourWheel.accepts(c))
                worst = minOf(worst, ColourWheel.contrast(ColourWheel.onAccent(c), c), ColourWheel.contrast(c, ColourWheel.DARK_SURFACE))
            }
            h += 0.3
        }
        // Une marge confortable : le pire point de la roue est encore au-dessus de 5,5:1.
        assertTrue("pire contraste $worst", worst >= 5.5)
        // Et la table du dessin, point par point.
        for (c in ColourWheel.table(32)) assertTrue(hex(c), ColourWheel.accepts(c))
    }

    @Test fun theTenColoursPassAndKeepTheRobotRule() {
        for (s in ten) {
            val c = parse(s)
            assertTrue(s, ColourWheel.accepts(c))
        }
        // La règle du robot (Ui.setAccent) : 12 % de chaque canal, arrondi vers le bas.
        assertEquals("#1E1007", hex(ColourWheel.onAccent(parse("#FF8A3D"))))
        assertEquals("#09171D", hex(ColourWheel.onAccent(parse("#4FC3F7"))))
    }

    /**
     * Ce que la garde refuse : les couleurs sombres, qui demanderaient des traits blancs (marine, bordeaux,
     * noir, un gris moyen). Le blanc et les gris très clairs passent : lisibles, seulement sans couleur.
     */
    @Test fun darkColoursAreRefusedWhiteIsReadable() {
        for (s in listOf("#1A237E", "#800020", "#000000", "#131D2E", "#5A5A5A", "#2E7D32", "#C62828")) {
            assertFalse(s, ColourWheel.accepts(parse(s)))
        }
        for (s in listOf("#FFFFFF", "#F0F0F0", "#C0C0C0")) assertTrue(s, ColourWheel.accepts(parse(s)))
        // L'alpha ne compte pas : la couleur est lue opaque.
        assertTrue(ColourWheel.accepts(0x00FF8A3D))
    }

    /** Le placement retrouve la couleur de la roue : le même point redonne la même couleur. */
    @Test fun placingAWheelColourFindsItsSpotAgain() {
        for (h in listOf(0.0, 47.0, 95.0, 160.0, 214.0, 281.0, 333.0)) for (t in listOf(0.2, 0.5, 0.8, 1.0)) {
            val c = ColourWheel.colour(h, t)
            val s = ColourWheel.place(c)
            val back = ColourWheel.colour(s.hue, s.intensity)
            val dist = Math.abs(((back shr 16) and 0xFF) - ((c shr 16) and 0xFF)) +
                Math.abs(((back shr 8) and 0xFF) - ((c shr 8) and 0xFF)) + Math.abs((back and 0xFF) - (c and 0xFF))
            assertTrue("teinte $h, intensité $t : ${hex(c)} → ${hex(back)}", dist <= 12)
        }
    }

    /**
     * L'encre : la même teinte, assombrie jusqu'à 4,5:1. Sur le blanc seul, celle de la maquette ;
     * sur plusieurs fonds, lisible sur chacun ; une couleur déjà lisible reste elle-même.
     */
    @Test fun theInkIsTheSameHueDarkenedUntilReadable() {
        val white = intArrayOf(0xFFFFFFFF.toInt())
        assertEquals("#BE5900", hex(ColourWheel.ink(parse("#FF8A3D"), white)))
        assertEquals("#857600", hex(ColourWheel.ink(parse("#E8D44D"), white)))
        assertEquals("#008187", hex(ColourWheel.ink(parse("#00F4FE"), white)))
        val light = intArrayOf(0xFFF3F5F8.toInt(), 0xFFFFFFFF.toInt(), 0xFFEBEEF3.toInt(), 0xFFDEE3EB.toInt())
        var h = 0.0
        while (h < 360.0) {
            for (t in listOf(0.0, 0.5, 1.0)) {
                val c = ColourWheel.colour(h, t)
                val ink = ColourWheel.ink(c, light)
                for (b in light) assertTrue("encre de ${hex(c)} sur ${hex(b)}", ColourWheel.contrast(ink, b) >= 4.5)
                // Même teinte, à quelques degrés près (sauf le pastel presque gris).
                if (t > 0.4) {
                    val d = Math.abs(ColourWheel.toLch(ink)[2] - ColourWheel.toLch(c)[2]).let { minOf(it, 360 - it) }
                    assertTrue("teinte de l'encre de ${hex(c)} : $d°", d < 12)
                }
            }
            h += 5.0
        }
        // Sur le sombre, l'accent se lit déjà : l'encre est l'accent lui-même.
        val dark = intArrayOf(0xFF0A1322.toInt(), 0xFF131D2E.toInt())
        for (s in ten) assertEquals(s, hex(ColourWheel.ink(parse(s), dark)))
    }

    /**
     * L'image de l'anneau : transparente au milieu et aux coins, opaque sur l'anneau, de la couleur de la
     * roue sous chaque point ; un doigt posé sur un point retrouve sa teinte et son intensité. Le temps de
     * calcul est écrit dans la sortie du test (le robot est bien plus lent).
     */
    @Test fun theRingImageAndTheFingerAgree() {
        val d = 380
        val t0 = System.nanoTime()
        val px = ColourWheel.ringPixels(d)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("anneau de $d px : $ms ms sur cette machine")
        assertEquals(d * d, px.size)
        val half = d / 2.0
        fun at(x: Int, y: Int) = px[y * d + x]
        assertEquals(0, at(d / 2, d / 2) ushr 24)
        assertEquals(0, at(0, 0) ushr 24)
        assertEquals(0, at(d - 1, d - 1) ushr 24)
        for (angle in 0 until 360 step 15) for (t in listOf(0.1, 0.5, 0.9)) {
            val r = ColourWheel.radiusOf(t) * half
            val a = Math.toRadians(angle.toDouble())
            val x = (half + Math.cos(a) * r).toInt()
            val y = (half + Math.sin(a) * r).toInt()
            val p = at(x, y)
            assertEquals("opaque à $angle°, $t", 255, p ushr 24)
            val spot = ColourWheel.spotAt(x + 0.5 - half, y + 0.5 - half, half)
            val want = ColourWheel.colour(spot.hue, spot.intensity)
            val dist = Math.abs(((p shr 16) and 0xFF) - ((want shr 16) and 0xFF)) +
                Math.abs(((p shr 8) and 0xFF) - ((want shr 8) and 0xFF)) + Math.abs((p and 0xFF) - (want and 0xFF))
            assertTrue("pixel à $angle°, $t : ${hex(p)} au lieu de ${hex(want)}", dist <= 18)
            assertEquals(angle.toDouble(), spot.hue, 1.0)
            assertEquals(t, spot.intensity, 0.02)
        }
        // Au-delà de l'anneau, le doigt donne les bouts : vif dehors, pastel dedans.
        assertEquals(1.0, ColourWheel.spotAt(0.0, half * 1.2, half).intensity, 0.0)
        assertEquals(0.0, ColourWheel.spotAt(half * 0.2, 0.0, half).intensity, 0.0)
        assertEquals(90.0, ColourWheel.spotAt(0.0, 50.0, half).hue, 1e-9)
    }
}
