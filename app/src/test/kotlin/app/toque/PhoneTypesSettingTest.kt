package app.toque

import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.toque.core.companion.EditStepRequest
import app.toque.core.companion.EditableRecipe
import app.toque.core.companion.RecipeEditRequest
import app.toque.core.companion.SettingEdit
import app.toque.core.companion.client.CompanionClient
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * Réglage du robot tapé sur le téléphone (lot B), vu du robot : enregistré à vérifier, la carte
 * « Réglage à vérifier » revient avant le premier lancement même pour un réglage au même résumé
 * qu'un réglage vérifié avant à ce rang, et les étapes que le téléphone n'a pas touchées gardent la leur.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w1024dp-h600dp-land-mdpi", application = ScreenshotApp::class)
class PhoneTypesSettingTest {
    private lateinit var activity: MainActivity
    private var activityController: ActivityController<MainActivity>? = null
    private var serviceController: ServiceController<MachineService>? = null

    /** Réglage vérifié autrefois à l'étape 1 (rang 0), puis retiré sur le robot. */
    private val before = StepMachine(seconds = 300, speed = 1, tempC = 90)
    private val cuire = StepMachine(StepProgram.MANUAL, 1200, 1, 100)
    private val mixer = StepMachine(seconds = 30, speed = 5)
    private val soupe = Recipe(
        id = "perso-soupe-reglage", title = "Soupe à régler", servings = 4, source = RecipeSource.USER,
        ingredients = listOf(IngredientGroup(items = listOf(Ingredient("2", "", "carottes")))),
        steps = listOf(
            Step("Faire revenir les carottes."),
            Step("Cuire.", cuire, needsReview = true),
            Step("Mixer.", mixer, needsReview = true),
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

    private fun start() {
        val app = app()
        val service = Robolectric.buildService(MachineService::class.java).create().also { serviceController = it }.get()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, MachineService::class.java),
            service.onBind(Intent(app, MachineService::class.java)))
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().also { activityController = it }.get()
        waitFor("base simulée prête") { app.machine.snapshot.ready }
        app.recipes.saveUser(soupe)
        waitFor("recette enregistrée") { app.recipes.index.entries.any { it.id == soupe.id } }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
    }

    private fun pairPhone(): CompanionClient {
        val hub = app().companion!!.hub
        val o = hub.pairing.open()
        idle()
        waitFor("serveur ouvert") { hub.running && hub.port > 0 }
        val phone = CompanionClient("127.0.0.1", hub.port)
        offMain { phone.pairWithCode(o.code) }
        hub.pairing.close()
        return phone
    }

    private val decor: View get() = activity.window.decorView

