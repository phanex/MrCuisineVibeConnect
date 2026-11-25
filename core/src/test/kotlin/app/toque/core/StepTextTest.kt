package app.toque.core

import app.toque.core.machine.Accessory
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.core.recipes.StepText
import app.toque.core.robot.RobotProfiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StepTextTest {
    @After fun reset() {
        RobotProfiles.active = RobotProfiles.MC_CONNECT
    }

    private fun manual(seconds: Int, speed: Int, tempC: Int = 0, reverse: Boolean = false, whisk: Boolean = false) =
        StepMachine(StepProgram.MANUAL, seconds, speed, tempC, reverse, if (whisk) Accessory.WHISK else Accessory.BLADE)

    @Test fun robotNotationInSeveralLanguages() {
        assertEquals(manual(300, 1, 100), StepText.parse("Ajouter l'eau et cuire 5 min/100°C/vitesse 1."))
        assertEquals(manual(10, 5), StepText.parse("Zwiebel zugeben und 10 Sek./Stufe 5 zerkleinern."))
        assertEquals(manual(180, 1, 120, reverse = true), StepText.parse("Cook 3 min/120°C/reverse/speed 1."))
        assertEquals(manual(420, 2, 90), StepText.parse("Cocinar 7 min/90 °C/vel 2"))
        assertEquals(manual(90, 3), StepText.parse("Mixer 1 min 30 s à vitesse 3"))
        assertEquals(manual(5400, 1, 90), StepText.parse("Mijoter 1 h 30 / 90 °C / vitesse 1"))
        assertEquals(manual(5400, 1, 90), StepText.parse("Mijoter 1h30/90°C/vitesse 1"))
        // Point d'abréviation suivi d'une majuscule : même phrase.
        assertEquals(manual(10, 5), StepText.parse("10 Sek. Stufe 5"))
        assertEquals(manual(240, 2, 100, reverse = true), StepText.parse("4 Min. | 100 °C | Linkslauf | Stufe 2"))
    }

    @Test fun rangesTakeTheLowestAndSpeedsRoundDown() {
        assertEquals(manual(20, 5), StepText.parse("Mixer 20 sec/vitesse 5-10 en augmentant progressivement"))
        assertEquals(manual(120, 4), StepText.parse("2-3 Min./Stufe 4,5"))
        assertEquals(manual(30, 2), StepText.parse("30 s / vitesse 2½"))
    }

    @Test fun whiskOnlyWhenItIsInThePhrase() {
        assertEquals(manual(180, 3, whisk = true), StepText.parse("Insérer le fouet et battre 3 min/vitesse 3."))
        assertEquals(manual(10, 8), StepText.parse("Mettre le fouet de côté. Mixer 10 sec/vitesse 8."))
        assertNull(StepText.parse("Retirer le fouet et mixer 10 sec/vitesse 8."))
    }

    @Test fun onlyTheSettingPhraseCounts() {
        assertEquals(manual(10, 5), StepText.parse("Mixer 10 sec/vitesse 5. Laisser reposer 5 min."))
        assertEquals(manual(10, 5), StepText.parse("Préchauffer le four à 180 °C. Mixer 10 sec/vitesse 5."))
        assertEquals(manual(300, 1, 100), StepText.parse("Cuire 5 min/100 °C/vitesse 1 puis laisser tiédir 10 min."))
    }

    @Test fun nothingWhenUnsure() {
        // Deux réglages, ou un réglage et un Turbo.
        assertNull(StepText.parse("Cuire 5 min/100°C/vitesse 1 et mixer 30 sec/vitesse 8."))
        assertNull(StepText.parse("Mixer 10 sec/vitesse 5, puis Turbo 2 fois."))
        // Varoma, °F, vitesse « mijotage » ou « soft », pas de vitesse, pas de durée, deux durées.
        assertNull(StepText.parse("Cuire 20 min/Varoma/vitesse 1."))
        assertNull(StepText.parse("Cook 5 min/212 °F/speed 1"))
        assertNull(StepText.parse("Cuire 10 min/100 °C/vitesse mijotage"))
        assertNull(StepText.parse("Cook 10 min/100°C/speed soft"))
        assertNull(StepText.parse("Faire revenir 3 min à 120 °C."))
        assertNull(StepText.parse("Mixer à vitesse 5."))
        assertNull(StepText.parse("Mixer 10 sec et 20 sec à vitesse 5."))
        assertNull(StepText.parse("Laisser reposer 30 min."))
        assertNull(StepText.parse(""))
        assertNull(StepText.parse("Stufe 12 für 10 Sek."))
        assertNull(StepText.parse("vitesse 0.5 pendant 10 sec"))
    }

    @Test fun turboAndPrograms() {
        assertEquals(StepMachine(StepProgram.TURBO, pulses = 3), StepText.parse("Turbo/0,5 Sek./3 Mal"))
        assertEquals(StepMachine(StepProgram.TURBO, pulses = 2), StepText.parse("Hacher en Turbo 2-3 fois"))
        assertEquals(StepMachine(StepProgram.TURBO, pulses = 1), StepText.parse("Turbo 1 sec"))
        assertEquals(StepMachine(StepProgram.TURBO, pulses = 10), StepText.parse("Turbo × 15"))
        assertEquals(StepMachine(StepProgram.KNEAD, seconds = 120, speed = 4), StepText.parse("Pétrir 2 min en mode pétrin."))
        assertEquals(StepMachine(StepProgram.KNEAD, seconds = 90, speed = 4), StepText.parse("Teig-Modus 1½ Min."))
        assertEquals(StepMachine(StepProgram.STEAM, seconds = 1200), StepText.parse("Lancer le programme vapeur 20 min."))
        assertEquals(StepMachine(StepProgram.TOAST, seconds = 300), StepText.parse("Dorer 5 min en mode rissoler."))
        // « Pétrir » seul : un geste, pas un programme.
        assertNull(StepText.parse("Pétrir la pâte 10 min à la main."))
    }

    @Test fun proposalFitsTheRobot() {
        // 98 °C n'est pas dans la table : celle juste en dessous.
        assertEquals(manual(300, 1, 95), StepText.propose("Cuire 5 min/98°C/vitesse 1"))
        // Trop rapide en chauffe : gardé, la cuisson proposera le réglage permis (PermittedSetting).
        assertEquals(manual(60, 8, 100), StepText.propose("Mixer 1 min/100 °C/vitesse 8"))
        // Sous la première température de la table : rien d'honnête.
        assertNull(StepText.propose("Mélanger 1 min/20 °C/vitesse 2"))
        // Le Smart n'a pas de programme Rissoler, mais il a les programmes propres.
        RobotProfiles.active = RobotProfiles.MC_SMART
        val toast = StepText.parse("Dorer 5 min en mode rissoler.")!!
        assertEquals(toast.takeIf { RobotProfiles.MC_SMART.capabilities.has(StepProgram.TOAST.feature) },
            StepText.propose("Dorer 5 min en mode rissoler.", RobotProfiles.MC_SMART))
    }
}
