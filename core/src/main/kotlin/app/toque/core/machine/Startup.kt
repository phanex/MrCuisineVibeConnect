package app.toque.core.machine

/**
 * Démarrage de l'interface : tant que la base moteur n'est pas prête, un écran d'attente (la toque et
 * un indicateur) couvre les écrans. [waiting] dit s'il doit rester.
 *
 * Sur le robot, le service machine ne dit rien pendant qu'il ouvre la liaison série (une dizaine de
 * secondes) : son premier état n'arrive qu'une fois la liaison ouverte, ou refusée.
 */
object Startup {
    /** Attente la plus longue : au-delà, l'écran d'attente s'efface et la barre du haut dit où en est la base. */
    const val MAX_WAIT_MS = 30_000L

    /**
     * [heard] : le service machine a déjà envoyé un état depuis le démarrage de l'interface ;
     * [elapsedMs] : temps écoulé depuis que l'écran d'attente est affiché.
     */
    fun waiting(s: Snapshot, heard: Boolean, elapsedMs: Long): Boolean = when {
        elapsedMs >= MAX_WAIT_MS -> false
        // Le service n'a encore rien dit : il démarre, ou il ouvre la liaison.
        !heard -> true
        s.link == LinkState.READY -> false
        // Une alerte, un programme (interface relancée seule pendant une cuisson) : jamais cachés.
        s.hasFault || s.jobActive || s.turboActive -> false
        // Liaison ouverte : la base n'a pas encore assez répondu (« Connexion… »).
        s.transportUp -> s.link != LinkState.LOST
        // Liaison refusée (root, port pris) ou rendue à l'appli d'origine : l'écran doit le montrer.
        else -> false
    }
}
