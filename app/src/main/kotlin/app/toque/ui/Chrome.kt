package app.toque.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.InsetDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.toque.Prefs
import app.toque.core.i18n.tr
import app.toque.core.machine.JobState
import app.toque.core.machine.LinkState
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.Snapshot

/** Écran de l'appli (vue construite une fois, réutilisée). */
abstract class Screen(val a: MainActivity) {
    abstract val title: String
    val view: View by lazy { build() }
    val c: Context get() = a

    protected abstract fun build(): View
    open fun onShow() {}
    open fun onHide() {}
    open fun onSnapshot(s: Snapshot) {}
    open fun onEvent(e: MachineEvent) {}
    /** L'écran joue lui-même le son de fin de ce programme (fin de recette) : pas de bip habituel. */
    open fun ownsFinishSound(e: MachineEvent): Boolean = false
    open fun onKnob(delta: Int): Boolean = false
    open fun onKnobPress(): Boolean = false
    /** false : l'écran demande d'abord confirmation (modifications non enregistrées) et appelle [proceed] si on confirme. */
    open fun canLeave(proceed: () -> Unit): Boolean = true
    /** Empêche la mise en veille tant que cet écran est affiché (balance). */
    open val keepAwake: Boolean get() = false
    /** Une recette guidée affichée attend un geste de la personne : l'anneau de la molette passe au vert. */
    open val cookWaiting: Boolean get() = false
}

