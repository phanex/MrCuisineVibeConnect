package app.toque

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import app.toque.ui.HoldButton
import app.toque.ui.Icon
import app.toque.ui.Ui
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Le bouton « maintenir pour Turbo » s'allume (jaune) tant qu'on le tient. Son éclair, jaune au repos, se
 * confondait alors avec le bouton : tenu, il est blanc, et il reprend sa couleur quand on relâche.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w1024dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TurboButtonTest {
    private val outDir = File(System.getProperty("toque.screenshots") ?: "build/screenshots").apply { mkdirs() }

    @After fun darkAgain() = Ui.setTheme(false)

    private fun touch(b: HoldButton, action: Int) {
        val t = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(t, t, action, 10f, 10f, 0)
        b.onTouchEvent(e)
        e.recycle()
    }

    private fun draw(b: HoldButton, canvas: Canvas, x: Float) {
        b.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(64, View.MeasureSpec.EXACTLY))
        b.layout(0, 0, b.measuredWidth, 64)
        canvas.save()
        canvas.translate(x, 18f)
        b.draw(canvas)
        canvas.restore()
    }

    @Test fun theBoltIsWhiteWhileTheButtonIsHeld() {
        val c = RuntimeEnvironment.getApplication()
        Ui.init(1024, 600)
        for (light in listOf(false, true)) {
            Ui.setTheme(light)
            val bmp = Bitmap.createBitmap(440, 100, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Ui.BG)
            var downs = 0
            var ups = 0
            val b = HoldButton(c, "Turbo", { downs++ }, { ups++ })
            val bolt = b.getChildAt(0) as Icon
            assertEquals(Icon.Kind.BOLT, bolt.kind)
            assertEquals("au repos, l'éclair est jaune", Ui.WARN, bolt.color)
            draw(b, canvas, 20f)

            touch(b, MotionEvent.ACTION_DOWN)
            assertEquals(1, downs)
            assertEquals("tenu, l'éclair est blanc", Color.WHITE, bolt.color)
            assertNotEquals("l'éclair a la couleur du bouton allumé", Ui.WARN, bolt.color)
            draw(b, canvas, 240f)
            File(outDir, "41-turbo-maintenu-" + (if (light) "clair" else "sombre") + ".png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

            touch(b, MotionEvent.ACTION_UP)
            assertEquals(1, ups)
            assertEquals("relâché, l'éclair reprend sa couleur", Ui.WARN, bolt.color)

            // Un appui annulé (le doigt quitte l'écran autrement) relâche aussi.
            touch(b, MotionEvent.ACTION_DOWN)
            touch(b, MotionEvent.ACTION_CANCEL)
            assertEquals(2, ups)
            assertEquals(Ui.WARN, bolt.color)
        }
    }
}
