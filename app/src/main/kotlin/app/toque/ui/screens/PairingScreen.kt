package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.companion.RobotCompanion
import app.toque.core.companion.CompanionHub
import app.toque.core.companion.Phone
import app.toque.core.companion.PairingStatus
import app.toque.core.companion.SystemCompanionClock
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.qr.QrCode
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.QrView
import app.toque.ui.Screen
import app.toque.ui.Ui
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Appairer un téléphone (Toque Compagnon) : QR code et code à six chiffres, valables 2 minutes,
 * et la liste des téléphones appairés. L'appairage n'est ouvert que pendant que cet écran est
 * affiché ; un code expiré n'est pas remplacé tout seul (« Nouveau code »). Un téléphone appairé
 * (QR code ou code saisi) : message, puis retour à l'accueil, ce qui ferme l'appairage.
 */
class PairingScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Appairer un téléphone")

    private enum class State { SHOW, STARTING, SERVER_ERROR, NO_WIFI, FULL, EXPIRED, DEAD, CONSUMED }

    private val companion: RobotCompanion? = a.app.companion
    private lateinit var qr: QrView
    private lateinit var qrMessage: TextView
    private lateinit var codeHint: TextView
    private lateinit var codeText: TextView
    private lateinit var addressText: TextView
    private lateinit var validLabel: TextView
    private lateinit var validTime: TextView
    private lateinit var newCode: View
    private lateinit var phonesList: LinearLayout

    private var address: String? = null
    private var shownPayload: String? = null
    private var lastState: State? = null
    /** Téléphones déjà appairés : un identifiant nouveau = un appairage fait depuis cet écran. */
    private var knownIds: Set<String> = emptySet()
    private var ticks = 0
    private var unsub: (() -> Unit)? = null
    private val dateFormat = SimpleDateFormat(tr("d MMMM"), I18n.locale)

    private val tick = object : Runnable {
        override fun run() {
            ticks++
            // Sans Wi-Fi : on regarde de temps en temps s'il est revenu.
            if (address == null && ticks % 6 == 0) {
                address = companion?.addressProvider?.invoke()
                if (address != null) openCode()
            }
            render()
            if (ticks % 20 == 0) renderPhones()
            view.postDelayed(this, 500)
        }
    }

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(18))

        // ---- à gauche : le code
        val left = Ui.vertical(c, Gravity.CENTER_HORIZONTAL)
        left.background = Ui.round(Ui.SURFACE, 24f)
        left.setPadding(Ui.u(20), Ui.u(18), Ui.u(20), Ui.u(18))
        left.addView(label(tr("À scanner avec le téléphone")), Ui.lp(Ui.MATCH, Ui.WRAP))
        val qrBox = FrameLayout(c)
        qr = QrView(c)
        qrBox.addView(qr, Ui.flp())
        qrMessage = Ui.text(c, "", 17f, Ui.TEXT2).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setPadding(Ui.u(22), 0, Ui.u(22), 0)
        }
        qrBox.addView(qrMessage, Ui.flp(Ui.MATCH, Ui.WRAP, Gravity.CENTER))
        // Aussi grand que la carte le permet (modules plus gros, plus faciles à lire pour l'appareil photo).
        left.addView(qrBox, LinearLayout.LayoutParams(Ui.u(QR_SIZE), Ui.u(QR_SIZE)).apply { topMargin = Ui.u(12) })
        codeHint = Ui.text(c, tr("Ou saisissez ce code sur le téléphone"), 14f, Ui.TEXT2)
        left.addView(codeHint, Ui.lp(Ui.WRAP, Ui.WRAP, t = 12))
        codeText = Ui.text(c, "", 36f, Ui.TEXT, Ui.LIGHT).apply { letterSpacing = 0.06f }
        left.addView(codeText, Ui.lp(Ui.WRAP, Ui.WRAP, t = 4))
        // Discrète : l'application ne la demande que si elle ne trouve pas le robot toute seule.
        addressText = Ui.text(c, "", 14f, Ui.TEXT3)
        left.addView(addressText, Ui.lp(Ui.WRAP, Ui.WRAP, t = 4))
        left.addView(View(c), LinearLayout.LayoutParams(1, 0, 1f))
        val bottom = Ui.horizontal(c)
        validLabel = Ui.text(c, "", 15f, Ui.TEXT2)
        bottom.addView(validLabel, Ui.lp(Ui.WRAP, Ui.WRAP))
        validTime = Ui.text(c, "", 15f, Ui.TEXT)
        bottom.addView(validTime, Ui.lp(Ui.WRAP, Ui.WRAP, l = 4))
        bottom.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        newCode = Ui.button(c, tr("Nouveau code"), Ui.Style.GHOST, height = 46, textSize = 16f) {
            address = companion?.addressProvider?.invoke()
            openCode()
            render()
        }
        bottom.addView(newCode, Ui.lp(Ui.WRAP, Ui.WRAP, l = 12))
        left.addView(bottom, Ui.lp(Ui.MATCH, Ui.WRAP))
        root.addView(left, LinearLayout.LayoutParams(Ui.u(380), Ui.MATCH).apply { rightMargin = Ui.u(24) })

        // ---- à droite : comment faire, et les téléphones
        val right = Ui.vertical(c)
        val how = card()
        how.addView(label(tr("Sur le téléphone")))
        how.addView(stepRow(1, tr("Connectez-le au Wi-Fi de la maison.")), Ui.lp(t = 12))
        how.addView(stepRow(2, tr("Ouvrez l'application Toque Compagnon et scannez le QR code.")), Ui.lp(t = 12))
        how.addView(stepRow(3, tr("Il s'ajoute à la liste ci-dessous.")), Ui.lp(t = 12))
        how.addView(Ui.divider(c), Ui.lp(Ui.MATCH, maxOf(1, Ui.u(1)), t = 14))
        how.addView(getAppRow(), Ui.lp(Ui.MATCH, Ui.WRAP, t = 12))
        right.addView(how, Ui.lp(Ui.MATCH, Ui.WRAP))

        val phones = card()
        phones.addView(label(tr("Téléphones appairés")))
        phonesList = Ui.vertical(c)
        phones.addView(Ui.scroll(c, phonesList).apply { isFillViewport = false }, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        phones.addView(Ui.text(c, tr("Un téléphone non appairé ne voit pas le robot, même sur le même Wi-Fi."), 15f, Ui.TEXT2)
            .apply { setLineSpacing(0f, 1.15f) }, Ui.lp(t = 10))
        right.addView(phones, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.u(14) })
        root.addView(right, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))
        return root
    }

    /** « Pas encore l'appli ? » : l'adresse des versions du Compagnon et un petit QR code (calculé une fois). */
    private fun getAppRow(): View {
        val row = Ui.horizontal(c)
        val code = try { QrCode.encode(APP_URL, QrCode.ErrorCorrection.L) } catch (t: Throwable) { null }
        if (code != null) row.addView(QrView(c).apply { this.code = code },
            LinearLayout.LayoutParams(Ui.u(APP_QR_SIZE), Ui.u(APP_QR_SIZE)).apply { rightMargin = Ui.u(16) })
        val col = Ui.vertical(c)
        col.addView(Ui.text(c, tr("Pas encore l'appli ?"), 17f, Ui.TEXT, Ui.MEDIUM))
        col.addView(Ui.text(c, APP_URL.removePrefix("https://"), 15f, Ui.TEXT2).apply { setLineSpacing(0f, 1.1f) },
            Ui.lp(t = 4))
        row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        return row
    }

    private fun card(): LinearLayout = Ui.vertical(c).apply {
        background = Ui.round(Ui.SURFACE, 24f)
        setPadding(Ui.u(20), Ui.u(8), Ui.u(20), Ui.u(18))
    }

    private fun label(t: String): TextView =
        Ui.text(c, t.uppercase(I18n.locale), 15f, Ui.TEXT2, Ui.MEDIUM).apply { letterSpacing = 0.08f; setPadding(0, Ui.u(10), 0, 0) }

    private fun stepRow(n: Int, text: String): View {
        val row = Ui.horizontal(c)
        val num = Ui.text(c, "$n", 17f, Ui.ACCENT_INK, Ui.MEDIUM).apply { gravity = Gravity.CENTER }
        num.background = Ui.round(Ui.ACCENT_DIM, 18f)
        row.addView(num, LinearLayout.LayoutParams(Ui.u(36), Ui.u(36)).apply { rightMargin = Ui.u(14) })
        row.addView(Ui.text(c, text, 18f, Ui.TEXT).apply { setLineSpacing(0f, 1.1f) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        return row
    }

    // ------------------------------------------------------------------ cycle de vie

    override fun onShow() {
        val comp = companion
        knownIds = comp?.hub?.pairing?.list?.mapTo(HashSet()) { it.id } ?: emptySet()
        address = comp?.addressProvider?.invoke()
        openCode()
        unsub = comp?.onChange { ch ->
            when (ch) {
                RobotCompanion.Change.PHONES -> onPhonesChanged()
                RobotCompanion.Change.PAIRING, RobotCompanion.Change.SERVER -> render()
                RobotCompanion.Change.WAITING -> Unit
            }
        }
        renderPhones()
        render()
        view.removeCallbacks(tick)
        view.postDelayed(tick, 500)
    }

    override fun onHide() {
        view.removeCallbacks(tick)
        unsub?.invoke()
        unsub = null
        // Écran quitté : plus d'appairage possible (le serveur se ferme s'il n'a aucun téléphone).
        try {
            companion?.hub?.pairing?.close()
        } catch (t: Throwable) {
            a.app.log.write("compagnon", "fermeture de l'appairage : $t")
        }
    }

    /** Nouveau code, s'il y a du Wi-Fi et de la place pour un téléphone de plus. */
    private fun openCode() {
        val p = companion?.hub?.pairing ?: return
        if (address == null || p.list.size >= p.maxPhones) return
        try {
            p.open()
        } catch (t: Throwable) {
            a.app.log.write("compagnon", "ouverture de l'appairage : $t")
        }
    }

    private fun onPhonesChanged() {
        val list = companion?.hub?.pairing?.list ?: emptyList()
        val added = list.filter { it.id !in knownIds }
        knownIds = list.mapTo(HashSet()) { it.id }
        renderPhones()
        render()
        // Téléphone retiré (« Retirer ») ou autre changement : on reste ici.
        if (added.isEmpty()) return
        // Appairage réussi : le message (dessiné par l'activité, il reste affiché sur l'accueil), puis
        // l'accueil ; quitter cet écran ferme l'appairage (onHide).
        added.maxByOrNull { it.pairedAt }?.let { a.toast(tr("%s appairé", it.name), Ui.OK) }
        if (a.overlayShown) a.closeOverlay()
        if (a.current === this) a.home()
    }

    // ------------------------------------------------------------------ affichage

    private fun state(): State {
        val comp = companion ?: return State.SERVER_ERROR
        val p = comp.hub.pairing
        val live = p.current
        return when {
            address == null -> State.NO_WIFI
            live == null && p.list.size >= p.maxPhones -> State.FULL
            live != null && comp.hub.port <= 0 -> if (comp.serverError.isNotEmpty()) State.SERVER_ERROR else State.STARTING
            live != null -> State.SHOW
            else -> when (p.status) {
                PairingStatus.LOCKED -> State.DEAD
                PairingStatus.USED -> State.CONSUMED
                // Écran ouvert sans code : un code, puis « Nouveau code » une fois expiré.
                PairingStatus.EXPIRED, PairingStatus.CLOSED, PairingStatus.OPEN -> State.EXPIRED
            }
        }
    }

    private fun render() {
        val comp = companion
        val st = state()
        if (st != lastState) {
            // Code expiré ou annulé : le serveur se ferme s'il n'a aucun téléphone à servir.
            if (lastState == State.SHOW) comp?.syncServer()
            lastState = st
        }
        val live = comp?.hub?.pairing?.current
        val showing = st == State.SHOW && live != null
        if (showing) {
            val payload = try {
                comp!!.hub.pairingPayload(address!!)
            } catch (t: Throwable) {
                null
            }
            if (payload != null && payload != shownPayload) {
                shownPayload = payload
                qr.code = try {
                    // Niveau L : un code affiché sur un écran n'est jamais abîmé ; moins de modules, plus gros.
                    QrCode.encode(payload, QrCode.ErrorCorrection.L)
                } catch (t: Throwable) {
                    null
                }
            }
            codeText.text = live!!.code.let { it.substring(0, 3) + " " + it.substring(3) }
            val port = comp!!.hub.port
            addressText.text = tr("Adresse du robot : %s", if (port == CompanionHub.DEFAULT_PORT) address!! else "$address:$port")
            val left = live.expiresMono - SystemCompanionClock.monoMs()
            validLabel.text = tr("Valable encore")
            validLabel.setTextColor(Ui.TEXT2)
            validTime.text = minutes(left)
            qrMessage.text = ""
        } else {
            shownPayload = null
            qr.code = null
            codeText.text = ""
            addressText.text = ""
            validTime.text = ""
            qrMessage.text = when (st) {
                State.NO_WIFI -> tr("Pas de Wi-Fi") + "\n\n" + tr("Connectez le robot au Wi-Fi de la maison pour appairer un téléphone.")
                State.STARTING -> tr("Préparation…")
                State.SERVER_ERROR -> tr("Le robot ne peut pas accueillir de téléphone pour l'instant : nouvel essai dans quelques secondes.")
                State.FULL -> tr("%d téléphones appairés : retirez-en un pour en ajouter un autre.", comp?.hub?.pairing?.maxPhones ?: 8)
                State.EXPIRED -> tr("Code expiré") + "\n\n" + tr("Touchez « Nouveau code » pour en afficher un autre.")
                State.DEAD -> tr("Trop d'essais : demandez un nouveau code")
                State.CONSUMED -> tr("Téléphone ajouté")
                State.SHOW -> ""
            }
            validLabel.text = when (st) {
                State.EXPIRED -> tr("Code expiré")
                State.DEAD -> tr("Trop d'essais")
                else -> ""
            }
            validLabel.setTextColor(Ui.WARN)
        }
        val textVisible = if (showing) View.VISIBLE else View.INVISIBLE
        codeHint.visibility = textVisible
        Ui.setButtonEnabled(newCode, st != State.NO_WIFI && st != State.FULL)
    }

    private fun minutes(ms: Long): String {
        val s = ((ms.coerceAtLeast(0) + 999) / 1000).toInt()
        return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    private fun renderPhones() {
        phonesList.removeAllViews()
        val list = companion?.hub?.pairing?.list?.sortedBy { it.number } ?: emptyList()
        if (list.isEmpty()) {
            phonesList.addView(Ui.text(c, tr("Aucun téléphone appairé pour l'instant."), 17f, Ui.TEXT3), Ui.lp(t = 14))
            return
        }
        list.forEachIndexed { i, p ->
            if (i > 0) phonesList.addView(Ui.divider(c), Ui.lp(Ui.MATCH, maxOf(1, Ui.u(1)), t = 12))
            phonesList.addView(phoneRow(p), Ui.lp(t = 12))
        }
    }

    private fun phoneRow(p: Phone): View {
        val row = Ui.horizontal(c)
        row.addView(Icon(c, Icon.Kind.PHONE, Ui.ACCENT_INK), LinearLayout.LayoutParams(Ui.u(26), Ui.u(26)).apply { rightMargin = Ui.u(14) })
        val col = Ui.vertical(c)
        col.addView(Ui.ellipsize(Ui.text(c, p.name, 18f, Ui.TEXT, Ui.MEDIUM)))
        col.addView(Ui.ellipsize(Ui.text(c, seen(p), 15f, Ui.TEXT2)), Ui.lp(t = 2))
        row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        row.addView(Ui.button(c, tr("Retirer"), Ui.Style.GHOST, height = 46, textSize = 16f) { removePhone(p) }, Ui.lp(Ui.WRAP, Ui.WRAP, l = 14))
        return row
    }

    private fun seen(p: Phone): String {
        val paired = tr("Appairé le %s", dateFormat.format(Date(p.pairedAt)))
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

    private fun removePhone(p: Phone) {
        a.confirm(tr("Retirer %s", p.name),
            tr("Ce téléphone ne pourra plus suivre ni piloter le robot. Pour le retrouver, il faudra l'appairer à nouveau."),
            tr("Retirer"), icon = Icon.Kind.PHONE) {
            companion?.hub?.pairing?.remove(p.id)
        }
    }

    companion object {
        /** Côté du carré du QR code, en unités Toque (la carte de gauche fait 380 de large). */
        const val QR_SIZE = 280
        /** Où télécharger Toque Compagnon (dernière version publiée). */
        const val APP_URL = "https://github.com/Wuki60/ToqueCompagnon/releases/latest"
        /** Petit QR de [APP_URL] : version 4 au niveau L, 41 modules avec la marge, 3 pixels chacun sur le Connect. */
        private const val APP_QR_SIZE = 124
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR
    }
}