/** Barre supérieure : retour, titre, minuteurs, état de la base, heure. */
class TopBar(private val a: MainActivity) : LinearLayout(a) {
    private val back = Icon(a, Icon.Kind.BACK, Ui.TEXT)
    private val titleView = Ui.ellipsize(Ui.text(a, "", 24f, Ui.TEXT, Ui.MEDIUM))
    private val temp = barChip("", Icon.Kind.THERMO, Ui.TEXT2)
    private val tempIcon = temp.getChildAt(0) as Icon
    /** Triangle rouge sur la température, qui clignote tant que le bol est chaud (≥ 60 °C). */
    private val hotWarning = Icon(a, Icon.Kind.WARNING_FILLED, Ui.DANGER)
    private val blink = ObjectAnimator.ofFloat(hotWarning, "alpha", 1f, 0.12f).apply {
        duration = 500
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
    }
    private val lid = barChip(tr("Couvercle ouvert"), Icon.Kind.LID, Ui.WARN, 0x33FFC14D)
    private val bowl = barChip(tr("Bol retiré"), Icon.Kind.POT, Ui.WARN, 0x33FFC14D)
    private val link = barChip("", Icon.Kind.LINK, Ui.TEXT2)
    private val clock = barChip("", Icon.Kind.CLOCK, Ui.TEXT)
    private val home = Icon(a, Icon.Kind.HOME, Ui.TEXT)
    /** Minuteur de cuisine : bouton rond au repos, pastille orange (temps restant, nombre) quand il en tourne. */
    private val timerButton = Icon(a, Icon.Kind.TIMER, Ui.TEXT)
    private val timerChip = Ui.chip(a, "", Icon.Kind.TIMER, Ui.ACCENT, Ui.ACCENT_DIM)
    private val timerText = Ui.chipText(timerChip)
    private val timerCount = Ui.text(a, "", 14f, Ui.ON_ACCENT, Ui.BOLD).apply {
        gravity = Gravity.CENTER
        background = Ui.round(Ui.ACCENT, 12f)
        minWidth = Ui.u(24)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            // « --:-- » tant que l'heure n'est pas réglée ou que le Wi-Fi est coupé (voir WallClock).
            Ui.chipText(clock).text = WallClock.text(a)
            handler.postDelayed(this, WallClock.nextMs(a, 10_000))
        }
    }
    private var adminTaps = 0
    private var adminFirst = 0L

    init {
        orientation = HORIZONTAL
        setGravity(Gravity.CENTER_VERTICAL)
        // Un peu d'air au-dessus : sans lui, les pastilles touchent le bord de l'écran.
        setPadding(Ui.u(10), Ui.u(TOP_GAP), Ui.u(18), 0)
        // Transparente : le dégradé du fond passe aussi derrière la barre du haut.
        roundButton(back) { a.back() }
        addView(back, LayoutParams(Ui.u(TOUCH), Ui.u(TOUCH)))
        addView(titleView, LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.u(6) })
        roundButton(timerButton) { TimerPanel.open(a) }
        timerButton.contentDescription = tr("Minuteur")
        addView(timerButton, LayoutParams(Ui.u(TOUCH), Ui.u(TOUCH)).apply { rightMargin = Ui.u(4) })
        timerChip.addView(timerCount, LayoutParams(Ui.WRAP, Ui.u(24)).apply { leftMargin = Ui.u(8) })
        timerChip.background = Ui.pressable(Ui.round(Ui.ACCENT_DIM, 40f, Ui.ACCENT, 1.5f), 40f)
        timerChip.isClickable = true
        timerChip.setOnClickListener { TimerPanel.open(a) }
        timerChip.visibility = View.GONE
        addView(timerChip, chipParams(10))
        addView(lid, chipParams(10))
        addView(bowl, chipParams(10))
        addView(temp, chipParams(10))
        addView(link, chipParams(10))
        addView(clock, chipParams(8))
        roundButton(home) { a.home() }
        addView(home, LayoutParams(Ui.u(TOUCH), Ui.u(TOUCH)))
        lid.visibility = View.GONE
        bowl.visibility = View.GONE
        temp.visibility = View.GONE
        hotWarning.contentDescription = tr("Bol chaud")
        hotWarning.visibility = View.GONE
        temp.addView(hotWarning, 0, LayoutParams(Ui.u(24), Ui.u(24)).apply { rightMargin = Ui.u(6) })
        // Accès caché aux réglages Android : 5 appuis rapides sur l'heure.
        clock.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - adminFirst > 3_000) { adminFirst = now; adminTaps = 0 }
            if (++adminTaps >= 5) {
                adminTaps = 0
                a.openAndroidSettings()
            }
        }
    }

    /**
     * Bouton rond de [ITEM] au centre d'une zone tactile de [TOUCH] (toute la hauteur utile de la barre), de
     * la même famille que les pastilles ; l'icône fait 30.
     */
    private fun roundButton(icon: Icon, onClick: () -> Unit) {
        val r = ITEM / 2f
        icon.background = InsetDrawable(Ui.pressable(Ui.round(Ui.SURFACE2, r, Ui.STROKE, 1f), r), Ui.u((TOUCH - ITEM) / 2))
        // Après le fond : un fond qui porte une marge impose sinon la sienne à la vue.
        icon.setPadding(Ui.u(15), Ui.u(15), Ui.u(15), Ui.u(15))
        icon.setOnClickListener { onClick() }
    }

    /** Pastille de la barre : aussi haute que les boutons ronds, pour une barre homogène. */
    private fun barChip(label: String, icon: Icon.Kind, color: Int, bg: Int = Ui.SURFACE2): LinearLayout =
        Ui.chip(a, label, icon, color, bg, CHIP_TEXT).apply { setPadding(Ui.u(16), 0, Ui.u(18), 0) }

    private fun chipParams(right: Int) = LayoutParams(Ui.WRAP, Ui.u(ITEM)).apply { rightMargin = Ui.u(right) }

    /** Seul le triangle clignote : la température à côté reste lisible. */
    private fun showHot(hot: Boolean) {
        if (hot == (hotWarning.visibility == View.VISIBLE)) return
        if (hot) {
            hotWarning.alpha = 1f
            hotWarning.visibility = View.VISIBLE
            blink.start()
        } else {
            blink.cancel()
            hotWarning.visibility = View.GONE
        }
    }

    override fun onDetachedFromWindow() {
        blink.cancel()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (hotWarning.visibility == View.VISIBLE && !blink.isStarted) blink.start()
    }

    fun startClock() {
        handler.removeCallbacks(tick)
        tick.run()
    }

    fun stopClock() = handler.removeCallbacks(tick)

    /** Pastille du minuteur qui finit le premier (et nombre de minuteurs en cours), ou le bouton rond. */
    fun showTimers(t: Timers) {
        val running = t.running
        val first = running.firstOrNull()
        timerButton.visibility = if (first == null) View.VISIBLE else View.GONE
        timerChip.visibility = if (first == null) View.GONE else View.VISIBLE
        if (first == null) return
        val text = Ui.clock(first.remaining(t.now()))
        if (timerText.text.toString() != text) timerText.text = text
        timerCount.text = running.size.toString()
        timerCount.visibility = if (running.size > 1) View.VISIBLE else View.GONE
    }

    fun setTitle(t: String, canGoBack: Boolean) {
        titleView.text = t
        back.visibility = if (canGoBack) View.VISIBLE else View.INVISIBLE
        home.visibility = if (canGoBack) View.VISIBLE else View.GONE
    }

    fun update(s: Snapshot) {
        lid.visibility = if (s.hasSample && s.lidOpen) View.VISIBLE else View.GONE
        // Bol retiré : pour la base le couvercle est « ouvert », mais ce n'est pas lui qu'il faut fermer.
        Ui.chipText(lid).text = if (s.bowlRemoved) tr("Bol retiré") else tr("Couvercle ouvert")
        (lid.getChildAt(0) as Icon).set(if (s.bowlRemoved) Icon.Kind.POT else Icon.Kind.LID)
        // Capteur du bol seul : seulement si la détection du bol est exigée (la personne a alors vérifié que
        // le capteur est fiable, sens réglé). Jamais deux fois « Bol retiré ».
        bowl.visibility = if (s.hasSample && a.prefs.requireBowl && !s.bowlDetected && !s.bowlRemoved) View.VISIBLE else View.GONE
        if (s.hasSample) {
            temp.visibility = View.VISIBLE
            Ui.chipText(temp).text = "${s.actualTempC} °C"
            val col = if (s.actualTempC >= 40) Ui.tempColor(s.actualTempC) else Ui.TEXT2
            Ui.chipText(temp).setTextColor(col)
            tempIcon.color = col
        } else temp.visibility = View.GONE
        showHot(s.hot)
        val mode = a.prefs.mode
        val simulated = mode != Prefs.Mode.HARDWARE
        val (label, color) = when {
            s.hasFault -> tr("Alerte") to Ui.DANGER
            s.link == LinkState.READY -> {
                val label = when (mode) {
                    Prefs.Mode.DEMO -> tr("Démo · Base prête")
                    Prefs.Mode.TWIN -> tr("Jumeau · Base prête")
                    Prefs.Mode.HARDWARE -> tr("Base prête")
                }
                label to (if (simulated) Ui.COOL else Ui.OK)
            }
            s.link == LinkState.ARMING -> tr("Connexion…") to Ui.WARN
            s.link == LinkState.LOST -> tr("Liaison perdue") to Ui.DANGER
            else -> tr("Base absente") to Ui.TEXT3
        }
        Ui.chipText(link).text = label
        Ui.chipText(link).setTextColor(color)
        (link.getChildAt(0) as Icon).color = color
    }

    companion object {
        /** Hauteur commune des boutons ronds et des pastilles. */
        const val ITEM = 50
        /** Zone tactile des boutons ronds : toute la hauteur utile de la barre. */
        const val TOUCH = 60
        /** Espace entre le haut de l'écran et la barre. */
        const val TOP_GAP = 8
        /** Hauteur de la barre, espace du haut compris. */
        const val HEIGHT = TOUCH + TOP_GAP
        private const val CHIP_TEXT = 18f
    }
}

