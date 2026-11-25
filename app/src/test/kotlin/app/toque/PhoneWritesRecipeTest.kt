package app.toque

import android.content.ComponentName
import android.content.Intent
import android.os.Environment
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import app.toque.core.companion.EditGroupWire
import app.toque.core.companion.EditStepRequest
import app.toque.core.companion.RecipeEditRequest
import app.toque.core.companion.client.CompanionClient
import app.toque.core.companion.client.CompanionException
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.core.recipes.UserRecipes
import app.toque.core.recipes.Weigh
import app.toque.machine.MachineService
import app.toque.recipes.RecipeFiles
import app.toque.ui.MainActivity
import app.toque.ui.screens.RecipeEditScreen
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
import java.io.File
import java.time.Duration

/**
 * Recettes écrites sur le téléphone (vrai client HTTP sur la boucle locale) : enregistrées dans
 * « Mes recettes » du robot, refusées pendant que l'éditeur du robot les a ouvertes. L'éditeur du
 * robot refuse et accepte exactement ce qu'il refusait et acceptait. L'import d'un fichier fait
 * vérifier chaque réglage sur le robot et compte les étapes à adapter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w1024dp-h600dp-land-mdpi", application = ScreenshotApp::class)
class PhoneWritesRecipeTest {
    private lateinit var activity: MainActivity
    private var activityController: ActivityController<MainActivity>? = null
    private var serviceController: ServiceController<MachineService>? = null

    /** Réglage refusé par les règles (vitesse 8 en chauffe). */
    private val hot = StepMachine(StepProgram.MANUAL, 60, 8, 100)
    private val soupe = Recipe(
        id = "perso-soupe-essai", title = "Soupe d'essai", servings = 4, totalMinutes = 30, source = RecipeSource.USER,
        ingredients = listOf(IngredientGroup(items = listOf(Ingredient("2", "", "carottes")))),
        steps = listOf(Step("Couper."), Step("Cuire.", StepMachine(StepProgram.MANUAL, 1200, 1, 100), needsReview = true)),
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

    private fun startRobot() {
        val app = app()
        val service = Robolectric.buildService(MachineService::class.java).create().also { serviceController = it }.get()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, MachineService::class.java),
            service.onBind(Intent(app, MachineService::class.java)))
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().also { activityController = it }.get()
        waitFor("base simulée prête") { app.machine.snapshot.ready }
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

    private fun findText(v: View, text: String): TextView? {
        if (v is TextView && v.text.toString() == text) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findText(v.getChildAt(i), text)?.let { return it }
        return null
    }

    private fun edits(v: View, out: MutableList<EditText> = ArrayList()): List<EditText> {
        if (v is EditText) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) edits(v.getChildAt(i), out)
        return out
    }

    private fun click(root: View, text: String) {
        var v: View = findText(root, text) ?: throw AssertionError("texte absent : $text")
        while (!v.isClickable) v = v.parent as View
        v.performClick()
        idle(3)
    }

    private fun toast(text: String) = findText(activity.window.decorView, text) != null

    // ------------------------------------------------------------------ du téléphone au robot

    @Test fun aRecipeWrittenOnThePhoneAppearsInTheRobotsListWithItsText() {
        startRobot()
        val app = app()
        app.recipes.saveUser(soupe)
        waitFor("recette enregistrée") { app.recipes.index.entries.any { it.id == soupe.id } }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        val phone = pairPhone()
        assertTrue(offMain { phone.state() }.can.editRecipes.allowed)

        // Nouvelle recette.
        val created = offMain {
            phone.createRecipe(RecipeEditRequest(draft = "0123456789abcdef0123456789abcdef", title = "Salade du téléphone", servings = 2,
                ingredients = listOf(EditGroupWire("", listOf("1 laitue", "2 tomates"))),
                steps = listOf(EditStepRequest(null, "Laver et couper.", 0), EditStepRequest(null, "Peser l'huile.", 30))))
        }
        assertTrue(created.id.startsWith(UserRecipes.ID_PREFIX))
        waitFor("dans la liste du robot") { app.recipes.index.entries.any { it.id == created.id && it.title == "Salade du téléphone" } }
        val salade = app.recipes.get(created.id)!!
        assertEquals(listOf("Laver et couper.", "Peser l'huile."), salade.steps.map { it.text })
        assertEquals(30, salade.steps[1].weigh!!.grams)
        assertEquals(2, salade.allIngredients.size)

        // Texte d'une recette à soi : le réglage du robot et sa vérification restent.
        val e = offMain { phone.editable(soupe.id) }
        val req = RecipeEditRequest(version = e.version, title = "Soupe d'hiver", servings = 6, totalMinutes = 45, difficulty = 2,
            ingredients = e.ingredients, steps = listOf(EditStepRequest(1, "Cuire 20 minutes.", 0), EditStepRequest(0, "Couper.", 0)))
        offMain { phone.saveRecipe(soupe.id, req) }
        waitFor("titre changé dans la liste") { app.recipes.index.entries.any { it.id == soupe.id && it.title == "Soupe d'hiver" } }
        val saved = app.recipes.get(soupe.id)!!
        assertEquals(listOf("Cuire 20 minutes.", "Couper."), saved.steps.map { it.text })
        assertEquals(soupe.steps[1].machine, saved.steps[0].machine)
        assertTrue(saved.steps[0].needsReview)
        assertEquals(6, saved.servings)

        // L'éditeur du robot a la recette ouverte : refusé ; refermé, accepté.
        val editor = RecipeEditScreen(activity, app.recipes.get(soupe.id)!!, isNew = false)
        activity.push(editor)
        idle(2)
        val again = offMain { phone.editable(soupe.id) }
        val next = req.copy(version = again.version, title = "Soupe de printemps", steps = listOf(EditStepRequest(0, "Cuire 20 minutes.", 0)))
        try {
            offMain { phone.saveRecipe(soupe.id, next) }
            fail()
        } catch (x: CompanionException) {
            assertEquals(409, x.status)
            assertEquals("editing", x.error.code)
        }
        assertEquals("Soupe d'hiver", app.recipes.get(soupe.id)!!.title)
        assertTrue(editor.canLeave {})
        activity.back()
        idle(2)
        offMain { phone.saveRecipe(soupe.id, next) }
        waitFor("enregistrée après fermeture") { app.recipes.get(soupe.id)?.title == "Soupe de printemps" }
    }

    // ------------------------------------------------------------------ l'éditeur du robot, inchangé

    private fun editAndSave(r: Recipe, isNew: Boolean): RecipeEditScreen {
        val e = RecipeEditScreen(activity, r, isNew)
        activity.push(e)
        idle(2)
        click(e.view, "Enregistrer")
        return e
    }

    @Test fun theRobotsEditorRefusesAndAcceptsExactlyAsBefore() {
        startRobot()
        val app = app()
        // Refus, avec les messages d'avant ; rien d'enregistré.
        editAndSave(Recipe(id = "perso-sans-titre", title = "  ", servings = 4, steps = listOf(Step("Mixer."))), isNew = true)
        assertTrue(toast("Donnez un titre à la recette"))
        activity.home()
        editAndSave(Recipe(id = "perso-vide", title = "Vide", servings = 4), isNew = true)
        assertTrue(toast("Ajoutez au moins un ingrédient ou une étape"))
        activity.home()
        editAndSave(Recipe(id = "perso-chaud", title = "Chaud", servings = 4, steps = listOf(Step("Mixer.", hot))), isNew = true)
        assertTrue(toast("Étape 1 : " + hot.violations().first().message))
        activity.home()
        idle(3)
        assertNull(app.recipes.get("perso-sans-titre"))
        assertNull(app.recipes.get("perso-vide"))
        assertNull(app.recipes.get("perso-chaud"))

        // Une copie Lidl hors des bornes de l'éditeur (24 personnes, 12 h, pesée de 6 kg) s'enregistre telle quelle, comme avant.
        val lidl = Recipe(id = "999", title = "Grande marmite", servings = 24, totalMinutes = 720,
            steps = listOf(Step("Peser l'eau.", weigh = Weigh("Eau", 6000)), Step("Cuire.")))
        val copy = UserRecipes.copyOf(lidl)
        editAndSave(copy, isNew = true)
        waitFor("copie enregistrée") { app.recipes.get(copy.id) != null }
        assertTrue(toast("Recette enregistrée dans Mes recettes"))
        assertEquals(24, app.recipes.get(copy.id)!!.servings)
        assertEquals(6000, app.recipes.get(copy.id)!!.steps[0].weigh!!.grams)
        assertTrue(activity.current is RecipeScreen)
    }

    @Test fun theRobotsStepEditorRefusesAndAcceptsExactlyAsBefore() {
        startRobot()
        var result: Step? = null
        var done = false
        val s = StepEditScreen(activity, Step(""), 1, isNew = true) { result = it; done = true }
        activity.push(s)
        idle(2)
        click(s.view, "OK")
        assertTrue(toast("Écrivez ce qu'il faut faire"))
        assertFalse(done)
        val (text, grams) = edits(s.view)
        text.setText("Peser la farine.")
        grams.setText("9000")
        idle()
        click(s.view, "OK")
        assertTrue(toast("Entrez un poids entre 1 et 5000 g"))
        assertFalse(done)
        grams.setText("5000")
        idle()
        click(s.view, "OK")
        assertTrue(done)
        assertEquals(Step("Peser la farine.", null, Weigh("", 5000)), result)
    }

    // ------------------------------------------------------------------ import du fichier

    @Test fun importMarksEveryMachineStepForReviewAndCountsTheStepsToAdapt() {
        startRobot()
        val app = app()
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val sure = Recipe(id = "perso-importee", title = "Importée", servings = 4, source = RecipeSource.USER,
            steps = listOf(Step("Couper."), Step("Cuire.", StepMachine(StepProgram.MANUAL, 600, 1, 100)), Step("Pétrir.", StepMachine(StepProgram.KNEAD, 90, 4))))
        val unsafe = Recipe(id = "perso-a-adapter", title = "À adapter", servings = 4, source = RecipeSource.USER,
            steps = listOf(Step("Mixer chaud.", hot), Step("Mixer encore.", hot, needsReview = true)))
        // Le fichier dit « déjà vérifié » (needsReview absent) : le robot ne le croit pas.
        assertFalse(RecipeCodec.encode(sure).contains("needsReview"))
        File(dir, UserRecipes.FILE_NAME).writeText(RecipeCodec.encodeFile(listOf(sure, unsafe)))

        val r = offMain { RecipeFiles(app.recipes, app.log).import() }
        assertTrue(r.message, r.ok)
        assertEquals(2, r.toAdapt)
        assertEquals("2 recettes ajoutées, 0 mises à jour ; 2 étapes ont un réglage à adapter au moment de cuisiner", r.message)
        waitFor("importées") { app.recipes.get(unsafe.id) != null && app.recipes.get(sure.id) != null }
        val a = app.recipes.get(sure.id)!!
        assertEquals(listOf(false, true, true), a.steps.map { it.needsReview })
        assertEquals(sure.steps.map { it.machine }, a.steps.map { it.machine })
        val b = app.recipes.get(unsafe.id)!!
        // Importées quand même, avec leur réglage : bloquées à la cuisson comme aujourd'hui.
        assertEquals(listOf(hot, hot), b.steps.map { it.machine })
        assertTrue(b.steps.all { it.needsReview })
        assertNotNull(RecipeFiles.reviewed(sure).steps[2].machine)
        assertEquals(0, RecipeFiles.adapt(sure))
    }
}
