package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
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

/** Balance intégrée avec panneau d'historique des pesées et auto-enregistrement. */
class ScaleScreen(a: MainActivity, private val forcedIngredient: String? = null) : Screen(a) {
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
    private lateinit var historyScroll: ScrollView
    private lateinit var clearBtn: View

    private var lastWeightG = 0
    private var simulated = 0

    // Auto-record timing
    private var lastAutoLoggedWeight = 0
    private var stableSinceMs = 0L
    private var pendingAutoWeight = 0
    private var lastAutoRecordTimeMs = 0L

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(24), Ui.u(10), Ui.u(24), Ui.u(20))

        // Panneau gauche : grand affichage et commandes
        val left = Ui.vertical(c, Gravity.CENTER_HORIZONTAL)
        left.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))

        val row = Ui.horizontal(c, Gravity.BOTTOM).apply {
            clipChildren = false
            clipToPadding = false
        }
        val scaleIcon = Icon(c, Icon.Kind.SCALE, Ui.TEXT2)
        row.addView(scaleIcon, LinearLayout.LayoutParams(Ui.u(44), Ui.u(44)).apply {
            rightMargin = Ui.u(16)
            bottomMargin = Ui.u(36)
        })
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
            handleTare()
        }
        buttons.addView(tareBtn)

        val recordBtn = Ui.button(c, tr("Fixer"), Ui.Style.SECONDARY, Icon.Kind.PLUS, height = 72, textSize = 22f) {
            if (lastWeightG != 0) {
                lastAutoLoggedWeight = lastWeightG
                recordCurrentWeight()
                a.weighRecordedSound()
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

        // Panneau droit : historique des pesées
        val panel = Ui.vertical(c)
        panel.background = Ui.round(Ui.SURFACE, 26f)
        panel.setPadding(Ui.u(20), Ui.u(16), Ui.u(20), Ui.u(16))

        val pHeader = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        pHeader.addView(Icon(c, Icon.Kind.SCALE, Ui.COOL), LinearLayout.LayoutParams(Ui.u(22), Ui.u(22)).apply { rightMargin = Ui.u(10) })
        pHeader.addView(Ui.text(c, tr("Historique des pesées"), 18f, Ui.TEXT, Ui.MEDIUM))
        pHeader.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(0, 1, 1f))

        val clearTouch = Ui.vertical(c, Gravity.CENTER).apply {
            isClickable = true
            val trashIcon = Icon(c, Icon.Kind.TRASH, Ui.TEXT2)
            addView(trashIcon, LinearLayout.LayoutParams(Ui.u(20), Ui.u(20)))
            setOnClickListener {
                history.clear()
                lastAutoLoggedWeight = 0
                renderHistory()
            }
        }
        clearBtn = clearTouch
        pHeader.addView(clearTouch, LinearLayout.LayoutParams(Ui.u(44), Ui.u(44)))
        panel.addView(pHeader, Ui.lp(b = 10))

        historyList = Ui.vertical(c)
        val scroll = Ui.scroll(c, historyList).apply {
            isVerticalScrollBarEnabled = true
        }
        historyScroll = scroll
        panel.addView(scroll, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        root.addView(panel, LinearLayout.LayoutParams(Ui.u(340), Ui.MATCH))

        renderHistory()
        return root
    }

    private fun currentIngredient(): String? = forcedIngredient

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
            historyList.gravity = Gravity.CENTER
            clearBtn.visibility = View.INVISIBLE
            val empty = Ui.text(c, tr("Aucune pesée"), 15f, Ui.TEXT3, Ui.LIGHT).apply {
                gravity = Gravity.CENTER
            }
            historyList.addView(empty, Ui.lp(Ui.MATCH, Ui.WRAP))
            return
        }

        historyList.gravity = Gravity.BOTTOM
        clearBtn.visibility = View.VISIBLE

        // Entrées empilées chronologiquement
        for (i in history.indices) {
            val entry = history[i]
            val item = Ui.vertical(c)
            item.background = Ui.round(Ui.SURFACE2, 16f)
            item.setPadding(Ui.u(14), Ui.u(10), Ui.u(14), Ui.u(10))

            if (!entry.ingredient.isNullOrBlank()) {
                val banner = Ui.horizontal(c, Gravity.CENTER_VERTICAL).apply {
                    background = Ui.round(0x26FFC14D.toInt(), 10f)
                    setPadding(Ui.u(10), Ui.u(4), Ui.u(10), Ui.u(4))
                }
                banner.addView(Icon(c, Icon.Kind.POT, Ui.WARN), LinearLayout.LayoutParams(Ui.u(16), Ui.u(16)).apply { rightMargin = Ui.u(8) })
                val ingText = Ui.text(c, entry.ingredient, 13f, Ui.WARN, Ui.MEDIUM).apply {
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                banner.addView(ingText, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                item.addView(banner, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { bottomMargin = Ui.u(8) })
            }

            val r = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
            r.addView(Ui.text(c, entry.time, 15f, Ui.TEXT2, Ui.LIGHT))
            r.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(0, 1, 1f))
            r.addView(Ui.text(c, "${entry.grams} g", 19f, Ui.TEXT, Ui.MEDIUM))

            val del = Icon(c, Icon.Kind.CLOSE, Ui.TEXT3).apply {
                isClickable = true
                setOnClickListener {
                    history.removeAt(i)
                    renderHistory()
                }
            }
            r.addView(del, LinearLayout.LayoutParams(Ui.u(22), Ui.u(22)).apply { leftMargin = Ui.u(12) })
            item.addView(r)

            historyList.addView(item, Ui.lp(b = 8))
        }

        // Défilement automatique vers la dernière mesure en bas
        historyScroll.post {
            historyScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onSnapshot(s: Snapshot) {
        if (!s.hasSample) {
            value.text = "—"
            state.visibility = View.INVISIBLE
            lastWeightG = 0
            stableSinceMs = 0L
            pendingAutoWeight = 0
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

        // Enregistrement automatique après stabilisation > 5s
        if (s.scaleStable && s.weightG >= 10) {
            val now = System.currentTimeMillis()
            if (kotlin.math.abs(s.weightG - pendingAutoWeight) > 5) {
                pendingAutoWeight = s.weightG
                stableSinceMs = now
            } else if (stableSinceMs > 0L && now - stableSinceMs >= 5000L) {
                if (kotlin.math.abs(s.weightG - lastAutoLoggedWeight) > 10) {
                    lastAutoLoggedWeight = s.weightG
                    lastAutoRecordTimeMs = now
                    recordCurrentWeight()
                    a.weighRecordedSound()
                }
                stableSinceMs = 0L
            }
        } else {
            if (!s.scaleStable || s.weightG < 10) {
                stableSinceMs = 0L
                pendingAutoWeight = 0
            }
            if (kotlin.math.abs(s.weightG) < 5) {
                lastAutoLoggedWeight = 0
            }
        }
    }

    private fun handleTare() {
        val now = System.currentTimeMillis()
        if (lastAutoRecordTimeMs > 0L && now - lastAutoRecordTimeMs <= 5000L && history.isNotEmpty()) {
            history.removeAt(history.lastIndex)
            renderHistory()
        } else if (lastWeightG > 5 && lastWeightG != lastAutoLoggedWeight) {
            recordCurrentWeight()
            a.weighRecordedSound()
        }
        lastAutoLoggedWeight = 0
        pendingAutoWeight = 0
        stableSinceMs = 0L
        lastAutoRecordTimeMs = 0L
        a.machine.tare()
    }

    override fun onKnob(delta: Int): Boolean {
        historyScroll.smoothScrollBy(0, delta * Ui.u(64))
        return true
    }

    override fun onKnobPress(): Boolean {
        handleTare()
        return true
    }
}
