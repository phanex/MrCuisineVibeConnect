package app.toque.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.recipes.StepDurations
import app.toque.core.time.KitchenTimerList

/**
 * Fenêtre des minuteurs, ouverte depuis la barre du haut : la liste de ceux qui tournent (+1 min,
 * annuler), ou le choix d'une durée pour un nouveau (grands boutons, préréglages, molette).
 */
class TimerPanel private constructor(private val a: MainActivity) {
    private val timers = Timers.of(a)
    private var seconds = DEFAULT_SECONDS
    private var label = ""
    private var unsubscribe: (() -> Unit)? = null

    private fun listen(card: View, onTick: () -> Unit) {
        unsubscribe?.invoke()
        unsubscribe = timers.listen(onTick)
        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                unsubscribe?.invoke()
                unsubscribe = null
            }
        })
    }

    // ------------------------------------------------------------------ nouveau minuteur

    private lateinit var value: TextView
    private val presets = ArrayList<Pair<Int, TextView>>()

    private fun chooser() {
        val card = Ui.card(a, 28f, Ui.SURFACE2)
        card.setPadding(Ui.u(32), Ui.u(26), Ui.u(32), Ui.u(24))
        card.addView(head(tr("Nouveau minuteur"), if (label.isNotBlank() && label != tr("Minuteur")) label else ""))

        val row = Ui.horizontal(a)
        row.addView(Ui.button(a, "", Ui.Style.SECONDARY, Icon.Kind.MINUS, height = 76, textSize = 26f) { bump(-1) }, Ui.lp(Ui.u(110), Ui.WRAP))
        value = Ui.fitText(a, 72f, 40f, Ui.TEXT, Ui.LIGHT).apply { gravity = Gravity.CENTER }
        row.addView(value, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        row.addView(Ui.button(a, "", Ui.Style.SECONDARY, Icon.Kind.PLUS, height = 76, textSize = 26f) { bump(1) }, Ui.lp(Ui.u(110), Ui.WRAP))
        card.addView(row, Ui.lp(t = 18))

        // Préréglages : un appui choisit la durée.
        val chips = Ui.horizontal(a, Gravity.CENTER)
        presets.clear()
        for (m in PRESETS) {
            val t = Ui.text(a, StepDurations.label(m * 60), 18f, Ui.TEXT, Ui.MEDIUM)
            t.gravity = Gravity.CENTER
            t.minHeight = Ui.u(56)
            t.isClickable = true
            t.setOnClickListener { seconds = m * 60; refresh() }
            presets += m * 60 to t
            chips.addView(t, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { if (m != PRESETS.last()) rightMargin = Ui.u(10) })
        }
        card.addView(chips, Ui.lp(t = 18))

        val buttons = Ui.horizontal(a, Gravity.END or Gravity.CENTER_VERTICAL)
        if (timers.running.isNotEmpty()) {
            buttons.addView(Ui.button(a, tr("En cours (%d)", timers.running.size), Ui.Style.GHOST, Icon.Kind.TIMER, height = 60, textSize = 18f) { list() })
        }
        buttons.addView(Ui.spacer(a, weight = 1f), LinearLayout.LayoutParams(0, 1, 1f))
        buttons.addView(Ui.button(a, tr("Annuler"), Ui.Style.GHOST, height = 60, textSize = 18f) { a.closeOverlay() })
        buttons.addView(Ui.spacer(a, w = 12))
        buttons.addView(Ui.button(a, tr("Démarrer"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 60, textSize = 19f) { start() })
        card.addView(buttons, Ui.lp(t = 24))
        refresh()
        show(card)
        a.overlayKnob = MainActivity.OverlayKnob({ d -> bump(d) }, { start() })
    }

    private fun refresh() {
        value.text = Ui.clock(seconds * 1000L)
        for ((s, t) in presets) {
            val on = s == seconds
            t.setTextColor(if (on) Ui.ON_ACCENT else Ui.TEXT)
            t.background = Ui.pressable(if (on) Ui.round(Ui.ACCENT, 28f) else Ui.round(Ui.SURFACE, 28f, Ui.STROKE, 1f), 28f)
        }
    }

    /** Pas plus fins pour les courtes durées : 30 s, puis 1 min, 5 min et 15 min. */
    private fun bump(dir: Int) {
        val s = seconds
        val step = when {
            dir > 0 && s < 300 || dir < 0 && s <= 300 -> 30
            dir > 0 && s < 3600 || dir < 0 && s <= 3600 -> 60
            dir > 0 && s < 3 * 3600 || dir < 0 && s <= 3 * 3600 -> 300
            else -> 900
        }
        val next = (s + dir * step).coerceIn(30, (KitchenTimerList.MAX_MS / 1000).toInt())
        // Sur la grille du pas : 7 min 30 + 5 min → 10 min, pas 12 min 30.
        seconds = if (next % step == 0 || next == 30) next else (if (dir > 0) next / step * step else (next / step + 1) * step)
        refresh()
    }

    private fun start() {
        if (!timers.canAdd()) {
            a.toast(tr("%d minuteurs au plus en même temps", KitchenTimerList.MAX), Ui.WARN)
            return
        }
        timers.start(label.ifBlank { tr("Minuteur") }, seconds * 1000L)
        a.closeOverlay()
    }

    // ------------------------------------------------------------------ minuteurs en cours

    private class Row(val id: Long, val remaining: TextView)

    private fun list() {
        val card = Ui.card(a, 28f, Ui.SURFACE2)
        card.setPadding(Ui.u(32), Ui.u(26), Ui.u(32), Ui.u(24))
        card.addView(head(tr("Minuteurs"), ""))
        val rowsBox = Ui.vertical(a)
        card.addView(rowsBox, Ui.lp(t = 8))
        val rows = ArrayList<Row>()
        fun build() {
            rowsBox.removeAllViews()
            rows.clear()
            for (t in timers.running) {
                val r = Ui.horizontal(a)
                r.background = Ui.round(Ui.SURFACE, 22f)
                r.setPadding(Ui.u(20), Ui.u(8), Ui.u(8), Ui.u(8))
                r.addView(Ui.ellipsize(Ui.text(a, t.label, 19f, Ui.TEXT, Ui.MEDIUM), 2), LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(12) })
                val rem = Ui.text(a, Ui.clock(t.remaining(timers.now())), 34f, Ui.ACCENT, Ui.LIGHT)
                r.addView(rem, Ui.lp(Ui.WRAP, Ui.WRAP, r = 14))
                r.addView(Ui.button(a, tr("+1 min"), Ui.Style.SECONDARY, height = 56, textSize = 18f) { timers.extend(t.id, 60_000L) })
                r.addView(Ui.button(a, "", Ui.Style.GHOST, Icon.Kind.CLOSE, height = 56, textSize = 20f) { timers.cancel(t.id) }, Ui.lp(Ui.WRAP, Ui.WRAP, l = 8))
                rowsBox.addView(r, Ui.lp(t = 10))
                rows += Row(t.id, rem)
            }
        }
        build()
        val buttons = Ui.horizontal(a, Gravity.CENTER_VERTICAL)
        val add = Ui.button(a, tr("Nouveau minuteur"), Ui.Style.SECONDARY, Icon.Kind.PLUS, height = 60, textSize = 18f) { chooser() }
        buttons.addView(add)
        buttons.addView(Ui.spacer(a, weight = 1f), LinearLayout.LayoutParams(0, 1, 1f))
        buttons.addView(Ui.button(a, tr("Fermer"), Ui.Style.GHOST, height = 60, textSize = 18f) { a.closeOverlay() })
        card.addView(buttons, Ui.lp(t = 22))
        Ui.setButtonEnabled(add, timers.canAdd())
        show(card)
        a.overlayKnob = MainActivity.OverlayKnob({ }, { a.closeOverlay() })
        listen(card) {
            val now = timers.running
            if (now.isEmpty()) {
                a.closeOverlay()
                return@listen
            }
            if (now.map { it.id } != rows.map { it.id }) build()
            else for ((i, t) in now.withIndex()) rows[i].remaining.text = Ui.clock(t.remaining(timers.now()))
            Ui.setButtonEnabled(add, timers.canAdd())
        }
    }

    private fun head(title: String, sub: String): View {
        val box = Ui.horizontal(a)
        val badge = FrameLayout(a)
        badge.background = Ui.round(Ui.ACCENT_DIM, 26f)
        badge.addView(Icon(a, Icon.Kind.TIMER, Ui.ACCENT), Ui.flp(Ui.u(30), Ui.u(30), Gravity.CENTER))
        box.addView(badge, LinearLayout.LayoutParams(Ui.u(52), Ui.u(52)).apply { rightMargin = Ui.u(16) })
        val col = Ui.vertical(a)
        col.addView(Ui.text(a, title, 26f, Ui.TEXT, Ui.MEDIUM))
        if (sub.isNotBlank()) col.addView(Ui.ellipsize(Ui.text(a, sub, 17f, Ui.TEXT2)), Ui.lp(t = 4))
        box.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        return box
    }

    private fun show(card: View) {
        a.showOverlay(card, 700, spareMachineBar = true)
    }

    companion object {
        const val DEFAULT_SECONDS = 5 * 60
        /** Préréglages, en minutes. */
        val PRESETS = intArrayOf(1, 5, 10, 15, 30, 60)

        /** Depuis la barre du haut : les minuteurs en cours s'il y en a, sinon un nouveau. */
        fun open(a: MainActivity) {
            val p = TimerPanel(a)
            if (Timers.of(a).running.isNotEmpty()) p.list() else p.chooser()
        }

        /** Nouveau minuteur prêt à lancer, avec sa durée et son libellé (une étape de recette). */
        fun choose(a: MainActivity, seconds: Int, label: String) {
            val p = TimerPanel(a)
            p.seconds = seconds
            p.label = label
            p.chooser()
        }
    }
}

