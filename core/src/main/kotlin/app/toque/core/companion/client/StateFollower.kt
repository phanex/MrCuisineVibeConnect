package app.toque.core.companion.client

import app.toque.core.companion.StateWire
import java.io.IOException

/**
 * Suit l'état du robot par attente longue (GET /api/state?since=N) sur un fil démon à lui.
 * Reconnexion avec attente croissante ; [finder] peut retrouver le robot à une autre adresse
 * (DHCP) après quelques échecs. Les rappels arrivent sur le fil du suiveur : côté Android, repasser
 * sur le fil principal.
 */
class StateFollower(
    client: CompanionClient,
    private val listener: Listener,
    private val waitSeconds: Int = 20,
    /** Appelé après [relocateAfter] échecs de suite : renvoie la nouvelle adresse du robot, ou null. */
    private val finder: (() -> String?)? = null,
    private val relocateAfter: Int = 3,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    /** Rythme choisi par l'appli (attente longue, pauses, recherche) ; null : les valeurs ci-dessus. */
    private val pacing: Pacing? = null,
    /** Horloge qui avance aussi quand le téléphone dort (SystemClock.elapsedRealtime sur Android). */
    private val elapsedMs: () -> Long = { System.currentTimeMillis() },
) {
    /** Rythme du suivi, décidé par l'appli à chaque requête. */
    interface Pacing {
        /** Attente longue à demander au robot pour la prochaine requête (secondes). */
        fun waitSeconds(): Int
        /** Pause avant de réessayer après [failures] échecs de suite. */
        fun backoffMs(failures: Int): Long
        /** Appeler le chercheur après [failures] échecs de suite ? */
        fun locate(failures: Int): Boolean
        /** Attente « quiet » : la télémesure seule ne réveille pas (robot au repos, appli fermée). */
        fun quiet(): Boolean = false
    }

    interface Listener {
        /** Nouvel état (révision différente de la précédente). */
        fun onState(state: StateWire)
        /** Le robot ne répond plus (une fois, jusqu'au retour). */
        fun onUnreachable(error: Exception) {}
        /** Le robot répond de nouveau. */
        fun onReachable() {}
        /** Jeton refusé : le téléphone a été retiré sur le robot. Le suiveur s'arrête. */
        fun onUnauthorized() {}
        /** Une réponse est arrivée (nouvel état ou non), avant tout le reste : le moment de garder le téléphone éveillé. */
        fun onAnswer() {}
        /** Prochain essai dans [delayMs] après [failures] échecs de suite. */
        fun onRetry(failures: Int, delayMs: Long) {}
    }

    /** Client propre au suiveur, pour pouvoir couper son attente sans toucher aux autres requêtes. */
    val client: CompanionClient = client.copy()
    @Volatile private var running = false
    @Volatile private var thread: Thread? = null
    /** Début de la requête en cours (horloge [elapsedMs]), [NO_POLL] sans requête ; son attente demandée. */
    @Volatile private var pollStart = NO_POLL
    @Volatile private var pollWait = 0
    @Volatile private var restarting = false
    @Volatile private var pausing = false
    @Volatile var latest: StateWire? = null
        private set

    val isRunning: Boolean get() = running

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "toque-companion-follow").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        client.cancelAll()
        thread?.interrupt()
        thread = null
    }

    /** Nouvelle adresse (trouvée par l'appli) : l'attente en cours est coupée et reprend là. */
    fun retarget(host: String, port: Int = client.port) {
        client.host = host
        client.port = port
        client.cancelAll()
    }

    /**
     * Réessayer tout de suite : coupe la pause en cours ; avec [restart], coupe aussi l'attente en
     * cours sans compter d'échec (nouveau rythme : l'appli revient à l'écran).
     */
    fun nudge(restart: Boolean = false) {
        if (pausing) thread?.interrupt()
        else if (restart && pollStart != NO_POLL) {
            restarting = true
            client.cancelAll()
        }
    }

    /**
     * Attente sans réponse bien après son terme (robot éteint pendant que le téléphone dormait : les
     * délais de la socket n'avancent pas en veille) : coupée, comptée comme un échec. Vrai si coupée.
     */
    fun cutIfStale(graceMs: Long = 15_000): Boolean {
        val start = pollStart
        if (start == NO_POLL || elapsedMs() - start < pollWait * 1000L + client.readTimeoutMs + graceMs) return false
        client.cancelAll()
        return true
    }

    /** Un écouteur qui lève quoi que ce soit ne doit pas tuer le suiveur. */
    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
    }

    private fun loop() {
        try {
            follow()
        } catch (_: Throwable) {
            // Dernier filet : rien ne sort du fil du suiveur.
            running = false
        }
    }

    private fun follow() {
        var since: Long? = null
        var reachable = true
        var failures = 0
        while (running) {
            try {
                restarting = false
                val wait = pacing?.waitSeconds() ?: waitSeconds
                pollWait = if (since == null) 0 else wait
                pollStart = elapsedMs()
                val s = try {
                    // replace : un seul suiveur par téléphone, sa nouvelle attente remplace l'ancienne sur le robot.
                    client.state(since, wait, pacing?.quiet() ?: false, replace = true)
                } finally {
                    pollStart = NO_POLL
                }
                if (!running) return
                safe { listener.onAnswer() }
                if (!reachable) {
                    reachable = true
                    safe { listener.onReachable() }
                }
                failures = 0
                if (since == null || s.revision != since) {
                    latest = s
                    safe { listener.onState(s) }
                }
                since = s.revision
            } catch (e: CompanionException) {
                if (!running) return
                if (e.unauthorized) {
                    running = false
                    safe { listener.onUnauthorized() }
                    return
                }
                failures++
                if (reachable) {
                    reachable = false
                    safe { listener.onUnreachable(e) }
                }
                pause(failures)
            } catch (e: IOException) {
                if (!running) return
                if (restarting) {
                    // Coupée exprès (nudge) : pas un échec, on repart avec le nouveau rythme.
                    restarting = false
                    continue
                }
                failures++
                since = null
                if (reachable) {
                    reachable = false
                    safe { listener.onUnreachable(e) }
                }
                val locate = pacing?.locate(failures) ?: (failures >= relocateAfter && (failures - relocateAfter) % 4 == 0)
                if (finder != null && locate) {
                    val h = try {
                        finder.invoke()
                    } catch (_: Throwable) {
                        null
                    }
                    if (h != null) {
                        client.host = h
                        continue
                    }
                }
                pause(failures)
            } catch (e: InterruptedException) {
                if (!running) return
            } catch (e: Throwable) {
                // Réponse illisible, ou pire : comme une panne passagère, le suiveur continue.
                if (!running) return
                failures++
                pause(failures)
            }
        }
    }

    private fun pause(failures: Int) {
        val ms = pacing?.backoffMs(failures) ?: backoffMs(failures)
        safe { listener.onRetry(failures, ms) }
        pausing = true
        try {
            sleeper(ms)
        } catch (_: InterruptedException) {
        } finally {
            pausing = false
            // Une demande de réveil arrivée après la pause ne doit pas couper la suivante.
            Thread.interrupted()
        }
    }

    companion object {
        private const val NO_POLL = Long.MIN_VALUE

        /** 1 s, 2 s, 4 s, 8 s puis 15 s. */
        fun backoffMs(failures: Int): Long = minOf(1_000L shl (failures - 1).coerceIn(0, 4), 15_000L)
    }
}
