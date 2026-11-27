package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.machine.JobKind
import app.toque.core.machine.JobSpec
import app.toque.core.machine.Limits
import app.toque.core.machine.Programs
import app.toque.core.machine.Snapshot
import app.toque.core.protocol.SmartProgram
import app.toque.core.robot.Feature
import app.toque.core.robot.RobotProfiles
import app.toque.ui.HoldButton
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

/**
 * Programmes automatiques : pétrir, vapeur, rissoler (ceux de l'appli d'origine), nettoyage (+ Turbo).
 * Seulement ceux du robot choisi : le Smart a en plus ses propres programmes (mixer, sous-vide…).
 * [openOnCleaning] : ouvert sur le nettoyage (fin de recette) ; le programme part comme d'habitude, par « Démarrer » ou la molette.
 */
class ProgramsScreen(a: MainActivity, openOnCleaning: Boolean = false) : Screen(a) {
    override val title = tr("Programmes")

    private class Prog(
        val feature: Feature, val icon: Icon.Kind, val name: String, val text: String,
        var seconds: Int, val min: Int, val max: Int, val step: Int,
        /** Température réglable (programmes du Smart à température exacte) : valeur, bornes ; 0 sinon. */
        var celsius: Int = 0, val minC: Int = 0, val maxC: Int = 0,
        val make: (Long, Int) -> JobSpec,
    ) {
        lateinit var value: TextView
        lateinit var unit: TextView
        var temp: TextView? = null
        lateinit var card: LinearLayout
        lateinit var start: View
        lateinit var minusIcon: Icon
        lateinit var plusIcon: Icon
    }

    private fun smart(f: Feature, icon: Icon.Kind, text: String, p: SmartProgram, default: Int, step: Int,
                      make: (Long, Int) -> JobSpec = { ms, c -> Programs.smartProgram(p, ms, f.label, celsius = c.takeIf { it > 0 }) }) =
        Prog(f, icon, f.label, text, default, p.minS, p.maxS, step,
            celsius = if (p.minCelsius < p.maxCelsius) p.celsius else 0, minC = p.minCelsius, maxC = p.maxCelsius, make = make)

