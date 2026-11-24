package app.toque.core

import app.toque.core.machine.ControllerConfig
import app.toque.core.machine.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Remise à zéro de la balance au retour du bol vide (et jamais d'un bol chargé). */
class BowlScaleTest {

    private fun bench(empty: Int = 1500, on: Boolean = true): Bench {
        val b = Bench(ControllerConfig(bowlAutoZero = on, emptyBowlG = empty))
        b.board.bowlPresent = true
        b.board.contentWeightG = empty
        b.ready()
        b.step(2_000)
        return b
    }

    private fun Bench.lift() {
        board.bowlPresent = false
        board.contentWeightG = 0
        step(3_000)
    }

    private fun Bench.putBack(grams: Int) {
        board.bowlPresent = true
        board.contentWeightG = grams
        step(3_000)
    }

    private fun Bench.has(type: EventType) = events.any { it.type == type }
    private fun near(expected: Int, actual: Int, tol: Int = 10) =
        assertTrue("attendu ≈ $expected g, lu $actual g", abs(expected - actual) <= tol)

    @Test fun emptyBowlPutBackZeroesTheScale() {
        val b = bench()
        near(1500, b.snap.weightG)
        b.lift()
        b.putBack(1500)
        assertTrue(b.has(EventType.SCALE_ZEROED))
        near(0, b.snap.weightG)
    }

    @Test fun loadedBowlIsNeverZeroed() {
        val b = bench()
        b.lift()
        b.putBack(1500 + 800)
        assertFalse(b.has(EventType.SCALE_ZEROED))
        val info = b.events.last { it.type == EventType.SCALE_INFO }
        val grams = Regex("(\\d+) g").find(info.message)?.groupValues?.get(1)?.toInt() ?: -1
        near(800, grams) // contenu annoncé
        near(2300, b.snap.weightG)
    }

    @Test fun earlierTaresDoNotFoolTheComparison() {
        val b = bench()
        // Bol avec 300 g, taré : la balance lit 0 alors que 1 800 g sont posés.
        b.board.contentWeightG = 1800
        b.step(2_000)
        b.c.tare(learn = false)
        b.step(2_000)
        near(0, b.snap.weightG)
        b.lift()
        near(-1800, b.snap.weightG)
        b.putBack(1500) // vidé, lavé, replacé
        assertTrue(b.has(EventType.SCALE_ZEROED))
        near(0, b.snap.weightG)
    }

    @Test fun lidPutOnAfterTheBowlIsStillRecognised() {
        val b = bench(empty = 1800)
        b.lift()
        b.putBack(1500) // bol sans couvercle : plus léger, on attend
        assertFalse(b.has(EventType.SCALE_ZEROED))
        b.board.contentWeightG = 1800 // couvercle posé
        b.step(3_000)
        assertTrue(b.has(EventType.SCALE_ZEROED))
    }

    @Test fun tareRightAfterPuttingTheBowlBackLearnsIt() {
        val b = bench(empty = 0)
        b.lift()
        b.putBack(1500)
        assertTrue(b.has(EventType.SCALE_INFO)) // conseil : appuyez sur Tare si le bol est vide
        b.c.tare()
        b.step(1_000)
        val learned = b.events.last { it.type == EventType.BOWL_LEARNED }
        near(1500, learned.value)
        near(1500, b.c.emptyBowlG)
        // Ensuite, le retour du bol vide remet à zéro tout seul.
        b.board.contentWeightG = 1500 + 400
        b.step(2_000)
        b.lift()
        b.putBack(1500)
        assertTrue(b.has(EventType.SCALE_ZEROED))
    }

    @Test fun automaticTaresNeverLearn() {
        val b = bench(empty = 0)
        b.lift()
        b.putBack(1500)
        b.c.tare(learn = false)
        b.step(1_000)
        assertFalse(b.has(EventType.BOWL_LEARNED))
        assertEquals(0, b.c.emptyBowlG)
    }

    @Test fun tareAfterAddingFoodDoesNotLearn() {
        val b = bench(empty = 0)
        b.lift()
        b.putBack(1500)
        b.step(31_000) // fenêtre d'évaluation du retour passée
        b.board.contentWeightG = 1500 + 250 // farine ajoutée
        b.step(3_000)
        b.c.tare()
        b.step(1_000)
        assertFalse(b.has(EventType.BOWL_LEARNED))
    }

    @Test fun offByDefault() {
        val b = bench(on = false)
        b.lift()
        b.putBack(1500)
        assertFalse(b.has(EventType.SCALE_ZEROED))
        near(1500, b.snap.weightG)
    }
}