/**
 * Message de fin d'un minuteur, par-dessus tout (même une fenêtre ouverte, qu'il ne referme pas),
 * sauf la barre machine et son bouton Stop quand un programme tourne. « OK » ou l'appui sur la
 * molette l'acquitte et coupe la sonnerie.
 */
class TimerAlarm(private val a: MainActivity, private val root: FrameLayout) {
    private var layer: FrameLayout? = null
    private var names: TextView? = null
    private var titleView: TextView? = null
    val shown: Boolean get() = layer != null

    /** Affiche (ou met à jour) le message pour les minuteurs terminés ; l'enlève s'il n'y en a plus. */
    fun refresh() {
        val ringing = Timers.of(a).ringing
        if (ringing.isEmpty()) { hide(); return }
        if (layer == null) build()
        titleView?.text = if (ringing.size == 1) tr("Minuteur terminé") else tr("%d minuteurs terminés", ringing.size)
        names?.text = ringing.joinToString("\n") { it.label }
        spare(a.machineBarSpace())
    }

    /** Laisse la barre machine (et son bouton Stop) hors du voile. */
    fun spare(bottom: Int) {
        val l = layer ?: return
        val lp = l.layoutParams as FrameLayout.LayoutParams
        if (lp.bottomMargin != bottom) {
            lp.bottomMargin = bottom
            l.layoutParams = lp
        }
    }

