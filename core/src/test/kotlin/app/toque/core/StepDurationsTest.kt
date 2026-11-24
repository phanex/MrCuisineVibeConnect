package app.toque.core

import app.toque.core.recipes.StepDurations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StepDurationsTest {
    private fun min(text: String): Int? = StepDurations.find(text)?.let { if (it % 60 == 0) it / 60 else -it }

    @Test fun frenchMinutesAndHours() {
        assertEquals(30, min("Couvrir et laisser reposer la pâte 30 min à température ambiante."))
        assertEquals(25, min("Enfourner 25 minutes à 180 °C."))
        assertEquals(1, min("Laisser tiédir 1 minute."))
        assertEquals(10, min("Laisser gonfler 10 mn."))
        assertEquals(15, min("Laisser reposer 15min."))
        assertEquals(120, min("Laisser lever 2 heures dans un endroit tiède."))
        assertEquals(60, min("Réfrigérer 1 h."))
        assertEquals(90, min("Cuire au four 1 h 30."))
        assertEquals(90, min("Cuire au four 1h30."))
        assertEquals(75, min("Laisser mariner 1 h 15 min au frais."))
        assertEquals(90, min("Laisser lever 1 heure et demie."))
        assertEquals(90, min("Laisser lever 1,5 h."))
        assertEquals(60, min("Laisser reposer une heure."))
        assertEquals(30, min("Laisser refroidir une demi-heure."))
        assertEquals(15, min("Laisser infuser un quart d'heure."))
        assertEquals(-150, min("Fouetter à la main 2 min 30 s."))
        assertEquals(-90, min("Laisser reposer 90 secondes."))
    }

    @Test fun rangesGiveTheShortestTime() {
        assertEquals(20, min("Cuire 20 à 25 min au four."))
        assertEquals(20, min("Cuire 20-25 min au four."))
        assertEquals(20, min("Cuire 20–25 minutes au four."))
        assertEquals(2, min("Laisser reposer 2 ou 3 minutes."))
        assertEquals(20, min("Bake for 20 to 25 minutes."))
        assertEquals(20, min("Hornear entre 20 y 25 minutos."))
        assertEquals(20, min("20 bis 25 Minuten backen."))
    }

    @Test fun firstDurationOfAtLeastAMinute() {
        // 45 secondes d'abord, puis 10 minutes : on propose 10 minutes.
        assertEquals(10, min("Battre 45 secondes, puis laisser reposer 10 minutes."))
        assertEquals(5, min("Laisser reposer 5 min, puis enfourner 25 min."))
        // Seulement des secondes : rien.
        assertNull(min("Battre 45 secondes."))
        assertNull(min("Mélanger 30 s."))
    }

    @Test fun otherLanguages() {
        // anglais
        assertEquals(30, min("Cover and let rest for 30 min."))
        assertEquals(45, min("Bake for 45 minutes."))
        assertEquals(120, min("Leave to rise for 2 hours."))
        assertEquals(60, min("Chill for an hour."))
        assertEquals(30, min("Let it cool for half an hour."))
        assertEquals(90, min("Bake 1 hour and a half."))
        assertEquals(90, min("Bake 1.5 hours."))
        assertEquals(10, min("Simmer 10 mins."))
        // espagnol
        assertEquals(30, min("Dejar reposar 30 minutos."))
        assertEquals(60, min("Refrigerar una hora."))
        assertEquals(30, min("Dejar enfriar media hora."))
        assertEquals(90, min("Hornear 1 hora y 30 minutos."))
        assertEquals(90, min("Hornear 1 hora y media."))
        // allemand
        assertEquals(30, min("30 Minuten ruhen lassen."))
        assertEquals(30, min("Den Teig 30 Min. gehen lassen."))
        assertEquals(120, min("2 Stunden kalt stellen."))
        assertEquals(60, min("1 Std. ruhen lassen."))
        assertEquals(75, min("1 Std. 15 Min. backen."))
        assertEquals(60, min("Eine Stunde ruhen lassen."))
        assertEquals(30, min("Eine halbe Stunde abkühlen lassen."))
        // catalan
        assertEquals(30, min("Deixeu reposar 30 minuts."))
        assertEquals(60, min("Refredeu una hora."))
        assertEquals(30, min("Deixeu refredar mitja hora."))
        assertEquals(15, min("Deixeu infusionar un quart d'hora."))
        assertEquals(120, min("Deixeu llevar 2 hores."))
    }

    @Test fun refusesWhatIsNotAKitchenTimer() {
        // Pas de durée.
        assertNull(min("Éplucher l'oignon et couper le potimarron en morceaux."))
        assertNull(min("Ajouter 200 g de farine et 2 œufs."))
        assertNull(min(""))
        // Réglage machine écrit dans le texte.
        assertNull(min("Cuire 5 min/100 °C/vitesse 1."))
        assertNull(min("5 Min./100 °C/Stufe 1 garen."))
        assertNull(min("Mixer 30 s vitesse 10, puis 2 min vitesse 4."))
        assertNull(min("Hacer 5 min a velocidad 1."))
        assertNull(min("Mix for 2 minutes on speed 4."))
        // Intervalle, pas une durée.
        assertNull(min("Remuer toutes les 5 min."))
        assertNull(min("Stir every 10 minutes."))
        assertNull(min("Remover cada 5 minutos."))
        assertNull(min("Alle 10 Minuten umrühren."))
        // Trop long pour un minuteur (une marinade de deux jours, une nuit de 24 h).
        assertNull(min("Laisser mariner 24 h au réfrigérateur."))
        // « h » seul n'est pas « une heure » ; « 1 h 30 g » n'est pas « 1 h 30 ».
        assertNull(min("une h"))
        assertEquals(60, min("Laisser reposer 1 h 30 g de beurre à côté."))
        // Les jours ne sont pas lus.
        assertNull(min("Laisser reposer 2 jours."))
        // Autres nombres suivis d'un mot qui ressemble à une unité, mais n'en est pas une.
        assertNull(min("Couper 2 morceaux de 3 cm."))
    }

    @Test fun mixedTextTakesTheKitchenTimer() {
        // Le réglage machine est écarté, le repos reste.
        assertEquals(30, min("Cuire 5 min/100 °C/vitesse 1, puis laisser reposer 30 min."))
    }

    @Test fun labels() {
        assertEquals("30 min", StepDurations.label(1800))
        assertEquals("1 h", StepDurations.label(3600))
        assertEquals("1 h 30", StepDurations.label(5400))
        assertEquals("2 h 05", StepDurations.label(7500))
        assertEquals("1 min 30 s", StepDurations.label(90))
        assertEquals("45 s", StepDurations.label(45))
    }
}
