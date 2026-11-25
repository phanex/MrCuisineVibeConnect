package app.toque

import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import app.toque.core.companion.JournalPage
import app.toque.core.companion.client.CompanionClient
import app.toque.core.companion.client.CompanionException
import app.toque.core.machine.JobState
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.machine.MachineService
import app.toque.ui.MainActivity
import app.toque.ui.screens.CookScreen
import app.toque.ui.screens.RecipeScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * « Arrêter la recette » envoyé par un téléphone (vrai client HTTP sur la boucle locale) à un hub en
 * marche : la recette guidée se ferme sur l'écran du robot comme avec son propre bouton (programme
 * arrêté, session close, plus de « Reprendre »), et le journal dit que c'est ce téléphone. Les
 * favoris choisis depuis le téléphone sont enregistrés comme l'étoile du robot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w1024dp-h600dp-land-mdpi", application = ScreenshotApp::class)
class PhoneStopsRecipeTest {
    private lateinit var activity: MainActivity
    private var activityController: ActivityController<MainActivity>? = null
    private var serviceController: ServiceController<MachineService>? = null

    private val pain = Recipe(
        id = "perso-pain-essai", title = "Pain d'essai", category = "Pains", servings = 4, source = RecipeSource.USER,
        steps = listOf(
            Step("Mettre la farine et l'eau dans le bol."),
            Step("Pétrir.", StepMachine(StepProgram.KNEAD, 120)),
            Step("Laisser reposer."),
        ),
    )

    @After fun tearDown() {
        try {
            activityController?.pause()?.stop()?.destroy()
        } catch (_: Throwable) {
        }
        try {
            serviceController?.destroy()
        } catch (_: Throwable) {
        }
        activityController = null
        serviceController = null
        idle(2)
    }

    private fun app(): ScreenshotApp = org.robolectric.RuntimeEnvironment.getApplication() as ScreenshotApp

    private fun idle(rounds: Int = 1) {
        repeat(rounds) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            assertTrue("attente trop longue : $what", System.currentTimeMillis() < end)
            idle()
            Thread.sleep(30)
        }
    }

    /** Requête du téléphone sur un autre fil ; le fil principal du robot tourne pendant ce temps. */
    private fun <T> offMain(block: () -> T): T {
        var out: Any? = null
        var error: Throwable? = null
        val t = Thread { try { out = block() } catch (e: Throwable) { error = e } }
        t.start()
        val end = System.currentTimeMillis() + 15_000
        while (t.isAlive && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        t.join(1_000)
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun start(): CompanionClient {
        val app = app()
        val service = Robolectric.buildService(MachineService::class.java).create().also { serviceController = it }.get()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, MachineService::class.java),
            service.onBind(Intent(app, MachineService::class.java)))
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().also { activityController = it }.get()
        waitFor("base simulée prête") { app.machine.snapshot.ready }
        app.recipes.saveUser(pain)
        waitFor("recette enregistrée") { app.recipes.index.entries.any { it.id == pain.id } }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        val hub = app.companion!!.hub
        val o = hub.pairing.open()
        idle()
        waitFor("serveur ouvert") { hub.running && hub.port > 0 }
        val phone = CompanionClient("127.0.0.1", hub.port)
        offMain { phone.pairWithCode(o.code) }
        hub.pairing.close()
        return phone
    }

    @Test fun thePhoneStopsTheGuidedRecipeLikeTheRobotsButton() {
        val phone = start()
        val app = app()
        // Rien d'ouvert : refusé, aucun écran ne bouge.
        assertEquals("no_recipe", offMain { phone.state() }.can.stopRecipe.code)
        try {
            offMain { phone.stopRecipe() }
            fail()
        } catch (e: CompanionException) {
            assertEquals("no_recipe", e.error.code)
        }

        // Fiche puis « Cuisiner » à l'étape du pétrissage, lancé sur le robot.
        activity.push(RecipeScreen(activity, app.recipes.get(pain.id)!!))
        idle()
        val cook = CookScreen(activity, app.recipes.getAdjusted(pain.id)!!, 1)
        activity.push(cook)
        idle(3)
        assertTrue(cook.onKnobPress())
        waitFor("pétrissage en cours") { app.machine.snapshot.state == JobState.RUNNING }
        idle(3)
        assertEquals(pain.id, app.prefs.guidedRecipe)
        assertTrue(offMain { phone.state() }.can.stopRecipe.allowed)

        assertTrue(offMain { phone.stopRecipe() }.ok)
        // Comme le bouton de l'écran : la fiche revient, plus de « Reprendre », la session est close.
        idle(3)
        assertTrue(activity.current is RecipeScreen)
        assertEquals("", app.prefs.guidedRecipe)
        waitFor("programme arrêté") { app.machine.snapshot.state == JobState.ABORTED || !app.machine.snapshot.jobActive }
        val st = offMain { phone.state() }
        assertEquals(null, st.session)
        assertEquals("no_recipe", st.can.stopRecipe.code)
        val page: JournalPage = offMain { phone.journal(0, 50) }
        val end = page.entries.first { it.kind == "recipe_abandoned" }
        assertEquals("Recette arrêtée depuis Téléphone 1", end.text)
        assertTrue(end.origin.self)
        waitFor("arrêt du programme au journal") { offMain { phone.journal(0, 50) }.entries.any { it.kind == "job_stopped" } }
        assertTrue(offMain { phone.journal(0, 50) }.entries.first { it.kind == "job_stopped" }.text.startsWith("Arrêté depuis Téléphone 1"))
        // Une seconde demande : plus rien à arrêter.
        try {
            offMain { phone.stopRecipe() }
            fail()
        } catch (e: CompanionException) {
            assertEquals(409, e.status)
        }
        assertTrue(activity.current is RecipeScreen)
    }

    @Test fun favoritesFromThePhoneAreTheRobotsStar() {
        val phone = start()
        val app = app()
        assertTrue(offMain { phone.state() }.can.favorite.allowed)
        assertFalse(app.recipes.isFavorite(pain.id))
        assertTrue(offMain { phone.setFavorite(pain.id, true) }.favorite)
        assertTrue(app.recipes.isFavorite(pain.id))
        waitFor("liste du robot à jour") { app.recipes.index.entries.first { it.id == pain.id }.favorite }
        assertTrue(offMain { phone.recipe(pain.id) }.favorite)
        assertFalse(offMain { phone.setFavorite(pain.id, false) }.favorite)
        assertFalse(app.recipes.isFavorite(pain.id))
        try {
            offMain { phone.setFavorite("recette-inconnue", true) }
            fail()
        } catch (e: CompanionException) {
            assertEquals(404, e.status)
        }
    }
}
