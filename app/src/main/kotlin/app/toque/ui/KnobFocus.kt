package app.toque.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Choix à la molette, la même règle sur tous les écrans : un cadre orange montre l'élément choisi,
 * tourner le déplace, appuyer l'ouvre ou l'active. Le cadre apparaît au premier cran (sans bouger)
 * et s'en va après un toucher de l'écran ou [IDLE_MS] sans molette : qui cuisine au doigt ne le voit
 * jamais. Pas de tour complet : au bout de la liste, le cadre reste sur le dernier élément.
 */
class KnobFocus(private val host: View, private val slots: Slots) {
    interface Slots {
        val size: Int
        /** Où le cadre apparaît au premier cran ; -1 : nulle part. */
        fun start(): Int
        /** L'élément [i] est affiché et utilisable (sinon le cadre le saute). */
        fun usable(i: Int): Boolean
        /** Montre ou retire le cadre de l'élément [i] (et le fait voir, liste qui défile). */
        fun mark(i: Int, on: Boolean)
        fun activate(i: Int)
    }

    /** Élément choisi (gardé quand le cadre s'efface : il revient au même endroit). */
    var index = -1
        private set
    var shown = false
        private set
    private val hideLater = Runnable { hide() }

    fun turn(delta: Int) {
        if (!shown) {
            val i = if (index in 0 until slots.size && slots.usable(index)) index else slots.start()
            if (i < 0) return
            move(i)
            return
        }
        var i = index
        val step = if (delta > 0) 1 else -1
        var left = Math.abs(delta)
        var j = i + step
        while (left > 0 && j in 0 until slots.size) {
            if (slots.usable(j)) {
                i = j
                left--
            }
            j += step
        }
        move(i)
    }

    /** Appui : active l'élément encadré ; sans cadre, le fait seulement apparaître. */
    fun press() {
        if (!shown || index !in 0 until slots.size || !slots.usable(index)) {
            turn(0)
            return
        }
        armTimer()
        slots.activate(index)
    }

    private fun move(i: Int) {
        if (shown && index != i && index in 0 until slots.size) slots.mark(index, false)
        index = i
        shown = true
        slots.mark(i, true)
        armTimer()
    }

    private fun armTimer() {
        host.removeCallbacks(hideLater)
        host.postDelayed(hideLater, IDLE_MS)
    }

    fun hide() {
        host.removeCallbacks(hideLater)
        if (shown && index in 0 until slots.size) slots.mark(index, false)
        shown = false
    }

    /** Les éléments ont changé (liste refaite) : le cadre repart du début au prochain cran. */
    fun reset() {
        hide()
        index = -1
    }

    companion object {
        const val IDLE_MS = 10_000L

        /** Cadre de la couleur d'accent par-dessus la vue (son fond ne change pas), aux coins arrondis de [radius]. */
        fun frame(v: View, on: Boolean, radius: Float) {
            v.foreground = if (on) Ui.round(0, radius, Ui.ACCENT, 3f) else null
        }
    }
}

/** Conteneur qui signale chaque toucher de l'écran (le cadre de la molette s'efface), sans le consommer. */
class TouchWatch(c: Context, private val onTouch: () -> Unit) : FrameLayout(c) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onTouch()
        return super.dispatchTouchEvent(ev)
    }
}
