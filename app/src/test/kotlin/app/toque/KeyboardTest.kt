package app.toque

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.GridView
import android.widget.ScrollView
import android.widget.TextView
import app.toque.machine.MachineService
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui
import app.toque.ui.screens.MealieLinkScreen
import app.toque.ui.screens.RecipesScreen
import app.toque.ui.screens.SiteLinkScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Le clavier à l'écran. Le robot n'a pas de bouton Retour et sa fenêtre, en plein écran, ne se réduit pas
 * d'elle-même quand le clavier sort. Dès qu'il s'ouvre, tout écran doit donc garder la place au-dessus de
 * lui, montrer le champ en cours et son bouton, et offrir de quoi le refermer : le bouton « Masquer le
 * clavier » de la barre du haut, ou un appui à côté du champ. Les essais n'ont pas de clavier : ils disent
 * à l'activité ce qu'il laisserait de visible ([MainActivity.visibleBottom]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w1024dp-h600dp-land-mdpi", application = ScreenshotApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardTest {
    private val outDir = File(System.getProperty("toque.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private lateinit var activity: MainActivity
    private var activityController: ActivityController<MainActivity>? = null
    private var serviceController: ServiceController<MachineService>? = null

    /** Hauteurs du clavier d'Android 6 mesurées sur deux robots (1024 × 600) : SKMC 1100 A1 et SKMC 1200. */
    private val keyboards = listOf(277, 322)
    private val screenH = 600

    @After fun tearDown() {
        try {
            activityController?.pause()?.stop()?.destroy()
        } catch (_: Throwable) {
        }
        try {
            serviceController?.destroy()
        } catch (_: Throwable) {
        }
        idle(2)
    }

    private fun idle(rounds: Int = 1) {
        repeat(rounds) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun start() {
        val app = RuntimeEnvironment.getApplication() as ScreenshotApp
        val service = Robolectric.buildService(MachineService::class.java).create().also { serviceController = it }.get()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, MachineService::class.java),
            service.onBind(Intent(app, MachineService::class.java)))
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().also { activityController = it }.get()
        val end = System.currentTimeMillis() + 10_000
        while (!app.machine.snapshot.ready) {
            assertTrue("base simulée pas prête", System.currentTimeMillis() < end)
            idle()
        }
    }

    /** Une mise en page comme Android la fait : mesure, placement, puis l'annonce aux écouteurs de la fenêtre. */
    private fun layout() {
        idle(2)
        val root = activity.window.decorView
        repeat(3) {
            root.measure(View.MeasureSpec.makeMeasureSpec(1024, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1024, screenH)
            root.viewTreeObserver.dispatchOnGlobalLayout()
            idle()
        }
    }

    /** Le clavier sort ([px] > 0) ou rentre (0), comme Android le dit à la fenêtre : par une mise en page. */
    private fun keyboard(px: Int) {
        activity.visibleBottom = { screenH - px }
        layout()
    }

    private fun shot(name: String) {
        layout()
        val bmp = Bitmap.createBitmap(1024, screenH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        activity.window.decorView.draw(canvas)
        // Le clavier, que les essais n'ont pas : la zone qu'il couvre.
        val k = activity.keyboardHeight
        if (k > 0) {
            canvas.drawRect(0f, (screenH - k).toFloat(), 1024f, screenH.toFloat(), Paint().apply { color = 0xFF2A2F3A.toInt() })
            canvas.drawText("(clavier, $k px)", 440f, screenH - k / 2f, Paint().apply { color = 0xFF9AA4B2.toInt(); textSize = 26f; isAntiAlias = true })
        }
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun <T : View> all(v: View, type: Class<T>, out: ArrayList<T> = ArrayList()): List<T> {
        if (type.isInstance(v)) out += type.cast(v)!!
        if (v is ViewGroup) for (i in 0 until v.childCount) all(v.getChildAt(i), type, out)
        return out
    }

    private fun findText(v: View, text: String): List<TextView> = all(v, TextView::class.java).filter { it.text.toString() == text }

    /** Haut et bas de [v] à l'écran. */
    private fun span(v: View): IntRange {
        val xy = IntArray(2)
        v.getLocationOnScreen(xy)
        return xy[1]..(xy[1] + v.height)
    }

    private fun tap(x: Float, y: Float, dragTo: Float? = null) {
        val t = SystemClock.uptimeMillis()
        val events = ArrayList<MotionEvent>()
        events += MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        if (dragTo != null) for (i in 1..6) events += MotionEvent.obtain(t, t + 10L * i, MotionEvent.ACTION_MOVE, x, y + (dragTo - y) * i / 6, 0)
        events += MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, x, dragTo ?: y, 0)
        for (e in events) {
            activity.dispatchTouchEvent(e)
            e.recycle()
        }
        idle()
    }

    private fun tap(v: View) {
        val xy = IntArray(2)
        v.getLocationOnScreen(xy)
        tap(xy[0] + v.width / 2f, xy[1] + v.height / 2f)
    }

    private val imm get() = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val shownKeyboard get() = shadowOf(imm).isSoftInputVisible

    private fun type(field: EditText) {
        field.requestFocus()
        imm.showSoftInput(field, 0)
        idle()
        assertTrue(shownKeyboard)
    }

    /** Le bouton « Masquer le clavier » de la barre du haut, s'il se voit. */
    private fun hideButton(): View? =
        findText(activity.window.decorView, "Masquer le clavier").firstOrNull { it.isShown }?.parent as? View

    private fun focused(): View? = activity.window.currentFocus

    /**
     * Sur une page « Relier » : dès que le clavier sort, le champ en cours se montre en entier au-dessus de
     * lui avec son bouton ; passer à un autre champ le montre à son tour ; la page défile jusqu'en bas.
     */
    private fun everyFieldIsReachable(screen: Screen, name: String, fields: Int, kb: Int) {
        activity.push(screen)
        layout()
        val scroll = screen.view as ScrollView
        // Sans animation : le banc d'essai date le départ d'un défilement animé et ses images avec deux horloges
        // différentes (mise en page à la main, images du Choreographer), ce que le robot ne fait pas.
        scroll.isSmoothScrollingEnabled = false
        val inputs = all(screen.view, EditText::class.java).filter { it.isShown }
        assertEquals(fields, inputs.size)
        val whole = screen.view.height
        assertFalse("sans clavier, la page tient à l'écran", scroll.canScrollVertically(1))
        shot("$name-sans-clavier")

        val limit = screenH - kb
        for ((i, input) in inputs.withIndex()) {
            if (i == 0) {
                type(input)
                keyboard(kb)
                assertEquals(kb, activity.keyboardHeight)
                assertEquals("la page ne garde que la place au-dessus du clavier", whole - kb, screen.view.height)
            } else {
                input.requestFocus()
                layout()
            }
            val top = span(screen.view).first
            val s = span(input)
            assertTrue("champ ${i + 1} (« ${input.hint} ») de $name, clavier de $kb : $s hors de $top..$limit", s.first >= top && s.last <= limit)
            // Son bouton aussi, sauf pour l'adresse du serveur, qui n'en a pas à elle.
            val buttons = findText(screen.view, "Relier").map { it.parent as View }.filter { it.isShown && span(it).first >= top && span(it).last <= limit }
            if (input.hint.toString().startsWith("http")) continue
            assertTrue("aucun bouton « Relier » visible avec le champ ${i + 1} (« ${input.hint} ») de $name, clavier de $kb", buttons.isNotEmpty())
            shot("$name-$kb-champ${i + 1}")
        }
        // Tout en bas : la fin de la page est atteinte au-dessus du clavier.
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        layout()
        assertTrue("le bas de la page reste sous le clavier", span(scroll.getChildAt(0)).last <= limit)

        keyboard(0)
        assertEquals(0, activity.keyboardHeight)
        assertEquals(whole, screen.view.height)
        assertFalse(scroll.canScrollVertically(1))
        activity.back()
        layout()
    }

    @Test fun aLinkPageShowsEachFieldAndItsButtonAboveTheKeyboard() {
        start()
        for (kb in keyboards) {
            everyFieldIsReachable(MealieLinkScreen(activity), "clavier-mealie", 4, kb)
            everyFieldIsReachable(SiteLinkScreen(activity), "clavier-compte", 3, kb)
        }
    }

    /** Dès que le clavier sort, l'écran affiché, quel qu'il soit, ne garde que la place au-dessus de lui. */
    @Test fun anyScreenKeepsTheRoomAboveTheKeyboardAsSoonAsItOpens() {
        start()
        val kb = keyboards.first()
        val recipes = RecipesScreen(activity)
        activity.push(recipes)
        layout()
        val whole = recipes.view.height
        val grid = all(recipes.view, GridView::class.java).single()
        assertEquals(4, grid.numColumns)
        assertEquals(null, hideButton())

        type(all(recipes.view, EditText::class.java).single())
        keyboard(kb)
        assertEquals(kb, activity.keyboardHeight)
        assertEquals(kb, activity.keyboardMargin)
        assertEquals(whole - kb, recipes.view.height)
        // La recherche passe en lignes compactes, pour garder des titres lisibles au-dessus du clavier.
        assertEquals(2, grid.numColumns)
        assertNotNull("pas de bouton pour masquer le clavier", hideButton())
        shot("clavier-recherche-$kb")

        // Une barre du système n'est pas un clavier.
        keyboard(60)
        assertEquals(0, activity.keyboardHeight)
        assertEquals(whole, recipes.view.height)
        assertEquals(4, grid.numColumns)
        assertEquals(null, hideButton())
    }

    @Test fun theTopBarButtonClosesTheKeyboard() {
        start()
        val screen = MealieLinkScreen(activity)
        activity.push(screen)
        layout()
        val url = all(screen.view, EditText::class.java).first()
        type(url)
        keyboard(keyboards.first())
        assertSame(url, focused())
        val button = hideButton()
        assertNotNull(button)
        tap(button!!)
        assertFalse("le clavier est resté ouvert", shownKeyboard)
        // Le champ rend la main : la molette agit de nouveau sur l'écran.
        assertFalse(focused() is EditText)
        keyboard(0)
        assertEquals(null, hideButton())
    }

    @Test fun aTapBesideTheFieldClosesTheKeyboard() {
        start()
        val screen = MealieLinkScreen(activity)
        activity.push(screen)
        layout()
        val (url, user) = all(screen.view, EditText::class.java)
        val label = findText(screen.view, "Adresse du serveur").single()
        val link = findText(screen.view, "Relier").first().parent as View

        // Un appui sur un texte de la page : le clavier se referme et le champ rend la main.
        type(url)
        keyboard(keyboards.first())
        assertTrue(label.isShown)
        tap(label)
        assertFalse("le clavier est resté ouvert", shownKeyboard)
        assertFalse(focused() is EditText)
        keyboard(0)

        // Faire défiler la page ne le referme pas.
        type(url)
        keyboard(keyboards.first())
        val at = IntArray(2).also { label.getLocationOnScreen(it) }
        tap(at[0] + 40f, at[1] + 10f, dragTo = at[1] + 90f)
        assertTrue("faire défiler a fermé le clavier", shownKeyboard)

        // Toucher un autre champ : c'est lui qui a la main, le clavier reste.
        layout()
        tap(user)
        assertSame(user, focused())
        assertTrue("passer à un autre champ a fermé le clavier", shownKeyboard)

        // Un bouton garde son effet : ici il manque le mot de passe, et la page le dit.
        url.setText("http://192.168.0.20:9000")
        layout()
        tap(link)
        assertNotNull(findText(screen.view, "Tapez votre identifiant et votre mot de passe.").firstOrNull { it.visibility == View.VISIBLE })
        keyboard(0)

        // Dans une fenêtre ouverte aussi : un appui sur sa carte, ou sur le voile autour, referme le clavier
        // et laisse la fenêtre ouverte ; un bouton de la carte garde son effet et décide lui-même.
        val card = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val inCard = TextView(activity).apply { text = "Nom du téléphone"; height = 80 }
        val input = EditText(activity)
        var pressed = 0
        val next = TextView(activity).apply { text = "Suivant"; height = 60; setOnClickListener { pressed++ } }
        card.addView(inCard)
        card.addView(input, ViewGroup.LayoutParams(400, 60))
        card.addView(next)
        activity.showOverlay(card, dismissOnScrim = false)
        layout()
        type(input)
        tap(next)
        assertEquals(1, pressed)
        assertTrue("le bouton d'une carte a fermé le clavier", shownKeyboard)
        tap(inCard)
        assertFalse("un appui sur la carte n'a pas fermé le clavier", shownKeyboard)
        assertTrue(activity.overlayShown)
        type(input)
        tap(20f, 300f)
        assertFalse("un appui sur le voile n'a pas fermé le clavier", shownKeyboard)
        assertTrue("la fenêtre s'est fermée avec le clavier", activity.overlayShown)

        // Tant que le clavier est là, l'écran sous la fenêtre garde sa taille ; la fenêtre fermée, il reprend
        // la place au-dessus du clavier, et toute sa hauteur quand le clavier part.
        val whole = screen.view.height
        type(input)
        keyboard(keyboards.first())
        assertEquals(whole, screen.view.height)
        activity.closeOverlay()
        layout()
        assertEquals(whole - keyboards.first(), screen.view.height)
        keyboard(0)
        assertEquals(whole, screen.view.height)
    }

    /** Laisse à un glissement le temps de finir. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3 * Ui.CARD_SLIDE_MS))
        layout()
    }

    /** La carte d'une fenêtre ouverte : l'ancêtre de [inside] posé directement sur le voile (large comme l'écran). */
    private fun cardOf(inside: View): View {
        var v = inside
        while ((v.parent as View).width != 1024) v = v.parent as View
        return v
    }

    /**
     * Une carte de saisie (quantité, note, ingrédient) s'ouvre en haut, là où le clavier la
     * laisse : elle ne bouge pas quand il arrive. Le clavier refermé, elle revient au milieu de l'écran, sans
     * attendre qu'Android annonce son départ ; son champ touché, elle remonte sans attendre son retour.
     */
    @Test fun anInputCardFollowsTheKeyboard() {
        start()
        layout()
        for (kb in keyboards) {
            val input = EditText(activity)
            val ok = TextView(activity).apply { text = "OK d'essai"; height = 52 }
            Ui.showInputOverlay(activity, "Carte d'essai", input) {
                addView(input, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56))
                addView(ok)
            }
            layout()
            assertTrue("la carte n'a pas ouvert le clavier", shownKeyboard)
            val title = findText(activity.window.decorView, "Carte d'essai").single()
            val card = cardOf(title)
            val atTop = 14..(14 + card.height)
            val inTheMiddle = ((screenH - card.height) / 2).let { it..(it + card.height) }
            assertEquals(atTop, span(card))
            val before = listOf(span(title), span(input), span(ok))
            val under = activity.current!!.view
            val underHeight = under.height
            shot("clavier-carte-$kb-avant")

            keyboard(kb)
            val limit = screenH - kb
            assertEquals(kb, activity.keyboardMargin)
            assertEquals("la carte a bougé à l'arrivée du clavier", before, listOf(span(title), span(input), span(ok)))
            assertEquals("l'écran sous la fenêtre s'est resserré", underHeight, under.height)
            assertTrue("champ à ${span(input)}, clavier à $limit", span(input).first >= 0 && span(input).last <= limit)
            assertTrue("bouton à ${span(ok)}, clavier à $limit", span(ok).last <= limit)
            // La barre du haut est sous le voile : c'est la carte qui porte le bouton.
            assertEquals(null, hideButton())
            val inCard = all(activity.window.decorView, View::class.java).filter { it.contentDescription == "Masquer le clavier" && it.isShown }
            assertEquals(1, inCard.size)
            shot("clavier-carte-$kb")

            // Un appui sur le titre de la carte referme le clavier. La carte reste ouverte et part au milieu de
            // l'écran tout de suite, avant même qu'Android ait constaté le départ du clavier.
            tap(title)
            assertFalse("le clavier est resté ouvert", shownKeyboard)
            assertTrue(activity.overlayShown)
            settle()
            assertEquals("la carte n'est pas revenue au milieu", inTheMiddle, span(card))
            keyboard(0)
            settle()
            assertEquals(inTheMiddle, span(card))
            shot("clavier-carte-$kb-apres")

            // Son champ touché : elle remonte tout de suite, avant que le clavier soit là, et n'en bouge plus.
            tap(input)
            assertSame(input, focused())
            settle()
            assertEquals("la carte n'est pas remontée", atTop, span(card))
            keyboard(kb)
            settle()
            assertEquals(atTop, span(card))

            // Le bouton de la carte referme aussi le clavier, et la carte revient au milieu.
            tap(inCard.single())
            assertFalse("le clavier est resté ouvert", shownKeyboard)
            keyboard(0)
            settle()
            assertEquals(inTheMiddle, span(card))
            activity.closeOverlay()
            layout()
        }
    }

    /** Sur la recherche de recettes, un appui hors de la liste et hors du champ referme le clavier. */
    @Test fun aTapOnTheSearchPageClosesTheKeyboard() {
        start()
        val recipes = RecipesScreen(activity)
        activity.push(recipes)
        layout()
        type(all(recipes.view, EditText::class.java).single())
        keyboard(keyboards.first())
        val message = all(recipes.view, TextView::class.java).first { it !is EditText && it.isShown && it.text.length > 30 }
        tap(message)
        assertFalse("le clavier est resté ouvert", shownKeyboard)
    }

    /**
     * La hauteur du clavier n'est pas fixe : un autre clavier que celui d'Android 6 (HeliBoard, 320 px sur le
     * SKMC 1100 A1) la laisse régler, d'un tiers à une fois et demie. Bas ou haut, l'écran garde ce qui reste
     * au-dessus de lui et montre le champ en cours. Quand la place manque, une carte de saisie se limite à ce
     * qui reste : son contenu défile jusqu'à ses boutons, et son bouton « Masquer le clavier » reste en vue.
     */
    @Test fun aShortOrATallKeyboardKeepsTheFieldAboveIt() {
        start()
        layout()
        for (kb in listOf(130, 400)) {
            val limit = screenH - kb
            val input = EditText(activity)
            val ok = TextView(activity).apply { text = "OK d'essai"; height = 52 }
            Ui.showInputOverlay(activity, "Carte d'essai", input) {
                addView(input, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56))
                addView(TextView(activity).apply { text = "Aide d'essai"; height = 34 })
                addView(ok)
            }
            layout()
            val card = cardOf(findText(activity.window.decorView, "Carte d'essai").single())
            keyboard(kb)
            settle()
            assertEquals(kb, activity.keyboardHeight)
            assertTrue("carte à ${span(card)}, clavier de $kb à $limit", span(card).first == 14 && span(card).last <= limit)
            assertTrue("champ à ${span(input)}, clavier de $kb à $limit", span(input).first >= 14 && span(input).last <= limit)
            val hide = all(card, View::class.java).single { it.contentDescription == "Masquer le clavier" }
            assertTrue("bouton du clavier à ${span(hide)}, clavier de $kb à $limit", hide.isShown && span(hide).last <= limit)
            val inside = all(card, ScrollView::class.java).single()
            inside.scrollTo(0, inside.getChildAt(0).height)
            layout()
            assertTrue("bouton à ${span(ok)}, clavier de $kb à $limit", span(ok).first >= 14 && span(ok).last <= limit)
            activity.closeOverlay()
            keyboard(0)

            for (screen in listOf(MealieLinkScreen(activity), SiteLinkScreen(activity))) {
                activity.push(screen)
                layout()
                (screen.view as ScrollView).isSmoothScrollingEnabled = false
                for ((i, field) in all(screen.view, EditText::class.java).filter { it.isShown }.withIndex()) {
                    if (i == 0) {
                        type(field)
                        keyboard(kb)
                    } else {
                        field.requestFocus()
                        layout()
                    }
                    val top = span(screen.view).first
                    assertTrue("champ ${i + 1} (« ${field.hint} ») de ${screen.title}, clavier de $kb : ${span(field)} hors de $top..$limit",
                        span(field).first >= top && span(field).last <= limit)
                }
                assertNotNull("pas de bouton « Masquer le clavier » avec un clavier de $kb", hideButton())
                keyboard(0)
                activity.back()
                layout()
            }
        }
    }
}
