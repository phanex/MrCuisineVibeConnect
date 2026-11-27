package app.toque.ui.screens

import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

/**
 * Relier ce robot à un serveur Mealie : son adresse, puis l'identifiant et le mot de passe (le robot
 * crée son propre jeton « Toque (robot) » ; le mot de passe n'est pas gardé), ou un jeton d'API créé
 * dans Mealie (Profil › Jetons d'API).
 */
class MealieLinkScreen(a: MainActivity) : Screen(a) {
    override val title = "Mealie"
    private lateinit var scroll: ScrollView
    private lateinit var col: LinearLayout
    private lateinit var intro: TextView
    private lateinit var urlLabel: TextView
    private lateinit var url: EditText
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var token: EditText
    private lateinit var loginBtn: View
    private lateinit var tokenBtn: View
    private lateinit var loginMsg: TextView
    private lateinit var tokenMsg: TextView
    private var working = false

    override fun build(): View {
        col = Ui.vertical(c)
        col.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(24))
        intro = Ui.text(c, tr("Toutes les recettes de votre serveur arrivent sur le robot, en lecture seule. Le robot et le serveur doivent être sur le même réseau, ou le serveur joignable depuis Internet."),
            17f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }
        col.addView(intro, Ui.lp(b = 12))
        urlLabel = Ui.text(c, tr("Adresse du serveur"), 18f, Ui.TEXT, Ui.MEDIUM)
        col.addView(urlLabel)
        url = field(a.prefs.mealieUrl, "http://192.168.1.20:9000", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply {
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NEXT
            setOnFocusChangeListener { _, has -> if (has && a.keyboardHeight > 0) Ui.revealInScroll(scroll, urlLabel, this) }
        }
        col.addView(url, Ui.lp(Ui.MATCH, Ui.u(54), t = 8, b = 14))
        val columns = Ui.horizontal(c, Gravity.TOP)
        col.addView(columns)

        // ---- Identifiant et mot de passe
        val left = Ui.card(c, 24f)
        columns.addView(left, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(18) })
        left.addView(Ui.text(c, tr("Avec votre identifiant (le plus simple)"), 21f, Ui.TEXT, Ui.MEDIUM))
        left.addView(Ui.text(c, tr("Le robot crée son propre jeton « Toque (robot) ». Le mot de passe n'est pas gardé."),
            16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }, Ui.lp(t = 8))
        user = field("", tr("Identifiant ou e-mail"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).apply {
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NEXT
            setOnFocusChangeListener { _, has -> if (has && a.keyboardHeight > 0) Ui.revealInScroll(scroll, this, loginBtn) }
        }
        left.addView(user, Ui.lp(Ui.MATCH, Ui.u(54), t = 12))
        password = field("", tr("Mot de passe"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> withPassword(); true }
            setOnFocusChangeListener { _, has -> if (has && a.keyboardHeight > 0) Ui.revealInScroll(scroll, this, loginBtn) }
        }
        left.addView(password, Ui.lp(Ui.MATCH, Ui.u(54), t = 8))
        loginBtn = Ui.button(c, tr("Relier"), Ui.Style.PRIMARY, Icon.Kind.LINK, height = 54) { withPassword() }
        left.addView(loginBtn, Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))
        loginMsg = Ui.text(c, "", 16f, Ui.WARN).apply { visibility = View.GONE }
        left.addView(loginMsg, Ui.lp(t = 10))

