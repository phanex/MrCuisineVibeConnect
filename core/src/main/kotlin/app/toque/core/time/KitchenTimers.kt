package app.toque.core.time

import app.toque.core.json.Json
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.json.str

/**
 * Minuteur de cuisine : indépendant de la machine (ni moteur ni chauffe). [endsAt] est compté sur
 * une horloge qui ne saute pas (temps écoulé depuis le démarrage de la tablette) : l'heure murale du
 * robot n'a pas de pile et se corrige par le réseau après un démarrage.
 */
data class KitchenTimer(val id: Long, val label: String, val durationMs: Long, val endsAt: Long) {
    fun remaining(now: Long): Long = maxOf(0L, endsAt - now)
    fun ended(now: Long): Boolean = now >= endsAt
}

/** La liste des minuteurs (au plus [MAX] en cours), et sa sauvegarde. */
class KitchenTimerList {
    private val items = ArrayList<KitchenTimer>()
    private var nextId = 1L

    val all: List<KitchenTimer> get() = ArrayList(items)

    fun running(now: Long): List<KitchenTimer> = items.filter { !it.ended(now) }.sortedBy { it.endsAt }

    /** Terminés et pas encore acquittés (« OK »), du plus ancien au plus récent. */
    fun ringing(now: Long): List<KitchenTimer> = items.filter { it.ended(now) }.sortedBy { it.endsAt }

    fun canAdd(now: Long): Boolean = running(now).size < MAX

    /** Null : déjà [MAX] minuteurs en cours, ou durée hors limites. */
    fun add(label: String, durationMs: Long, now: Long): KitchenTimer? {
        if (!canAdd(now) || durationMs < MIN_MS || durationMs > MAX_MS) return null
        val t = KitchenTimer(nextId++, label, durationMs, now + durationMs)
        items += t
        return t
    }

    /** Ajoute [ms] au minuteur [id] encore en cours (« +1 min »). */
    fun extend(id: Long, ms: Long, now: Long): Boolean {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0 || items[i].ended(now)) return false
        val t = items[i]
        val end = minOf(t.endsAt + ms, now + MAX_MS)
        items[i] = t.copy(endsAt = end, durationMs = t.durationMs + (end - t.endsAt))
        return true
    }

    /** Annule un minuteur en cours, ou acquitte un minuteur terminé. */
    fun remove(id: Long): Boolean {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return false
        items.removeAt(i)
        return true
    }

    fun clear() = items.clear()

    fun encode(bootId: String, now: Long): String = Json.write(linkedMapOf(
        "boot" to bootId,
        "savedAt" to now,
        "next" to nextId,
        "timers" to items.map { linkedMapOf("id" to it.id, "label" to it.label, "duration" to it.durationMs, "endsAt" to it.endsAt) },
    ))

    companion object {
        /** Minuteurs en cours en même temps, au plus. */
        const val MAX = 3
        const val MIN_MS = 10_000L
        const val MAX_MS = 12 * 3_600_000L

        /**
         * Reprend les minuteurs enregistrés par [text]. Le temps écoulé repart de zéro à chaque démarrage
         * de la tablette : après un redémarrage (identifiant de démarrage [bootId] différent, ou à défaut une
         * horloge revenue en arrière), on ne sait plus combien de temps a passé et les minuteurs sont perdus
         * ([Restored.lost]). Un texte illisible ne donne rien.
         */
        fun restore(text: String, bootId: String, now: Long): Restored {
            val m = try {
                Json.parse(text).obj()
            } catch (_: Exception) {
                return Restored(KitchenTimerList(), emptyList())
            }
            val timers = m["timers"].list().mapNotNull { v ->
                val o = v.obj()
                val id = (o["id"] as? Number)?.toLong() ?: return@mapNotNull null
                val duration = (o["duration"] as? Number)?.toLong() ?: return@mapNotNull null
                val end = (o["endsAt"] as? Number)?.toLong() ?: return@mapNotNull null
                KitchenTimer(id, o.str("label").orEmpty(), duration, end)
            }
            val savedBoot = m.str("boot").orEmpty()
            val savedAt = (m["savedAt"] as? Number)?.toLong() ?: 0L
            val restarted = if (savedBoot.isNotEmpty() && bootId.isNotEmpty()) savedBoot != bootId else now < savedAt
            val list = KitchenTimerList()
            list.nextId = maxOf(1L, (m["next"] as? Number)?.toLong() ?: 1L, (timers.maxOfOrNull { it.id } ?: 0L) + 1)
            if (restarted) return Restored(list, timers)
            // Un minuteur dont la fin est trop loin ne vient pas de ce démarrage : écarté.
            for (t in timers) if (t.endsAt - now <= MAX_MS) list.items += t
            return Restored(list, emptyList())
        }
    }

    /** Minuteurs repris, et ceux perdus dans un redémarrage de la tablette. */
    class Restored(val list: KitchenTimerList, val lost: List<KitchenTimer>)
}