    fun ok() {
        Timers.of(a).acknowledge()
        hide()
    }

    private fun build() {
        val l = FrameLayout(a)
        l.setBackgroundColor(Ui.SCRIM)
        l.isClickable = true
        val card = Ui.vertical(a, Gravity.CENTER_HORIZONTAL)
        card.background = Ui.round(Ui.SURFACE2, 28f, Ui.ACCENT, 2f)
        card.setPadding(Ui.u(36), Ui.u(30), Ui.u(36), Ui.u(28))
        card.isClickable = true
        val badge = FrameLayout(a)
        badge.background = Ui.round(Ui.ACCENT_DIM, 48f)
        badge.addView(Icon(a, Icon.Kind.TIMER, Ui.ACCENT), Ui.flp(Ui.u(56), Ui.u(56), Gravity.CENTER))
        card.addView(badge, LinearLayout.LayoutParams(Ui.u(96), Ui.u(96)))
        val t = Ui.text(a, "", 32f, Ui.TEXT, Ui.MEDIUM).apply { gravity = Gravity.CENTER }
        card.addView(t, Ui.lp(Ui.WRAP, Ui.WRAP, t = 16))
        val n = Ui.ellipsize(Ui.text(a, "", 22f, Ui.TEXT2).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f) }, 3)
        card.addView(n, Ui.lp(Ui.WRAP, Ui.WRAP, t = 8))
        card.addView(Ui.button(a, "OK", Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 76, textSize = 26f) { ok() }, Ui.lp(Ui.MATCH, Ui.WRAP, t = 24))
        l.addView(card, Ui.flp(Ui.u(600), Ui.WRAP, Gravity.CENTER))
        root.addView(l, Ui.flp())
        layer = l
        titleView = t
        names = n
    }

    fun hide() {
        layer?.let { root.removeView(it) }
        layer = null
        names = null
        titleView = null
    }
}
