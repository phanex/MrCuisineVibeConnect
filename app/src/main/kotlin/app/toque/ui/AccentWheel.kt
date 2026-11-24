package app.toque.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import app.toque.core.look.ColourWheel

/**
 * La roue des couleurs (Réglages › Apparence › Roue des couleurs) : un anneau dont l'angle est la teinte
 * et la distance au centre l'intensité (pastel dedans, vif dehors), une poignée qu'on fait glisser, et
 * au milieu un disque de la couleur choisie avec une icône foncée dessus. Couleurs, garde et dessin de
 * l'anneau viennent de [ColourWheel] (core).
 *
 * [diameter] : l'anneau, en px ; la vue est plus grande de la taille de la poignée, qui déborde.
 * L'anneau est dessiné une fois dans une image, hors du fil principal, puis gardé pour toute la vie du
 * processus ([Ring]) : rouvrir la roue ne recalcule rien.
 */
class AccentWheel(c: Context, private val diameter: Int) : FrameLayout(c) {
    /** Couleur choisie (celle de la roue sous la poignée, ou un raccourci tel quel). */
    var color: Int = Ui.ACCENT
        private set
    /** Place de la poignée. */
    var hue = 0.0
        private set
    var intensity = 1.0
        private set
    /** Appelé à chaque nouvelle couleur choisie au doigt ou à la molette. */
    var onPick: ((Int) -> Unit)? = null

    private val ring = RingView(c)
    private val icon = Icon(c, Icon.Kind.POT, Ui.ON_ACCENT)

    init {
        addView(ring, Ui.flp(Ui.MATCH, Ui.MATCH))
        val side = (diameter * ColourWheel.RING_INNER * 0.6).toInt()
        addView(icon, Ui.flp(side, side, Gravity.CENTER))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Montre [c] (un raccourci, la couleur en cours), la poignée à sa place sur la roue. */
    fun show(c: Int) {
        val s = ColourWheel.place(c)
        hue = s.hue
        intensity = s.intensity
        setColour(c or (0xFF shl 24), notify = false)
    }

    /** Molette : un cran tourne la teinte de [STEP_DEG] degrés, à la même intensité. */
    fun turn(delta: Int) = pick(hue + delta * STEP_DEG, intensity)

    /** La poignée à la teinte [h] et à l'intensité [t] (bornée), et sa couleur sur la roue. */
    fun pick(h: Double, t: Double) {
        hue = ((h % 360.0) + 360.0) % 360.0
        intensity = Math.min(1.0, Math.max(0.0, t))
        setColour(ColourWheel.colour(hue, intensity), notify = true)
    }

    private fun setColour(c: Int, notify: Boolean) {
        val changed = c != color
        color = c
        icon.color = ColourWheel.onAccent(c)
        contentDescription = String.format(java.util.Locale.ROOT, "#%06X", c and 0xFFFFFF)
        ring.invalidate()
        if (notify && changed) onPick?.invoke(c)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Ring.get(diameter) { ring.invalidate() }
    }

    /** L'anneau, la poignée et le disque du milieu. */
    private inner class RingView(c: Context) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var dragging = false

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val half = diameter / 2f
            val bmp = Ring.ready(diameter)
            if (bmp != null) canvas.drawBitmap(bmp, cx - half, cy - half, paint)
            else {
                // Le temps du premier calcul (une fois par processus) : l'anneau en gris.
                stroke.color = Ui.SURFACE3
                stroke.strokeWidth = (half * (ColourWheel.RING_OUTER - ColourWheel.RING_INNER)).toFloat()
                canvas.drawCircle(cx, cy, (half * (ColourWheel.RING_OUTER + ColourWheel.RING_INNER) / 2).toFloat(), stroke)
            }
            // Disque du milieu : la couleur choisie, un filet du fond le sépare de l'anneau.
            paint.color = color
            canvas.drawCircle(cx, cy, (half * ColourWheel.RING_INNER).toFloat() - Ui.uf(10f), paint)
            // Poignée : un rond de la couleur, bordé de blanc puis d'un trait foncé (visible sur tout l'anneau).
            val a = Math.toRadians(hue)
            val r = ColourWheel.radiusOf(intensity) * half
            val hx = cx + (Math.cos(a) * r).toFloat()
            val hy = cy + (Math.sin(a) * r).toFloat()
            val hr = Ui.uf(HANDLE / 2f)
            paint.color = 0x55000000
            canvas.drawCircle(hx, hy + Ui.uf(2f), hr + Ui.uf(1f), paint)
            paint.color = Color.WHITE
            canvas.drawCircle(hx, hy, hr, paint)
            paint.color = color
            canvas.drawCircle(hx, hy, hr - Ui.uf(6f), paint)
            stroke.color = 0x99000000.toInt()
            stroke.strokeWidth = Ui.uf(1.5f)
            canvas.drawCircle(hx, hy, hr, stroke)
        }