/** Fine barre de progression arrondie. */
class ProgressLine(c: Context) : View(c) {
    var progress = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var color = Ui.ACCENT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        paint.color = Ui.SURFACE3
        r.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(r, h / 2, h / 2, paint)
        paint.color = color
        r.set(0f, 0f, maxOf(h, width * progress), h)
        if (progress > 0f) canvas.drawRoundRect(r, h / 2, h / 2, paint)
    }
}

/**
 * Barre machine persistante : visible dès qu'un programme tourne, est en pause, vient de
 * finir, ou qu'une alerte est active — sur tous les écrans.
 *
 * Une carte arrondie détachée des bords de l'écran, alignée sur les cartes des écrans ([SIDE]) ; ses
 * boutons sont à la même distance ([INSET]) de son bord en haut, en bas et à droite.
 */
class MachineBar(private val a: MainActivity) : FrameLayout(a) {
    private val jobRow = Ui.horizontal(a)
    private val faultRow = Ui.horizontal(a)
    private val label = Ui.ellipsize(Ui.text(a, "", 20f, Ui.TEXT, Ui.MEDIUM))
    private val state = Ui.ellipsize(Ui.text(a, "", 16f, Ui.TEXT2))
    /** Rapetissé plutôt que coupé quand les pastilles prennent la place (libellés longs, trois pastilles). */
    private val time = Ui.fitText(a, 40f, 24f, Ui.TEXT, Ui.LIGHT)
    private val progress = ProgressLine(a)
    private val chips = Ui.horizontal(a)
    private val speedChip = Ui.chip(a, "", Icon.Kind.SPEED, Ui.TEXT)
    private val tempChip = Ui.chip(a, "", Icon.Kind.THERMO, Ui.TEXT)
    private val dirChip = Ui.chip(a, tr("Inverse"), Icon.Kind.REVERSE, Ui.COOL)
    private val pauseBtn: LinearLayout
    private val stopBtn: LinearLayout
    private val okBtn: LinearLayout
    private val faultText = Ui.text(a, "", 19f, Ui.TEXT, Ui.MEDIUM)
    private var last: Snapshot? = null

