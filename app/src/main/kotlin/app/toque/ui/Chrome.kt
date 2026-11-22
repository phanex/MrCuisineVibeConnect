package app.toque.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
import app.toque.core.protocol.Mcc

/** Écran de l'appli (vue construite une fois, réutilisée). */
abstract class Screen(val a: MainActivity) {
    abstract val title: String
    open val titleCounter: String? get() = null
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
}

/** Barre supérieure : retour, titre, état de la base, heure. */
class TopBar(private val a: MainActivity) : LinearLayout(a) {
    private val back = Icon(a, Icon.Kind.BACK, Ui.TEXT)
    private val titleBox = Ui.horizontal(a, Gravity.CENTER_VERTICAL)
    private val titleView = Ui.ellipsize(Ui.text(a, "", 24f, Ui.TEXT, Ui.MEDIUM))
    private val counterView = Ui.text(a, "", 18f, Ui.TEXT2, Ui.LIGHT)
    private val tempIcon = Icon(a, Icon.Kind.THERMO, Ui.TEXT2)
    private val tempText = Ui.text(a, "", 28f, Ui.TEXT, Ui.THIN)
    private val tempBox = Ui.horizontal(a, Gravity.CENTER_VERTICAL).apply {
        addView(tempIcon, LayoutParams(Ui.u(20), Ui.u(20)).apply { rightMargin = Ui.u(6) })
        addView(tempText)
    }
    private val lid = Ui.chip(a, tr("Couvercle ouvert"), Icon.Kind.LID, Ui.WARN, 0x33FFC14D)
    private val linkIcon = Icon(a, Icon.Kind.LINK, Ui.OK)
    private val clock = Ui.text(a, "", 28f, Ui.TEXT, Ui.THIN)
    private val home = Icon(a, Icon.Kind.HOME, Ui.TEXT)
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            clock.text = MainActivity.now()
            handler.postDelayed(this, 10_000)
        }
    }
    private var adminTaps = 0
    private var adminFirst = 0L

    init {
        orientation = HORIZONTAL
        setGravity(Gravity.CENTER_VERTICAL)
        setPadding(Ui.u(34), 0, Ui.u(24), 0)

        // Cluster de navigation à gauche : [◁]   [⌂]    Titre
        back.setPadding(Ui.u(10), Ui.u(10), Ui.u(10), Ui.u(10))
        back.background = Ui.pressable(Ui.round(0, 24f), 24f)
        back.setOnClickListener { a.back() }
        addView(back, LayoutParams(Ui.u(48), Ui.u(48)))

        home.setPadding(Ui.u(10), Ui.u(10), Ui.u(10), Ui.u(10))
        home.background = Ui.pressable(Ui.round(0, 24f), 24f)
        home.setOnClickListener { a.home() }
        addView(home, LayoutParams(Ui.u(48), Ui.u(48)).apply { leftMargin = Ui.u(20) })

        titleBox.addView(titleView)
        titleBox.addView(counterView, LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.u(12) })
        counterView.visibility = View.GONE
        addView(titleBox, LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.u(28) })

        // Indicateurs et horloge à droite
        addView(lid, Ui.lp(Ui.WRAP, Ui.WRAP, r = 16))
        addView(tempBox, Ui.lp(Ui.WRAP, Ui.WRAP, r = 20))
        addView(linkIcon, Ui.lp(Ui.u(20), Ui.u(20), r = 16))
        addView(clock, Ui.lp(Ui.WRAP, Ui.WRAP))

        lid.visibility = View.GONE
        tempBox.visibility = View.GONE
        linkIcon.visibility = View.GONE
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

    fun startClock() {
        handler.removeCallbacks(tick)
        tick.run()
    }

    fun stopClock() = handler.removeCallbacks(tick)

    fun setTitle(t: String, counter: String? = null, canGoBack: Boolean) {
        titleView.text = t
        if (!counter.isNullOrBlank()) {
            counterView.text = counter
            counterView.visibility = View.VISIBLE
        } else {
            counterView.visibility = View.GONE
        }
        back.visibility = if (canGoBack) View.VISIBLE else View.GONE
        home.visibility = if (canGoBack) View.VISIBLE else View.GONE
        // Pas de doublon d'horloge sur l'écran d'accueil qui affiche déjà l'heure en grand.
        clock.visibility = if (canGoBack) View.VISIBLE else View.GONE
    }

    fun update(s: Snapshot) {
        lid.visibility = if (s.hasSample && s.lidOpen) View.VISIBLE else View.GONE
        if (s.hasSample) {
            tempBox.visibility = View.VISIBLE
            tempText.text = "${s.actualTempC} °C"
            val col = if (s.actualTempC >= 40) Ui.tempColor(s.actualTempC) else Ui.TEXT
            tempText.setTextColor(col)
            tempIcon.color = if (s.actualTempC >= 40) col else Ui.TEXT2
        } else tempBox.visibility = View.GONE
        val mode = a.prefs.mode
        val simulated = mode != Prefs.Mode.HARDWARE
        val showLink = s.hasFault || s.link != LinkState.READY || simulated
        linkIcon.visibility = if (showLink) View.VISIBLE else View.GONE
        if (showLink) {
            linkIcon.color = when {
                s.hasFault -> Ui.DANGER
                s.link == LinkState.READY -> if (simulated) Ui.COOL else Ui.OK
                s.link == LinkState.ARMING -> Ui.WARN
                s.link == LinkState.LOST -> Ui.DANGER
                else -> Ui.TEXT3
            }
        }
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
 */
class MachineBar(private val a: MainActivity) : FrameLayout(a) {
    private val jobRow = Ui.horizontal(a, Gravity.CENTER_VERTICAL)
    private val faultRow = Ui.horizontal(a)
    private val label = Ui.ellipsize(Ui.text(a, "", 20f, Ui.TEXT, Ui.MEDIUM))
    private val state = Ui.ellipsize(Ui.text(a, "", 16f, Ui.TEXT2))
    private val timeIcon = Icon(a, Icon.Kind.TIMER, Ui.TEXT2)
    private val time = Ui.text(a, "", 36f, Ui.TEXT, Ui.LIGHT)
    private val progress = ProgressLine(a)
    private val chips = Ui.horizontal(a, Gravity.CENTER_VERTICAL)
    private val speedChip = Ui.chip(a, "", Icon.Kind.SPEED, Ui.TEXT)
    private val tempChip = Ui.chip(a, "", Icon.Kind.THERMO, Ui.TEXT)
    private val dirChip = Ui.chip(a, tr("Inverse"), Icon.Kind.REVERSE, Ui.COOL)
    private val pauseBtn: LinearLayout
    private val stopBtn: LinearLayout
    private val okBtn: LinearLayout
    private val faultText = Ui.text(a, "", 19f, Ui.TEXT, Ui.MEDIUM)
    private var last: Snapshot? = null

    init {
        setBackgroundColor(Ui.SURFACE)
        // Programme
        jobRow.setPadding(Ui.u(22), Ui.u(12), Ui.u(18), Ui.u(12))
        val left = Ui.vertical(a)
        left.addView(label)
        left.addView(state, Ui.lp(t = 4))
        jobRow.addView(left, LinearLayout.LayoutParams(Ui.u(210), Ui.WRAP))

        val mid = Ui.vertical(a)
        val midTop = Ui.horizontal(a, Gravity.CENTER_VERTICAL)
        val timeBox = Ui.horizontal(a, Gravity.CENTER_VERTICAL).apply {
            addView(timeIcon, LinearLayout.LayoutParams(Ui.u(22), Ui.u(22)).apply { rightMargin = Ui.u(8) })
            addView(time)
        }
        midTop.addView(timeBox, Ui.lp(Ui.WRAP, Ui.WRAP))
        midTop.addView(Ui.spacer(a, weight = 1f), LinearLayout.LayoutParams(0, 1, 1f))

        chips.addView(speedChip, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        chips.addView(tempChip, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        chips.addView(dirChip, Ui.lp(Ui.WRAP, Ui.WRAP))
        midTop.addView(chips, Ui.lp(Ui.WRAP, Ui.WRAP))

        mid.addView(midTop, Ui.lp(Ui.MATCH, Ui.WRAP))
        mid.addView(progress, Ui.lp(Ui.MATCH, Ui.u(6), t = 8))
        jobRow.addView(mid, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.u(16); rightMargin = Ui.u(20) })

        pauseBtn = Ui.button(a, "", Ui.Style.SECONDARY, Icon.Kind.PAUSE, height = 64) { onPause() }
        stopBtn = Ui.button(a, "", Ui.Style.DANGER, Icon.Kind.STOP, height = 64) {
            a.machine.stop()
        }
        okBtn = Ui.button(a, "OK", Ui.Style.SECONDARY, Icon.Kind.CHECK, height = 64) { a.machine.clearFinished() }
        jobRow.addView(pauseBtn, Ui.lp(Ui.u(84), Ui.WRAP, l = 6))
        jobRow.addView(stopBtn, Ui.lp(Ui.u(84), Ui.WRAP, l = 10))
        jobRow.addView(okBtn, Ui.lp(Ui.WRAP, Ui.WRAP, l = 10))
        addView(jobRow, Ui.flp(Ui.MATCH, Ui.WRAP))
        // Alerte
        faultRow.setPadding(Ui.u(22), Ui.u(14), Ui.u(18), Ui.u(14))
        faultRow.setBackgroundColor(0xFF3A1416.toInt())
        faultRow.addView(Icon(a, Icon.Kind.WARNING, Ui.DANGER), LinearLayout.LayoutParams(Ui.u(34), Ui.u(34)).apply { rightMargin = Ui.u(14) })
        faultRow.addView(faultText, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        faultRow.addView(Ui.button(a, tr("Acquitter"), Ui.Style.SECONDARY, Icon.Kind.CHECK, height = 60) {
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
                    a.confirm(tr("Contenu chaud"), tr("Le bol est à %d °C. Reprendre le mixage ?", s.actualTempC), tr("Reprendre"), icon = Icon.Kind.THERMO) {
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
        visibility = if (showJob || s.hasFault) View.VISIBLE else View.GONE
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
        val isSteam = s.jobKind == "STEAM"
        (speedChip.getChildAt(0) as Icon).set(if (isSteam) Icon.Kind.STEAM else Icon.Kind.SPEED)
        Ui.chipText(speedChip).text = if (isSteam) tr("Vapeur") else tr("Vitesse %d", s.jobSpeed)
        tempChip.visibility = if (s.jobTempLevel > 0) View.VISIBLE else View.GONE
        Ui.chipText(tempChip).text = "${Mcc.celsiusForLevel(s.jobTempLevel)} °C"
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

}