        /**
         * Doigt mouillé, à bout de bras : on pose le doigt n'importe où sur l'anneau ou jusqu'à [SLOP]
         * au-delà (pas besoin d'attraper la poignée), puis on glisse partout, même hors de la roue. Le
         * disque du milieu ne réagit pas : le toucher par mégarde ne change rien.
         */
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val dx = (e.x - width / 2f).toDouble()
            val dy = (e.y - height / 2f).toDouble()
            val half = diameter / 2.0
            val dist = Math.sqrt(dx * dx + dy * dy)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (dist < half * ColourWheel.RING_INNER * 0.8 || dist > half + Ui.uf(SLOP)) return false
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> if (!dragging) return false
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val was = dragging
                    dragging = false
                    if (was && e.actionMasked == MotionEvent.ACTION_UP) performClick()
                    return was
                }
                else -> return dragging
            }
            val s = ColourWheel.spotAt(dx, dy, half)
            pick(s.hue, s.intensity)
            return true
        }

        /** Fin d'un glissé : comptée comme un toucher (lecteurs d'écran). */
        override fun performClick(): Boolean = super.performClick()
    }

    /**
     * L'image de l'anneau, calculée une fois par processus et par taille ([ColourWheel.ringPixels]), sur
     * un fil à part. Lue et rendue sur le fil principal.
     */
    object Ring {
        private var size = 0
        private var bitmap: Bitmap? = null
        private var working = false
        private val waiting = ArrayList<() -> Unit>()
        /** Durée du dernier calcul (ms), pour le journal et les tests. */
        var lastMs = 0L
            private set

        fun ready(d: Int): Bitmap? = if (size == d) bitmap else null

        /** Prépare l'image de [d] px (rien si elle existe) ; [done] sur le fil principal quand elle est là. */
        fun get(d: Int, done: () -> Unit) {
            if (size == d && bitmap != null) return
            waiting += done
            if (working) return
            working = true
            val main = Handler(Looper.getMainLooper())
            Thread({
                // Fil discret : l'image se prépare dès l'ouverture des Réglages, sans gêner l'écran.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val t0 = SystemClock.uptimeMillis()
                val px = ColourWheel.ringPixels(d)
                val bmp = Bitmap.createBitmap(px, d, d, Bitmap.Config.ARGB_8888)
                val ms = SystemClock.uptimeMillis() - t0
                main.post {
                    lastMs = ms
                    bitmap = bmp
                    size = d
                    working = false
                    val calls = ArrayList(waiting)
                    waiting.clear()
                    for (f in calls) f()
                }
            }, "roue-couleurs").start()
        }
    }

    companion object {
        /** Poignée (unités de l'écran de référence) et marge de toucher autour de l'anneau. */
        const val HANDLE = 60
        const val SLOP = 48f
        /** Un cran de molette : 6 degrés de teinte (un tour de roue en 60 crans). */
        const val STEP_DEG = 6.0
    }
}
