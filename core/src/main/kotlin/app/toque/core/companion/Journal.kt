package app.toque.core.companion

/** Auteur d'une action : le robot (phoneId vide) ou un téléphone. */
data class Actor(val phoneId: String = "", val name: String = "") {
    val isRobot: Boolean get() = phoneId.isEmpty()

    fun wire(viewer: String): Origin =
        if (isRobot) Origin() else Origin(Origin.PHONE, phoneId, name, phoneId == viewer)

    companion object {
        val ROBOT = Actor()
        fun of(p: Phone) = Actor(p.id, p.name)
    }
}

/** Genres d'alerte portés par les lignes du journal (le téléphone notifie une seule fois par [JournalEntry.seq]). */
object Alerts {
    /** Le robot attend la personne : pesée, étape à la main, étape à lancer, recette reçue… */
    const val WAITING = "waiting"
    /** Une étape de recette est finie. */
    const val STEP_END = "step_end"
    /** Fin d'une cuisson, d'un programme ou d'une recette (terminé, arrêté, annulé). */
    const val JOB_END = "job_end"
    /** Pause, avec sa raison (USER, LID, BOWL, SAFETY) dans [JournalEntry.detail]. */
    const val PAUSE = "pause"
    /** Alerte machine (défaut de la base). */
    const val FAULT = "fault"
    /** Le bol a atteint la température demandée (cuisson manuelle ou étape de recette), une fois. */
    const val TEMP_REACHED = "temp_reached"
}

/**
 * Journal d'activité, borné : [maxEntries] lignes et [maxActivities] activités, les plus anciennes
 * sortent d'abord. Pas sûr entre fils : [CompanionHub] l'appelle sous son verrou.
 */
class Journal(val maxEntries: Int = 300, val maxActivities: Int = 60) {
    private class Line(
        val seq: Long, val at: Long, val kind: String, val text: String, val alert: String, val actor: Actor,
        val activityId: Long, val stepIndex: Int, val elapsedMs: Long, val detail: String,
    )

    class Activity(
        val id: Long, val kind: String, val title: String, val recipeId: String, val stepCount: Int,
        val handSteps: Int, val startedAt: Long, var summary: String, var totalMs: Long,
    ) {
        var endedAt = 0L
        var outcome = "running"
        var endActor: Actor? = null

        fun wire(viewer: String) = ActivityWire(id, kind, title, recipeId, stepCount, handSteps, startedAt, endedAt,
            outcome, summary, totalMs, endActor?.wire(viewer))
    }

    private val lines = ArrayList<Line>()
    private val activities = ArrayList<Activity>()
    var lastSeq = 0L
        private set
    private var nextActivity = 1L

    fun add(at: Long, kind: String, text: String, alert: String = "", actor: Actor = Actor.ROBOT, activityId: Long = 0,
            stepIndex: Int = -1, elapsedMs: Long = 0, detail: String = ""): Long {
        val seq = ++lastSeq
        lines += Line(seq, at, kind, text, alert, actor, activityId, stepIndex, elapsedMs, detail)
        while (lines.size > maxEntries) lines.removeAt(0)
        return seq
    }

    fun start(at: Long, kind: String, title: String, recipeId: String = "", stepCount: Int = 0, handSteps: Int = 0,
              summary: String = "", totalMs: Long = 0): Activity {
        val a = Activity(nextActivity++, kind, title, recipeId, stepCount, handSteps, at, summary, totalMs)
        activities += a
        while (activities.size > maxActivities) activities.removeAt(0)
        return a
    }

    fun end(a: Activity, at: Long, outcome: String, actor: Actor? = null) {
        if (a.outcome != "running") return
        a.endedAt = at
        a.outcome = outcome
        a.endActor = actor
    }

    fun activity(id: Long): Activity? = activities.lastOrNull { it.id == id }

    /** Dernière activité terminée. */
    fun lastEnded(): Activity? = activities.lastOrNull { it.outcome != "running" }

    /** Lignes de numéro > [after], les plus récentes d'abord, au plus [limit] ; avec leurs activités. */
    fun page(after: Long, limit: Int, viewer: String): JournalPage {
        val picked = ArrayList<Line>()
        for (i in lines.indices.reversed()) {
            val l = lines[i]
            if (l.seq <= after || picked.size >= limit) break
            picked += l
        }
        val entries = picked.map {
            JournalEntry(it.seq, it.at, it.kind, it.text, it.alert, it.actor.wire(viewer), it.activityId, it.stepIndex, it.elapsedMs, it.detail)
        }
        val acts = if (after > 0) {
            val ids = HashSet<Long>()
            for (l in picked) if (l.activityId != 0L) ids += l.activityId
            activities.filter { it.id in ids }
        } else activities
        return JournalPage(lastSeq, entries, acts.reversed().map { it.wire(viewer) })
    }
}
