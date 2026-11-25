package app.toque

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.toque.companion.RobotCompanion
import app.toque.core.companion.AdjustRequest
import app.toque.core.companion.StartRequest
import app.toque.core.companion.client.CompanionClient
import app.toque.core.machine.JobState
import app.toque.core.machine.LinkState
import app.toque.core.machine.Snapshot
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.core.recipes.Weigh
import app.toque.core.i18n.I18n
import app.toque.core.ring.RingColour
import app.toque.core.robot.RobotProfiles
import app.toque.machine.MachineService
import app.toque.ui.MachineBar
import app.toque.ui.MainActivity
import app.toque.ui.Splash
import app.toque.ui.TopBar
import app.toque.ui.screens.ManualScreen
import app.toque.ui.screens.HomeScreen
import app.toque.ui.screens.PairingScreen
import app.toque.ui.screens.RecipeScreen
import app.toque.ui.screens.RecipesScreen
import app.toque.ui.screens.SettingsScreen
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
import java.net.InetAddress
import java.util.Locale

/** Appli d'essai : aucune tâche réseau, compagnon sur la boucle locale, adresse Wi-Fi fictive. */
class ScreenshotApp : ToqueApp() {
    override fun onCreate() {
        Locale.setDefault(Locale.FRANCE)
        // Pas d'écran de démarrage dans les essais d'écran : StartupScreenTest le redemande pour lui.
        Splash.done = true
        super.onCreate()
        // TOQUE_THEME=light dans l'environnement : tous les écrans d'essai sont rendus en thème clair
        // (revue du thème sur chaque écran ; les essais qui comparent des couleurs lisent celles de Ui).
        if (LIGHT) app.toque.ui.Ui.setTheme(true)
    }

    override fun startBackground() {
        prefs.updateAlerts = false
        prefs.knobSetupDone = true
        prefs.knobSetupShown = true
        prefs.welcomeDone = true
        // Robot déjà confirmé (comme après la question du premier démarrage) ; RobotAndThemeTest l'efface pour la voir.
        if (!prefs.robotConfirmed) prefs.robotId = RobotProfiles.MC_CONNECT.id
        // Compte toqueapp.org déjà proposé (RobotAndThemeTest l'efface pour voir la proposition), et pas de recettes de
        // la communauté : aucun essai d'écran ne parle au réseau.
        prefs.siteOffered = true
        prefs.siteCommunity = false
    }

    override fun configureCompanion(c: RobotCompanion) {
        c.port = 0
        c.bind = InetAddress.getLoopbackAddress()
        c.addressProvider = { wifi }
    }

    companion object {
        @Volatile var wifi: String? = "192.168.0.12"
        /** Revue du thème clair : `TOQUE_THEME=light gradlew :app:testDebugUnitTest --rerun`. */
        val LIGHT: Boolean = System.getenv("TOQUE_THEME") == "light"
    }
}

