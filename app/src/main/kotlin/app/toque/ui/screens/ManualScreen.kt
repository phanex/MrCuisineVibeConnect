package app.toque.ui.screens

import app.toque.core.robot.Feature
import app.toque.core.robot.RobotProfiles
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.machine.Accessory
import app.toque.core.machine.JobKind
import app.toque.core.machine.JobState
import app.toque.core.machine.ManualLimits
import app.toque.core.machine.ManualSettings
import app.toque.core.machine.SafetyPolicy
import app.toque.core.machine.Snapshot
import app.toque.ui.HoldButton
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.TempRing
import app.toque.ui.Ui
import app.toque.ui.ValueCard

/**
 * Cuisson manuelle. Les réglages incompatibles sont corrigés à la saisie (vitesse limitée en
 * chauffe, en sens inverse ou avec le fouet) plutôt que refusés au démarrage ; les règles sont
 * celles de [ManualSettings], partagées avec le téléphone.
 *
 * Pendant une cuisson manuelle (en cours, au démarrage ou en pause), les cartes montrent le réglage
 * de la cuisson lu dans l'instantané, qu'il vienne d'ici ou d'un téléphone, sauf pendant une saisie
 * locale le temps que la base l'applique.
 *
 * Avec [onPick], sert à choisir le réglage d'une étape de recette perso : mêmes cartes et mêmes
 * limites de sécurité, mais rien ne démarre ; « Utiliser ce réglage » le renvoie.
 */
