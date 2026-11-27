package app.toque

import android.view.View
import android.widget.ImageView
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.Step
import app.toque.recipes.RecipeRepo
import app.toque.ui.ImageLoader
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.screens.ScaleScreen
import app.toque.update.Updater
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Tests de non-régression pour les correctifs cycle de vie, mémoire et bornes dans `:app`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w1024dp-h600dp-land-mdpi", application = ScreenshotApp::class)
class OptimizationRegressionTest {

    @After
    fun tearDown() {
        ScaleScreen.history.clear()
        Updater.onInstallError = null
    }

    @Test
    fun mainActivityForwardsStopAndStartToCurrentScreenAndClearsUpdaterCallback() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller.get()
        var shown = 0
        var hidden = 0
        val spyScreen = object : Screen(activity) {
            override val title: String = "Spy"
            override fun build(): View = View(c)
            override fun onShow() { shown++ }
            override fun onHide() { hidden++ }
        }

        activity.push(spyScreen)
        assertEquals(1, shown)
        assertEquals(0, hidden)

        Updater.onInstallError = { }
        controller.pause().stop()
        assertEquals("onStop doit appeler onHide sur l'écran courant", 1, hidden)

        controller.restart().start().resume()
        assertEquals("onStart après onStop doit rappeler onShow sur l'écran courant", 2, shown)

        controller.pause().stop().destroy()
        assertEquals(2, hidden)
        assertNull("onDestroy doit libérer Updater.onInstallError", Updater.onInstallError)
    }

    @Test
    fun scaleScreenHistoryIsBoundedAtFiftyEntries() {
        ScaleScreen.history.clear()
        for (i in 1..65) {
            ScaleScreen.recordHistory(ScaleScreen.WeightEntry("12:00", i * 10, "ing-$i"))
        }
        assertEquals(50, ScaleScreen.history.size)
        assertEquals(160, ScaleScreen.history.first().grams)
        assertEquals(650, ScaleScreen.history.last().grams)
    }

    @Test
    fun imageLoaderIgnoresNonPositiveTargetWidthWithoutLooping() {
        val ctx = RuntimeEnvironment.getApplication()
        val view = ImageView(ctx)
        val dummy = File(ctx.cacheDir, "nonexistent.jpg")
        ImageLoader.load(view, dummy, 0)
        ImageLoader.load(view, dummy, -100)
        assertNull(view.drawable)
    }

    @Test
    fun recipeRepoCacheEvictsOnUpsertWithoutRebuildAndOnDelete() {
        val ctx = RuntimeEnvironment.getApplication()
        val repo = RecipeRepo(ctx)
        val v1 = Recipe(id = "web-cache-test", title = "V1", category = "Test", servings = 2,
            steps = listOf(Step("Étape 1")), source = RecipeSource.IMPORTED)
        val v2 = v1.copy(title = "V2")

        repo.upsertAll(listOf(v1), rebuild = false)
        assertEquals("V1", repo.get("web-cache-test")?.title)

        // Mise à jour sans rebuild (ex. synchro interrompue avant reloadNow) : le cache ne doit pas garder V1.
        repo.upsertAll(listOf(v2), rebuild = false)
        assertEquals("V2", repo.get("web-cache-test")?.title)

        // Suppression sans rebuild : le cache ne doit plus renvoyer V2.
        repo.removeAllExcept(emptySet(), rebuild = false)
        assertNull(repo.get("web-cache-test"))
    }
}