        // ---- Jeton d'API
        val right = Ui.card(c, 24f)
        columns.addView(right, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        right.addView(Ui.text(c, tr("Avec un jeton d'API"), 21f, Ui.TEXT, Ui.MEDIUM))
        right.addView(Ui.text(c, tr("Dans Mealie : Profil › Jetons d'API › Générer. Pour un compte sans mot de passe (connexion externe)."),
            16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }, Ui.lp(t = 8))
        token = field("", tr("Jeton"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> withToken(); true }
            setOnFocusChangeListener { _, has -> if (has && a.keyboardHeight > 0) Ui.revealInScroll(scroll, this, tokenBtn) }
        }
        right.addView(token, Ui.lp(Ui.MATCH, Ui.u(54), t = 12))
        tokenBtn = Ui.button(c, tr("Relier"), Ui.Style.PRIMARY, Icon.Kind.LINK, height = 54) { withToken() }
        right.addView(tokenBtn, Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))
        tokenMsg = Ui.text(c, "", 16f, Ui.WARN).apply { visibility = View.GONE }
        right.addView(tokenMsg, Ui.lp(t = 10))
        scroll = Ui.scroll(c, col).apply { isVerticalScrollBarEnabled = true }
        return scroll
    }

    override fun onKeyboard(visible: Boolean) {
        intro.visibility = if (visible) View.GONE else View.VISIBLE
        col.setPadding(Ui.u(24), Ui.u(if (visible) 4 else 6), Ui.u(24), Ui.u(if (visible) 8 else 24))
        if (!visible) return
        when {
            user.hasFocus() -> Ui.revealInScroll(scroll, user, loginBtn)
            password.hasFocus() -> Ui.revealInScroll(scroll, password, loginBtn)
            token.hasFocus() -> Ui.revealInScroll(scroll, token, tokenBtn)
            url.hasFocus() -> Ui.revealInScroll(scroll, urlLabel, url)
        }
    }

    private fun field(value: String, hint: String, type: Int) = EditText(c).apply {
        setText(value)
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.uf(19f))
        setTextColor(Ui.TEXT)
        setHintTextColor(Ui.TEXT3)
        setSingleLine()
        inputType = type
        // Un champ mot de passe passe en police à chasse fixe : on garde celle de l'appli.
        typeface = Ui.REGULAR
        background = Ui.round(Ui.SURFACE2, 16f, Ui.STROKE, 1f)
        setPadding(Ui.u(14), 0, Ui.u(14), 0)
    }

    private fun busy(on: Boolean) {
        working = on
        Ui.setButtonEnabled(loginBtn, !on)
        Ui.setButtonEnabled(tokenBtn, !on)
    }

    private fun show(t: TextView, message: String) {
        t.text = message
        t.visibility = View.VISIBLE
    }

    private fun address(msg: TextView): String? {
        val u = url.text.toString().trim()
        if (u.isEmpty()) { show(msg, tr("Tapez l'adresse du serveur.")); a.showKeyboard(url) { Ui.revealInScroll(scroll, urlLabel, url) }; return null }
        return u
    }

    private fun withPassword() {
        if (working) return
        val u = address(loginMsg) ?: return
        a.hideKeyboard()
        val name = user.text.toString().trim()
        val pass = password.text.toString()
        if (name.isEmpty() || pass.isEmpty()) { show(loginMsg, tr("Tapez votre identifiant et votre mot de passe.")); return }
        busy(true)
        loginMsg.visibility = View.GONE
        tokenMsg.visibility = View.GONE
        a.app.mealie.linkWithPassword(u, name, pass) { error -> done(error, loginMsg) }
    }

    private fun withToken() {
        if (working) return
        val u = address(tokenMsg) ?: return
        a.hideKeyboard()
        val t = token.text.toString().trim()
        if (t.isEmpty()) { show(tokenMsg, tr("Collez le jeton créé dans Mealie.")); return }
        busy(true)
        loginMsg.visibility = View.GONE
        tokenMsg.visibility = View.GONE
        a.app.mealie.linkWithToken(u, t) { error -> done(error, tokenMsg) }
    }

    private fun done(error: String?, msg: TextView) {
        busy(false)
        if (error != null) { show(msg, error); return }
        password.setText("")
        token.setText("")
        a.toast(tr("Serveur Mealie relié : réception des recettes…"), Ui.OK)
        a.back()
    }

    override fun onHide() {
        // Le mot de passe et le jeton ne restent pas dans l'écran une fois quitté.
        if (::password.isInitialized) password.setText("")
        if (::token.isInitialized) token.setText("")
    }
}
