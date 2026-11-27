package app.toque

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import app.toque.core.i18n.I18n
import app.toque.machine.MachineService
import app.toque.ui.MainActivity
import app.toque.ui.Ui
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

/** Réglages › Avancé : la case « Magisk », sur l'écran du robot (1024×600). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w1024dp-h600dp-land-mdpi", application = ScreenshotApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdvancedToolsTest {
    private val outDir = File(System.getProperty("toque.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private lateinit var activity: MainActivity
    private var activityController: ActivityController<MainActivity>? = null
    private var serviceController: ServiceController<MachineService>? = null

    @After fun tearDown() {
        I18n.select("fr")
        try { activityController?.pause()?.stop()?.destroy() } catch (_: Throwable) {}
        try { serviceController?.destroy() } catch (_: Throwable) {}
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

    private fun start(): ScreenshotApp {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as ScreenshotApp
        val service = Robolectric.buildService(MachineService::class.java).create().also { serviceController = it }.get()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, MachineService::class.java),
            service.onBind(Intent(app, MachineService::class.java)))
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().also { activityController = it }.get()
        waitFor("base simulée prête") { app.machine.snapshot.ready }
        return app
    }

    private fun findText(v: View, text: String): TextView? {
        if (v is TextView && v.text.toString() == text) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findText(v.getChildAt(i), text)?.let { return it }
        return null
    }

    private fun texts(v: View, out: MutableList<TextView> = ArrayList()): List<TextView> {
        if (v is TextView) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
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

    private fun click(root: View, text: String) {
        var v: View = findText(root, text) ?: throw AssertionError("texte absent : $text")
        while (!v.isClickable) v = v.parent as View
        v.performClick()
        idle(3)
    }

    /**
     * Rien de coupé ni de poussé hors de sa carte : chaque vue visible tient dans son parent, chaque
     * texte affiche toutes ses lignes, sans points de suspension.
     */
    private fun assertNothingCut(v: View, where: String) {
        if (v.visibility != View.VISIBLE) return
        val parent = v.parent as? View
        // Une zone qui défile contient plus grand qu'elle : c'est voulu (la rangée des sources, dans une langue aux mots longs).
        if (parent != null && parent !is ScrollView && parent !is HorizontalScrollView) {
            assertTrue("$where : ${describe(v)} dépasse à droite (${v.right} > ${parent.width})", v.right <= parent.width)
            assertTrue("$where : ${describe(v)} dépasse en bas (${v.bottom} > ${parent.height})", v.bottom <= parent.height)
        }
        if (v is TextView && v !is EditText && v.text.isNotEmpty()) {
            val l = v.layout
            assertNotNull("$where : ${describe(v)} sans mise en page", l)
            assertTrue("$where : ${describe(v)} coupé (${l.height} > ${v.height - v.paddingTop - v.paddingBottom})",
                l.height <= v.height - v.paddingTop - v.paddingBottom)
            for (line in 0 until l.lineCount) assertEquals("$where : ${describe(v)} abrégé", 0, l.getEllipsisCount(line))
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) assertNothingCut(v.getChildAt(i), where)
    }

    private fun describe(v: View) = if (v is TextView) "« ${v.text} »" else v.javaClass.simpleName

    /** Un texte exactement égal à [text] est affiché quelque part dans [root]. */
    private fun onScreen(root: View, text: String) = texts(root).any { it.text.toString() == text && shown(it) }

    /** La case « Magisk » de Réglages › Avancé, et la fenêtre de Toque où chercher un message ou une question. */
    private fun magiskTile(): Pair<View, View> {
        val settings = SettingsScreen(activity)
        activity.push(settings)
        idle(3)
        click(settings.view, "Avancé")
        var tile: View = findText(settings.pane, "Magisk")!!
        while (!tile.isClickable) tile = tile.parent as View
        assertTrue("case de ${tile.height} px", tile.height >= Ui.u(48))
        assertFalse("la page tient à l'écran avec une case de plus", settings.pane.canScrollVertically(1))
        assertNothingCut(settings.view, "Avancé")
        return tile to activity.window.decorView
    }

    /** Réglages › Avancé › Magisk, sans Magisk sur le robot (désinstallé, ou masqué sous un autre nom) : un message, rien d'ouvert. */
    @Test fun magiskNotFoundSaysSo() {
        start()
        val (tile, window) = magiskTile()
        tile.performClick()
        idle(2)
        assertTrue(onScreen(window, "Magisk est introuvable sur ce robot : désinstallé, ou masqué sous un autre nom."))
        assertFalse(onScreen(window, "Ouvrir Magisk ?"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    /**
     * Magisk installé : trouvé par son lanceur, quel que soit le nom de son écran. Un rappel avant d'ouvrir
     * (ne jamais y lancer l'installation directe), et « Annuler » n'ouvre rien.
     */
    @Test fun magiskOpensFromAdvancedWithAReminder() {
        start()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(MainActivity.MAGISK_PACKAGE)
        @Suppress("DEPRECATION")
        shadowOf(activity.packageManager).addResolveInfoForIntent(launcher, android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = MainActivity.MAGISK_PACKAGE
                name = "un.ecran.dont.le.nom.change"
            }
        })
        val (tile, window) = magiskTile()
        shot("58b-reglages-avance-avec-magisk")
        tile.performClick()
        idle(2)
        assertTrue(onScreen(window, "Ouvrir Magisk ?"))
        assertTrue("le rappel sur l'installation directe", texts(window).any { shown(it) && it.text.toString().contains("« Installation directe »") })
        assertNothingCut(window, "Avancé, rappel avant Magisk")
        shot("58c-reglages-avance-ouvrir-magisk")
        // « Annuler » : rien n'est ouvert.
        var cancel: View = texts(window).last { it.text.toString() == "Annuler" && shown(it) }
        while (!cancel.isClickable) cancel = cancel.parent as View
        cancel.performClick()
        idle(2)
        assertFalse(onScreen(window, "Ouvrir Magisk ?"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