/**
 * Rend les écrans du compagnon (accueil avec la recette reçue, fiche avec sa pastille, réglages,
 * appairage) en PNG dans app/build/screenshots/, à 1024 × 600 comme l'écran du robot, en mode démo.
 * Une recette est envoyée par un vrai client HTTP sur la boucle locale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w1024dp-h600dp-land-mdpi", application = ScreenshotApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionScreensTest {
    private val outDir = File(System.getProperty("toque.screenshots") ?: "build/screenshots").apply { mkdirs() }

    private val veloute = Recipe(
        id = "perso-veloute-de-potimarron", title = "Velouté de potimarron", category = "Soupes", servings = 4,
        totalMinutes = 35, difficulty = 1, source = RecipeSource.USER,
        ingredients = listOf(IngredientGroup(items = listOf(Ingredient("1", "", "oignon"), Ingredient("20", "g", "beurre"),
            Ingredient("600", "g", "potimarron"), Ingredient("500", "g", "eau"), Ingredient("100", "g", "crème")))),
        steps = listOf(
            Step("Éplucher l'oignon et couper le potimarron en morceaux."),
            Step("Mettre le beurre dans le bol.", weigh = Weigh("beurre", 20)),
            Step("Faire revenir l'oignon.", StepMachine(StepProgram.MANUAL, 180, 1, 100)),
            Step("Ajouter le potimarron et l'eau.", weigh = Weigh("eau", 500)),
            Step("Cuire jusqu'à ce que le potimarron soit tendre.", StepMachine(StepProgram.MANUAL, 1200, 1, 100)),
            Step("Ajouter la crème et mixer.", StepMachine(StepProgram.MANUAL, 60, 8, 0), Weigh("crème", 100)),
        ),
    )

    private lateinit var activity: MainActivity
    private var activityController: ActivityController<MainActivity>? = null
    private var serviceController: ServiceController<MachineService>? = null

    /**
     * Arrête l'activité et le service machine du test. Laissés en vie, la boucle de la base simulée
     * et les écrans du test continuent d'écrire dans la file du fil principal pendant le test
     * suivant, qui peut alors ne jamais voir sa propre base prête.
     */
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

    /** Appel HTTP bloquant hors du fil principal, qui continue de tourner (RobotHost y passe). */
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

    private fun findQr(v: View): app.toque.ui.QrView? {
        if (v is app.toque.ui.QrView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findQr(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Lit le QR code tel qu'il est dessiné à l'écran (carré clair et sa marge seulement). */
    private fun decodeQr(root: View): String {
        val bmp = Bitmap.createBitmap(1024, 600, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val qr = findQr(root)!!
        val at = IntArray(2)
        qr.getLocationInWindow(at)
        val w = qr.width
        val h = qr.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, at[0], at[1], w, h)
        val source = com.google.zxing.RGBLuminanceSource(w, h, px)
        val result = com.google.zxing.qrcode.QRCodeReader().decode(
            com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)),
            mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true))
        return result.text
    }

    /** Ouvre une famille des réglages par son entrée du menu de gauche. */
    private fun openFamily(settings: SettingsScreen, name: String) {
        var v: View = findText(settings.view, name)!!
        while (!v.isClickable) v = v.parent as View
        v.performClick()
        idle(3)
    }

    private fun findText(v: View, text: String): TextView? {
        if (v is TextView && v.text.toString() == text) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findText(v.getChildAt(i), text)?.let { return it }
        return null
    }

    private fun start() {
        val app = androidx_free_app()
        // Base simulée (mode démo) : le vrai service machine, lié dans le même processus.
        val service = Robolectric.buildService(MachineService::class.java).create().also { serviceController = it }.get()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, MachineService::class.java),
            service.onBind(Intent(app, MachineService::class.java)))
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().also { activityController = it }.get()
        try {
            waitFor("base simulée prête") { app.machine.snapshot.ready }
        } catch (e: AssertionError) {
            // De quoi comprendre un échec : l'état vu par l'interface et la fin du journal de l'appli.
            val s = app.machine.snapshot
            val journal = File(app.filesDir, "journal.txt").takeIf { it.isFile }?.readLines()?.takeLast(25)?.joinToString("\n") ?: "(pas de journal)"
            throw AssertionError("${e.message} : liaison ${s.link}, alerte « ${s.faultCode} », transport « ${s.transportName} »\n$journal", e)
        }
        app.recipes.saveUser(veloute)
        waitFor("recette enregistrée") { app.recipes.index.entries.any { it.id == veloute.id } }
    }

    private fun androidx_free_app(): ScreenshotApp = org.robolectric.RuntimeEnvironment.getApplication() as ScreenshotApp

    /** Dessine [v] seule, à [w] × [h], et l'agrandit [zoom] fois (lecture des détails). */
    private fun shotOf(v: View, name: String, w: Int, h: Int, zoom: Int = 1) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF0B111C.toInt())
        v.draw(canvas)
        val out = if (zoom > 1) Bitmap.createScaledBitmap(bmp, w * zoom, h * zoom, false) else bmp
        File(outDir, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** [v] et tous ses parents sont visibles. */
    private fun shown(v: View?): Boolean {
        var x: View? = v ?: return false
        while (x != null) {
            if (x.visibility != View.VISIBLE) return false
            x = x.parent as? View
        }
        return true
    }

    /**
     * Cuisson manuelle en pause : la reprise est dans la barre du bas (et sur la molette), l'écran n'a
     * plus de second bouton ; à l'arrêt, la barre garde « Arrêté » et « OK », et l'anneau de la molette
     * est vert jusqu'à « OK ».
     */
    @Test fun pausedManualCookingResumesFromTheBarOnly() {
        start()
        val app = androidx_free_app()
        val manual = ManualScreen(activity)
        activity.push(manual)
        idle(3)
        assertTrue(shown(findText(manual.view, "Démarrer")))
        assertTrue(manual.onKnobPress())
        waitFor("en cours") { app.machine.snapshot.state == JobState.RUNNING }
        idle(3)
        assertFalse(shown(findText(manual.view, "Démarrer")))
        shot("30-cuisson-manuelle-en-cours")

        assertTrue(manual.onKnobPress())
        waitFor("en pause") { app.machine.snapshot.state == JobState.PAUSED }
        idle(3)
        assertNull(findText(manual.view, "Reprendre"))
        assertFalse(shown(findText(manual.view, "Démarrer")))
        assertTrue(shown(findText(activity.window.decorView, "En pause")))
        assertEquals(RingColour.ORANGE, RingColour.of(app.machine.snapshot))
        shot("31-cuisson-manuelle-en-pause")

        // La molette reprend, comme le bouton de la barre.
        assertTrue(manual.onKnobPress())
        waitFor("reprise") { app.machine.snapshot.state == JobState.RUNNING }

        // La barre suit sur les autres écrans : l'accueil et la liste des recettes.
        activity.home()
        idle(3)
        shot("41-accueil-pendant-une-cuisson")
        // Les tuiles se resserrent : titres et sous-titres tiennent en entier au-dessus de la barre.
        for (label in listOf("Recettes", "Cuisson\nmanuelle", "Programmes", "Balance", "Favoris", "Réglages", "Peser au gramme")) {
            val t = findText(activity.window.decorView, label)!!
            val box = t.parent as View
            assertTrue("« $label » rogné : ${t.bottom} > ${box.height}", t.bottom <= box.height)
            assertTrue("« $label » sur ${t.lineCount} lignes, ${t.layout.height} > ${t.height}", t.layout.height <= t.height)
        }
        activity.push(RecipesScreen(activity))
        idle(5)
        shot("42-recettes-pendant-une-cuisson")
        activity.home()
        activity.push(manual)
        idle(3)

        // Arrêt : « Arrêté » et « OK » dans la barre, « Démarrer » revenu, anneau vert jusqu'à « OK ».
        app.machine.stop()
        waitFor("arrêtée") { app.machine.snapshot.state == JobState.ABORTED }
        idle(3)
        assertTrue(shown(findText(activity.window.decorView, "OK")))
        assertTrue(shown(findText(activity.window.decorView, "Arrêté")))
        assertTrue(shown(findText(manual.view, "Démarrer")))
        assertEquals(RingColour.GREEN, RingColour.of(app.machine.snapshot))
        shot("32-cuisson-manuelle-arretee")
        app.machine.clearFinished()
        waitFor("effacée") { !app.machine.snapshot.jobActive }
        assertEquals(RingColour.WHITE, RingColour.of(app.machine.snapshot))
    }

    /**
     * Barre du bas seule, dans chacun de ses états : une carte détachée des bords, ses boutons à la
     * même distance de son bord en haut, en bas et à droite ; le temps rapetisse plutôt que de se
     * couper quand les pastilles prennent la place (allemand, trois pastilles).
     */
    @Test fun machineBarIsACardWithEvenMargins() {
        start()
        val running = Snapshot(link = LinkState.READY, hasSample = true, actualTempC = 64, jobActive = true, jobKind = "MANUAL",
            jobLabel = "Cuisson manuelle", jobState = "RUNNING", phaseCount = 1, elapsedMs = 120_000, totalMs = 600_000,
            remainingMs = 480_000, jobSpeed = 3, jobTempLevel = 11, jobReverse = true)

        fun bar(s: Snapshot, name: String): MachineBar {
            val b = MachineBar(activity)
            b.update(s)
            shotOf(b, name, 1024, MachineBar.HEIGHT)
            return b
        }

        /** Le bouton le plus à droite de la carte affichée, et la carte. */
        fun lastButton(b: MachineBar): Pair<View, ViewGroup> {
            val card = (0 until b.childCount).map { b.getChildAt(it) }.first { it.visibility == View.VISIBLE } as ViewGroup
            val button = (card.childCount - 1 downTo 0).map { card.getChildAt(it) }.first { it.visibility == View.VISIBLE }
            return button to card
        }

        fun assertEvenMargins(b: MachineBar) {
            val (button, card) = lastButton(b)
            assertEquals("carte à gauche", MachineBar.SIDE, card.left)
            assertEquals("carte à droite", 1024 - MachineBar.SIDE, card.right)
            assertEquals("carte en haut", 0, card.top)
            assertEquals("espace sous la carte", MachineBar.BOTTOM_GAP, MachineBar.HEIGHT - card.bottom)
            assertEquals("bouton, haut", MachineBar.INSET, button.top)
            assertEquals("bouton, bas", MachineBar.INSET, card.height - button.bottom)
            assertEquals("bouton, droite", MachineBar.INSET, card.width - button.right)
            assertEquals("bouton, hauteur", MachineBar.BUTTON, button.height)
        }

        assertEvenMargins(bar(running, "33-barre-en-cours"))
        assertEvenMargins(bar(running.copy(jobState = "PAUSED", pauseReason = "USER"), "34-barre-en-pause"))
        assertEvenMargins(bar(running.copy(jobState = "ABORTED", endReason = "Arrêté"), "35-barre-arretee"))
        assertEvenMargins(bar(running.copy(jobState = "FINISHED", endReason = "Terminé", remainingMs = 0, elapsedMs = 600_000), "36-barre-terminee"))
        assertEvenMargins(bar(running.copy(jobActive = false, jobState = "", turboActive = true), "37-barre-turbo"))
        assertEvenMargins(bar(running.copy(faultCode = "COMM_LOST", faultMessage = "Liaison avec la base moteur perdue"), "38-barre-alerte"))
        // Programme d'une heure et plus, titre de recette long.
        assertEvenMargins(bar(running.copy(jobLabel = "Velouté de potimarron aux châtaignes", totalMs = 5_400_000, remainingMs = 5_025_000),
            "39-barre-titre-long-une-heure"))

        // Allemand : « Geschwindigkeit 3 », trois pastilles ; le temps reste sur une ligne, entier.
        try {
            I18n.select("de")
            val b = bar(running, "40-barre-allemand")
            assertEvenMargins(b)
            val time = findText(b, "08:00")!!
            assertEquals(1, time.lineCount)
            assertTrue("temps coupé", time.layout.getEllipsisCount(0) == 0)
            assertTrue("temps plus large que sa place", time.paint.measureText("08:00") <= time.width)
        } finally {
            I18n.select("fr")
        }
    }

    /** Bandeau du haut : triangle rouge clignotant sur la température quand le bol est chaud ; réglage des couleurs de la molette. */
    @Test fun hotBowlHeaderAndKnobColours() {
        start()
        val app = androidx_free_app()
        val hot = Snapshot(link = LinkState.READY, hasSample = true, actualTempC = 72)
        val bar = TopBar(activity)
        bar.setTitle("Cuisson manuelle", true)
        bar.update(hot)
        assertEquals(View.VISIBLE, bar.hotWarning.visibility)
        assertNotNull(findText(bar, "72 °C"))
        shotOf(bar, "10-bandeau-bol-chaud", 1024, TopBar.HEIGHT)
        shotOf(bar, "10b-bandeau-bol-chaud-zoom", 1024, TopBar.HEIGHT, zoom = 3)
        // Le triangle seul clignote : la température garde toute son opacité.
        assertEquals(1f, findText(bar, "72 °C")!!.alpha)
        bar.update(hot.copy(actualTempC = 59))
        assertEquals(View.GONE, bar.hotWarning.visibility)
        shotOf(bar, "11-bandeau-bol-tiede", 1024, TopBar.HEIGHT)
        bar.update(hot.copy(actualTempC = 60))
        assertEquals(View.VISIBLE, bar.hotWarning.visibility)
        // Sans mesure : ni température ni triangle.
        bar.update(Snapshot(link = LinkState.READY))
        assertEquals(View.GONE, bar.hotWarning.visibility)

        // Réglages : le code couleur de la molette, actif par défaut.
        assertTrue(app.prefs.knobColours)
        val settings = SettingsScreen(activity)
        activity.push(settings)
        idle(3)
        assertNotNull(findText(settings.view, "Couleurs sur la molette"))
        openFamily(settings, "Molette et sons")
        shot("12-reglages-couleurs-molette")
    }

    @Test fun companionScreens() {
        start()
        val app = androidx_free_app()
        val comp = app.companion!!
        val hub = comp.hub
        shot("00-accueil-sans-recette")

        // Un téléphone appairé sur le robot : le serveur s'ouvre (boucle locale, port libre).
        val first = hub.pairing.open()
        assertTrue(hub.pairing.pairWithCode(first.code).ok)
        idle()
        waitFor("serveur ouvert") { hub.running && hub.port > 0 }

        // Un second téléphone s'appaire et envoie la recette, par HTTP comme la vraie application.
        val o = hub.pairing.open()
        // Le client garde jeton, téléphone et robot : il prouve l'identité du robot avant d'envoyer le jeton.
        val phone = CompanionClient("127.0.0.1", hub.port)
        phone.pairWithCode(o.code)
        phone.sendRecipe(veloute.id)
        waitFor("recette reçue") { hub.waitingRecipe != null }
        idle(3)
        assertEquals(veloute.id, hub.waitingRecipe!!.recipeId)
        shot("01a-accueil-recette-recue-message")
        // Le message s'efface (3 s) ; la recette reste.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
        shot("01-accueil-recette-recue")
        val saved = File(app.filesDir, "compagnon/attente.json")
        waitFor("recette en attente enregistrée") { saved.isFile }
        assertTrue(saved.readText().contains(veloute.id))
        // Relance de l'interface : un nouveau compagnon retrouve la recette en attente et les téléphones.
        val again = RobotCompanion(app).apply { port = 0; bind = InetAddress.getLoopbackAddress(); addressProvider = { "192.168.0.12" } }
        again.start()
        waitFor("recette retrouvée après relance") { again.hub.waitingRecipe != null }
        assertEquals(veloute.id, again.hub.waitingRecipe!!.recipeId)
        assertEquals(hub.pairing.robotId, again.hub.pairing.robotId)
        assertEquals(2, again.hub.pairing.list.size)
        waitFor("second serveur ouvert") { again.hub.running }
        again.hub.stop()

        // Recette reçue et recette en cours à la fois.
        app.prefs.guidedRecipe = veloute.id
        app.prefs.guidedStep = 2
        activity.push(SettingsScreen(activity))
        activity.home()
        shot("02-accueil-recue-et-en-cours")
        app.prefs.knobSetupDone = false
        activity.push(SettingsScreen(activity))
        activity.home()
        shot("03-accueil-recue-en-cours-et-bouton")
        app.prefs.knobSetupDone = true
        app.prefs.guidedRecipe = ""

        // Fiche ouverte depuis la carte : pastille sur la photo.
        activity.push(RecipeScreen(activity, app.recipes.get(veloute.id)!!))
        shot("04-recette-recue")
        // Nombre de personnes changé depuis le téléphone : la page affichée suit, la recette en attente aussi.
        assertEquals(6, offMain { phone.setServings(veloute.id, 6) }.servings)
        waitFor("page à 6 personnes") { findText(activity.window.decorView, "6 pers.") != null }
        shot("04b-recette-6-personnes-depuis-telephone")
        assertEquals(6, hub.waitingRecipe!!.servings)
        assertEquals(6, app.recipes.getAdjusted(veloute.id)!!.servings)
        assertEquals(6, offMain { phone.recipe(veloute.id) }.servings)
        assertEquals(4, offMain { phone.recipe(veloute.id) }.baseServings)
        activity.home()
        waitFor("accueil à 6 personnes") { findText(activity.window.decorView, "Reçue du téléphone · 6 pers.") != null }
        shot("04c-accueil-recue-6-personnes")
        // Changé sur la page de la recette du robot : la recette en attente suit aussi.
        app.recipes.setAdjustments(veloute.id, app.recipes.adjustmentsOf(veloute.id).withServings(3, 4))
        waitFor("attente à 3 personnes") { hub.waitingRecipe!!.servings == 3 }
        assertEquals(4, offMain { phone.setServings(veloute.id, 4) }.servings)
        waitFor("attente à 4 personnes") { hub.waitingRecipe!!.servings == 4 }

        // Réglages : la famille Téléphone.
        val settings = SettingsScreen(activity)
        activity.push(settings)
        shot("05-reglages")
        openFamily(settings, "Téléphone")
        assertNotNull(findText(settings.pane, "2 téléphones appairés"))
        shot("06-reglages-compagnon")
        activity.home()

        // Appairage : QR code, code, téléphones.
        activity.push(PairingScreen(activity))
        waitFor("code affiché") { hub.pairing.current != null && hub.port > 0 }
        shot("07-appairage")
        // Le QR code dessiné se lit, et porte le texte d'appairage du hub.
        assertEquals(hub.pairingPayload("192.168.0.12"), decodeQr(activity.window.decorView))
        // Sans Wi-Fi : pas de code.
        activity.home()
        ScreenshotApp.wifi = null
        activity.push(PairingScreen(activity))
        shot("08-appairage-sans-wifi")
        assertNull(hub.pairing.current)
        ScreenshotApp.wifi = "192.168.0.12"
        activity.home()

        // Appairage réussi depuis l'écran, par le code saisi puis par le QR code : le message, puis
        // l'accueil ; l'appairage est fermé et le téléphone est dans la liste.
        for (byQr in listOf(false, true)) {
            activity.push(PairingScreen(activity))
            waitFor("code affiché") { hub.pairing.current != null && hub.port > 0 }
            val before = hub.pairing.list.map { it.id }.toSet()
            val client = CompanionClient("127.0.0.1", hub.port)
            if (byQr) offMain { client.pairWithQr(hub.pairingPayload("127.0.0.1")!!) }
            else offMain { client.pairWithCode(hub.pairing.current!!.code) }
            waitFor("retour à l'accueil") { activity.current is HomeScreen }
            assertNull(hub.pairing.current)
            assertFalse(hub.pairing.isOpen)
            val added = hub.pairing.list.filter { it.id !in before }
            assertEquals(1, added.size)
            assertEquals(if (byQr) "qr" else "code", added[0].method)
            // Le message est dessiné par l'activité : il reste visible sur l'accueil.
            assertNotNull(findText(activity.window.decorView, "${added[0].name} appairé"))
            shot(if (byQr) "07c-appaire-qr-accueil" else "07b-appaire-code-accueil")
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
        }

        // Un téléphone retiré (« Retirer ») : on reste sur l'écran d'appairage, toujours ouvert.
        val pairingScreen = PairingScreen(activity)
        activity.push(pairingScreen)
        waitFor("code affiché") { hub.pairing.current != null && hub.port > 0 }
        val count = hub.pairing.list.size
        assertTrue(hub.pairing.remove(hub.pairing.list.last().id))
        idle(3)
        assertEquals(count - 1, hub.pairing.list.size)
        assertTrue(activity.current === pairingScreen)
        assertTrue(hub.pairing.isOpen)
        activity.home()

        // « Cuisiner » depuis la fiche de la recette reçue : elle quitte l'accueil.
        val page = RecipeScreen(activity, app.recipes.get(veloute.id)!!)
        activity.push(page)
        idle()
        assertTrue(page.onKnobPress())
        idle()
        assertNull(hub.waitingRecipe)
        shot("09-cuisson")
        // Première étape, sans réglage machine : un seul bouton pour passer à la suite (« Suivant », en bas).
        assertNull(findText(activity.window.decorView, "Étape suivante"))
        assertTrue(shown(findText(activity.window.decorView, "Suivant")))
        assertTrue(shown(findText(activity.window.decorView, "Pas de réglage machine pour cette étape")))
        waitFor("fichier de la recette en attente effacé") { !saved.exists() }

        // Ce que voit le téléphone : la base (instantanés) et la recette guidée (écran de cuisson).
        var st = phone.state()
        assertTrue(st.machine.ready)
        assertEquals(veloute.id, st.session!!.recipeId)
        assertEquals(0, st.session!!.current)
        assertEquals("hand", st.session!!.waitingFor)
        assertTrue((activity.current as app.toque.ui.screens.CookScreen).onKnobPress())
        idle()
        st = phone.state()
        assertEquals(1, st.session!!.current)
        assertEquals("weigh", st.session!!.waitingFor)
        // Écran quitté : la session se clôt pour les téléphones.
        activity.home()
        idle(3)
        assertNull(phone.state().session)

        // Réglage depuis le téléphone (durée et sens) : RobotHost, fil principal, MachineClient, base simulée.
        val spec = StepMachine(StepProgram.MANUAL, 300, 2, 0).toJob("Cuisson manuelle")!!
        var started: Boolean? = null
        app.machine.start(spec) { v -> started = v.ok }
        waitFor("cuisson lancée") { started == true && app.machine.snapshot.state == JobState.RUNNING }
        val reply = offMain { phone.adjust(AdjustRequest(addMs = 60_000, reverse = true)) }
        assertTrue(reply.ok)
        waitFor("réglage appliqué") { app.machine.snapshot.totalMs == 360_000L && app.machine.snapshot.jobReverse }
        assertTrue(offMain { phone.stop() }.ok)
        waitFor("cuisson arrêtée") { app.machine.snapshot.state == JobState.ABORTED || !app.machine.snapshot.jobActive }
    }

    /** Le réglage d'où qu'il vienne : la carte du robot suit le téléphone, et un téléphone peut lancer une cuisson manuelle. */
    @Test fun manualScreenFollowsThePhoneAndAPhoneCanStartFromHome() {
        start()
        val app = androidx_free_app()
        val hub = app.companion!!.hub
        val o = hub.pairing.open()
        idle()
        waitFor("serveur ouvert") { hub.running && hub.port > 0 }
        val phone = CompanionClient("127.0.0.1", hub.port)
        phone.pairWithCode(o.code)
        hub.pairing.close()

        // Réglages › Téléphone : « Lancement depuis le téléphone », oui par défaut, donné au hub.
        assertTrue(app.prefs.remoteStart)
        assertTrue(hub.remoteStartEnabled)
        val settings = SettingsScreen(activity)
        activity.push(settings)
        idle(3)
        val label: View = findText(settings.view, "Lancement depuis le téléphone")!!
        assertNotNull(findText(settings.view, "Un téléphone appairé peut lancer une cuisson manuelle, couvercle fermé et bol en place."))
        val row = label.parent as ViewGroup
        openFamily(settings, "Téléphone")
        shot("13-reglages-compagnon-lancement")
        // Non : la permission change aussitôt pour le téléphone.
        row.getChildAt(1).performClick()
        idle()
        assertFalse(app.prefs.remoteStart)
        assertEquals("disabled", offMain { phone.state() }.can.start.code)
        row.getChildAt(1).performClick()
        idle()
        assertTrue(app.prefs.remoteStart)
        assertTrue(offMain { phone.state() }.can.start.allowed)
        activity.home()
        idle(3)

        // Lancée depuis le téléphone, accueil affiché : message et écran Cuisson manuelle, qui montre la cuisson.
        assertTrue(activity.current is app.toque.ui.screens.HomeScreen)
        val reply = offMain { phone.start(StartRequest(seconds = 600, tempC = 0, speed = 2)) }
        assertTrue(reply.ok)
        waitFor("écran Cuisson manuelle ouvert") { activity.current is ManualScreen }
        waitFor("en cours") { app.machine.snapshot.state == JobState.RUNNING }
        idle(3)
        assertNotNull(findText(activity.window.decorView, "Cuisson lancée depuis Téléphone 1"))
        shot("14-cuisson-lancee-depuis-telephone")
        val manual = activity.current as ManualScreen

        // Réglage changé depuis le téléphone : les cartes suivent (vitesse, sens, température).
        assertTrue(offMain { phone.adjust(AdjustRequest(speed = 3, tempC = 90, reverse = true)) }.ok)
        waitFor("réglage appliqué") { app.machine.snapshot.jobSpeed == 3 && app.machine.snapshot.jobReverse }
        waitFor("cartes à jour") { idle(); findText(manual.view, "Mélanger · inverse") != null && findText(manual.view, "90") != null }
        shot("15-cuisson-manuelle-suit-le-telephone")

        // Un réglage fait ensuite sur le robot (la durée, au bouton) n'efface pas celui du téléphone.
        val total = app.machine.snapshot.totalMs
        assertTrue(manual.onKnob(1))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        waitFor("durée changée sur le robot") { app.machine.snapshot.totalMs != total }
        assertEquals(3, app.machine.snapshot.jobSpeed)
        assertTrue(app.machine.snapshot.jobReverse)
        assertEquals(11, app.machine.snapshot.jobTempLevel)

        // Ailleurs que sur l'accueil, un lancement depuis le téléphone ne change pas d'écran.
        assertTrue(offMain { phone.stop() }.ok)
        waitFor("arrêtée") { !app.machine.snapshot.busy }
        activity.home()
        activity.push(SettingsScreen(activity))
        idle(3)
        waitFor("lancement permis") { offMain { phone.state() }.can.start.allowed }
        assertTrue(offMain { phone.start(StartRequest(seconds = 60, tempC = 0, speed = 2)) }.ok)
        idle(3)
        assertTrue(activity.current is SettingsScreen)
        assertTrue(offMain { phone.stop() }.ok)
    }
}
