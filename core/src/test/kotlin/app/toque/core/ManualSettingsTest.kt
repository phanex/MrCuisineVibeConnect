package app.toque.core

import app.toque.core.companion.LimitsWire
import app.toque.core.companion.CompanionHub
import app.toque.core.machine.Accessory
import app.toque.core.machine.ManualLimits
import app.toque.core.machine.ManualSettings
import app.toque.core.machine.ManualSettings.Companion.CARD_SPEED
import app.toque.core.machine.ManualSettings.Companion.CARD_TEMP
import app.toque.core.machine.ManualSettings.Companion.CARD_TIME
import app.toque.core.machine.SafetyPolicy
import app.toque.core.protocol.Direction
import app.toque.core.protocol.Smart
import app.toque.core.robot.RobotProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Règles de saisie de la cuisson manuelle, partagées par l'écran du robot et le téléphone. */
class ManualSettingsTest {
    private fun ManualSettings.times(card: Int, dir: Int, n: Int): ManualSettings {
        var s = this
        repeat(n) { s = s.change(card, dir).settings }
        return s
    }

    @Test fun defaultsAreThoseOfTheRobotScreen() {
        val d = ManualSettings()
        assertEquals(60, d.seconds)
        assertEquals(0, d.tempLevel)
        assertEquals(2, d.speed)
        assertEquals(false, d.reverse)
        assertEquals(Accessory.BLADE, d.accessory)
        assertTrue(d.violations(22).isEmpty())
    }

    @Test fun durationSteps() {
        val d = ManualSettings()
        // 5 s sous la minute, 30 s sous 10 min, puis 1 min (comme l'écran du robot).
        assertEquals(90, d.nextSeconds(60, 1))
        assertEquals(55, d.nextSeconds(60, -1))
        assertEquals(60, d.nextSeconds(55, 1))
        assertEquals(660, d.nextSeconds(600, 1))
        assertEquals(570, d.nextSeconds(600, -1))
        assertEquals(570, d.nextSeconds(630, -1))
        assertEquals(5, d.copy(seconds = 5).change(CARD_TIME, -1).settings.seconds)
        assertEquals(99 * 60, d.copy(seconds = 99 * 60).change(CARD_TIME, 1).settings.seconds)
        // Au-dessus de la vitesse 6 : 15 min au plus, la durée est ramenée.
        val fast = ManualSettings(seconds = 20 * 60, speed = 6).change(CARD_SPEED, 1).settings
        assertEquals(7, fast.speed)
        assertEquals(15 * 60, fast.seconds)
        assertEquals(15 * 60, fast.change(CARD_TIME, 1).settings.seconds)
    }

    @Test fun heatingLimitsTheSpeedWithTheRobotMessage() {
        val e = ManualSettings(speed = 5).change(CARD_TEMP, 1)
        assertEquals(1, e.settings.tempLevel)
        assertEquals(3, e.settings.speed)
        assertEquals(listOf("En chauffe, vitesse 3 maximum"), e.messages)
        val up = e.settings.change(CARD_SPEED, 1)
        assertEquals(3, up.settings.speed)
        assertEquals(listOf("En chauffe, vitesse 3 maximum"), up.messages)
        // En chauffe, la vitesse descend jusqu'à 0 ; sans chauffe, 1 au moins.
        val noStir = e.settings.times(CARD_SPEED, -1, 3)
        assertEquals(0, noStir.speed)
        val cold = noStir.change(CARD_TEMP, -1)
        assertEquals(1, cold.settings.speed)
        assertEquals(listOf("Sans chauffe, vitesse 1 minimum"), cold.messages)
        assertEquals(listOf("Sans chauffe, vitesse 1 minimum"), ManualSettings(speed = 1).change(CARD_SPEED, -1).messages)
        // Réglage d'une recette au-dessus du maximum (vitesse 8 en chauffe) : « − » ramène au maximum, « + » ne change rien.
        val tooFast = ManualSettings(speed = 8).withCelsius(100)
        val lowered = tooFast.change(CARD_SPEED, -1)
        assertEquals(3, lowered.settings.speed)
        assertEquals(listOf("En chauffe, vitesse 3 maximum"), lowered.messages)
        assertNull(lowered.settings.outOfBounds())
        assertEquals(8, tooFast.change(CARD_SPEED, 1).settings.speed)
        assertEquals(3, ManualSettings(speed = 8, reverse = true).change(CARD_SPEED, -1).settings.speed)
    }

    @Test fun noStirIsCappedAt120() {
        val hot = ManualSettings(speed = 0, tempLevel = 17).change(CARD_TEMP, 1)
        assertEquals(17, hot.settings.tempLevel)
        assertEquals(listOf("Sans mélange, 120 °C maximum"), hot.messages)
        val down = ManualSettings(speed = 1, tempLevel = 19).change(CARD_SPEED, -1)
        assertEquals(0, down.settings.speed)
        assertEquals(17, down.settings.tempLevel)
        // Sans mélange, pas de sens de rotation ; le sens envoyé est toujours le sens normal.
        assertNull(down.settings.withReverse(true))
        assertEquals(Direction.CLOCKWISE, ManualSettings(speed = 0, tempLevel = 5, reverse = true).spec().phases[0].setting.direction)
    }