class ManualScreen(
    a: MainActivity,
    preset: app.toque.core.recipes.StepMachine? = null,
    private val onPick: ((app.toque.core.recipes.StepMachine) -> Unit)? = null,
) : Screen(a) {
    override val title = if (onPick != null) tr("Réglage de l'étape") else JobKind.MANUAL.label

    /** Températures du robot choisi (le Smart a 40 °C en plus) ; chauffe sans mélange seulement s'il la sait faire. */
    private val caps = RobotProfiles.active.capabilities
    // Le même départ que l'éditeur d'étape du téléphone (StepSettings.manualStart) : température de la
    // table juste en dessous, vitesse 0 seulement avec une température et si le robot le permet.
    private var set = app.toque.core.recipes.StepSettings.manualStart(preset,
        ManualLimits(temperaturesC = caps.temperaturesC, heatNoStir = caps.has(Feature.HEAT_NO_STIR)))
    private val seconds get() = set.seconds
    private val tempLevel get() = set.tempLevel
    /** °C réglés (40 °C sur le Smart compris) ; 0 = sans chauffe. */
    private val tempC get() = set.tempC
    /** Smart, 40 °C : niveau de sa table ; null sinon. */
    private val smartLevel get() = set.smartLevel
    private val speed get() = set.speed
    private val reverse get() = set.reverse
    private val accessory get() = set.accessory
    private var selected = 0

    private lateinit var timeCard: ValueCard
    private lateinit var tempCard: ValueCard
    private lateinit var speedCard: ValueCard
    private lateinit var dirRow: LinearLayout
    private lateinit var accRow: LinearLayout
    private lateinit var ring: TempRing
    private lateinit var weight: TextView
    private lateinit var startBtn: LinearLayout
    private lateinit var hint: TextView
    private lateinit var turbo: HoldButton
    private var live = false
    private var wasLive = false
    /** Cuisson manuelle à une phase en cours, au démarrage ou en pause : les cartes la suivent et la règlent. */
    private var following = false
    private var wasFollowing = false
    /** La cuisson suivie a été lancée par cet écran (sinon, sa durée totale devient la durée réglée à la fin). */
    private var startedHere = false
    private var pendingEdit = false
    private var timeEdited = false
    /** Réglages touchés ici depuis le dernier envoi : seuls ceux-là partent (un réglage venu d'un téléphone n'est pas écrasé). */
    private var touchedTemp = false
    private var touchedSpeed = false
    private var touchedDir = false
    private var configuredSeconds = seconds
    private val pushLive = Runnable { sendAdjust() }
    /** Jusqu'à cette heure, cartes et anneau montrent le réglage fait ici plutôt que celui de la cuisson en cours. */
    private var ownTargetUntil = 0L

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(24), Ui.u(8), Ui.u(24), Ui.u(20))
        val left = Ui.vertical(c)
        val cards = Ui.horizontal(c)
        timeCard = ValueCard(c, tr("Durée"), Icon.Kind.CLOCK)
        tempCard = ValueCard(c, tr("Température"), Icon.Kind.THERMO)
        speedCard = ValueCard(c, tr("Vitesse"), Icon.Kind.SPEED)
        val all = listOf(timeCard, tempCard, speedCard)
        for ((i, card) in all.withIndex()) {
            card.onSelect = { selected = i; refresh() }
            card.onMinus = { change(i, -1) }
            card.onPlus = { change(i, 1) }
            cards.addView(card, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (i < 2) rightMargin = Ui.u(14) })
        }
        left.addView(cards, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        val options = Ui.horizontal(c)
        dirRow = Ui.horizontal(c)
        accRow = Ui.horizontal(c)
        options.addView(dirRow)
        options.addView(Ui.spacer(c, w = 18))
        options.addView(accRow)
        options.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(0, 1, 1f))
        turbo = HoldButton(c, "Turbo", onDown = {
            a.turboDown(accessory)
        }, onUp = { a.machine.turboUp() })
        options.addView(turbo, Ui.lp(Ui.WRAP, Ui.u(64)))
        if (onPick != null) turbo.visibility = View.GONE
        left.addView(options, Ui.lp(Ui.MATCH, Ui.WRAP, t = 16))
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { rightMargin = Ui.u(20) })

        val right = Ui.vertical(c, Gravity.CENTER_HORIZONTAL)
        right.background = Ui.round(Ui.SURFACE, 26f)
        right.setPadding(Ui.u(18), Ui.u(14), Ui.u(18), Ui.u(18))
        ring = TempRing(c)
        right.addView(ring, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        weight = Ui.text(c, "", 18f, Ui.TEXT2)
        right.addView(weight, Ui.lp(Ui.WRAP, Ui.WRAP, t = 2))
        hint = Ui.text(c, "", 16f, Ui.WARN).apply { gravity = Gravity.CENTER }
        right.addView(hint, Ui.lp(Ui.MATCH, Ui.WRAP, t = 8))
        startBtn = if (onPick != null) Ui.button(c, tr("Utiliser ce réglage"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 80, textSize = 22f) { start() }
        else Ui.button(c, tr("Démarrer"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 80, textSize = 24f) { start() }
        right.addView(startBtn, Ui.lp(Ui.MATCH, Ui.WRAP, t = 12))
        root.addView(right, LinearLayout.LayoutParams(Ui.u(300), Ui.MATCH))
        refresh()
        return root
    }

    private fun change(card: Int, dir: Int) {
        val s = a.snapshot
        val old = set
        val e = set.change(card, dir)
        set = e.settings
        for (m in e.messages) a.toast(m)
        val liveEdit = following && s.kind?.name == "MANUAL"
        if (liveEdit) {
            if (card == 0) timeEdited = true
            if (set.tempLevel != old.tempLevel || set.smartLevel != old.smartLevel) touchedTemp = true
            if (set.speed != old.speed) touchedSpeed = true
            pendingEdit = true
            // Le temps que la base applique le réglage et le renvoie dans son état.
            ownTargetUntil = SystemClock.uptimeMillis() + 1_500
        }
        refresh()
        if (liveEdit) {
            view.removeCallbacks(pushLive)
            view.postDelayed(pushLive, 350)
        }
    }

    private fun setReverse(r: Boolean) {
        val e = set.withReverse(r) ?: return // sans mélange, pas de sens de rotation
        val old = set
        set = e.settings
        for (m in e.messages) a.toast(m)
        refresh()
        if (following) {
            touchedDir = true
            if (set.speed != old.speed) touchedSpeed = true
            pendingEdit = true
            ownTargetUntil = SystemClock.uptimeMillis() + 1_500
            view.removeCallbacks(pushLive)
            view.postDelayed(pushLive, 200)
        }
    }

    private fun setAccessory(acc: Accessory) {
        // L'accessoire d'une cuisson lancée ne se change pas.
        if (live || following) return
        val e = set.withAccessory(acc)
        set = e.settings
        for (m in e.messages) a.toast(m)
        refresh()
    }

    private fun spec(hot: Boolean = false) = set.spec(hot)

    private fun start() {
        onPick?.let { pick ->
            pick(app.toque.core.recipes.StepSettings.manualPicked(set))
            a.back()
            return
        }
        val s = a.snapshot
        if (s.state == JobState.PAUSED && s.kind?.name == "MANUAL") {
            a.machine.resume(s.hotBlendConfirmed) { a.refusal(it) }
            return
        }
        configuredSeconds = seconds
        startedHere = true
        a.startJob(spec()) { v -> if (!v.ok) startedHere = false }
    }

    private fun sendAdjust() {
        pendingEdit = false
        val s = a.snapshot
        if (!s.jobActive || s.kind?.name != "MANUAL") return
        // Seuls les réglages touchés ici partent ; la durée n'est renvoyée que si elle a été modifiée :
        // changer la vitesse ne relance pas le minuteur.
        val total: Long? = if (timeEdited) s.elapsedMs + seconds * 1000L else null
        val sp = if (touchedSpeed) speed else null
        val tl = if (touchedTemp) tempLevel else null
        val rv = if (touchedDir) reverse else null
        timeEdited = false
        touchedSpeed = false
        touchedTemp = false
        touchedDir = false
        if (sp == null && tl == null && rv == null && total == null) return
        // Smart, 40 °C : le niveau de sa table part avec la température.
        val sl = if (tl != null) smartLevel else null
        // L'instantané qui suivra la réponse porte le réglage : les cartes l'attendent encore un peu.
        ownTargetUntil = maxOf(ownTargetUntil, SystemClock.uptimeMillis() + 1_500)
        a.machine.adjust(sp, tl, rv, total, s.hotBlendConfirmed, sl) { v ->
            if (!v.ok && v.has(SafetyPolicy.HOT_BLEND)) {
                // La confirmation envoie le réglage demandé à l'ouverture de la fenêtre, quoi qu'il arrive entre-temps.
                val shown = sp ?: a.snapshot.jobSpeed
                val q = if (RobotProfiles.active.capabilities.measuresTemperature) tr("Le bol est à %d °C. Passer à la vitesse %d ?", a.snapshot.actualTempC, shown)
                    else tr("Ce robot ne mesure pas toujours la température du bol : Toque considère le contenu comme chaud. Passer à la vitesse %d ?", shown)
                a.confirm(tr("Contenu chaud"), q, tr("Confirmer"), icon = Icon.Kind.THERMO) {
                    a.machine.adjust(sp, tl, rv, total, true, sl) { a.refusal(it) }
                }
                resync()
            } else {
                a.refusal(v)
                if (!v.ok) resync()
            }
        }
    }

    /** Refus : les cartes reprennent aussitôt le réglage de la cuisson. */
    private fun resync() {
        if (pendingEdit) return
        ownTargetUntil = 0L
        onSnapshot(a.snapshot)
    }

    private fun refresh() {
        val maxS = if (speed > 6) "max 15 min" else "max 99 min"
        timeCard.set(Ui.clock(seconds * 1000L), "", if (live && !pendingEdit) tr("restant") else maxS)
        if (tempLevel == 0) tempCard.set("—", "", tr("Sans chauffe"), Ui.TEXT2)
        else {
            val t = tempC
            tempCard.set("$t", "°C", if (t >= 100) tr("Ébullition") else if (t >= 60) tr("Cuisson") else tr("Tiédir"), Ui.tempColor(t))
        }
        speedCard.set("$speed", "", when (speed) {
            0 -> tr("Sans mélange")
            1 -> tr("Mijoter")
            2, 3 -> tr("Mélanger")
            in 4..6 -> tr("Hacher")
            else -> tr("Mixer")
        } + if (reverse && speed > 0) " · " + tr("inverse") else "")
        timeCard.select(selected == 0)
        tempCard.select(selected == 1)
        speedCard.select(selected == 2)

        dirRow.removeAllViews()
        dirRow.alpha = if (speed == 0) 0.35f else 1f
        dirRow.addView(Ui.toggleChip(c, tr("Sens normal"), !reverse) { setReverse(false) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        dirRow.addView(Ui.toggleChip(c, tr("Inverse"), reverse) { setReverse(true) })
        accRow.removeAllViews()
        accRow.addView(Ui.toggleChip(c, Accessory.BLADE.label, accessory == Accessory.BLADE) { setAccessory(Accessory.BLADE) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        accRow.addView(Ui.toggleChip(c, Accessory.WHISK.label, accessory == Accessory.WHISK) { setAccessory(Accessory.WHISK) })

        // Pour une étape de recette, le bol d'aujourd'hui ne compte pas.
        val bowl = if (onPick == null && a.snapshot.hasSample) a.snapshot.actualTempC else null
        val v = SafetyPolicy.checkJob(spec(), bowl).filter { it.code != SafetyPolicy.HOT_BLEND }
        hint.text = v.firstOrNull()?.message ?: if (onPick == null && a.snapshot.hot && speed >= 4) tr("Contenu chaud : confirmation demandée") else idleHint()
        Ui.setButtonEnabled(startBtn, v.isEmpty())
        showRing(a.snapshot)
    }

    /**
     * Anneau : température mesurée et repère de consigne. Le repère suit la carte Température dès
     * l'appui, sans attendre la prochaine trame de la base (jusqu'à une seconde au repos) ; en
     * cuisson, il revient à la consigne du programme une fois le réglage appliqué.
     */
    private fun showRing(s: Snapshot) {
        val editing = pendingEdit || SystemClock.uptimeMillis() < ownTargetUntil
        // °C exacts du programme (40 °C sur le Smart), pas le niveau de Toque juste en dessous.
        val target = if (s.jobActive && s.jobTempLevel > 0 && !editing) s.jobTempC else tempC
        ring.set(if (s.hasSample) s.actualTempC else 0, target, s.speedLevel)
    }

    override fun onSnapshot(s: Snapshot) {
        showRing(s)
        weight.text = if (s.hasSample) tr("Balance : %d g", s.weightG) else tr("Base non connectée")
        if (onPick != null) return
        val manualJob = s.kind?.name == "MANUAL" && (s.state == JobState.RUNNING || s.state == JobState.STARTING || s.state == JobState.PAUSED)
        live = manualJob && s.state != JobState.PAUSED
        following = manualJob && s.phaseCount == 1
        tempCard.repeatEnabled = !live
        speedCard.repeatEnabled = !live
        val editing = pendingEdit || SystemClock.uptimeMillis() < ownTargetUntil
        if (following && !editing) {
            // Le réglage de la cuisson, d'où qu'il vienne (cet écran, un téléphone) ; la carte Durée
            // affiche le temps restant.
            val remaining = ((s.remainingMs + 999) / 1000).toInt().coerceAtLeast(1)
            val acc = runCatching { Accessory.valueOf(s.accessory) }.getOrElse { accessory }
            val job = set.copy(seconds = remaining, speed = s.jobSpeed, reverse = s.jobReverse, accessory = acc)
                .withCelsius(if (s.jobTempLevel > 0) s.jobTempC else 0)
            if (!startedHere) configuredSeconds = (s.totalMs / 1000).toInt().coerceIn(ManualLimits.MIN_SECONDS, job.maxSeconds)
            if (job != set || !wasFollowing) {
                val onlyTime = job.copy(seconds = seconds) == set
                set = job
                if (onlyTime) timeCard.set(Ui.clock(seconds * 1000L), "", tr("restant")) else refresh()
            }
        }
        if (wasLive && !live && !manualJob) {
            set = set.copy(seconds = configuredSeconds)
            refresh()
        }
        if (wasFollowing && !following) startedHere = false
        wasLive = live
        wasFollowing = following
        val busy = s.busy
        // En pause aussi : la reprise est dans la barre du bas (et sur la molette), pas de second bouton ici.
        startBtn.visibility = if (busy) View.GONE else View.VISIBLE
        turbo.isEnabled = !busy || s.turboActive
        turbo.alpha = if (turbo.isEnabled) 1f else 0.35f
        if (live) hint.text = tr("Réglages appliqués en direct")
        else if (!busy) refreshHintOnly()
    }

    private fun refreshHintOnly() {
        val v = SafetyPolicy.checkJob(spec(), if (a.snapshot.hasSample) a.snapshot.actualTempC else null)
            .filter { it.code != SafetyPolicy.HOT_BLEND }
        hint.text = v.firstOrNull()?.message ?: if (a.snapshot.hot && speed >= 4) tr("Contenu chaud : confirmation demandée") else idleHint()
    }

    /** Rappel affiché quand rien ne s'oppose au démarrage : sans mélange, rien ne décolle le fond du bol. */
    private fun idleHint(): String = if (speed == 0 && tempLevel > 0) tr("Sans mélange : surveillez la cuisson") else ""

    override fun onKnob(delta: Int): Boolean {
        change(selected, delta)
        return true
    }

    override fun onKnobPress(): Boolean {
        if (onPick != null) { start(); return true }
        val s = a.snapshot
        when {
            s.state == JobState.RUNNING -> a.machine.pause { a.refusal(it) }
            else -> start()
        }
        return true
    }
}
