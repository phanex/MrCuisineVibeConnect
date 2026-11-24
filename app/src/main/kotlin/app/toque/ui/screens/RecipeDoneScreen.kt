package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.toque.core.i18n.tr
import app.toque.core.recipes.StepDurations
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

/**
 * Fin d'une recette guidée : « Bon appétit ! », puis ce qui vient ensuite. Le nettoyage ouvre
 * seulement l'écran Programmes sur Nettoyage (le programme ne part que sur un geste de la personne,
 * comme d'habitude) ; « Ajouter une note » ouvre la note de la recette ; « Terminer » revient en arrière.
 * [seconds] : durée de la recette à l'écran (0 : inconnue, reprise après un redémarrage).
 */
class RecipeDoneScreen(a: MainActivity, private val recipeId: String, override val title: String, private val seconds: Int) : Screen(a) {
    private val actions = ArrayList<Pair<View, () -> Unit>>()
    /** Choix de la molette : « Terminer » d'abord (l'appui qui a fini la recette ne lance rien d'autre). */
    private var selected = 0

    override fun build(): View {
        val root = Ui.vertical(c, Gravity.CENTER_HORIZONTAL)
        root.setPadding(Ui.u(24), Ui.u(10), Ui.u(24), Ui.u(18))
        val badge = FrameLayout(c)
        // Vert du thème, assombri par la transparence (sombre ou clair).
        badge.background = Ui.round((Ui.OK and 0x00FFFFFF) or 0x33000000, 46f)
        badge.addView(Icon(c, Icon.Kind.CHECK, Ui.OK), Ui.flp(Ui.u(48), Ui.u(48), Gravity.CENTER))
        root.addView(badge, LinearLayout.LayoutParams(Ui.u(92), Ui.u(92)))
        root.addView(Ui.text(c, tr("Bon appétit !"), 44f, Ui.TEXT, Ui.LIGHT), Ui.lp(Ui.WRAP, Ui.WRAP, t = 14))
        val sub = if (seconds >= 60) tr("Recette terminée en %s", StepDurations.label(seconds / 60 * 60)) else tr("Recette terminée")
        root.addView(Ui.text(c, sub, 19f, Ui.TEXT2), Ui.lp(Ui.WRAP, Ui.WRAP, t = 8))

        val cards = Ui.horizontal(c, Gravity.CENTER)
        val clean = card(Icon.Kind.DROP, tr("Nettoyer le bol"), tr("Ouvre le programme Nettoyage : 1 L d'eau chaude et une goutte de liquide vaisselle.")) {
            a.replace(ProgramsScreen(a, openOnCleaning = true))
        }
        cards.addView(clean, LinearLayout.LayoutParams(Ui.u(380), Ui.MATCH).apply { rightMargin = Ui.u(20) })
        val note = a.app.recipes.adjustmentsOf(recipeId).note
        val canNote = a.app.recipes.get(recipeId) != null
        val noteCard = card(Icon.Kind.EDIT, if (note.isBlank()) tr("Ajouter une note") else tr("Modifier ma note"),
            if (note.isBlank()) tr("Pour la prochaine fois : moins de sel, plus de crème…") else note) { openNote() }
        if (canNote) cards.addView(noteCard, LinearLayout.LayoutParams(Ui.u(380), Ui.MATCH))
        root.addView(cards, Ui.lp(Ui.MATCH, Ui.WRAP, t = 30))

        root.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
        val finish = Ui.button(c, tr("Terminer"), Ui.Style.SECONDARY, Icon.Kind.CHECK, height = 64) { a.back() }
        root.addView(finish, Ui.lp(Ui.u(220), Ui.WRAP))
        actions += finish to { a.back() }
        actions += clean to { a.replace(ProgramsScreen(a, openOnCleaning = true)) }
        if (canNote) actions += noteCard to { openNote() }
        highlight()
        return root
    }

    private fun card(icon: Icon.Kind, label: String, detail: String, onClick: () -> Unit): LinearLayout {
        val box = Ui.horizontal(c, Gravity.TOP)
        box.setPadding(Ui.u(24), Ui.u(22), Ui.u(24), Ui.u(22))
        box.minimumHeight = Ui.u(120)
        box.addView(Icon(c, icon, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(36), Ui.u(36)).apply { rightMargin = Ui.u(16) })
        val col = Ui.vertical(c)
        col.addView(Ui.text(c, label, 21f, Ui.TEXT, Ui.MEDIUM))
        col.addView(Ui.ellipsize(Ui.text(c, detail, 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.12f) }, 3), Ui.lp(t = 6))
        box.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        box.isClickable = true
        box.setOnClickListener { onClick() }
        return box
    }

    /** La note est sur la fiche : on y revient (ou on l'ouvre) et l'éditeur s'ouvre. */
    private fun openNote() {
        val original = a.app.recipes.get(recipeId) ?: return
        a.back()
        val below = a.current
        if (below is RecipeScreen && below.recipe.id == recipeId) below.editNote()
        else {
            val page = RecipeScreen(a, original)
            a.push(page)
            page.editNote()
        }
    }

    /** Cadre orange autour du choix de la molette. */
    private fun highlight() {
        for ((i, p) in actions.withIndex()) {
            val v = p.first
            val on = i == selected
            if (v === actions[0].first) {
                v.alpha = 1f
                v.background = Ui.pressable(Ui.round(Ui.SURFACE3, 32f, if (on) Ui.ACCENT else Ui.SURFACE3, 2.5f), 32f)
            } else {
                v.background = Ui.pressable(Ui.round(Ui.SURFACE, 26f, if (on) Ui.ACCENT else Ui.STROKE, if (on) 2.5f else 1f), 26f)
            }
        }
    }

    override fun onKnob(delta: Int): Boolean {
        if (actions.isEmpty()) return true
        selected = (selected + delta + actions.size) % actions.size
        highlight()
        return true
    }

    override fun onKnobPress(): Boolean {
        actions.getOrNull(selected)?.second?.invoke()
        return true
    }
}
