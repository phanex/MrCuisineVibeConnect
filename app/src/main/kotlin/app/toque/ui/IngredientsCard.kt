package app.toque.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.Portions
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.unlistedWeighings

/**
 * Liste des ingrédients par-dessus une recette guidée, avec les quantités déjà adaptées (nombre de
 * personnes, corrections). Se ferme par « Fermer », le voile ou l'appui sur la molette ; la molette
 * fait défiler.
 */
object IngredientsCard {
    /** La recette a quelque chose à montrer dans cette liste. */
    fun has(r: Recipe): Boolean = r.allIngredients.isNotEmpty() || r.steps.any { it.weigh != null }

    fun show(a: MainActivity, r: Recipe) {
        val card = Ui.card(a, 28f, Ui.SURFACE2)
        card.setPadding(Ui.u(32), Ui.u(24), Ui.u(32), Ui.u(22))
        val head = Ui.horizontal(a)
        head.addView(Ui.text(a, tr("Ingrédients"), 26f, Ui.TEXT, Ui.MEDIUM), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        if (r.servings > 0) head.addView(Ui.text(a, tr("Quantités pour %s", Portions.label(r.servings, r.servingUnit)), 17f, Ui.TEXT2))
        card.addView(head)

        val list = Ui.vertical(a)
        var first = true
        for (g in r.ingredients) {
            if (g.title.isNotBlank()) list.addView(Ui.text(a, g.title, 17f, Ui.ACCENT, Ui.MEDIUM), Ui.lp(t = if (first) 4 else 14, b = 2))
            for (i in g.items) {
                if (!first) list.addView(Ui.divider(a))
                list.addView(row(a, quantity(i), name(i), i.originalQty.isNotBlank(), 22f, 19f, Ui.TEXT))
                first = false
            }
        }
        // Pesées des étapes absentes de la liste (souvent l'eau).
        val unlisted = r.unlistedWeighings().mapNotNull { (si, n) -> r.steps.getOrNull(si)?.weigh?.let { n to it } }
        if (unlisted.isNotEmpty()) {
            list.addView(Ui.text(a, tr("Aussi dans les étapes"), 17f, Ui.ACCENT, Ui.MEDIUM), Ui.lp(t = 14, b = 2))
            for ((n, w) in unlisted) list.addView(row(a, "${w.grams} g", n, w.originalGrams > 0, 22f, 19f, Ui.TEXT))
        }
        val scroll = MaxHeightScroll(a, Ui.u(600) - a.machineBarSpace() - Ui.u(230))
        scroll.isVerticalScrollBarEnabled = true
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(list)
        card.addView(scroll, Ui.lp(t = 10))

        val buttons = Ui.horizontal(a, Gravity.END or Gravity.CENTER_VERTICAL)
        buttons.addView(Ui.button(a, tr("Fermer"), Ui.Style.SECONDARY, height = 60, textSize = 19f) { a.closeOverlay() }, Ui.lp(Ui.u(170), Ui.WRAP))
        card.addView(buttons, Ui.lp(t = 14))
        a.showOverlay(card, 660, spareMachineBar = true)
        a.overlayKnob = MainActivity.OverlayKnob({ d -> scroll.smoothScrollBy(0, d * Ui.u(120)) }, { a.closeOverlay() })
    }

    fun quantity(i: Ingredient): String = listOf(i.qty.trim(), i.unit.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    fun name(i: Ingredient): String = if (i.note.isBlank()) i.name.trim() else i.name.trim() + ", " + i.note.trim()

    /** Quantité à gauche, dans une colonne fixe (en orange si elle a été adaptée à la main), nom à droite. */
    fun row(c: Context, qty: String, name: String, changed: Boolean, qtySize: Float, nameSize: Float, nameColor: Int, qtyWidth: Int = 120): View {
        val r = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        r.minimumHeight = Ui.u(48)
        r.setPadding(0, Ui.u(6), 0, Ui.u(6))
        val q: TextView = Ui.fitText(c, qtySize, qtySize * 0.7f, if (changed) Ui.ACCENT else Ui.TEXT, Ui.LIGHT)
        q.text = qty
        r.addView(q, LinearLayout.LayoutParams(Ui.u(qtyWidth), Ui.WRAP).apply { rightMargin = Ui.u(14) })
        r.addView(Ui.ellipsize(Ui.text(c, name, nameSize, nameColor).apply { setLineSpacing(0f, 1.08f) }, 2), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        return r
    }
}

/** Zone qui défile, pas plus haute que [maxHeight] : la carte garde la taille de son contenu en dessous. */
class MaxHeightScroll(c: Context, private val maxHeight: Int) : ScrollView(c) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxOf(Ui.u(120), maxHeight), MeasureSpec.AT_MOST))
    }
}
