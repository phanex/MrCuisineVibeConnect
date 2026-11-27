package app.toque

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.toque.core.companion.CookSession
import app.toque.core.i18n.I18n
import app.toque.core.recipes.OwnStepSettings
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeSource
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
 * Réglage du propriétaire pour une étape d'une recette Mealie, sans « Ma version » : choisi depuis la
 * fiche, marqué « Mon réglage », utilisé tel quel par la cuisson guidée (sans étape « à vérifier »),
 * retiré d'un appui. Rendus en PNG (app/build/screenshots/130-… à 135-…), mode démo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w1024dp-h600dp-land-mdpi", application = ScreenshotApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MealieStepSettingScreenTest {
    private val outDir = File(System.getProperty("toque.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private lateinit var activity: MainActivity
    private var activityController: ActivityController<MainActivity>? = null
    private var serviceController: ServiceController<MachineService>? = null

    /** Recette inventée, telle que la synchro Mealie l'écrit : réglages lus dans le texte, à vérifier. */
    private val soupe = Recipe(
        id = "mealie-11111111-2222-4333-8444-555555555555", title = "Soupe d'essai de Mealie", category = "Soupes", servings = 4,
        source = RecipeSource.MEALIE,
        steps = listOf(
            Step("Mettre les légumes et l'eau dans le bol."),
            Step("Cuire 10 min à 90 °C, vitesse 4.", StepMachine(seconds = 600, speed = 4, tempC = 90), needsReview = true),
            Step("Mixer 30 s, vitesse 8.", StepMachine(seconds = 30, speed = 8), needsReview = true),
            Step("Servir."),
        ),
    )
    private val knead = StepSettings.default(StepProgram.KNEAD)

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

    private fun pass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun waitFor(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            assertTrue("attente trop longue : $what", System.currentTimeMillis() < end)
            idle()
            Thread.sleep(30)
        }
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

    private val decor: View get() = activity.window.decorView

    private fun findAll(v: View, text: String, out: MutableList<TextView> = ArrayList()): MutableList<TextView> {
        if (v is TextView && v.text.toString() == text) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) findAll(v.getChildAt(i), text, out)
        return out
    }

    private fun allTexts(v: View = decor, out: MutableList<TextView> = ArrayList()): MutableList<TextView> {
        if (v is TextView) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) allTexts(v.getChildAt(i), out)
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

    private fun visibleTexts(): List<String> = allTexts().filter { shown(it) }.map { it.text.toString() }

    private fun countShown(text: String): Int = findAll(decor, text).count { shown(it) }

    /** Le bouton (premier parent cliquable) d'un texte affiché. */
    private fun buttonOf(text: String): View {
        var x: View = findShown(text) ?: throw AssertionError("texte absent : $text\n${visibleTexts()}")
        while (!x.isClickable) x = x.parent as View
        return x
    }

    private fun click(text: String) {
        val b = buttonOf(text)
        assertTrue("désactivé : $text", b.isEnabled)
        assertTrue("trop petit pour un doigt : $text (${b.height} px)", b.height == 0 || b.height >= 48)
        b.performClick()
        idle(3)
    }

    private fun start(): ScreenshotApp {
        val app = app()
        val service = Robolectric.buildService(MachineService::class.java).create().also { serviceController = it }.get()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, MachineService::class.java),
            service.onBind(Intent(app, MachineService::class.java)))
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().also { activityController = it }.get()
        waitFor("base simulée prête") { app.machine.snapshot.ready }
        app.recipes.upsertAll(listOf(soupe))
        waitFor("recette reçue") { app.recipes.index.entries.any { it.id == soupe.id } }
        pass(1_000)
        return app
    }

    /** Session publiée pour les téléphones (CookScreen.publish). */
    private fun session(): CookSession? {
        val hub = activity.app.companion?.hub ?: return null
        val f = hub.javaClass.getDeclaredField("session")
        f.isAccessible = true
        return f.get(hub) as CookSession?
    }

    @Test fun aMealieStepGetsTheOwnersSettingWithoutACopy() {
        val app = start()
        activity.push(RecipeScreen(activity, app.recipes.get(soupe.id)!!))
        idle(3)
        click("Étapes")
        assertNotNull(findShown("Touchez une étape pour lui donner votre réglage du robot, sans copier la recette."))
        assertEquals(2, countShown("À vérifier"))
        assertNull(findShown("Mon réglage"))
        shot("130-mealie-etapes-a-verifier")

        // Étape sans réglage, OK sans rien changer : rien n'est gardé.
        click("Mettre les légumes et l'eau dans le bol.")
        assertTrue(activity.current is StepEditScreen)
        assertNull(findShown("Réglage lu dans le texte de l'étape : vérifiez-le avant de l'enregistrer."))
        click("OK")
        assertTrue(app.recipes.adjustmentsOf(soupe.id).isEmpty)

        // Étape 2 : le réglage lu dans le texte arrive pré-rempli ; le propriétaire choisit Pétrir.
        click("Cuire 10 min à 90 °C, vitesse 4.")
        val editor = activity.current as StepEditScreen
        assertNotNull(findShown("Réglage lu dans le texte de l'étape : vérifiez-le avant de l'enregistrer."))
        // Le texte se lit, il ne se change pas ; pas de pesée ni de « Supprimer l'étape » ici.
        assertTrue(allTexts().filter { shown(it) }.none { it is android.widget.EditText })
        assertNull(findShown("Supprimer l'étape"))
        assertNull(findShown("Réglage de la recette"))
        assertTrue(editor.onKnob(1))
        shot("131-mealie-reglage-etape-propose")
        // Le réglage lu (vitesse 4 en chauffe) est refusé par la règle des étapes perso : rien n'est gardé.
        click("OK")
        assertTrue(activity.current === editor)
        assertTrue(app.recipes.adjustmentsOf(soupe.id).isEmpty)
        click(StepProgram.KNEAD.label)
        shot("132-mealie-reglage-etape-petrir")
        click("OK")
        assertTrue(activity.current is RecipeScreen)
        assertEquals(1, countShown("Mon réglage"))
        assertNotNull(findShown(knead.summary))

        // Étape 3 : réglage lu dans le texte, vérifié et gardé tel quel d'un appui sur OK.
        click("Mixer 30 s, vitesse 8.")
        click("OK")
        assertEquals(2, countShown("Mon réglage"))
        assertEquals(0, countShown("À vérifier"))
        assertEquals(setOf(1, 2), OwnStepSettings.owned(app.recipes.get(soupe.id)!!, app.recipes.adjustmentsOf(soupe.id)).keys)
        shot("133-mealie-etapes-mon-reglage")
        // Une seule recette : pas de copie dans Mes recettes.
        assertTrue(app.recipes.userRecipes().isEmpty())

        // Cuisson guidée depuis la fiche : l'étape 2 part avec Pétrir, sans « Réglage à vérifier ».
        click("Cuisiner")
        val cook = activity.current as CookScreen
        cook.onKnob(1)
        idle(3)
        assertNull(findShown("Réglage à vérifier"))
        assertNull(findShown("J'ai vérifié ce réglage"))
        assertTrue(buttonOf("Lancer").isEnabled)
        session()!!.let { s ->
            assertEquals(knead, s.steps[1].step.machine)
            assertFalse(s.steps[1].step.needsReview)
            assertEquals(soupe.steps[2].machine, s.steps[2].step.machine)
            assertFalse(s.steps[2].step.needsReview)
        }
        shot("134-mealie-cuisson-mon-reglage")
        activity.back()
        idle(3)

        // Retour au réglage de la recette : l'étape redevient « à vérifier », la marque disparaît.
        assertTrue(activity.current is RecipeScreen)
        click("Cuire 10 min à 90 °C, vitesse 4.")
        // Le réglage du propriétaire, pas celui du texte : plus de « vérifiez-le ».
        assertNull(findShown("Réglage lu dans le texte de l'étape : vérifiez-le avant de l'enregistrer."))
        shot("136-mealie-reglage-etape-mon-reglage")
        click("Réglage de la recette")
        assertEquals(1, countShown("Mon réglage"))
        assertEquals(1, countShown("À vérifier"))
        assertEquals(setOf(2), OwnStepSettings.owned(app.recipes.get(soupe.id)!!, app.recipes.adjustmentsOf(soupe.id)).keys)
        val back = app.recipes.getAdjusted(soupe.id)!!.steps[1]
        assertEquals(soupe.steps[1].machine, back.machine)
        assertTrue(back.needsReview)
        shot("135-mealie-retour-reglage-recette")
    }
}
