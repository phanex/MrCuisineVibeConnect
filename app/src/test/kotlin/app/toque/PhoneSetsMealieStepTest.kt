package app.toque

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.toque.core.companion.client.CompanionClient
import app.toque.core.companion.client.CompanionException
import app.toque.core.i18n.I18n
import app.toque.core.recipes.OwnStepSettings
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.SettingAuthor
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.core.recipes.StepSettings
import app.toque.machine.MachineService
import app.toque.ui.MainActivity
import app.toque.ui.screens.CookScreen
import app.toque.ui.screens.RecipeScreen
import app.toque.ui.screens.StepEditScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Le réglage d'une étape d'une recette Mealie donné depuis un téléphone, vu du robot : la page de la recette
 * ouverte suit aussitôt et marque l'étape « Du téléphone : à vérifier » ; la cuisson guidée la fait vérifier
 * sur sa propre carte avant « Lancer » ; une fois vérifiée (à la cuisson, ou d'un OK sur l'étape), elle devient
 * « Mon réglage ». Rien ne s'écrit pendant que la recette est cuisinée. Rendus en PNG (137-…, 138-…).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w1024dp-h600dp-land-mdpi", application = ScreenshotApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneSetsMealieStepTest {
    private val outDir = File(System.getProperty("toque.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private lateinit var activity: MainActivity
    private var activityController: ActivityController<MainActivity>? = null
    private var serviceController: ServiceController<MachineService>? = null

    /** Recette inventée, telle que la synchro Mealie l'écrit. */
    private val soupe = Recipe(
        id = "mealie-99999999-8888-4777-8666-555555555555", title = "Velouté d'essai de Mealie", category = "Soupes", servings = 4,
        source = RecipeSource.MEALIE,
        steps = listOf(
            Step("Mettre les légumes dans le bol."),
            Step("Pétrir la pâte à pain.", StepMachine(seconds = 120, speed = 4), needsReview = true),
            Step("Cuire 10 min à 90 °C.", StepMachine(seconds = 600, speed = 1, tempC = 90), needsReview = true),
            Step("Servir."),
        ),
    )
    private val knead = StepSettings.default(StepProgram.KNEAD)
    private val steam = StepMachine(StepProgram.STEAM, 1200)

    @After fun tearDown() {
        I18n.select("fr")
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

    private fun shot(name: String) {
        idle(3)
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1024, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1024, 600)
        val bmp = Bitmap.createBitmap(1024, 600, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun start() {
        val app = app()
        val service = Robolectric.buildService(MachineService::class.java).create().also { serviceController = it }.get()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, MachineService::class.java),
            service.onBind(Intent(app, MachineService::class.java)))
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().also { activityController = it }.get()
        waitFor("base simulée prête") { app.machine.snapshot.ready }
        app.recipes.upsertAll(listOf(soupe))
        waitFor("recette reçue") { app.recipes.index.entries.any { it.id == soupe.id } }
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
    private fun countShown(text: String): Int = findAll(decor, text).count { shown(it) }

    private fun buttonOf(text: String): View {
        var x: View = findShown(text) ?: throw AssertionError("texte absent : $text")
        while (!x.isClickable) x = x.parent as View
        return x
    }

    private fun click(text: String) {
        buttonOf(text).performClick()
        idle(3)
    }

    private fun own(i: Int) = OwnStepSettings.owned(app().recipes.get(soupe.id)!!, app().recipes.adjustmentsOf(soupe.id))[i]

    private fun refusal(block: () -> Unit): CompanionException {
        try {
            offMain(block)
        } catch (e: CompanionException) {
            return e
        }
        fail("le robot aurait dû refuser")
        throw AssertionError()
    }

    @Test fun aSettingFromThePhoneIsMarkedOnTheRecipePageAndCheckedBeforeItRuns() {
        start()
        val app = app()
        val id = soupe.id
        val keys = OwnStepSettings.keys(soupe)
        // Une vérification gardée au rang 1 pour un réglage au même résumé, et une d'une autre recette.
        val other = "perso-autre|1|" + knead.summary
        app.prefs.reviewedSteps = setOf(CookScreen.stepKey(id, 1) + knead.summary, other)

        activity.push(RecipeScreen(activity, app.recipes.get(id)!!))
        idle(3)
        click("Étapes")
        assertEquals(2, countShown("À vérifier"))

        val phone = pairPhone()
        val form = offMain { phone.ownSteps(id) }
        assertEquals(keys, form.steps.map { it.key })
        assertTrue(offMain { phone.state() }.can.ownSettings.allowed)
        assertTrue(offMain { phone.recipe(id) }.ownSettings)

        // Le téléphone donne Pétrir à l'étape 2 : enregistré comme venu du téléphone, la page ouverte suit.
        val after = offMain { phone.setOwnStep(id, 1, keys[1], knead) }
        assertEquals("phone", after.steps[1].own)
        waitFor("réglage enregistré") { own(1)?.by == SettingAuthor.PHONE }
        idle(3)
        assertEquals(1, countShown("Du téléphone : à vérifier"))
        assertNotNull(findShown(knead.summary))
        assertNull(findShown("Mon réglage"))
        assertEquals(1, countShown("À vérifier"))
        // Vérification gardée à ce rang oubliée, pour cette recette seulement ; le journal du robot le dit.
        assertEquals(setOf(other), app.prefs.reviewedSteps)
        assertTrue(app.log.read().contains("réglage de l'étape 2 de $id donné depuis "))
        shot("137-mealie-reglage-du-telephone")

        // Une étape désignée par une autre empreinte (texte changé, ou autre étape) : refusée, rien d'écrit.
        val stale = refusal { phone.setOwnStep(id, 2, keys[1], steam) }
        assertEquals(409, stale.status)
        assertEquals("stale", stale.error.code)
        // Un réglage que la règle refuse : la raison du robot, rien d'écrit.
        val hot = refusal { phone.setOwnStep(id, 2, keys[2], StepMachine(seconds = 60, speed = 8, tempC = 100)) }
        assertEquals(422, hot.status)
        assertTrue(hot.error.message, hot.error.message.startsWith("Étape 3 : "))
        assertNull(own(2))

        // L'éditeur de l'étape sur le robot dit d'où vient le réglage.
        click("Pétrir la pâte à pain.")
        assertTrue(activity.current is StepEditScreen)
        assertNotNull(findShown("Réglage saisi sur le téléphone : vérifiez-le avant de l'enregistrer."))
        assertNull(findShown("Réglage lu dans le texte de l'étape : vérifiez-le avant de l'enregistrer."))
        shot("138-mealie-reglage-du-telephone-editeur")
        activity.back()
        idle(3)
        assertEquals(SettingAuthor.PHONE, own(1)?.by)

        // Cuisson guidée : sa propre carte, « Lancer » bloqué jusqu'à « J'ai vérifié ce réglage ».
        val cook = CookScreen(activity, app.recipes.getAdjusted(id)!!, 1)
        activity.push(cook)
        idle(3)
        assertNotNull(findShown("Réglage saisi sur le téléphone : vérifiez-le avant de lancer."))
        assertFalse(buttonOf("Lancer").isEnabled)
        // Pendant la cuisson de cette recette, le téléphone ne change rien.
        val busy = refusal { phone.setOwnStep(id, 2, keys[2], steam) }
        assertEquals(409, busy.status)
        assertEquals("cooking", busy.error.code)
        click("J'ai vérifié ce réglage")
        assertTrue(buttonOf("Lancer").isEnabled)
        // Vérifié ici : le réglage devient celui du propriétaire.
        waitFor("réglage confirmé") { own(1)?.by == SettingAuthor.ROBOT }
        activity.home()
        idle(3)

        // Un autre réglage du téléphone, vérifié d'un OK sur l'étape de la page de la recette.
        offMain { phone.setOwnStep(id, 2, keys[2], steam) }
        waitFor("deuxième réglage") { own(2)?.by == SettingAuthor.PHONE }
        activity.push(RecipeScreen(activity, app.recipes.get(id)!!))
        idle(3)
        click("Étapes")
        assertEquals(1, countShown("Mon réglage"))
        assertEquals(1, countShown("Du téléphone : à vérifier"))
        click("Cuire 10 min à 90 °C.")
        click("OK")
        assertTrue(activity.current is RecipeScreen)
        assertEquals(SettingAuthor.ROBOT, own(2)?.by)
        assertEquals(steam, own(2)?.machine)
        assertEquals(2, countShown("Mon réglage"))
        assertEquals(0, countShown("Du téléphone : à vérifier"))

        // Retour au réglage de la recette depuis le téléphone : la page suit.
        offMain { phone.clearOwnStep(id, 2, keys[2]) }
        waitFor("réglage retiré") { own(2) == null }
        idle(3)
        assertEquals(1, countShown("Mon réglage"))
        assertEquals(1, countShown("À vérifier"))
    }
}
