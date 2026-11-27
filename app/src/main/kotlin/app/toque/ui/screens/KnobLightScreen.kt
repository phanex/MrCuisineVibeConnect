package app.toque.ui.screens

import android.content.res.ColorStateList
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.ring.Rgb
import app.toque.core.ring.RingLook
import app.toque.ui.Icon
import app.toque.ui.KnobLight
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

/**
 * Lumière de la molette (Réglages › Molette et sons) : le code couleur, la luminosité de l'anneau
 * allumé et pendant la veille (jamais plus forte qu'allumé), et, quand le code couleur est coupé, une
 * couleur pour chacune. « Personnalisée » se compose LED par LED (rouge, verte, blanche) : les anneaux
 * ne rendent pas tous un mélange de la même façon.
 *
 * L'anneau montre le bloc entouré, « Allumée » ou « Pendant la veille ». Choisir une couleur la montre
 * au plus fort (à une faible luminosité, on la jugerait mal) ; choisir une luminosité montre le bloc
 * tel qu'il sera. L'anneau passe d'un choix au suivant sans repasser par autre chose.
 */
class KnobLightScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Lumière de la molette")
    private lateinit var codeHint: TextView
    private lateinit var cards: LinearLayout
    private lateinit var below: TextView
    private lateinit var adjust: LinearLayout
    private var lastWrite = 0L
    /** Le bloc entouré : celui que l'anneau montre. */
    private var selected = Block.ON
    /** L'anneau montre la couleur du bloc au plus fort (une couleur vient d'être choisie), et non sa vraie luminosité. */
    private var strongest = false

    private enum class Block { ON, STANDBY }

    override fun build(): View {
        val col = Ui.vertical(c)
        col.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(20))
        col.addView(Ui.switchRow(c, tr("Couleurs sur la molette"), a.prefs.knobColours) {
            a.prefs.knobColours = it
            changed()
        })
        codeHint = hint(tr("L'anneau de la molette montre l'état du robot : rouge pour un problème, jaune pendant une pause, vert quand c'est à vous, cyan pendant un programme, blanc sinon."))
        col.addView(codeHint, Ui.lp(t = 4))
        cards = Ui.horizontal(c, Gravity.TOP)
        col.addView(cards, Ui.lp(t = 12))
        below = hint("")
        col.addView(below, Ui.lp(t = 12))
        adjust = Ui.card(c, 24f)
        adjust.orientation = LinearLayout.HORIZONTAL
        adjust.gravity = Gravity.CENTER_VERTICAL
        col.addView(adjust, Ui.lp(t = 12))
        render()
        return col
    }

    override fun onShow() = sync()

    /** On quitte l'écran : l'anneau reprend ce qui est réglé pour « Allumée ». */
    override fun onHide() {
        KnobLight.hold(null)
    }

    private fun hint(s: String) = Ui.text(c, Ui.guillemets(s), 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }

    /** Les deux blocs, « Allumée » et « Pendant la veille », d'après les réglages du moment. */
    private fun render() {
        cards.removeAllViews()
        val s = a.prefs.ring
        val colours = !s.colourCode
        // Code couleur coupé : son explication laisse la place aux couleurs.
        codeHint.visibility = if (colours) View.GONE else View.VISIBLE
        val custom = colours && (if (selected == Block.ON) s.tint else s.standbyTint) == RingLook.Tint.CUSTOM
        renderAdjust(s, custom)
        below.visibility = if (custom) View.GONE else View.VISIBLE
        below.text = Ui.guillemets(tr("La molette montre le bloc entouré.") +
            if (colours) " " + tr("La molette n'a pas de LED bleue : ses couleurs mélangent le rouge, le vert et le blanc.") else "")

        val on = block(Block.ON, tr("Allumée"))
        cards.addView(on, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { rightMargin = Ui.u(18) })
        on.addView(label(tr("Luminosité")), Ui.lp(t = 12, b = 8))
        on.addView(levels(1, RingLook.MAX_LEVEL, s.onLevel) { level ->
            a.prefs.ringLevel = level
            // La veille n'est jamais plus lumineuse qu'allumé : elle suit quand on baisse.
            if (a.prefs.ringStandbyLevel > level) a.prefs.ringStandbyLevel = level
            show(Block.ON, strongest = false)
        })
        if (colours) {
            on.addView(label(tr("Couleur")), Ui.lp(t = 14, b = 8))
            on.addView(tints(s.tint) {
                // Première fois : la couleur personnalisée part de celle qu'on avait.
                if (it == RingLook.Tint.CUSTOM && a.prefs.ringCustom == null) a.prefs.ringCustom = s.onMix
                a.prefs.ringTint = it.key
                show(Block.ON, strongest = true)
            })
        }
        on.addView(note(Block.ON, colours, tr("Pendant le réglage, la molette est au plus fort. Ensuite, elle aura la luminosité choisie."), ""), Ui.lp(t = 12))

        val sleep = block(Block.STANDBY, tr("Pendant la veille"))
        cards.addView(sleep, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))
        sleep.addView(label(tr("Luminosité")), Ui.lp(t = 12, b = 8))
        sleep.addView(levels(0, s.onLevel, s.sleepLevel) { level ->
            a.prefs.ringStandbyLevel = level
            show(Block.STANDBY, strongest = false)
        })
        if (colours) {
            sleep.addView(label(tr("Couleur")), Ui.lp(t = 14, b = 8))
            sleep.addView(tints(s.standbyTint) {
                if (it == RingLook.Tint.CUSTOM && a.prefs.ringStandbyCustom == null) a.prefs.ringStandbyCustom = s.sleepMix
                a.prefs.ringStandbyTint = it.key
                show(Block.STANDBY, strongest = true)
            })
        }
        sleep.addView(note(Block.STANDBY, colours, tr("Pendant le réglage, la molette est au plus fort. En veille, elle aura la luminosité choisie."),
            tr("Jamais plus lumineuse qu'allumée.")), Ui.lp(t = 12))
    }

    /** Un bloc : entouré quand c'est lui que l'anneau montre ; le toucher le choisit. */
    private fun block(which: Block, title: String): LinearLayout {
        val on = which == selected
        val card = Ui.card(c, 24f)
        card.background = Ui.round(Ui.SURFACE, 24f, if (on) Ui.ACCENT else 0, if (on) 2.5f else 0f)
        card.isSelected = on
        card.contentDescription = title
        card.isClickable = true
        card.setOnClickListener { show(which, strongest = true) }
        card.addView(Ui.text(c, title, 21f, Ui.TEXT, Ui.MEDIUM))
        return card
    }

    /**
     * Le texte au bas d'un bloc, sur deux lignes pour que les blocs ne changent pas de taille : quand
     * l'anneau montre sa couleur au plus fort, [strong] le dit dans la couleur d'accent ; sinon [usual], en gris.
     */
    private fun note(which: Block, colours: Boolean, strong: String, usual: String): TextView {
        val loud = colours && strongest && which == selected
        return Ui.text(c, if (loud) Ui.guillemets(strong) else Ui.guillemets(usual), 16f, if (loud) Ui.ACCENT_INK else Ui.TEXT2, if (loud) Ui.MEDIUM else Ui.REGULAR).apply {
            setLineSpacing(0f, 1.15f)
            minLines = 2
        }
    }

    /**
     * Les curseurs de la couleur personnalisée du bloc entouré ([shown]) : une LED chacun (rouge, verte,
     * blanche). L'anneau suit le doigt, au plus fort.
     */
    private fun renderAdjust(s: RingLook.Settings, shown: Boolean) {
        adjust.removeAllViews()
        adjust.visibility = if (shown) View.VISIBLE else View.GONE
        if (!shown) return
        val standby = selected == Block.STANDBY
        val mix = if (standby) s.sleepMix else s.onMix
        val values = intArrayOf(mix.red, mix.green, mix.white)
        val head = Ui.vertical(c)
        head.addView(Ui.text(c, tr("Personnalisée"), 18f, Ui.TEXT, Ui.MEDIUM))
        head.addView(label(if (standby) tr("Pendant la veille") else tr("Allumée")), Ui.lp(t = 4))
        adjust.addView(head, LinearLayout.LayoutParams(Ui.u(200), Ui.WRAP))
        for ((i, name) in listOf(tr("Rouge"), tr("Vert"), tr("Blanc")).withIndex()) {
            val box = Ui.vertical(c)
            val value = Ui.text(c, "$name  ${values[i]}", 16f, Ui.TEXT2)
            val bar = SeekBar(c).apply {
                max = Rgb.MAX
                progress = values[i]
                contentDescription = "$name  ${values[i]}"
                progressTintList = ColorStateList.valueOf(Ui.ACCENT)
                thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
                progressBackgroundTintList = ColorStateList.valueOf(Ui.TEXT3)
            }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, v: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    values[i] = v
                    value.text = "$name  $v"
                    b.contentDescription = value.text
                    mixed(standby, values, last = false)
                }

                override fun onStartTrackingTouch(b: SeekBar) {}

                override fun onStopTrackingTouch(b: SeekBar) {
                    mixed(standby, values, last = true)
                    render()
                }
            })
            box.addView(value)
            box.addView(bar, Ui.lp(Ui.MATCH, Ui.u(44), t = 2))
            adjust.addView(box, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.u(14) })
        }
    }

    /**
     * Le mélange de la couleur personnalisée ([standby] : celle de la veille) vient de changer sous le
     * doigt. Pendant le glissé, l'anneau n'est écrit qu'à intervalles ([WRITE_GAP_MS]) : des écritures trop
     * rapprochées le rendent irrégulier. [last] : le doigt est levé. Un mélange tout éteint n'est pas gardé.
     */
    private fun mixed(standby: Boolean, v: IntArray, last: Boolean) {
        val mix = Rgb(v[0], v[1], v[2])
        if (!RingLook.usable(mix)) return
        val now = SystemClock.uptimeMillis()
        if (!last && now - lastWrite < WRITE_GAP_MS) return
        lastWrite = now
        if (standby) a.prefs.ringStandbyCustom = mix else a.prefs.ringCustom = mix
        strongest = true
        sync()
    }

    /** Un choix dans [block] : il devient le bloc entouré ; [strongest] : c'est une couleur, à voir au plus fort. */
    private fun show(block: Block, strongest: Boolean) {
        selected = block
        this.strongest = strongest
        changed()
    }

    /** Un réglage a changé : les blocs se redessinent (choix permis, couleurs, cadre) et l'anneau suit. */
    private fun changed() {
        render()
        sync()
    }

    /**
     * L'anneau d'après les réglages et le bloc entouré. Ce qui est réglé pour « Allumée » est ce qu'il
     * montrera hors de cet écran ; ici, il montre le bloc entouré : sa couleur au plus fort si on vient
     * de la choisir, sinon le bloc tel qu'il sera. Un seul changement sur l'anneau à chaque choix.
     */
    private fun sync() {
        val s = a.prefs.ring
        val max = strongest && !s.colourCode
        val held = when (selected) {
            Block.ON -> if (max) s.onMix else null
            Block.STANDBY -> if (max) s.sleepMix else s.sleep
        }
        // L'ordre évite un passage par une autre couleur : ce qui est tenu d'abord, « Allumée » se règle dessous.
        if (held != null) {
            KnobLight.hold(held)
            KnobLight.idle(s.on)
        } else {
            KnobLight.idle(s.on)
            KnobLight.hold(null)
        }
    }

    private fun label(s: String) = Ui.text(c, s, 16f, Ui.TEXT2)

    /** Niveaux de [from] à [to], celui de [selected] marqué ; 0 s'écrit « Éteinte ». */
    private fun levels(from: Int, to: Int, selected: Int, onPick: (Int) -> Unit): View {
        val row = Ui.horizontal(c)
        for (level in from..to) {
            val on = level == selected
            val name = if (level == 0) tr("Éteinte") else level.toString()
            val chip = Ui.text(c, name, 18f, if (on) Ui.ON_ACCENT else Ui.TEXT, Ui.MEDIUM).apply {
                gravity = Gravity.CENTER
                minWidth = Ui.u(52)
                if (level == 0) setPadding(Ui.u(16), 0, Ui.u(16), 0)
                background = Ui.pressable(if (on) Ui.round(Ui.ACCENT, 26f) else Ui.round(Ui.SURFACE2, 26f, Ui.STROKE, 1f), 26f)
                contentDescription = if (level == 0) name else tr("Niveau %d", level)
                isSelected = on
                isClickable = true
                setOnClickListener { onPick(level) }
            }
            row.addView(chip, LinearLayout.LayoutParams(Ui.WRAP, Ui.u(52)).apply { if (level > from) leftMargin = Ui.u(8) })
        }
        return row
    }

    /** Les couleurs que l'anneau sait faire puis « Personnalisée » (un crayon), [selected] marquée. */
    private fun tints(selected: RingLook.Tint, onPick: (RingLook.Tint) -> Unit): View {
        val row = Ui.horizontal(c)
        for (t in RingLook.Tint.values()) {
            val on = t == selected
            val custom = t == RingLook.Tint.CUSTOM
            val (name, colour) = look(t)
            val dot = FrameLayout(c).apply {
                background = Ui.pressable(Ui.round(colour, 24f, if (on) Ui.TEXT else Ui.STROKE, if (on) 3f else 1f), 24f)
                if (custom) addView(Icon(c, Icon.Kind.EDIT, if (on) Ui.TEXT else Ui.TEXT2), Ui.flp(Ui.u(22), Ui.u(22), Gravity.CENTER))
                else if (on) addView(Icon(c, Icon.Kind.CHECK, CHECK_INK), Ui.flp(Ui.u(22), Ui.u(22), Gravity.CENTER))
                contentDescription = name
                isSelected = on
                isClickable = true
                setOnClickListener { onPick(t) }
            }
            row.addView(dot, LinearLayout.LayoutParams(Ui.u(DOT), Ui.u(DOT)).apply { if (t.ordinal > 0) leftMargin = Ui.u(DOT_GAP) })
        }
        return row
    }

    /** Nom d'une couleur de l'anneau et la couleur qui la représente à l'écran. */
    private fun look(t: RingLook.Tint): Pair<String, Int> = when (t) {
        RingLook.Tint.WHITE -> tr("Blanc") to 0xFFF4F6F8.toInt()
        RingLook.Tint.RED -> tr("Rouge") to 0xFFFF453A.toInt()
        RingLook.Tint.ORANGE -> tr("Orange") to 0xFFFF9F0A.toInt()
        RingLook.Tint.YELLOW -> tr("Jaune") to 0xFFFFD60A.toInt()
        RingLook.Tint.GREEN -> tr("Vert") to 0xFF30D158.toInt()
        RingLook.Tint.CYAN -> tr("Cyan") to 0xFF40C8E0.toInt()
        RingLook.Tint.PINK -> tr("Rose") to 0xFFFF6FB5.toInt()
        RingLook.Tint.CUSTOM -> tr("Personnalisée") to Ui.SURFACE2
    }

    private companion object {
        /** La coche d'une couleur choisie : sombre, lisible sur toutes ces couleurs claires. */
        const val CHECK_INK = 0xFF10151C.toInt()
        /** Écart minimal entre deux écritures sur l'anneau pendant qu'un curseur glisse. */
        const val WRITE_GAP_MS = 400L
        /** Pastilles de couleur : huit tiennent dans une carte. */
        const val DOT = 48
        const val DOT_GAP = 7
    }
}