    @Test fun reverseAndWhiskLimits() {
        val r = ManualSettings(speed = 5).withReverse(true)!!
        assertEquals(3, r.settings.speed)
        assertTrue(r.settings.reverse)
        assertEquals(listOf("En sens inverse, vitesse 3 maximum"), r.messages)
        assertEquals(listOf("En sens inverse, vitesse 3 maximum"), r.settings.change(CARD_SPEED, 1).messages)
        val w = ManualSettings(speed = 8).withAccessory(Accessory.WHISK)
        assertEquals(4, w.settings.speed)
        assertEquals(listOf("Fouet : vitesse 4 maximum"), w.messages)
        assertEquals(listOf("Avec le fouet, vitesse 4 maximum"), w.settings.change(CARD_SPEED, 1).messages)
        // À 10, rien ne change et rien n'est dit (comme sur le robot).
        assertTrue(ManualSettings(speed = 10).change(CARD_SPEED, 1).messages.isEmpty())
    }

    @Test fun outOfBoundsNamesTheFirstProblemAndCorrectsNothing() {
        assertNull(ManualSettings().outOfBounds())
        assertEquals("too_short", ManualSettings(seconds = 4).outOfBounds()!!.code)
        assertEquals("too_long", ManualSettings(seconds = 901, speed = 7).outOfBounds()!!.code)
        assertEquals("speed_min", ManualSettings(speed = 0).outOfBounds()!!.code)
        assertEquals("heated_speed", ManualSettings(speed = 4, tempLevel = 1).outOfBounds()!!.code)
        assertEquals("accessory_speed", ManualSettings(speed = 5, accessory = Accessory.WHISK).outOfBounds()!!.code)
        // Tout réglage obtenu par la saisie est dans les bornes.
        var s = ManualSettings()
        for (i in 0 until 400) {
            val card = i % 3
            val dir = if ((i / 7) % 2 == 0) 1 else -1
            s = s.change(card, dir).settings
            if (i % 11 == 0) s = s.withReverse(i % 22 == 0)?.settings ?: s
            if (i % 13 == 0) s = s.withAccessory(if (i % 26 == 0) Accessory.WHISK else Accessory.BLADE).settings
            // Sans mélange, le sens inverse gardé par l'écran n'est pas envoyé (StartRequest.of).
            assertNull("$s", s.copy(reverse = s.reverse && s.speed > 0).outOfBounds())
            assertTrue("$s", s.violations(22).isEmpty())
        }
    }

    @Test fun hotBlendIsAViolation() {
        val v = ManualSettings(speed = 5).violations(70)
        assertEquals(listOf(SafetyPolicy.HOT_BLEND), v.map { it.code })
    }

    @Test fun theRobotLimitsTravelAndOlderRobotsKeepToqueValues() {
        assertEquals(ManualLimits(), CompanionHub.LIMITS.manual())
        assertEquals(ManualLimits(), LimitsWire.fromMap(emptyMap()).manual())
        val strict = LimitsWire(maxSpeedHeated = 2, maxTempNoStirC = 100).manual()
        assertEquals(2, strict.maxSpeedHeated)
        assertEquals(13, strict.maxTempLevelNoStir)
    }

    /** Smart (0.8.0) : sa table a 40 °C en plus, et pas de chauffe sans mélange ; le Connect est inchangé. */
    @Test fun theSmartHas40DegreesAndNoHeatWithoutStirring() {
        val smart = ManualLimits(temperaturesC = RobotProfiles.MC_SMART.capabilities.temperaturesC, heatNoStir = false)
        val s37 = ManualSettings(speed = 2, limits = smart).change(CARD_TEMP, 1).settings
        assertEquals(37, s37.tempC)
        assertNull(s37.smartLevel)
        val s40 = s37.change(CARD_TEMP, 1).settings
        assertEquals(40, s40.tempC)
        // Règles de sécurité : 40 °C compte pour 37 °C ; la trame porte le niveau du Smart.
        assertEquals(1, s40.tempLevel)
        assertEquals(Smart.TEMPERATURE_LEVELS.indexOf(40), s40.smartLevel)
        assertEquals(s40.smartLevel, s40.spec().phases.single().setting.smartLevel)
        assertEquals(45, s40.change(CARD_TEMP, 1).settings.tempC)
        assertEquals(37, s40.change(CARD_TEMP, -1).settings.tempC)
        // En chauffe, la vitesse ne descend pas sous 1.
        val low = ManualSettings(speed = 1, limits = smart).withCelsius(60).change(CARD_SPEED, -1)
        assertEquals(1, low.settings.speed)
        assertEquals("speed_min", ManualSettings(speed = 0, limits = smart).withCelsius(60).outOfBounds()?.code)
        // Connect : 37 puis 45 °C, et la vitesse 0 en chauffe.
        val c = ManualSettings(speed = 2).change(CARD_TEMP, 1).settings.change(CARD_TEMP, 1).settings
        assertEquals(45, c.tempC)
        assertNull(c.smartLevel)
        assertEquals(0, c.copy(speed = 1).change(CARD_SPEED, -1).settings.speed)
        // Le téléphone reçoit les bornes du robot choisi, sans 40 °C (il envoie un niveau de la table de Toque).
        val phone = CompanionHub.limitsFor(RobotProfiles.MC_SMART).manual()
        assertEquals(false, phone.heatNoStir)
        assertTrue(40 !in phone.temperaturesC)
        assertEquals(ManualLimits(), CompanionHub.limitsFor(RobotProfiles.MC_CONNECT).manual())
        assertEquals(false, LimitsWire.fromMap(CompanionHub.limitsFor(RobotProfiles.MC_SMART).toMap()).heatNoStir)
    }
}
