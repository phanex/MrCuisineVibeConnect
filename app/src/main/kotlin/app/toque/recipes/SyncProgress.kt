package app.toque.recipes

import android.os.Handler
import android.os.Looper

/**
 * Avancement de la synchronisation des recettes, gardé hors des écrans : celui des Réglages est
 * reconstruit à chaque ouverture, alors que la synchronisation continue en arrière-plan.
 * Lu et écouté sur le fil principal ; [report] s'appelle depuis n'importe quel fil.
 */
object SyncProgress {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()

    /** Dernier message d'avancement ; null quand aucune synchronisation n'est en cours. */
    var message: String? = null
        private set

    val running: Boolean get() = message != null

    /** La synchronisation en cours sait s'interrompre (celle du site ; pas la copie depuis l'appli d'origine). */
    var stoppable = true
        private set

    /** Arrêt demandé par l'utilisateur : la synchronisation s'interrompt dès que possible. Lu depuis son fil. */
    @Volatile var stopRequested = false
        private set

    /** Début demandé depuis l'interface (fil principal) : pris en compte tout de suite. */
    fun start(text: String, stoppable: Boolean = true) {
        stopRequested = false
        this.stoppable = stoppable
        message = text
        for (l in ArrayList(listeners)) l()
    }

    /** Avancement (depuis le fil de synchronisation). */
    fun report(text: String) {
        main.post {
            message = text
            for (l in ArrayList(listeners)) l()
        }
    }

    /** Fin de la synchronisation (depuis son fil), quelle qu'en soit l'issue. */
    fun finished() {
        stopRequested = false
        main.post {
            message = null
            stoppable = true
            for (l in ArrayList(listeners)) l()
        }
    }

    /** Bouton Arrêter (fil principal). */
    fun requestStop() {
        if (!running || !stoppable || stopRequested) return
        stopRequested = true
        for (l in ArrayList(listeners)) l()
    }

    /** À appeler sur le fil principal. Rend la fonction de désabonnement. */
    fun onChange(l: () -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }
}
