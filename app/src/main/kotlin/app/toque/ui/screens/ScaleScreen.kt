package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.Prefs
import app.toque.core.i18n.tr
import app.toque.core.machine.Snapshot
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Balance intégrée avec panneau d'historique des pesées en mémoire. */
class ScaleScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Balance")
    override val keepAwake: Boolean get() = true

    data class WeightEntry(
        val time: String,
        val grams: Int,
        val ingredient: String? = null
    )

    companion object {
        val history = mutableListOf<WeightEntry>()
    }

    private lateinit var value: TextView
    private lateinit var unit: TextView
    private lateinit var state: LinearLayout
    private lateinit var historyList: LinearLayout
    private lateinit var countBadge: TextView
    private lateinit var totalValue: TextView
    private lateinit var clearBtn: View

    private var lastWeightG = 0
    private var simulated = 0

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(24), Ui.u(10), Ui.u(24), Ui.u(20))

        // --- Ліва частина : Велике табло та органи керування ---
        val left = Ui.vertical(c, Gravity.CENTER_HORIZONTAL)
        left.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))

        val row = Ui.horizontal(c, Gravity.BOTTOM).apply {
            clipChildren = false
            clipToPadding = false
        }
        value = Ui.text(c, "—", 140f, Ui.TEXT, Ui.THIN).apply {
            includeFontPadding = false
        }
        row.addView(value)
        unit = Ui.text(c, "g", 48f, Ui.TEXT2, Ui.THIN).apply {
            includeFontPadding = false
        }
        row.addView(unit, Ui.lp(Ui.WRAP, Ui.WRAP, l = 10, b = 18))
        left.addView(row, Ui.lp(Ui.WRAP, Ui.WRAP))

        state = Ui.chip(c, "", Icon.Kind.SCALE, Ui.COOL)
        left.addView(state, Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))

        left.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))

        val buttons = Ui.horizontal(c, Gravity.CENTER)
        val tareBtn = Ui.button(c, tr("Tare (remise à zéro)"), Ui.Style.PRIMARY, Icon.Kind.SYNC, height = 72, textSize = 22f) {
            if (lastWeightG > 5) {
                recordCurrentWeight()
            }
            a.machine.tare()
        }
        buttons.addView(tareBtn)

        val recordBtn = Ui.button(c, tr("Fixer"), Ui.Style.SECONDARY, Icon.Kind.PLUS, height = 72, textSize = 22f) {
            if (lastWeightG != 0) {
                recordCurrentWeight()
            }
        }
        buttons.addView(recordBtn, Ui.lp(Ui.WRAP, Ui.WRAP, l = 14))

        if (a.prefs.mode == Prefs.Mode.DEMO) {
            buttons.addView(Ui.spacer(c, w = 24))
            for (g in intArrayOf(100, 250)) {
                buttons.addView(Ui.button(c, "+$g g", Ui.Style.GHOST, height = 64) {
                    simulated += g
                    a.machine.simulate(weight = simulated)
                }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
            }
            buttons.addView(Ui.button(c, tr("Vider"), Ui.Style.GHOST, height = 64) {
                simulated = 0
                a.machine.simulate(weight = 0)
            })
        }
        left.addView(buttons)

        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { rightMargin = Ui.u(20) })

        // --- Права частина : Панель історії зважувань ---
        val panel = Ui.vertical(c)
        panel.background = Ui.round(Ui.SURFACE, 26f)
        panel.setPadding(Ui.u(20), Ui.u(18), Ui.u(20), Ui.u(16))

        val pHeader = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        pHeader.addView(Icon(c, Icon.Kind.SCALE, Ui.COOL), LinearLayout.LayoutParams(Ui.u(22), Ui.u(22)).apply { rightMargin = Ui.u(10) })
        pHeader.addView(Ui.text(c, tr("Historique des pesées"), 18f, Ui.TEXT, Ui.MEDIUM))
        pHeader.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(0, 1, 1f))
        countBadge = Ui.text(c, "", 15f, Ui.TEXT2, Ui.LIGHT)
        pHeader.addView(countBadge)
        panel.addView(pHeader, Ui.lp(b = 12))

        historyList = Ui.vertical(c)
        val scroll = Ui.scroll(c, historyList)
        panel.addView(scroll, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        val pFooter = Ui.vertical(c)
        val divider = View(c).apply { background = Ui.round(Ui.STROKE, 1f) }
        pFooter.addView(divider, LinearLayout.LayoutParams(Ui.MATCH, Ui.u(1)).apply { bottomMargin = Ui.u(10) })

        val totalRow = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        totalRow.addView(Ui.text(c, tr("Total pesé :"), 15f, Ui.TEXT2))
        totalRow.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(0, 1, 1f))
        totalValue = Ui.text(c, "0 g", 18f, Ui.TEXT, Ui.MEDIUM)
        totalRow.addView(totalValue)
        pFooter.addView(totalRow, Ui.lp(b = 10))

        clearBtn = Ui.button(c, tr("Effacer"), Ui.Style.GHOST, Icon.Kind.TRASH, height = 48, textSize = 16f) {
            history.clear()
            renderHistory()
        }
        pFooter.addView(clearBtn, Ui.lp(Ui.MATCH, Ui.WRAP))
        panel.addView(pFooter, Ui.lp(t = 8))

        root.addView(panel, LinearLayout.LayoutParams(Ui.u(340), Ui.MATCH))

        renderHistory()
        return root
    }

    private fun currentIngredient(): String? {
        if (a.prefs.guidedRecipe.isBlank()) return null
        val recipe = a.app.recipes.getAdjusted(a.prefs.guidedRecipe) ?: return null
        val step = recipe.steps.getOrNull(a.prefs.guidedStep) ?: return null
        val raw = step.weigh?.label ?: step.text
        val trimmed = raw.trim().replace(Regex("\\s+"), " ")
        return if (trimmed.length > 32) trimmed.take(30) + "…" else trimmed
    }

    private fun recordCurrentWeight() {
        if (lastWeightG == 0) return
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val ing = currentIngredient()
        history.add(WeightEntry(time, lastWeightG, ing))
        renderHistory()
    }

    private fun renderHistory() {
        historyList.removeAllViews()
        if (history.isEmpty()) {
            val empty = Ui.text(c, tr("Aucune pesée"), 15f, Ui.TEXT3, Ui.LIGHT).apply {
                gravity = Gravity.CENTER
            }
            historyList.addView(empty, Ui.lp(Ui.MATCH, Ui.WRAP, t = 40))
            countBadge.text = ""
            totalValue.text = "0 g"
            clearBtn.visibility = View.INVISIBLE
            return
        }

        clearBtn.visibility = View.VISIBLE
        countBadge.text = "${history.size}"
        val total = history.sumOf { it.grams }
        totalValue.text = "$total g"

        // Нові записи зверху
        for (i in history.indices.reversed()) {
            val entry = history[i]
            val item = Ui.vertical(c)
            item.setPadding(0, Ui.u(8), 0, Ui.u(8))

            if (!entry.ingredient.isNullOrBlank()) {
                val chip = Ui.chip(c, entry.ingredient, Icon.Kind.POT, Ui.WARN, 0x26FFC14D.toInt(), 13f)
                item.addView(chip, Ui.lp(Ui.WRAP, Ui.WRAP, b = 6))
            }

            val r = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
            r.addView(Ui.text(c, entry.time, 16f, Ui.TEXT2, Ui.LIGHT))
            r.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(0, 1, 1f))
            r.addView(Ui.text(c, "${entry.grams} g", 19f, Ui.TEXT, Ui.MEDIUM))

            val del = Icon(c, Icon.Kind.CLOSE, Ui.TEXT3).apply {
                isClickable = true
                setOnClickListener {
                    history.removeAt(i)
                    renderHistory()
                }
            }
            r.addView(del, LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)).apply { leftMargin = Ui.u(12) })
            item.addView(r)

            val sep = View(c).apply { background = Ui.round(0x1AFFFFFF.toInt(), 1f) }
            item.addView(sep, LinearLayout.LayoutParams(Ui.MATCH, Ui.u(1)).apply { topMargin = Ui.u(8) })

            historyList.addView(item)
        }
    }

    override fun onSnapshot(s: Snapshot) {
        if (!s.hasSample) {
            value.text = "—"
            state.visibility = View.INVISIBLE
            lastWeightG = 0
            return
        }
        state.visibility = View.VISIBLE
        lastWeightG = s.weightG
        value.text = s.weightG.toString()

        val (txt, col, ic) = when {
            s.scaleState == "overload" -> Triple(tr("Surcharge"), Ui.DANGER, Icon.Kind.WARNING)
            s.scaleStable -> Triple(tr("Stable"), Ui.OK, Icon.Kind.CHECK)
            else -> Triple(tr("Mesure…"), Ui.COOL, Icon.Kind.SCALE)
        }
        val iconView = state.getChildAt(0) as Icon
        iconView.set(ic)
        iconView.color = col
        val textView = Ui.chipText(state)
        textView.text = txt
        textView.setTextColor(col)
    }

    override fun onKnobPress(): Boolean {
        if (lastWeightG > 5) {
            recordCurrentWeight()
        }
        a.machine.tare()
        return true
    }
}