    init {
        // Transparente autour de la carte : le dégradé du fond passe derrière, comme pour la barre du haut.
        setPadding(Ui.u(SIDE), 0, Ui.u(SIDE), Ui.u(BOTTOM_GAP))
        // Programme
        jobRow.background = Ui.round(Ui.SURFACE, RADIUS, Ui.STROKE, 1f)
        jobRow.setPadding(Ui.u(TEXT_INSET), Ui.u(INSET), Ui.u(INSET), Ui.u(INSET))
        val left = Ui.vertical(a)
        left.addView(label)
        left.addView(state, Ui.lp(t = 4))
        jobRow.addView(left, LinearLayout.LayoutParams(Ui.u(210), Ui.WRAP))
        val mid = Ui.vertical(a, Gravity.CENTER_HORIZONTAL)
        mid.addView(time, Ui.lp(Ui.WRAP, Ui.WRAP))
        mid.addView(progress, Ui.lp(Ui.MATCH, Ui.u(6), t = 6))
        jobRow.addView(mid, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.u(10); rightMargin = Ui.u(16) })
        chips.addView(speedChip, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        chips.addView(tempChip, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        chips.addView(dirChip, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        jobRow.addView(chips)
        pauseBtn = Ui.button(a, "", Ui.Style.SECONDARY, Icon.Kind.PAUSE, height = BUTTON) { onPause() }
        stopBtn = Ui.button(a, "", Ui.Style.DANGER, Icon.Kind.STOP, height = BUTTON) {
            a.machine.stop()
        }
        okBtn = Ui.button(a, "OK", Ui.Style.SECONDARY, Icon.Kind.CHECK, height = BUTTON) { a.machine.clearFinished() }
        jobRow.addView(pauseBtn, Ui.lp(Ui.u(84), Ui.WRAP, l = 6))
        jobRow.addView(stopBtn, Ui.lp(Ui.u(84), Ui.WRAP, l = 10))
        jobRow.addView(okBtn, Ui.lp(Ui.WRAP, Ui.WRAP, l = 10))
        addView(jobRow, Ui.flp(Ui.MATCH, Ui.WRAP))
        // Alerte : la même carte, en rouge sombre ; en rouge pâle sur le thème clair, où le texte est foncé.
        faultRow.background = if (Ui.light) Ui.round(0xFFFDE7E7.toInt(), RADIUS, 0xFFF2C4C4.toInt(), 1f)
            else Ui.round(0xFF3A1416.toInt(), RADIUS, 0xFF6B2529.toInt(), 1f)
        faultRow.setPadding(Ui.u(TEXT_INSET), Ui.u(INSET), Ui.u(INSET), Ui.u(INSET))
        faultRow.minimumHeight = Ui.u(BUTTON + 2 * INSET)
        faultRow.addView(Icon(a, Icon.Kind.WARNING, Ui.DANGER), LinearLayout.LayoutParams(Ui.u(34), Ui.u(34)).apply { rightMargin = Ui.u(14) })
        faultRow.addView(faultText, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(14) })
        faultRow.addView(Ui.button(a, tr("Acquitter"), Ui.Style.SECONDARY, Icon.Kind.CHECK, height = BUTTON) {
            a.machine.acknowledge { v -> a.refusal(v) }
        })
        addView(faultRow, Ui.flp(Ui.MATCH, Ui.WRAP))
        visibility = View.GONE
    }

    private fun onPause() {
        val s = last ?: return
        if (s.state == JobState.PAUSED) {
            a.machine.resume(s.hotBlendConfirmed) { v ->
                if (!v.ok && v.has(app.toque.core.machine.SafetyPolicy.HOT_BLEND)) {
                    val q = if (app.toque.core.robot.RobotProfiles.active.capabilities.measuresTemperature) tr("Le bol est à %d °C. Reprendre le mixage ?", s.actualTempC)
                        else tr("Ce robot ne mesure pas toujours la température du bol : Toque considère le contenu comme chaud. Reprendre le mixage ?")
                    a.confirm(tr("Contenu chaud"), q, tr("Reprendre"), icon = Icon.Kind.THERMO) {
                        a.machine.resume(true) { a.refusal(it) }
                    }
                } else a.refusal(v)
            }
        } else a.machine.pause { a.refusal(it) }
    }

    fun update(s: Snapshot) {
        last = s
        val st = s.state
        val showJob = s.jobActive || s.turboActive
        visibility = if (shownFor(s)) View.VISIBLE else View.GONE
        faultRow.visibility = if (s.hasFault) View.VISIBLE else View.GONE
        jobRow.visibility = if (!s.hasFault && showJob) View.VISIBLE else View.GONE
        if (s.hasFault) {
            faultText.text = s.faultMessage
            return
        }
        if (!showJob) return
        if (s.turboActive) {
            label.text = "Turbo"
            state.text = tr("Relâchez pour arrêter")
            time.text = "●"
            progress.progress = 0f
            setButtons(running = false, ended = false, turbo = true)
            return
        }
        label.text = s.jobLabel
        state.text = when (st) {
            JobState.STARTING -> if (s.elapsedMs == 0L) tr("Démarrage…") else tr("Reprise…")
            JobState.RUNNING -> if (s.waitingStop) tr("Changement de sens…") else if (s.phaseCount > 1) tr("En cours · phase %d/%d", s.phaseIndex + 1, s.phaseCount) else tr("En cours")
            JobState.STOPPING -> tr("Arrêt du moteur…")
            JobState.PAUSED -> when (s.pauseReason) {
                "LID" -> tr("Pause : couvercle ouvert")
                "SAFETY" -> tr("Interrompu par sécurité")
                else -> tr("En pause")
            }
            JobState.FINISHED -> tr("Terminé")
            JobState.ABORTED -> s.endReason
            null -> ""
        }
        state.setTextColor(when (st) {
            JobState.PAUSED -> Ui.WARN
            JobState.FINISHED -> Ui.OK
            JobState.ABORTED -> Ui.DANGER
            else -> Ui.TEXT2
        })
        time.text = if (st == JobState.FINISHED) "00:00" else Ui.clock(s.remainingMs)
        progress.progress = if (s.totalMs > 0) s.elapsedMs.toFloat() / s.totalMs else 0f
        progress.color = if (s.jobTempLevel > 0) Ui.ACCENT else Ui.COOL
        Ui.chipText(speedChip).text = when {
            s.jobKind == "STEAM" -> tr("Vapeur")
            s.jobKind == "MANUAL" && s.jobSpeed == 0 -> tr("Sans mélange")
            else -> tr("Vitesse %d", s.jobSpeed)
        }
        tempChip.visibility = if (s.jobTempLevel > 0) View.VISIBLE else View.GONE
        // °C exacts du programme (40 °C et programmes du Smart compris), pas le niveau de Toque juste en dessous.
        Ui.chipText(tempChip).text = "${s.jobTempC} °C"
        dirChip.visibility = if (s.jobReverse && s.jobKind == "MANUAL") View.VISIBLE else View.GONE
        val ended = st == JobState.FINISHED || st == JobState.ABORTED
        setButtons(running = st != JobState.PAUSED, ended = ended, turbo = false)
    }

    private fun setButtons(running: Boolean, ended: Boolean, turbo: Boolean) {
        pauseBtn.visibility = if (ended || turbo) View.GONE else View.VISIBLE
        stopBtn.visibility = if (ended) View.GONE else View.VISIBLE
        okBtn.visibility = if (ended) View.VISIBLE else View.GONE
        (pauseBtn.getChildAt(0) as Icon).set(if (running) Icon.Kind.PAUSE else Icon.Kind.PLAY)
        chips.visibility = if (turbo) View.GONE else View.VISIBLE
    }

    companion object {
        /** Marge de chaque côté : celle des écrans, la carte s'aligne sur les leurs. */
        const val SIDE = 24
        /** Espace entre la carte et le bas de l'écran. */
        const val BOTTOM_GAP = 14
        /** Hauteur des boutons. */
        const val BUTTON = 64
        /** Distance des boutons au bord de la carte, la même en haut, en bas et à droite. */
        const val INSET = 8
        /** Retrait du texte à gauche, dans l'arrondi de la carte. */
        const val TEXT_INSET = 28
        /** Bouts arrondis du rayon des boutons plus [INSET] : le bouton de droite épouse le bord de la carte. */
        const val RADIUS = BUTTON / 2f + INSET

        /** La barre est affichée pour [s] : un programme (même terminé, jusqu'à « OK »), Turbo ou une alerte. */
        fun shownFor(s: Snapshot): Boolean = s.jobActive || s.turboActive || s.hasFault
    }
}
