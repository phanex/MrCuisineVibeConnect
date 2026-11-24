package app.toque.ui.screens

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.look.ColourWheel
import app.toque.ui.AccentWheel
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.ProgressLine
import app.toque.ui.Screen
import app.toque.ui.Ui

/**
 * Réglages › Apparence › Roue des couleurs : la roue à gauche ; à droite, un aperçu en direct (tuile de
 * l'accueil, bouton principal, puce choisie, texte en couleur, barre de progression), les dix couleurs
 * d'origine en raccourcis, « Annuler » et « Appliquer ». La molette tourne la teinte, un appui applique.
 * Rien ne change dans l'appli avant « Appliquer », qui fait ce que fait une pastille (applyAccent).
 */
class AccentWheelScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Roue des couleurs")
    lateinit var wheel: AccentWheel
        private set

    // L'aperçu, recoloré à chaque mouvement de la poignée (sans reconstruire les vues).
    private val badgeFill = GradientDrawable()
    private lateinit var badgeIcon: Icon
    private val buttonFill = GradientDrawable()
    private lateinit var buttonIcon: Icon
    private lateinit var buttonText: TextView
    private val chipFill = GradientDrawable()
    private lateinit var chipText: TextView
    private lateinit var inkText: TextView
    private lateinit var progress: ProgressLine
    private lateinit var apply: LinearLayout
    private val shortcuts = ArrayList<Pair<Int, FrameLayout>>()

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        root.setPadding(Ui.u(24), Ui.u(12), Ui.u(32), Ui.u(16))

        // La roue : anneau de 380 px, et la place de la poignée tout autour.
        val ring = Ui.u(RING)
        wheel = AccentWheel(c, ring)
        wheel.show(Ui.ACCENT)
        wheel.onPick = { paint(it) }
        val left = FrameLayout(c)
        left.addView(wheel, Ui.flp(ring + Ui.u(AccentWheel.HANDLE), ring + Ui.u(AccentWheel.HANDLE), Gravity.CENTER))
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))

        val right = Ui.vertical(c)
        right.addView(Ui.text(c, tr("Faites glisser la poignée sur l'anneau, ou tournez le bouton du robot."), 17f, Ui.TEXT2)
            .apply { setLineSpacing(0f, 1.15f) })

        // Aperçu : une tuile de l'accueil à gauche ; bouton, puces, texte et progression à droite.
        val preview = Ui.card(c, 24f)
        val row = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        val tile = Ui.vertical(c, Gravity.CENTER_HORIZONTAL)
        tile.background = Ui.round(Ui.SURFACE, 22f, Ui.STROKE, 1f)
        tile.setPadding(Ui.u(10), Ui.u(14), Ui.u(10), Ui.u(12))
        val badge = FrameLayout(c)
        badgeFill.cornerRadius = Ui.uf(36f)
        badge.background = badgeFill
        badgeIcon = Icon(c, Icon.Kind.BOOK, Ui.ON_ACCENT)
        badge.addView(badgeIcon, Ui.flp(Ui.u(38), Ui.u(38), Gravity.CENTER))
        tile.addView(badge, LinearLayout.LayoutParams(Ui.u(72), Ui.u(72)))
        tile.addView(Ui.ellipsize(Ui.text(c, tr("Recettes"), 18f, Ui.TEXT, Ui.MEDIUM)), Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))
        row.addView(tile, LinearLayout.LayoutParams(Ui.u(130), Ui.WRAP).apply { rightMargin = Ui.u(18) })
        val col = Ui.vertical(c)
        val button = Ui.horizontal(c, Gravity.CENTER)
        button.setPadding(Ui.u(20), 0, Ui.u(20), 0)
        buttonFill.cornerRadius = Ui.uf(26f)
        button.background = buttonFill
        buttonIcon = Icon(c, Icon.Kind.PLAY, Ui.ON_ACCENT)
        button.addView(buttonIcon, LinearLayout.LayoutParams(Ui.u(22), Ui.u(22)).apply { rightMargin = Ui.u(8) })
        buttonText = Ui.text(c, tr("Lancer"), 18f, Ui.ON_ACCENT, Ui.MEDIUM)
        button.addView(buttonText)
        col.addView(button, Ui.lp(Ui.WRAP, Ui.u(52)))
        val chips = Ui.horizontal(c)
        chipText = Ui.ellipsize(Ui.text(c, tr("Favoris"), 16f, Ui.ON_ACCENT, Ui.MEDIUM))
        chipText.setPadding(Ui.u(16), Ui.u(8), Ui.u(16), Ui.u(8))
        chipFill.cornerRadius = Ui.uf(40f)
        chipText.background = chipFill
        chips.addView(chipText, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        chips.addView(Ui.ellipsize(Ui.text(c, tr("Mes recettes"), 16f, Ui.TEXT, Ui.MEDIUM)).apply {
            setPadding(Ui.u(16), Ui.u(8), Ui.u(16), Ui.u(8))
            background = Ui.round(Ui.SURFACE2, 40f, Ui.STROKE, 1f)
        }, Ui.lp(Ui.WRAP, Ui.WRAP))
        col.addView(chips, Ui.lp(t = 10))
        inkText = Ui.ellipsize(Ui.text(c, tr("Texte en couleur"), 18f, Ui.ACCENT_INK, Ui.MEDIUM))
        col.addView(inkText, Ui.lp(t = 10))
        progress = ProgressLine(c).apply { progress = 0.62f }
        col.addView(progress, Ui.lp(Ui.MATCH, Ui.u(8), t = 10))
        row.addView(col, Ui.lp(0, Ui.WRAP, 1f))
        preview.addView(row)
        right.addView(preview, Ui.lp(t = 12))

        // Les dix couleurs d'origine : un toucher met la poignée sur elles.
        for (r in 0 until 2) {
            val line = Ui.horizontal(c, Gravity.CENTER)
            for (i in r * 5 until r * 5 + 5) {
                val color = Ui.ACCENTS[i]
                val box = FrameLayout(c)
                val dot = FrameLayout(c)
                dot.isClickable = true
                dot.contentDescription = tr("Couleur %d", i + 1)
                dot.setOnClickListener {
                    wheel.show(color)
                    paint(color)
                }
                box.addView(dot, Ui.flp(Ui.u(52), Ui.u(52), Gravity.CENTER))
                shortcuts += color to dot
                line.addView(box, LinearLayout.LayoutParams(0, Ui.u(58), 1f))
            }
            right.addView(line, Ui.lp(t = if (r == 0) 12 else 4))
        }

        val buttons = Ui.horizontal(c, Gravity.CENTER_VERTICAL or Gravity.END)
        buttons.addView(Ui.button(c, tr("Annuler"), Ui.Style.SECONDARY, height = 64) { a.back() }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 12))
        apply = Ui.button(c, tr("Appliquer"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 64) { applyChoice() }
        buttons.addView(apply)
        right.addView(buttons, Ui.lp(t = 14))
        root.addView(right, LinearLayout.LayoutParams(Ui.u(470), Ui.WRAP))
        paint(wheel.color)
        return root
    }

    /** L'aperçu, les raccourcis et « Appliquer » dans la couleur [color]. */
    private fun paint(color: Int) {
        val on = ColourWheel.onAccent(color)
        badgeFill.setColor(color)
        badgeIcon.color = on
        buttonFill.setColor(color)
        buttonIcon.color = on
        buttonText.setTextColor(on)
        chipFill.setColor(color)
        chipText.setTextColor(on)
        inkText.setTextColor(Ui.inkOf(color))
        progress.color = color
        progress.invalidate()
        // « Appliquer » aussi prend la couleur : c'est le bouton principal tel qu'il sera.
        apply.background = Ui.pressable(Ui.round(color, 32f), 32f)
        for (i in 0 until apply.childCount) when (val v = apply.getChildAt(i)) {
            is Icon -> v.color = on
            is TextView -> v.setTextColor(on)
        }
        for ((sc, dot) in shortcuts) {
            val sel = sc == color
            dot.background = Ui.pressable(Ui.round(sc, 26f, if (sel) Ui.TEXT else 0, if (sel) 3f else 0f), 26f)
            if (sel && dot.childCount == 0) dot.addView(Icon(c, Icon.Kind.CHECK, ColourWheel.onAccent(sc)), Ui.flp(Ui.u(24), Ui.u(24), Gravity.CENTER))
            if (!sel && dot.childCount > 0) dot.removeAllViews()
        }
    }

    /** « Appliquer » (ou un appui sur la molette) : comme une pastille ; la même couleur ne fait que revenir. */
    private fun applyChoice() {
        val color = wheel.color
        if (color == Ui.ACCENT || !Ui.accepts(color)) a.back() else a.applyAccent(color)
    }

    override fun onKnob(delta: Int): Boolean {
        wheel.turn(delta)
        return true
    }

    override fun onKnobPress(): Boolean {
        applyChoice()
        return true
    }

    companion object {
        /** Diamètre de l'anneau sur l'écran de référence 1024×600. */
        const val RING = 380
    }
}
