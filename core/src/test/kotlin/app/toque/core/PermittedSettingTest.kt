package app.toque.core

import app.toque.core.i18n.I18n
import app.toque.core.machine.Accessory
import app.toque.core.machine.Limits
import app.toque.core.protocol.Mcc
import app.toque.core.recipes.PermittedSetting
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.core.robot.RecipeCompat
import app.toque.core.robot.RobotProfiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PermittedSettingTest {
    @Before fun french() {
        I18n.select("fr")
        RobotProfiles.active = RobotProfiles.MC_CONNECT
    }

    @After fun connect() {
        RobotProfiles.active = RobotProfiles.MC_CONNECT
    }

    private fun of(m: StepMachine) = PermittedSetting.of(m)

    @Test fun heatingWithSpeed4GivesSpeed3() {
        val m = StepMachine(seconds = 600, speed = 4, tempC = 90)
        val p = of(m)!!
        assertEquals(m.copy(speed = Limits.MAX_SPEED_HEATED), p)
        assertEquals("vitesse 3 au lieu de 4", PermittedSetting.changes(m, p))
        assertEquals("10 min · 90 °C · vitesse 3", p.summary)
    }

    @Test fun reverseWithSpeed6Gives3() {
        val m = StepMachine(seconds = 30, speed = 6, reverse = true)
        assertEquals(m.copy(speed = Limits.MAX_SPEED_REVERSE), of(m))
        // En chauffe et en sens inverse : toujours 3.
        val hot = StepMachine(seconds = 300, speed = 5, tempC = 100, reverse = true)
        assertEquals(hot.copy(speed = 3), of(hot))
    }

    @Test fun aTemperatureBetweenTwoTableValuesGoesDown() {
        val m = StepMachine(seconds = 300, speed = 2, tempC = 92)
        val p = of(m)!!
        assertEquals(90, p.tempC)
        assertEquals("90 °C au lieu de 92 °C", PermittedSetting.changes(m, p))
        assertEquals("5 min · 92 °C · vitesse 2", PermittedSetting.asked(m))
        // Au-dessus de la table : sa dernière valeur.
        assertEquals(130, of(m.copy(tempC = 150, speed = 1))!!.tempC)
        // Les deux à la fois : 92 °C et vitesse 5 → 90 °C, vitesse 3.
        assertEquals(m.copy(tempC = 90, speed = 3), of(m.copy(speed = 5)))
    }

    @Test fun nothingBelowTheFirstTableTemperature() {
        // 30 °C : en dessous, plus rien ne chauffe ; ce serait un autre réglage, pas une baisse.
        assertNull(of(StepMachine(seconds = 300, speed = 2, tempC = 30)))
        assertNull(of(StepMachine(seconds = 300, speed = 2, tempC = -5)))
    }

    @Test fun aDurationOf0GivesNothing() {
        assertNull(of(StepMachine(seconds = 0, speed = 4, tempC = 100)))
        assertNull(of(StepMachine(seconds = 0, speed = 5)))
        assertNull(of(StepMachine(StepProgram.KNEAD, 0)))
    }

    @Test fun anAllowedSettingGivesNothing() {
        assertNull(of(StepMachine(seconds = 600, speed = 3, tempC = 100)))
        assertNull(of(StepMachine(seconds = 30, speed = 8)))
        assertNull(of(StepMachine(StepProgram.KNEAD, 120)))
        assertNull(of(StepMachine(StepProgram.TURBO, pulses = 3)))
    }

    @Test fun otherRefusalsGiveNothing() {
        // Vitesse 0 sans chauffe : la vitesse minimale n'est pas un « trop ».
        assertNull(of(StepMachine(seconds = 60, speed = 0)))
        assertNull(of(StepMachine(seconds = 60, speed = -1)))
    }

    @Test fun theWhisk() {
        val m = StepMachine(seconds = 120, speed = 6, accessory = Accessory.WHISK)
        assertEquals(m.copy(speed = Accessory.WHISK.maxSpeed), of(m))
        // Fouet en chauffe : 3.
        val hot = m.copy(tempC = 70)
        assertEquals(hot.copy(speed = 3), of(hot))
        // Turbo avec le fouet : changer d'accessoire n'est pas baisser.
        assertNull(of(StepMachine(StepProgram.TURBO, accessory = Accessory.WHISK)))
    }

    @Test fun eachProgram() {
        // Pétrir : 2 min au plus.
        val knead = StepMachine(StepProgram.KNEAD, 300)
        assertEquals(knead.copy(seconds = (Limits.MAX_MS_KNEAD / 1000).toInt()), of(knead))
        // Rissoler : 14 min au plus.
        val toast = StepMachine(StepProgram.TOAST, 1200)
        assertEquals(toast.copy(seconds = (Limits.MAX_MS_TOAST / 1000).toInt()), of(toast))
        // Vapeur : 99 min au plus.
        val steam = StepMachine(StepProgram.STEAM, 120 * 60)
        assertEquals(steam.copy(seconds = (Limits.MAX_MS_DEFAULT / 1000).toInt()), of(steam))
        assertEquals("99 min au lieu de 120 min", PermittedSetting.changes(steam, of(steam)!!))
        // Manuel, vitesse 8 pendant 20 min : 15 min (la vitesse reste celle demandée).
        val fast = StepMachine(seconds = 20 * 60, speed = 8)
        assertEquals(fast.copy(seconds = (Limits.MAX_MS_HIGH_SPEED / 1000).toInt()), of(fast))
        // Vitesse 12 : la lame s'arrête à 10, puis 15 min au plus.
        val over = StepMachine(seconds = 20 * 60, speed = 12)
        assertEquals(over.copy(speed = Accessory.BLADE.maxSpeed, seconds = 900), of(over))
        // Sans mélange à 130 °C : 120 °C, la température permise sans mélange.
        val still = StepMachine(seconds = 600, speed = 0, tempC = 130)
        assertEquals(still.copy(tempC = Mcc.celsiusForLevel(Limits.MAX_TEMP_LEVEL_NO_STIR)), of(still))
        // Vitesse 4 en chauffe pendant 2 h : vitesse 3 et 99 min.
        val long = StepMachine(seconds = 2 * 3600, speed = 4, tempC = 100)
        assertEquals(long.copy(speed = 3, seconds = 99 * 60), of(long))
    }

    /** Sur une large grille, pour chaque robot : rien, ou un réglage permis qui ne demande rien de plus. */
    @Test fun neverRaisesAndNeverReturnsARefusedSetting() {
        for (robot in RobotProfiles.ALL) {
            RobotProfiles.active = robot
            walkTheGrid(robot.id)
        }
    }

    private fun walkTheGrid(robot: String) {
        val temps = ArrayList<Int>()
        for (t in Mcc.TEMPERATURE_LEVELS) temps += t
        temps.addAll(listOf(-10, 1, 20, 36, 38, 40, 52, 92, 118, 121, 124, 129, 131, 140, 200))
        val durations = intArrayOf(-5, 0, 1, 2, 30, 59, 60, 119, 120, 121, 600, 839, 840, 841, 900, 901, 1800, 5940, 5941, 7200, 100_000)
        var proposed = 0
        var refused = 0
        for (program in StepProgram.values()) for (speed in -1..12) for (t in temps) for (reverse in listOf(false, true))
            for (acc in Accessory.values()) for (s in durations) {
                val m = StepMachine(program, s, speed, t, reverse, acc, pulses = if (program == StepProgram.TURBO) 2 else 0)
                val p = of(m)
                // Un programme du Smart à une température hors de sa plage : le cœur refuse de le décrire.
                val valid = try {
                    m.valid
                } catch (_: IllegalStateException) {
                    assertNull("proposé pour un réglage que le cœur ne sait pas décrire : $robot : $m", p)
                    false
                }
                if (!valid) refused++
                if (p == null) continue
                proposed++
                val where = "$robot : $m → $p"
                assertTrue("proposé pour un réglage permis : $where", !valid)
                assertTrue("proposition refusée : $where ${p.violations()}", p.violations().isEmpty())
                assertTrue("proposition au-dessus de la recette : $where", PermittedSetting.atMost(p, m))
                assertNull("proposition que ce robot ne sait pas faire : $where", RecipeCompat.stepReason(p, RobotProfiles.active))
                assertTrue("rien n'a changé : $where", p != m)
                assertTrue(PermittedSetting.changes(m, p).isNotEmpty())
            }
        assertTrue(refused > 0)
        assertTrue("$robot : trop peu de propositions : $proposed", proposed > 1000)
    }

    /** La table est celle du robot choisi : 40 °C se cuit sur le Smart, pas sur le Connect. */
    @Test fun theTemperatureTableIsTheChosenRobots() {
        val warm = StepMachine(seconds = 300, speed = 2, tempC = 40)
        // Connect : 40 °C n'est pas dans sa table, la valeur juste en dessous.
        assertEquals(warm.copy(tempC = 37), of(warm))
        assertEquals("37 °C au lieu de 40 °C", PermittedSetting.changes(warm, of(warm)!!))
        // Vitesse 5 à 40 °C : les deux baissent sur le Connect.
        assertEquals(warm.copy(tempC = 37, speed = 3), of(warm.copy(speed = 5)))
        // Un programme que le Connect n'a pas : rien à proposer, même trop long.
        assertNull(of(StepMachine(StepProgram.SMOOTHIE, 100_000)))

        RobotProfiles.active = RobotProfiles.MC_SMART
        // Smart : 40 °C est permis, rien à proposer ; trop vite, seule la vitesse baisse.
        assertNull(of(warm))
        assertEquals(warm.copy(speed = 3), of(warm.copy(speed = 5)))
        // Sans mélange : le Smart ne chauffe pas lame à l'arrêt, rien d'honnête à proposer.
        assertNull(of(StepMachine(seconds = 600, speed = 0, tempC = 130)))
    }

    @Test fun theProposalIsStable() {
        // Le réglage proposé est permis : rien d'autre à proposer ensuite.
        val p = of(StepMachine(seconds = 600, speed = 5, tempC = 95, reverse = true))
        assertNotNull(p)
        assertNull(of(p!!))
    }
}
