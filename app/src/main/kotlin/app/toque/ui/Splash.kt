package app.toque.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import app.toque.core.i18n.tr
import app.toque.core.machine.Snapshot
import app.toque.core.machine.Startup

/**
 * Écran de démarrage : la toque de l'appli et un anneau qui tourne, par-dessus tout l'écran, le temps
 * que la base moteur soit prête (une dizaine de secondes sur le robot, voir [Startup]). Il retient
 * les gestes et la molette : on ne lance rien sur une base qui n'est pas prête.
 *
 * Une fois par démarrage de Toque seulement, pas quand l'écran est reconstruit (changement de
 * langue). Si la base est déjà prête (interface relancée seule, le service machine tournait), il
 * part avant que quoi que ce soit n'apparaisse.
 */
class Splash(private val a: MainActivity, private val root: FrameLayout) {
    private val main = Handler(Looper.getMainLooper())
    private var view: FrameLayout? = null
    private var shownAtMs = 0L
    private var leaving = false
    private val giveUp = Runnable { hide() }

    /** Affiché, ou en train de s'effacer : les écrans qu'il couvre ne reçoivent ni gestes ni molette. */
    val shown: Boolean get() = view != null

    /** Au démarrage de l'activité : affiche l'écran si Toque vient de démarrer. */
    fun start() {
        if (done) return
        done = true
        show()
    }

    fun show() {
        if (view != null) return
        val v = FrameLayout(a)
        v.background = Ui.background()
        v.isClickable = true
        val box = Ui.vertical(a, Gravity.CENTER_HORIZONTAL)
        box.addView(Mark(a), Ui.lp(Ui.u(Mark.SIZE), Ui.u(Mark.SIZE)))
        box.addView(Ui.text(a, "Toque", 44f, Ui.TEXT, Ui.LIGHT), Ui.lp(Ui.WRAP, Ui.WRAP, t = 24))
        box.addView(Ui.text(a, tr("Démarrage…"), 19f, Ui.TEXT2), Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))
        v.addView(box, Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
        root.addView(v, Ui.flp())
        view = v
        leaving = false
        shownAtMs = SystemClock.uptimeMillis()
        // Le fond d'abord, la toque un instant après : rien ne clignote si la base est déjà prête.
        box.alpha = 0f
        box.animate().alpha(1f).setStartDelay(CONTENT_DELAY_MS).setDuration(CONTENT_FADE_MS).start()
        main.postDelayed(giveUp, Startup.MAX_WAIT_MS)
    }

    /** État de la base ; [heard] : le service machine a déjà parlé depuis le démarrage de l'interface. */
    fun onSnapshot(s: Snapshot, heard: Boolean) {
        if (view == null || leaving) return
        if (!Startup.waiting(s, heard, SystemClock.uptimeMillis() - shownAtMs)) hide()
    }

    private fun hide() {
        val v = view ?: return
        if (leaving) return
        main.removeCallbacks(giveUp)
        if (SystemClock.uptimeMillis() - shownAtMs < CONTENT_DELAY_MS) {
            remove(v)
            return
        }
        leaving = true
        v.animate().alpha(0f).setDuration(FADE_MS).start()
        // Retiré par l'horloge, pas par la fin de l'animation : un fondu interrompu ne le laisse pas en place.
        main.postDelayed({ remove(v) }, FADE_MS)
    }

    private fun remove(v: View) {
        v.animate().cancel()
        root.removeView(v)
        if (view === v) {
            view = null
            leaving = false
        }
    }

    /**
     * La toque de l'icône de l'appli (mêmes tracés que res/drawable/ic_launcher.xml) dans un disque,
     * entourée d'un arc qui tourne tant que la vue est à l'écran.
     */
    private class Mark(c: Context) : View(c) {
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT_DIM }
        private val hat = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Ui.SURFACE3
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Ui.ACCENT
        }
        private val top = Path().apply {
            moveTo(36f, 64f); lineTo(36f, 52f)
            cubicTo(26f, 50f, 22f, 40f, 28f, 33f)
            cubicTo(33f, 27f, 41f, 28f, 44f, 32f)
            cubicTo(46f, 24f, 62f, 24f, 64f, 32f)
            cubicTo(67f, 28f, 75f, 27f, 80f, 33f)
            cubicTo(86f, 40f, 82f, 50f, 72f, 52f)
            lineTo(72f, 64f); close()
        }
        private val band = Path().apply {
            moveTo(36f, 68f); lineTo(72f, 68f); lineTo(72f, 78f)
            cubicTo(72f, 80f, 70f, 82f, 68f, 82f)
            lineTo(40f, 82f)
            cubicTo(38f, 82f, 36f, 80f, 36f, 78f)
            close()
        }
        private val oval = RectF()
        private var angle = 0f
        private val spin = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = TURN_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                angle = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            spin.start()
        }

        override fun onDetachedFromWindow() {
            spin.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val s = minOf(width, height) / SIZE.toFloat()
            if (s <= 0f) return
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, DISC * s, disc)
            // La toque : tracés sur une grille de 108, où le disque de l'icône a un rayon de 50.
            val k = DISC * s / 50f
            canvas.save()
            canvas.translate(cx - 54f * k, cy - 54f * k)
            canvas.scale(k, k)
            canvas.drawPath(top, hat)
            canvas.drawPath(band, hat)
            canvas.restore()
            val r = RING * s
            track.strokeWidth = STROKE * s
            arc.strokeWidth = STROKE * s
            oval.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawCircle(cx, cy, r, track)
            canvas.drawArc(oval, angle - 90f, SWEEP, false, arc)
        }

        companion object {
            /** Côté de la vue, en unités de l'écran de référence. */
            const val SIZE = 184
            private const val DISC = 70f
            private const val RING = 87f
            private const val STROKE = 5f
            private const val SWEEP = 80f
            private const val TURN_MS = 1_100L
        }
    }

    companion object {
        /** Déjà montré depuis le démarrage du processus de l'interface (les essais le remettent à faux). */
        internal var done = false
        /** La toque n'apparaît qu'après ce délai ; une base prête avant lui ne montre rien. */
        const val CONTENT_DELAY_MS = 400L
        private const val CONTENT_FADE_MS = 300L
        const val FADE_MS = 250L
    }
}
