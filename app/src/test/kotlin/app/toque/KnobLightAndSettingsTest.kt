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
import android.widget.SeekBar
import android.widget.TextView
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.Step
import app.toque.core.ring.Rgb
import app.toque.core.ring.RingLook
import app.toque.core.robot.Feature
import app.toque.core.robot.RobotProfiles
import app.toque.machine.MachineService
import app.toque.ui.KnobLight
import app.toque.ui.MainActivity
import app.toque.ui.Ui
import app.toque.ui.screens.KnobLightScreen
import app.toque.ui.screens.ProgramsScreen
import app.toque.ui.screens.SettingsScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/**
 * Écrans vus sur le robot (1024×600) : la lumière de la molette, les téléphones appairés dans
 * Réglages › Téléphone, et les cartes des programmes. Chaque essai pilote l'écran comme le doigt le ferait
 * et enregistre des images dans build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w1024dp-h600dp-land-mdpi", application = ScreenshotApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KnobLightAndSettingsTest {
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

    private fun findDescribed(v: View, description: String, out: MutableList<View> = ArrayList()): List<View> {
        if (v.contentDescription?.toString() == description) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) findDescribed(v.getChildAt(i), description, out)
        return out
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

    /** Position de [v] dans [ancestor] (gauche, haut). */
    private fun pos(v: View, ancestor: View): Pair<Int, Int> {
        var x = 0
        var y = 0
        var p: View = v
        while (p !== ancestor) {
            x += p.left
            y += p.top
            p = p.parent as View
        }
        return x to y
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

    /** Les cases à liste déroulante de la page ouverte, de haut en bas : une case avec le chevron tourné vers le bas. */
    /** Les pastilles d'un niveau ([label] : « 1 » à « 5 », « Éteinte »), carte « Allumée » puis carte « Pendant la veille ». */
    private fun levelChips(root: View, label: String) = texts(root).filter { it.text.toString() == label && it.isClickable }

    private fun selectedLevels(root: View) = texts(root).filter { it.isClickable && it.isSelected }.map { it.text.toString() }

    private fun seekBars(v: View, out: MutableList<SeekBar> = ArrayList()): List<SeekBar> {
        if (v is SeekBar && shown(v)) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) seekBars(v.getChildAt(i), out)
        return out
    }

    /** Le doigt glisse le curseur jusqu'à [value], puis se lève. */
    private fun slide(bar: SeekBar, value: Int) {
        val l = shadowOf(bar).onSeekBarChangeListener
        l.onStartTrackingTouch(bar)
        bar.progress = value
        l.onProgressChanged(bar, value, true)
        l.onStopTrackingTouch(bar)
        idle(2)
    }

    /** Le bloc entouré, « Allumée » ou « Pendant la veille » : celui que l'anneau montre. */
    private fun framed(root: View): String = listOf("Allumée", "Pendant la veille").single { name -> findDescribed(root, name).any { it.isSelected } }

    private fun levelLabels(root: View) = texts(root).filter { it.isClickable }.map { it.text.toString() }

    /**
     * Lumière de la molette : la luminosité allumée et pendant la veille, celle de la veille jamais au-dessus ;
     * les couleurs ne se montrent que si le code couleur est coupé ; l'ancien réglage de la veille est repris.
     * L'anneau montre le bloc entouré : une couleur qu'on choisit au plus fort, une luminosité telle qu'elle sera.
     */
    @Test fun knobLightHasItsOwnScreen() {
        val app = start()
        // Rien de réglé : le blanc propre au robot, et l'ancien choix par défaut (« Plus légère ») pour la veille.
        assertEquals(null, app.prefs.ring.on)
        assertEquals(Rgb(0, 0, 3), app.prefs.ring.standby)
        app.prefs.standbyKnob = "off"
        assertEquals(Rgb(0, 0, 0), app.prefs.ring.standby)
        app.prefs.standbyKnob = "normal"
        assertEquals("« Normale » : la veille ne touche pas l'anneau", null, app.prefs.ring.standby)
        app.prefs.standbyKnob = "dim"

        // On y arrive par Réglages › Molette et sons.
        val settings = SettingsScreen(activity)
        activity.push(settings)
        idle(3)
        click(settings.view, "Molette et sons")
        click(settings.pane, "Lumière de la molette")
        val screen = activity.current as KnobLightScreen
        val root = screen.view
        for (t in listOf("Couleurs sur la molette", "Allumée", "Pendant la veille", "Luminosité"))
            assertTrue("« $t » à l'écran", onScreen(root, t))
        val framedHint = "La molette montre le bloc entouré."
        val noBlue = "La molette n'a pas de LED bleue : ses couleurs mélangent le rouge, le vert et le blanc."
        val strongOn = Ui.guillemets("Pendant le réglage, la molette est au plus fort. Ensuite, elle aura la luminosité choisie.")
        val strongSleep = Ui.guillemets("Pendant le réglage, la molette est au plus fort. En veille, elle aura la luminosité choisie.")
        val usual = Ui.guillemets("Jamais plus lumineuse qu'allumée.")

        // Code couleur actif : deux luminosités, pas de couleur à choisir. « Allumée » est entouré, rien n'est tenu sur l'anneau.
        assertEquals("Allumée", framed(root))
        assertEquals(null, KnobLight.held)
        assertEquals(listOf("4", "1"), selectedLevels(root))
        assertEquals(listOf("1", "2", "3", "4", "5", "Éteinte", "1", "2", "3", "4"), levelLabels(root))
        assertTrue(findDescribed(root, "Rouge").isEmpty())
        assertFalse(onScreen(root, "Couleur"))
        assertTrue("pas de mélange à composer avec le code couleur", seekBars(root).isEmpty())
        assertTrue(onScreen(root, Ui.guillemets(framedHint)))
        assertTrue(onScreen(root, usual))
        for (chip in texts(root).filter { it.isClickable }) assertTrue("pastille de ${chip.height} px", chip.height >= Ui.u(52) && chip.width >= Ui.u(52))
        assertNothingCut(activity.window.decorView, "Lumière de la molette")
        shot("59-lumiere-de-la-molette")

        // Allumée au niveau 2 : la veille ne propose plus que jusqu'à 2.
        levelChips(root, "2")[0].performClick()
        idle(2)
        assertEquals(2, app.prefs.ringLevel)
        assertEquals(Rgb(0, 0, 10), app.prefs.ring.on)
        assertEquals(null, KnobLight.held)
        assertEquals(listOf("1", "2", "3", "4", "5", "Éteinte", "1", "2"), levelLabels(root))
        // Un niveau de veille : ce bloc est entouré, et l'anneau montre la veille telle qu'elle sera.
        levelChips(root, "1")[1].performClick()
        idle(2)
        assertEquals("Pendant la veille", framed(root))
        assertEquals(Rgb(0, 0, 3), KnobLight.held)
        levelChips(root, "2")[1].performClick()
        idle(2)
        assertEquals(2, app.prefs.ringStandbyLevel)
        assertEquals(Rgb(0, 0, 10), KnobLight.held)
        assertEquals("même luminosité qu'allumée : la veille ne touchera pas l'anneau", null, app.prefs.ring.standby)
        // Allumée baissée sous la veille : la veille suit, et l'anneau montre de nouveau « Allumée ».
        levelChips(root, "1")[0].performClick()
        idle(2)
        assertEquals(1, app.prefs.ringLevel)
        assertEquals(1, app.prefs.ringStandbyLevel)
        assertEquals(listOf("1", "1"), selectedLevels(root))
        assertEquals("Allumée", framed(root))
        assertEquals(null, KnobLight.held)
        levelChips(root, "Éteinte")[0].performClick()
        idle(2)
        assertEquals(Rgb(0, 0, 0), app.prefs.ring.standby)
        assertEquals("veille éteinte : l'anneau s'éteint tant que ce bloc est entouré", Rgb(0, 0, 0), KnobLight.held)
        levelChips(root, "5")[0].performClick()
        idle(2)
        assertEquals(listOf("5", "Éteinte"), selectedLevels(root))
        assertEquals(null, KnobLight.held)
        // Toucher un bloc l'entoure.
        findDescribed(root, "Pendant la veille").single().performClick()
        idle(2)
        assertEquals("Pendant la veille", framed(root))
        assertEquals(Rgb(0, 0, 0), KnobLight.held)
        assertFalse("avec le code couleur, rien n'est montré au plus fort", onScreen(root, strongSleep))

        // Code couleur coupé : une couleur pour chaque bloc, parmi celles que l'anneau sait faire, puis « Personnalisée ».
        click(root, "Couleurs sur la molette")
        assertFalse(app.prefs.knobColours)
        assertEquals(2, texts(root).count { it.text.toString() == "Couleur" && shown(it) })
        for (name in listOf("Blanc", "Rouge", "Orange", "Jaune", "Vert", "Cyan", "Rose", "Personnalisée")) assertEquals(name, 2, findDescribed(root, name).size)
        assertTrue(findDescribed(root, "Bleu").isEmpty())
        assertTrue(onScreen(root, Ui.guillemets("$framedHint $noBlue")))
        for (dot in findDescribed(root, "Rose") + findDescribed(root, "Personnalisée"))
            assertTrue("pastille de couleur de ${dot.height} px", dot.height >= Ui.u(48) && dot.width >= Ui.u(48))

        // Une couleur prévue, pour « Allumée » : l'anneau la montre au plus fort, et le bloc le dit en couleur.
        findDescribed(root, "Rouge")[0].performClick()
        idle(2)
        assertEquals("red", app.prefs.ringTint)
        assertEquals(Rgb(79, 0, 0), app.prefs.ring.on)
        assertEquals("Allumée", framed(root))
        assertEquals(Rgb(79, 0, 0), KnobLight.held)
        assertTrue(onScreen(root, strongOn))
        assertEquals("texte bien visible", Ui.ACCENT_INK, findText(root, strongOn)!!.currentTextColor)
        assertTrue(onScreen(root, usual))
        // La luminosité : la vraie, seulement quand on la change.
        levelChips(root, "3")[0].performClick()
        idle(2)
        assertEquals(Rgb(25, 0, 0), app.prefs.ring.on)
        assertEquals("l'anneau montre « Allumée » tel qu'il sera", null, KnobLight.held)
        assertFalse(onScreen(root, strongOn))
        // Une couleur prévue pour la veille : au plus fort elle aussi, même veille éteinte.
        findDescribed(root, "Vert")[1].performClick()
        idle(2)
        assertEquals("Pendant la veille", framed(root))
        assertEquals(Rgb(0, 79, 0), KnobLight.held)
        assertTrue(onScreen(root, strongSleep))
        assertEquals(Ui.ACCENT_INK, findText(root, strongSleep)!!.currentTextColor)
        assertFalse(onScreen(root, usual))
        levelChips(root, "2")[1].performClick()
        idle(2)
        assertEquals(Rgb(0, 10, 0), app.prefs.ring.standby)
        assertEquals("la vraie luminosité de la veille", Rgb(0, 10, 0), KnobLight.held)
        assertTrue(onScreen(root, usual))
        assertFalse(onScreen(root, strongSleep))
        findDescribed(root, "Cyan")[1].performClick()
        idle(2)
        assertEquals(Rgb(0, 79, 79), KnobLight.held)
        assertTrue(findDescribed(root, "Rouge")[0].isSelected)
        assertTrue(findDescribed(root, "Cyan")[1].isSelected)
        // Les couleurs prévues ne se règlent pas : pas de curseur tant que « Personnalisée » n'est pas choisie.
        assertTrue(seekBars(root).isEmpty())
        assertNothingCut(activity.window.decorView, "Lumière de la molette, couleurs")
        shot("59b-lumiere-de-la-molette-couleurs")

        // « Personnalisée », bloc « Allumée » : elle part de la couleur qu'on avait et se compose LED par LED.
        findDescribed(root, "Personnalisée")[0].performClick()
        idle(2)
        assertEquals("custom", app.prefs.ringTint)
        assertEquals(Rgb(79, 0, 0), app.prefs.ringCustom)
        assertEquals("Allumée", framed(root))
        assertTrue(findDescribed(root, "Personnalisée")[0].isSelected)
        assertEquals(Rgb(79, 0, 0), KnobLight.held)
        assertEquals(listOf(79, 0, 0), seekBars(root).map { it.progress })
        assertFalse("les curseurs prennent la place du texte sous les blocs", onScreen(root, Ui.guillemets("$framedHint $noBlue")))
        for (bar in seekBars(root)) assertTrue("curseur de ${bar.height} px", bar.height >= Ui.u(44))
        slide(seekBars(root)[1], 20)
        assertEquals(Rgb(79, 20, 0), app.prefs.ringCustom)
        assertEquals("au plus fort sous le doigt", Rgb(79, 20, 0), KnobLight.held)
        assertEquals("hors de l'écran, à la luminosité choisie", Rgb(25, 6, 0), app.prefs.ring.on)
        assertTrue(onScreen(root, "Vert  20"))
        // Tout éteindre n'est pas une couleur : le dernier curseur revient où il était.
        slide(seekBars(root)[0], 0)
        slide(seekBars(root)[1], 0)
        assertEquals(Rgb(0, 20, 0), app.prefs.ringCustom)
        assertEquals(listOf(0, 20, 0), seekBars(root).map { it.progress })
        slide(seekBars(root)[0], 79)

        // « Personnalisée », bloc « Pendant la veille » : un autre mélange que celui de l'anneau allumé.
        findDescribed(root, "Personnalisée")[1].performClick()
        idle(2)
        assertEquals("custom", app.prefs.ringStandbyTint)
        assertEquals("elle part de la couleur de veille qu'on avait", Rgb(0, 79, 79), app.prefs.ringStandbyCustom)
        assertEquals("Pendant la veille", framed(root))
        assertEquals(Rgb(0, 79, 79), KnobLight.held)
        assertTrue(onScreen(root, strongSleep))
        assertEquals(listOf(0, 79, 79), seekBars(root).map { it.progress })
        slide(seekBars(root)[2], 40)
        assertEquals(Rgb(0, 79, 40), app.prefs.ringStandbyCustom)
        assertEquals(Rgb(0, 79, 40), KnobLight.held)
        assertEquals("en veille, à la luminosité choisie (niveau 2)", Rgb(0, 10, 5), app.prefs.ring.standby)
        assertEquals("celle de l'anneau allumé n'a pas bougé", Rgb(79, 20, 0), app.prefs.ringCustom)
        assertNothingCut(activity.window.decorView, "Lumière de la molette, couleur personnalisée")
        shot("59d-lumiere-de-la-molette-personnalisee")
        // Sa luminosité : la vraie quand on la change, puis de nouveau au plus fort dès qu'on touche la couleur.
        levelChips(root, "1")[1].performClick()
        idle(2)
        assertEquals(Rgb(0, 3, 2), KnobLight.held)
        assertFalse(onScreen(root, strongSleep))
        findDescribed(root, "Personnalisée")[1].performClick()
        idle(2)
        assertEquals(Rgb(0, 79, 40), KnobLight.held)
        // Le bloc « Allumée » touché : c'est lui que l'anneau montre, les curseurs sont les siens.
        findDescribed(root, "Allumée").single().performClick()
        idle(2)
        assertEquals("Allumée", framed(root))
        assertEquals(Rgb(79, 20, 0), KnobLight.held)
        assertEquals(listOf(79, 20, 0), seekBars(root).map { it.progress })
        // Une couleur prévue pour la veille : rien à composer de ce côté, le mélange reste gardé.
        findDescribed(root, "Jaune")[1].performClick()
        idle(2)
        assertEquals(Rgb(79, 30, 0), KnobLight.held)
        assertTrue(seekBars(root).isEmpty())
        findDescribed(root, "Personnalisée")[1].performClick()
        idle(2)
        assertEquals("le mélange composé est gardé", Rgb(0, 79, 40), app.prefs.ringStandbyCustom)

        // Code couleur remis : les couleurs choisies sont gardées, mais l'anneau au repos redevient blanc.
        click(root, "Couleurs sur la molette")
        assertEquals(Rgb(0, 0, 25), app.prefs.ring.on)
        assertEquals(Rgb(0, 0, 3), app.prefs.ring.standby)
        assertEquals(RingLook.Tint.CUSTOM, app.prefs.ring.tint)
        assertTrue(findDescribed(root, "Rouge").isEmpty())
        assertTrue(seekBars(root).isEmpty())
        assertEquals("Pendant la veille", framed(root))
        assertEquals("la veille telle qu'elle sera", Rgb(0, 0, 3), KnobLight.held)

        // On quitte l'écran : plus rien n'est tenu, l'anneau reprend ce qui est réglé pour « Allumée ».
        activity.back()
        idle(2)
        assertEquals(null, KnobLight.held)

        // Dans les langues aux mots les plus longs, rien ne déborde des blocs.
        app.prefs.knobColours = false
        for (lang in listOf("de", "uk", "es")) {
            I18n.select(lang)
            val other = KnobLightScreen(activity)
            activity.push(other)
            idle(3)
            (findDescribed(other.view, tr("Personnalisée")).first()).performClick()
            idle(2)
            assertNothingCut(activity.window.decorView, "Lumière de la molette ($lang)")
            if (lang == "de") shot("59c-lumiere-de-la-molette-allemand")
            activity.back()
            idle(2)
        }
    }

    /**
     * Réglages › Téléphone : les téléphones appairés sont dans la page, avec « Retirer », sans passer par
     * l'écran d'appairage ; la page suit quand un téléphone s'ajoute ou part.
     */
    @Test fun pairedPhonesAreListedInThePhonePage() {
        val app = start()
        val pairing = app.companion!!.hub.pairing
        val settings = SettingsScreen(activity)
        activity.push(settings)
        idle(3)
        click(settings.view, "Téléphone")
        assertTrue(onScreen(settings.pane, "Aucun téléphone appairé"))
        assertFalse(onScreen(settings.pane, "Retirer"))

        // Deux téléphones s'appairent : la page les montre aussitôt.
        assertTrue(pairing.pairWithCode(pairing.open().code).ok)
        assertTrue(pairing.pairWithCode(pairing.open().code).ok)
        idle(3)
        val phones = pairing.list.sortedBy { it.number }
        assertEquals(2, phones.size)
        assertTrue(onScreen(settings.pane, "2 téléphones appairés"))
        for (p in phones) assertTrue("« ${p.name} » dans la page", onScreen(settings.pane, p.name))
        assertEquals(2, texts(settings.pane).count { it.text.toString() == "Retirer" && shown(it) })
        assertTrue(texts(settings.pane).any { it.text.toString().startsWith("Appairé le ") && it.text.toString().endsWith("vu à l'instant") })
        // Le premier appairé est au-dessus du second, et tous deux au-dessus du lancement à distance.
        val content = settings.pane.getChildAt(0)
        val y = phones.map { pos(findText(settings.pane, it.name)!!, content).second }
        assertTrue(y[0] < y[1])
        assertTrue(y[1] < pos(findText(settings.pane, "Lancement depuis le téléphone")!!, content).second)
        assertTrue("« Appairer un téléphone » reste dans la page", onScreen(settings.pane, "Appairer un téléphone"))
        assertFalse("la page tient à l'écran avec deux téléphones", settings.pane.canScrollVertically(1))
        assertNothingCut(settings.view, "Téléphone, deux appairés")
        shot("55b-reglages-telephone-appaires")

        // Le crayon renomme un téléphone : une carte de saisie, puis la page et le robot prennent le nouveau nom.
        val window = activity.window.decorView
        findDescribed(settings.pane, "Renommer ${phones[1].name}").single().performClick()
        idle(2)
        assertTrue(onScreen(window, "Renommer ${phones[1].name}"))
        val input = findDescribed(window, "Nom du téléphone").single() as EditText
        assertEquals("rien de saisi tant qu'il porte son nom d'origine", "", input.text.toString())
        assertEquals(phones[1].name, input.hint.toString())
        input.setText("Tablette du salon")
        click(input.parent as View, "OK")
        assertEquals("Tablette du salon", pairing.list.first { it.id == phones[1].id }.name)
        assertTrue(onScreen(settings.pane, "Tablette du salon"))
        assertFalse(onScreen(settings.pane, phones[1].name))
        assertFalse("la carte est refermée", onScreen(window, "Nom du téléphone"))
        // « Annuler » ne change rien ; un nom vidé rend « Téléphone 2 ».
        findDescribed(settings.pane, "Renommer Tablette du salon").single().performClick()
        idle(2)
        val again = findDescribed(window, "Nom du téléphone").single() as EditText
        assertEquals("Tablette du salon", again.text.toString())
        again.setText("Autre")
        click(again.parent as View, "Annuler")
        assertEquals("Tablette du salon", pairing.list.first { it.id == phones[1].id }.name)
        assertNothingCut(settings.view, "Téléphone, un renommé")
        shot("55c-reglages-telephone-renomme")
        findDescribed(settings.pane, "Renommer Tablette du salon").single().performClick()
        idle(2)
        val emptied = findDescribed(window, "Nom du téléphone").single() as EditText
        emptied.setText("")
        click(emptied.parent as View, "OK")
        assertEquals(phones[1].name, pairing.list.first { it.id == phones[1].id }.name)
        assertTrue(onScreen(settings.pane, phones[1].name))

        // « Retirer » demande confirmation, puis le téléphone quitte la page.
        var remove: View = texts(settings.pane).first { it.text.toString() == "Retirer" }
        while (!remove.isClickable) remove = remove.parent as View
        remove.performClick()
        idle(2)
        assertTrue(onScreen(window, "Retirer ${phones[0].name}"))
        var yes: View = texts(window).last { it.text.toString() == "Retirer" && shown(it) }
        while (!yes.isClickable) yes = yes.parent as View
        yes.performClick()
        idle(3)
        assertEquals(listOf(phones[1].id), pairing.list.map { it.id })
        assertTrue(onScreen(settings.pane, "1 téléphone appairé"))
        assertFalse(onScreen(settings.pane, phones[0].name))
        assertTrue(onScreen(settings.pane, phones[1].name))
        assertEquals(1, texts(settings.pane).count { it.text.toString() == "Retirer" && shown(it) })
    }

    /**
     * Depuis le yaourt (0.11.0) le Connect a cinq programmes : « Nettoyage » passe sur une seconde ligne,
     * de hauteur fixe. Avec le bouton « Recette Lidl », sa carte débordait et « Démarrer » était coupé.
     * Ouvert sur le nettoyage (fin de recette), l'écran doit aussi montrer cette carte sans faire défiler.
     */
    @Test fun everyProgramCardShowsItsStartButtonWhole() {
        val app = start()
        // Une recette de rinçage dans le catalogue : la carte « Nettoyage » gagne le bouton « Recette Lidl ».
        app.recipes.saveUser(Recipe(id = "perso-rincage", title = "Nettoyage", category = "Divers", source = RecipeSource.USER,
            steps = listOf(Step("Rincer le bol."))))
        waitFor("recette de rinçage") { app.recipes.index.entries.any { it.title == "Nettoyage" } }

        fun visible(text: String) = texts(activity.window.decorView).filter { it.text.toString() == text && shown(it) }
        fun startButtons(): List<View> = visible("Démarrer").map { t ->
            var b: View = t
            while (!b.isClickable) b = b.parent as View
            b
        }
        fun name(card: ViewGroup) = (card.getChildAt(1) as TextView).text.toString()

        try {
            for (robot in listOf(RobotProfiles.MC_CONNECT, RobotProfiles.MC_SMART)) {
                app.prefs.robotId = robot.id
                RobotProfiles.active = robot
                activity.push(ProgramsScreen(activity))
                idle(3)
                shot(if (robot == RobotProfiles.MC_CONNECT) "94-programmes-connect-nettoyage" else "95-programmes-smart-nettoyage")
                assertTrue("${robot.id} : « Recette Lidl » sur la carte du nettoyage", visible("Recette Lidl").isNotEmpty())
                val buttons = startButtons()
                assertTrue("${robot.id} : ${buttons.size} programmes", buttons.size >= 5)
                for (b in buttons) {
                    val card = b.parent as ViewGroup
                    val over = b.bottom + card.paddingBottom - card.height
                    assertTrue("${robot.id}, ${name(card)} : « Démarrer » dépasse de la carte de $over px", over <= 0)
                    assertTrue("${robot.id}, ${name(card)} : bouton écrasé (${b.height} px)", b.height >= Ui.u(66))
                }
                activity.home()
                idle(2)
            }

            // Fin de recette : l'écran ouvert sur le nettoyage montre sa carte entière, « Démarrer » compris.
            app.prefs.robotId = RobotProfiles.MC_CONNECT.id
            RobotProfiles.active = RobotProfiles.MC_CONNECT
            activity.push(ProgramsScreen(activity, openOnCleaning = true))
            idle(3)
            shot("96-programmes-connect-ouvert-sur-nettoyage")
            val clean = startButtons().single { name(it.parent as ViewGroup) == "Nettoyage" }
            val seen = android.graphics.Rect()
            assertTrue("« Démarrer » du nettoyage est à l'écran", clean.getGlobalVisibleRect(seen))
            assertEquals("« Démarrer » du nettoyage se voit en entier", clean.height, seen.height())
        } finally {
            app.prefs.robotId = RobotProfiles.MC_CONNECT.id
            RobotProfiles.active = RobotProfiles.MC_CONNECT
        }
    }

    /** #130, partie « masquer » : Réglages › Cuisson et balance cache des programmes, jamais tous. */
    @Test fun hiddenProgramsLeaveTheProgramsScreen() {
        val app = start()
        fun shownTexts() = texts(activity.window.decorView).filter { shown(it) }.map { it.text.toString() }
        try {
            app.prefs.robotId = RobotProfiles.MC_CONNECT.id
            RobotProfiles.active = RobotProfiles.MC_CONNECT
            app.prefs.hiddenPrograms = emptySet()
            val settings = SettingsScreen(activity)
            activity.push(settings)
            idle(3)
            click(settings.view, "Cuisson et balance")
            assertTrue(onScreen(settings.pane, "Programmes affichés sur l'écran Programmes :"))
            click(settings.pane, "Vapeur")
            assertEquals(setOf("STEAM"), app.prefs.hiddenPrograms)

            activity.push(ProgramsScreen(activity))
            idle(3)
            assertFalse("« Vapeur » masqué", "Vapeur" in shownTexts())
            assertTrue("« Rissoler » reste", "Rissoler" in shownTexts())
            activity.home()
            idle(2)

            // Le dernier programme affiché ne se masque pas : l'interrupteur revient et rien n'est enregistré.
            val others = ProgramsScreen.features().filter { it != Feature.TOAST }.map { it.name }.toSet()
            app.prefs.hiddenPrograms = others
            val again = SettingsScreen(activity)
            activity.push(again)
            idle(3)
            click(again.view, "Cuisson et balance")
            click(again.pane, "Rissoler")
            idle(3)
            assertEquals(others, app.prefs.hiddenPrograms)
            activity.home()
            idle(2)

            // Tout masqué (réglage d'une version future, par exemple) : l'écran montre tout plutôt que rien.
            app.prefs.hiddenPrograms = ProgramsScreen.features().map { it.name }.toSet()
            activity.push(ProgramsScreen(activity))
            idle(3)
            assertTrue("Vapeur" in shownTexts() && "Rissoler" in shownTexts())
            activity.home()
            idle(2)

            // Ouvert pour le nettoyage (fin de recette) : sa carte est là même masquée.
            app.prefs.hiddenPrograms = setOf("CLEANING")
            activity.push(ProgramsScreen(activity, openOnCleaning = true))
            idle(3)
            assertTrue("Nettoyage" in shownTexts())
        } finally {
            app.prefs.hiddenPrograms = emptySet()
            app.prefs.robotId = RobotProfiles.MC_CONNECT.id
            RobotProfiles.active = RobotProfiles.MC_CONNECT
        }
    }
}
