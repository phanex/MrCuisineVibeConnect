package app.toque.ui.screens

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.toque.core.companion.PairingManager
import app.toque.core.companion.Phone
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Ui
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Les téléphones appairés, une ligne chacun : son nom, quand il a été appairé et vu pour la dernière
 * fois, un crayon pour le renommer, « Lecture seule » (il suit le robot sans rien commander : écran
 * mural, domotique) ou « Rendre les commandes », et « Retirer ». Montrés sur l'écran d'appairage et dans
 * Réglages › Téléphone.
 */
internal object PairedPhones {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** Les téléphones du robot, dans l'ordre où ils ont été appairés. */
    fun list(a: MainActivity): List<Phone> = a.app.companion?.hub?.pairing?.list?.sortedBy { it.number } ?: emptyList()

    /** Remplit [into] ; sans téléphone, [empty] s'il y en a un, sinon rien. */
    fun fill(a: MainActivity, into: LinearLayout, empty: String? = null) {
        into.removeAllViews()
        val phones = list(a)
        if (phones.isEmpty()) {
            if (empty != null) into.addView(Ui.text(a, empty, 17f, Ui.TEXT3), Ui.lp(t = 14))
            return
        }
        val dates = SimpleDateFormat(tr("d MMMM"), I18n.locale)
        phones.forEachIndexed { i, p ->
            if (i > 0) into.addView(Ui.divider(a), Ui.lp(Ui.MATCH, maxOf(1, Ui.u(1)), t = 12))
            into.addView(row(a, p, dates), Ui.lp(t = 12))
        }
    }

    private fun row(a: MainActivity, p: Phone, dates: SimpleDateFormat): View {
        val row = Ui.horizontal(a)
        row.addView(Icon(a, Icon.Kind.PHONE, Ui.ACCENT_INK), LinearLayout.LayoutParams(Ui.u(26), Ui.u(26)).apply { rightMargin = Ui.u(14) })
        val col = Ui.vertical(a)
        col.addView(Ui.ellipsize(Ui.text(a, p.name, 18f, Ui.TEXT, Ui.MEDIUM)))
        val seenText = if (p.readOnly) seen(p, dates) + " · " + tr("lecture seule") else seen(p, dates)
        col.addView(Ui.ellipsize(Ui.text(a, seenText, 15f, Ui.TEXT2)), Ui.lp(t = 2))
        row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val pencil = FrameLayout(a).apply {
            background = Ui.pressable(Ui.round(0, 23f, Ui.STROKE, 1f), 23f)
            addView(Icon(a, Icon.Kind.EDIT, Ui.TEXT2), Ui.flp(Ui.u(20), Ui.u(20), Gravity.CENTER))
            contentDescription = tr("Renommer %s", p.name)
            isClickable = true
            setOnClickListener { rename(a, p) }
        }
        row.addView(pencil, LinearLayout.LayoutParams(Ui.u(46), Ui.u(46)).apply { leftMargin = Ui.u(14) })
        // Par le hub : une nouvelle révision, le téléphone voit tout de suite ce qu'il peut faire.
        row.addView(Ui.button(a, if (p.readOnly) tr("Rendre les commandes") else tr("Lecture seule"), Ui.Style.GHOST, height = 46, textSize = 16f) {
            a.app.companion?.hub?.setPhoneReadOnly(p.id, !p.readOnly)
        }, Ui.lp(Ui.WRAP, Ui.WRAP, l = 10))
        row.addView(Ui.button(a, tr("Retirer"), Ui.Style.GHOST, height = 46, textSize = 16f) { remove(a, p) }, Ui.lp(Ui.WRAP, Ui.WRAP, l = 10))
        return row
    }

    /**
     * Carte de saisie du nom de [p], en haut de l'écran pour que le clavier ne la couvre pas. Un nom
     * vidé rend « Téléphone N ».
     */
    private fun rename(a: MainActivity, p: Phone) {
        val imm = a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val card = Ui.card(a, 28f, Ui.SURFACE2)
        card.setPadding(Ui.u(32), Ui.u(24), Ui.u(32), Ui.u(22))
        val head = Ui.horizontal(a)
        head.addView(Icon(a, Icon.Kind.PHONE, Ui.ACCENT_INK), LinearLayout.LayoutParams(Ui.u(30), Ui.u(30)).apply { rightMargin = Ui.u(14) })
        head.addView(Ui.ellipsize(Ui.text(a, tr("Renommer %s", p.name), 24f, Ui.TEXT, Ui.MEDIUM)))
        card.addView(head)
        val input = EditText(a).apply {
            setText(p.label)
            setSelection(text.length)
            hint = tr("Téléphone %d", p.number)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.uf(21f))
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT3)
            background = Ui.round(Ui.SURFACE, 16f, Ui.STROKE, 1f)
            setPadding(Ui.u(16), Ui.u(12), Ui.u(16), Ui.u(12))
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(PairingManager.MAX_LABEL))
            contentDescription = tr("Nom du téléphone")
        }
        card.addView(input, Ui.lp(Ui.MATCH, Ui.u(60), t = 16))
        card.addView(Ui.text(a, tr("Laissez vide pour reprendre « %s ».", tr("Téléphone %d", p.number)), 16f, Ui.TEXT2), Ui.lp(t = 8, b = 18))
        fun close() {
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            a.closeOverlay()
        }
        fun commit() {
            // Par le hub : une nouvelle révision, le téléphone renommé voit son nom tout de suite.
            a.app.companion?.hub?.renamePhone(p.id, input.text.toString())
            close()
        }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { commit(); true } else false
        }
        val buttons = Ui.horizontal(a, Gravity.END or Gravity.CENTER_VERTICAL)
        buttons.addView(Ui.button(a, tr("Annuler"), Ui.Style.GHOST, height = 56, textSize = 17f) { close() })
        buttons.addView(Ui.spacer(a, w = 12))
        buttons.addView(Ui.button(a, tr("OK"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 56, textSize = 17f) { commit() })
        card.addView(buttons)
        a.showOverlay(card, 680, dismissOnScrim = false)
        (card.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = Ui.u(14)
        }
        card.requestLayout()
        input.requestFocus()
        input.post { imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun seen(p: Phone, dates: SimpleDateFormat): String {
        val paired = tr("Appairé le %s", dates.format(Date(p.pairedAt)))
        val ago = System.currentTimeMillis() - p.lastSeenAt
        val seen = when {
            ago < 2 * MINUTE -> tr("vu à l'instant")
            ago < HOUR -> tr("vu il y a %d min", (ago / MINUTE).toInt())
            ago < DAY -> tr("vu il y a %d h", (ago / HOUR).toInt())
            ago < 2 * DAY -> tr("vu hier")
            else -> tr("vu il y a %d jours", (ago / DAY).toInt())
        }
        return "$paired · $seen"
    }

    private fun remove(a: MainActivity, p: Phone) {
        a.confirm(tr("Retirer %s", p.name),
            tr("Ce téléphone ne pourra plus suivre ni piloter le robot. Pour le retrouver, il faudra l'appairer à nouveau."),
            tr("Retirer"), icon = Icon.Kind.PHONE) {
            a.app.companion?.hub?.pairing?.remove(p.id)
        }
    }
}