    private val all = listOf(
        Prog(Feature.KNEAD, Icon.Kind.KNEAD, JobKind.KNEAD.label, tr("Pâtes à pain, pizza, brioche. Alterne sens inverse et normal à vitesse 4."),
            45, 30, 120, 5) { ms, _ -> Programs.knead(ms) },
        Prog(Feature.STEAM, Icon.Kind.STEAM, JobKind.STEAM.label, tr("Mettez au moins 500 g d'eau dans le bol. 120 °C, la lame reste immobile."),
            1200, 300, 3600, 60) { ms, _ -> Programs.steam(ms) },
        Prog(Feature.TOAST, Icon.Kind.FLAME, JobKind.TOAST.label, tr("Dorer oignons, lardons, viandes à 130 °C, avec un brassage doux en sens inverse."),
            420, 180, 840, 30) { ms, _ -> Programs.toast(ms) },
        Prog(Feature.YOGURT, Icon.Kind.TIMER, Feature.YOGURT.label,
            tr("Lait tiède ensemencé, couvercle fermé. Garde %d °C sans remuer : la lame reste immobile.", Programs.YOGURT_C),
            8 * 3600, 6 * 3600, Limits.MAX_MS_FERMENT.toInt() / 1000, 15 * 60) { ms, _ -> Programs.yogurt(ms) },
        Prog(Feature.CLEANING, Icon.Kind.DROP, tr("Nettoyage"), tr("1 L d'eau chaude et une goutte de liquide vaisselle. La vitesse monte de 4 à 10."),
            90, 60, 180, 15) { ms, _ -> Programs.clean(ms) },
        // Programmes du Monsieur Cuisine Smart, avec les trames de son appli d'origine.
        smart(Feature.MIXER, Icon.Kind.SPEED, tr("Purées, soupes mixées, compotes. Vitesse 8, sans chauffe."), SmartProgram.MIXER, 30, 10),
        smart(Feature.SMOOTHIE, Icon.Kind.DROP, tr("Fruits et glace pilée. Vitesse 10, sans chauffe."), SmartProgram.SMOOTHIE, 60, 10),
        smart(Feature.EGGS, Icon.Kind.TIMER, tr("Œufs dans le panier vapeur, eau dans le bol. La durée fait la cuisson."), SmartProgram.EGGS, 8 * 60, 60),
        smart(Feature.SOUS_VIDE, Icon.Kind.THERMO, tr("Bain d'eau à température précise, sachets fermés. Brassage doux."), SmartProgram.SOUS_VIDE, 3600, 15 * 60),
        smart(Feature.SLOW_COOK, Icon.Kind.POT, tr("Cuisson lente : brasse 30 s toutes les 2 min 30 pendant 10 min, puis sans remuer."),
            SmartProgram.MIJOTER_BRASSE, 2 * 3600, 15 * 60) { ms, c -> Programs.smartMijoter(ms, Feature.SLOW_COOK.label, c.takeIf { it > 0 }) },
        smart(Feature.FERMENT, Icon.Kind.TIMER, tr("Yaourts, pâtes levées : brasse 5 min, puis garde la température."),
            SmartProgram.FERMENTER_BRASSE, 8 * 3600, 30 * 60) { ms, c -> Programs.smartFermenter(ms, Feature.FERMENT.label, c.takeIf { it > 0 }) },
        smart(Feature.RICE, Icon.Kind.POT, tr("Riz à 100 °C en remuant doucement en sens inverse."), SmartProgram.RICE, 30 * 60, 60),
        smart(Feature.KETTLE, Icon.Kind.THERMO, tr("Chauffe l'eau à 100 °C en remuant doucement."), SmartProgram.KETTLE, 10 * 60, 60),
        Prog(Feature.PREWASH, Icon.Kind.DROP, Feature.PREWASH.label, tr("Rinçage rapide du bol à l'eau : 1 min 30 à 37 °C."),
            90, 90, 90, 1) { _, _ -> Programs.smartPrelavage(Feature.PREWASH.label) },
        Prog(Feature.LONG_CLEAN, Icon.Kind.DROP, Feature.LONG_CLEAN.label, tr("Nettoyage de l'appli d'origine : chauffe l'eau à 40 °C, puis brasse fort."),
            app.toque.core.protocol.NETTOYAGE_MAX_S, app.toque.core.protocol.NETTOYAGE_MAX_S, app.toque.core.protocol.NETTOYAGE_MAX_S, 1) { _, _ ->
            Programs.smartNettoyageLong(Feature.LONG_CLEAN.label)
        },
    )
    private val progs = all.filter { RobotProfiles.active.capabilities.has(it.feature) }.let { shown ->
        // Réglages › Cuisson › Programmes affichés (#130) ; le nettoyage reste là quand on l'ouvre pour lui.
        val hidden = a.prefs.hiddenPrograms
        shown.filter { it.feature.name !in hidden || (openOnCleaning && it.feature == Feature.CLEANING) }.ifEmpty { shown }
    }
    private val cleaning = progs.indexOfFirst { it.feature == Feature.CLEANING }
    private var selected = if (openOnCleaning && cleaning >= 0) cleaning else 0
    private var focusedIndex = selected
    private var editing = false
    private var lastStartMs = 0L
    private lateinit var turbo: HoldButton

    companion object {
        /** Programmes de l'écran, dans son ordre, pour le robot choisi (Réglages › Cuisson). */
        private val ORDER = listOf(
            Feature.KNEAD, Feature.STEAM, Feature.TOAST, Feature.YOGURT, Feature.CLEANING, Feature.MIXER,
            Feature.SMOOTHIE, Feature.EGGS, Feature.SOUS_VIDE, Feature.SLOW_COOK, Feature.FERMENT, Feature.RICE,
            Feature.KETTLE, Feature.PREWASH, Feature.LONG_CLEAN,
        )

        fun features(): List<Feature> = ORDER.filter { RobotProfiles.active.capabilities.has(it) }
    }