    private fun findAll(v: View, text: String, out: MutableList<TextView> = ArrayList()): MutableList<TextView> {
        if (v is TextView && v.text.toString() == text) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) findAll(v.getChildAt(i), text, out)
        return out
    }

    private fun shown(v: View?): Boolean {
        var x: View? = v ?: return false
        while (x != null) {
            if (x.visibility != View.VISIBLE) return false
            x = x.parent as? View
        }
        return true
    }

    private fun findShown(text: String): TextView? = findAll(decor, text).lastOrNull { shown(it) }

    private fun buttonOf(text: String): View {
        var x: View = findShown(text) ?: throw AssertionError("texte absent : $text")
        while (!x.isClickable) x = x.parent as View
        return x
    }

    private fun cook(step: Int): CookScreen {
        activity.push(RecipeScreen(activity, activity.app.recipes.get(soupe.id)!!))
        idle()
        val cook = CookScreen(activity, activity.app.recipes.getAdjusted(soupe.id)!!, step)
        activity.push(cook)
        idle(3)
        return cook
    }

    private fun leaveCooking() {
        activity.home()
        idle(3)
    }

    private fun request(e: EditableRecipe, settings: Map<Int, SettingEdit>) = RecipeEditRequest(version = e.version, title = e.title,
        servings = e.servings, totalMinutes = e.totalMinutes, difficulty = e.difficulty, ingredients = e.ingredients,
        steps = e.steps.map { EditStepRequest(it.index, it.text, it.grams, settings[it.index]) })

    @Test fun aSettingTypedOnThePhoneIsConfirmedOnTheRobotBeforeItsFirstLaunch() {
        start()
        val app = app()
        val id = soupe.id
        // Ce que le robot avait gardé : le réglage d'avant vérifié et adopté au rang 0, les deux autres étapes vérifiées.
        val mine = setOf(CookScreen.stepKey(id, 0) + before.summary, CookScreen.stepKey(id, 1) + cuire.summary,
            CookScreen.stepKey(id, 2) + mixer.summary)
        val other = "perso-autre|0|" + before.summary
        app.prefs.reviewedSteps = mine + other
        app.prefs.adaptedSteps = setOf(CookScreen.stepKey(id, 0) + "manuel/300/4/90/0/BLADE/0>manuel/300/3/90/0/BLADE/0", "perso-autre|0|x>y")

        val phone = pairPhone()
        val e = offMain { phone.editable(id) }
        assertNotNull(e.settings)
        // Le téléphone tape à l'étape 1 le réglage vérifié autrefois à ce rang (même résumé), renvoie tel quel celui de l'étape 2.
        val saved = offMain { phone.saveRecipe(id, request(e, mapOf(0 to SettingEdit(before), 1 to SettingEdit(cuire)))) }
        assertTrue(saved.steps[0].needsReview)
        waitFor("enregistrée") { app.recipes.get(id)?.steps?.get(0)?.machine == before }
        val r = app.recipes.get(id)!!
        assertEquals(listOf(true, true, true), r.steps.map { it.needsReview })
        assertEquals(listOf(before, cuire, mixer), r.steps.map { it.machine })
        // Oublié au rang 0 seulement, pour cette recette seulement.
        assertEquals(mine - (CookScreen.stepKey(id, 0) + before.summary) + other, app.prefs.reviewedSteps)
        assertEquals(setOf("perso-autre|0|x>y"), app.prefs.adaptedSteps)
        // Le journal du robot dit quel téléphone et quelle étape.
        assertTrue(app.log.read().contains("réglage écrit sur le téléphone à l'étape 1"))
        assertTrue(app.log.read().contains("recette enregistrée depuis "))

        // Sur le robot : la carte de vérification avant le lancement, « Lancer » bloqué jusqu'à la vérification.
        val c = cook(0)
        assertNotNull(findShown("J'ai vérifié ce réglage"))
        assertFalse(buttonOf("Lancer").isEnabled)
        // Les étapes que le téléphone n'a pas touchées gardent leur vérification.
        c.onKnob(1)
        idle(3)
        assertNull(findShown("J'ai vérifié ce réglage"))
        assertTrue(buttonOf("Lancer").isEnabled)
        c.onKnob(1)
        idle(3)
        assertNull(findShown("J'ai vérifié ce réglage"))
        // Vérifié une fois : gardé, la carte ne revient pas.
        c.onKnob(-2)
        idle(3)
        buttonOf("J'ai vérifié ce réglage").performClick()
        idle(3)
        assertTrue(buttonOf("Lancer").isEnabled)
        leaveCooking()
        cook(0)
        assertNull(findShown("J'ai vérifié ce réglage"))
        leaveCooking()

        // Le téléphone change seulement le texte : le réglage vérifié reste vérifié.
        val e2 = offMain { phone.editable(id) }
        offMain { phone.saveRecipe(id, request(e2, emptyMap()).let { q -> q.copy(steps = q.steps.mapIndexed { i, s -> if (i == 0) s.copy(text = "Faire revenir.") else s }) }) }
        waitFor("texte changé") { app.recipes.get(id)?.steps?.get(0)?.text == "Faire revenir." }
        assertTrue(CookScreen.stepKey(id, 0) + before.summary in app.prefs.reviewedSteps)
        cook(0)
        assertNull(findShown("J'ai vérifié ce réglage"))
        leaveCooking()

        // Le téléphone retape le même réglage : la carte revient.
        val e3 = offMain { phone.editable(id) }
        offMain { phone.saveRecipe(id, request(e3, mapOf(0 to SettingEdit(before.copy(seconds = 240))))) }
        waitFor("réglage changé") { app.recipes.get(id)?.steps?.get(0)?.machine?.seconds == 240 }
        val e4 = offMain { phone.editable(id) }
        offMain { phone.saveRecipe(id, request(e4, mapOf(0 to SettingEdit(before)))) }
        waitFor("réglage remis") { app.recipes.get(id)?.steps?.get(0)?.machine == before }
        assertFalse(CookScreen.stepKey(id, 0) + before.summary in app.prefs.reviewedSteps)
        cook(0)
        assertNotNull(findShown("J'ai vérifié ce réglage"))
        assertFalse(buttonOf("Lancer").isEnabled)
    }

    /** Un réglage tapé sur le téléphone, sans texte, juste après un réglage du robot : jamais enchaîné avant d'être vérifié. */
    @Test fun anUncheckedTypedSettingIsNeverChainedToThePreviousStep() {
        start()
        val app = app()
        assertTrue(app.prefs.chainSteps)
        val id = soupe.id
        val phone = pairPhone()
        val e = offMain { phone.editable(id) }
        val mixe = StepMachine(seconds = 20, speed = 5)
        // L'étape « Mixer. » devient une étape sans texte, au réglage tapé sur le téléphone ; « Cuire. » est vérifiée.
        app.prefs.reviewedSteps = setOf(CookScreen.stepKey(id, 1) + cuire.summary)
        val req = request(e, mapOf(2 to SettingEdit(mixe))).let { q -> q.copy(steps = q.steps.mapIndexed { i, st -> if (i == 2) st.copy(text = "") else st }) }
        offMain { phone.saveRecipe(id, req) }
        waitFor("enregistrée") { app.recipes.get(id)?.steps?.get(2)?.machine == mixe }
        val c = cook(1)
        assertTrue(buttonOf("Lancer").isEnabled)
        assertNull(findShown("Puis, enchaînés automatiquement :\n• " + mixe.summary))
        // Vérifiée une fois sur le robot : elle s'enchaîne comme avant.
        c.onKnob(1)
        idle(3)
        buttonOf("J'ai vérifié ce réglage").performClick()
        idle(3)
        c.onKnob(-1)
        idle(3)
        assertNotNull(findShown("Puis, enchaînés automatiquement :\n• " + mixe.summary))
    }

    /** Un réglage importé à vérifier, sans texte propre, s'enchaîne toujours : la première étape de la série le liste et se vérifie. */
    @Test fun anImportedSettingToCheckStillChainsWithItsSeries() {
        start()
        val app = app()
        assertTrue(app.prefs.chainSteps)
        val suite = StepMachine(seconds = 20, speed = 5)
        // Comme une recette Lidl : la troisième étape n'a pour texte que le résumé de son réglage.
        app.recipes.saveUser(soupe.copy(steps = listOf(soupe.steps[0], soupe.steps[1], Step(suite.summary, suite, needsReview = true))))
        waitFor("recette enregistrée") { app.recipes.get(soupe.id)?.steps?.get(2)?.machine == suite }
        cook(1)
        assertNotNull(findShown("Puis, enchaînés automatiquement :\n• " + suite.summary))
        assertNotNull(findShown("J'ai vérifié ce réglage"))
    }

    /** Une étape déplacée sur le téléphone ne reprend pas la vérification gardée à son nouveau rang. */
    @Test fun aMovedStepDoesNotInheritTheCheckKeptAtItsNewRank() {
        start()
        val app = app()
        val id = soupe.id
        // Le même réglage aux deux étapes ; seule celle du rang 1 a été vérifiée sur le robot.
        app.recipes.saveUser(soupe.copy(steps = listOf(soupe.steps[0], Step("Cuire.", cuire, needsReview = true), Step("Cuire encore.", cuire, needsReview = true))))
        waitFor("recette enregistrée") { app.recipes.get(id)?.steps?.get(2)?.text == "Cuire encore." }
        app.prefs.reviewedSteps = setOf(CookScreen.stepKey(id, 1) + cuire.summary)
        val phone = pairPhone()
        val q = request(offMain { phone.editable(id) }, emptyMap())
        // Le téléphone échange les deux étapes, sans toucher à leurs réglages.
        offMain { phone.saveRecipe(id, q.copy(steps = listOf(q.steps[0], q.steps[2], q.steps[1]))) }
        waitFor("échangées") { app.recipes.get(id)?.steps?.get(1)?.text == "Cuire encore." }
        assertEquals(emptySet<String>(), app.prefs.reviewedSteps)
        cook(1)
        assertNotNull(findShown("J'ai vérifié ce réglage"))
        assertFalse(buttonOf("Lancer").isEnabled)
    }

    /** Un programme tapé sur le téléphone le dit sur sa carte : il n'a pas été « importé ». */
    @Test fun aProgramTypedOnThePhoneSaysWhereItComesFrom() {
        start()
        val app = app()
        val id = soupe.id
        val phone = pairPhone()
        val e = offMain { phone.editable(id) }
        val petrir = StepMachine(StepProgram.KNEAD, 90, 4)
        offMain { phone.saveRecipe(id, request(e, mapOf(0 to SettingEdit(petrir)))) }
        waitFor("enregistrée") { app.recipes.get(id)?.steps?.get(0)?.machine == petrir }
        assertTrue(app.recipes.get(id)!!.steps[0].reviewAlone)
        cook(0)
        assertNotNull(findShown("Réglage saisi sur le téléphone : vérifiez-le avant de lancer."))
        assertNull(findShown("Réglage importé : vérifiez-le avant de lancer."))
        assertNotNull(findShown("J'ai vérifié ce réglage"))
        // Une étape importée garde sa phrase.
        leaveCooking()
        app.recipes.saveUser(soupe.copy(steps = listOf(Step("Pétrir.", petrir, needsReview = true))))
        waitFor("recette importée") { app.recipes.get(id)?.steps?.size == 1 }
        cook(0)
        assertNotNull(findShown("Réglage importé : vérifiez-le avant de lancer."))
    }
}