    override fun build(): View {
        val root = Ui.vertical(c)
        root.setPadding(Ui.u(24), Ui.u(8), Ui.u(24), Ui.u(20))
        // 4 cartes par ligne. Jusqu'à 4 programmes, une ligne sur toute la hauteur ; au-delà (le Connect depuis
        // le yaourt, le Smart), des lignes qui défilent.
        val many = progs.size > 4
        val rows = Ui.vertical(c)
        var row = Ui.horizontal(c, Gravity.TOP)
        // La largeur d'une ligne (l'écran moins les marges), pour mesurer ses cartes avant l'affichage.
        val rowWidth = c.resources.displayMetrics.widthPixels - Ui.u(48)
        // Hauteur d'une ligne : celle de sa carte la plus haute, 400 au moins. Une carte grandit avec un réglage
        // de température (sous-vide, mijoter, fermenter), avec le bouton « Recette Lidl » du nettoyage, ou avec
        // un texte plus long dans une autre langue : à hauteur fixe, son bouton « Démarrer » était coupé.
        fun rowHeight(line: LinearLayout): Int {
            line.measure(View.MeasureSpec.makeMeasureSpec(rowWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            return maxOf(Ui.u(400), line.measuredHeight)
        }
        for ((i, p) in progs.withIndex()) {
            if (i > 0 && i % 4 == 0) {
                rows.addView(row, Ui.lp(Ui.MATCH, rowHeight(row), b = 16))
                row = Ui.horizontal(c, Gravity.TOP)
            }
            val card = Ui.vertical(c)
            card.setPadding(Ui.u(22), Ui.u(20), Ui.u(22), Ui.u(20))
            p.card = card
            val badge = FrameLayout(c)
            badge.background = Ui.round(Ui.ACCENT_DIM, 28f)
            badge.addView(Icon(c, p.icon, Ui.ACCENT_INK), Ui.flp(Ui.u(32), Ui.u(32), Gravity.CENTER))
            val head = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
            head.addView(badge, LinearLayout.LayoutParams(Ui.u(56), Ui.u(56)))
            if (i == cleaning) rinseRecipe()?.let { r ->
                // La recette de rinçage du catalogue Lidl, quand elle est synchronisée : à droite de la pastille,
                // pour ne pas allonger la carte (sous le texte, elle poussait « Démarrer » hors de la carte).
                head.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
                // Sans icône : le nom doit tenir à côté de la pastille dans toutes les langues.
                head.addView(Ui.button(c, tr("Recette Lidl"), Ui.Style.GHOST, height = 44, textSize = 14f) {
                    a.app.recipes.get(r)?.let { a.push(RecipeScreen(a, it)) }
                }.apply { setPadding(Ui.u(14), 0, Ui.u(14), 0) }, Ui.lp(Ui.WRAP, Ui.WRAP, l = 8))
            }
            card.addView(head, Ui.lp())
            card.addView(Ui.ellipsize(Ui.text(c, p.name, 26f, Ui.TEXT, Ui.MEDIUM)), Ui.lp(t = 14))
            card.addView(Ui.text(c, p.text, 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }, Ui.lp(t = 6))
            card.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
            val dur = Ui.horizontal(c)
            val minusBtn = Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.MINUS, height = 56) { selected = i; focusedIndex = i; editing = true; bump(p, -1) }
            p.minusIcon = minusBtn.getChildAt(0) as Icon
            dur.addView(minusBtn, Ui.lp(Ui.u(56), Ui.WRAP))
            // Le nombre en grand sur une ligne, l'unité en dessous : les cartes sont étroites à 4 par ligne.
            val gauge = Ui.vertical(c, Gravity.CENTER_HORIZONTAL)
            p.value = Ui.fitText(c, 38f, 24f, Ui.TEXT, Ui.LIGHT).apply { gravity = Gravity.CENTER }
            gauge.addView(p.value, Ui.lp(Ui.MATCH, Ui.u(45)))
            p.unit = Ui.text(c, "", 14f, Ui.TEXT2).apply { gravity = Gravity.CENTER }
            gauge.addView(p.unit, Ui.lp(Ui.MATCH, Ui.WRAP, t = 1))
            dur.addView(gauge, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            val plusBtn = Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.PLUS, height = 56) { selected = i; focusedIndex = i; editing = true; bump(p, 1) }
            p.plusIcon = plusBtn.getChildAt(0) as Icon
            dur.addView(plusBtn, Ui.lp(Ui.u(56), Ui.WRAP))
            card.addView(dur, Ui.lp(t = 10))
            if (p.maxC > p.minC) {
                // Température exacte (sous-vide, mijoter, fermenter), dans les bornes de l'appli d'origine.
                val t = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
                t.addView(Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.MINUS, height = 48) { selected = i; focusedIndex = i; editing = true; bumpTemp(p, -1) }
                    .apply { setPadding(0, 0, 0, 0) }, Ui.lp(Ui.u(48), Ui.WRAP))
                p.temp = Ui.text(c, "", 20f, Ui.TEXT).apply { gravity = Gravity.CENTER }
                t.addView(p.temp, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                t.addView(Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.PLUS, height = 48) { selected = i; focusedIndex = i; editing = true; bumpTemp(p, 1) }
                    .apply { setPadding(0, 0, 0, 0) }, Ui.lp(Ui.u(48), Ui.WRAP))
                card.addView(t, Ui.lp(t = 8))
            }
            p.start = Ui.button(c, tr("Démarrer"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 66) {
                selected = i
                a.startJob(p.make(p.seconds * 1000L, p.celsius))
            }
            card.addView(p.start, Ui.lp(t = 14))
            card.isClickable = true
            card.setOnClickListener { selected = i; focusedIndex = i; editing = true; refresh() }
            row.addView(card, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (i % 4 != 3 && (many || i < progs.size - 1)) rightMargin = Ui.u(16) })
        }
        // Dernière ligne incomplète : des places vides gardent la largeur des cartes.
        if (many && progs.size % 4 != 0) for (k in progs.size % 4 until 4)
            row.addView(View(c), LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (k < 3) rightMargin = Ui.u(16) })
        if (many) {
            rows.addView(row, Ui.lp(Ui.MATCH, rowHeight(row)))
            val scroll = Ui.scroll(c, rows)
            root.addView(scroll, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
            // Ouvert sur un programme d'une ligne plus bas (le nettoyage, en fin de recette) : sa ligne est amenée
            // à l'écran. Sinon la molette lancerait un programme qu'on ne voit pas.
            if (selected >= 4) scroll.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                    if (b <= t) return
                    v.removeOnLayoutChangeListener(this)
                    scroll.scrollTo(0, rows.getChildAt(selected / 4).top)
                }
            })
        } else {
            root.addView(row, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        }

        val bottom = Ui.horizontal(c)
        bottom.addView(Ui.text(c, tr("Turbo : impulsions à vitesse maximale, sans chauffe, 3 s maximum par appui."), 17f, Ui.TEXT2),
            LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        turbo = HoldButton(c, tr("Maintenir pour Turbo"), onDown = {
            a.turboDown(app.toque.core.machine.Accessory.BLADE)
        }, onUp = { a.machine.turboUp() })
        bottom.addView(turbo, Ui.lp(Ui.WRAP, Ui.u(64)))
        root.addView(bottom, Ui.lp(t = 16))
        refresh()
        return root
    }

    private fun rinseRecipe(): String? =
        a.app.recipes.index.entries.firstOrNull { Programs.isRinseRecipe(it.title) }?.id

    private fun bump(p: Prog, dir: Int) {
        p.seconds = (p.seconds + dir * p.step).coerceIn(p.min, p.max)
        refresh()
    }

    private fun bumpTemp(p: Prog, dir: Int) {
        p.celsius = (p.celsius + dir).coerceIn(p.minC, p.maxC)
        refresh()
    }

    private fun refresh() {
        for ((i, p) in progs.withIndex()) {
            val s = p.seconds
            p.value.text = when {
                s < 60 -> s.toString()
                s >= 3600 -> String.format("%d:%02d", s / 3600, s / 60 % 60)
                s % 60 == 0 -> (s / 60).toString()
                else -> String.format("%d:%02d", s / 60, s % 60)
            }
            p.unit.text = if (s < 60) "s" else if (s >= 3600) "h" else "min"
            p.temp?.text = "${p.celsius} °C"
            val isFocused = (i == focusedIndex)
            val isSelected = (i == selected)
            val isEditing = isSelected && editing
            val (strokeColor, strokeWidth) = when {
                isEditing -> Ui.ACCENT_INK to 2.5f
                isFocused -> Ui.TEXT3 to 2.5f
                isSelected -> Ui.ACCENT_INK to 1.5f
                else -> Ui.STROKE to 1f
            }
            p.card.background = Ui.round(if (isEditing) Ui.SURFACE2 else Ui.SURFACE, 26f, strokeColor, strokeWidth)
            p.value.setTextColor(if (isEditing) Ui.ACCENT_INK else Ui.TEXT)
            p.unit.setTextColor(if (isEditing) Ui.ACCENT_INK else Ui.TEXT2)
            val btnFg = if (isEditing) Ui.ACCENT_INK else Ui.TEXT
            p.minusIcon.color = btnFg
            p.plusIcon.color = btnFg
        }
    }

    override fun onSnapshot(s: Snapshot) {
        for (p in progs) Ui.setButtonEnabled(p.start, !s.busy)
        turbo.isEnabled = !s.busy || s.turboActive
        turbo.alpha = if (turbo.isEnabled) 1f else 0.35f
    }

    override fun onKnob(delta: Int): Boolean {
        if (editing) {
            bump(progs[selected], delta)
        } else {
            val next = (focusedIndex + delta).coerceIn(0, progs.size - 1)
            if (next != focusedIndex) {
                focusedIndex = next
                refresh()
            }
        }
        return true
    }

    private fun launchSelected() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastStartMs < 600L) return
        lastStartMs = now
        editing = false
        val p = progs[selected]
        a.startJob(p.make(p.seconds * 1000L, p.celsius))
        refresh()
    }

    override fun onKnobPress(): Boolean {
        val s = a.snapshot
        if (s.busy) {
            a.machine.pause { a.refusal(it) }
            editing = false
            refresh()
            return true
        }
        if (editing) {
            launchSelected()
        } else {
            selected = focusedIndex
            editing = true
            refresh()
        }
        return true
    }

    fun onKnobDoubleClick(): Boolean {
        if (!a.snapshot.busy) {
            selected = focusedIndex
            launchSelected()
            return true
        }
        return false
    }

    fun onKnobLongPress(): Boolean {
        if (editing) {
            editing = false
            refresh()
            return true
        }
        a.back()
        return true
    }
}
