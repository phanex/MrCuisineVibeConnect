package app.toque.ui

import android.app.Activity
import android.provider.Settings
import android.view.Surface
import app.toque.Prefs
import app.toque.core.machine.RootShell
import app.toque.core.robot.RobotProfiles

/**
 * Monsieur Cuisine Smart : l'écran de sa tablette est en portrait de nature. Quand une autre fenêtre passe
 * un instant devant Toque, Android peut revenir au portrait pendant que le tactile reste calé sur le paysage,
 * et les appuis tombent à côté (#59). Toque bloque donc la rotation du système sur le paysage qu'il occupe
 * déjà (rotation automatique coupée, rotation fixée) : plus aucune fenêtre ne repasse en portrait.
 *
 * Seulement sur un Smart confirmé, avec la vraie base (pas en démo sur une autre tablette), par root, au
 * plus une fois par démarrage de l'appli, dans un fil à part, et rien si c'est déjà réglé. Le Connect n'est
 * pas concerné. Toque garde son « paysage selon le capteur » : la rotation qu'il a choisie est celle qui est
 * à l'endroit sur ce robot, c'est elle qui est retenue.
 */
object RotationLock {
    @Volatile private var done = false

    fun apply(a: Activity, prefs: Prefs, log: (String) -> Unit) {
        if (done || RobotProfiles.active !== RobotProfiles.MC_SMART || !prefs.robotConfirmed || prefs.mode != Prefs.Mode.HARDWARE) return
        @Suppress("DEPRECATION")
        val rotation = a.windowManager.defaultDisplay.rotation
        // Pas encore en paysage (fenêtre en cours de création) : on attendra le prochain retour au premier plan.
        if (rotation != Surface.ROTATION_90 && rotation != Surface.ROTATION_270) return
        val cr = a.contentResolver
        val auto = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1)
        val user = Settings.System.getInt(cr, Settings.System.USER_ROTATION, -1)
        done = true
        if (auto == 0 && user == rotation) return
        Thread {
            val r = RootShell.run("settings put system accelerometer_rotation 0 && settings put system user_rotation $rotation", 8_000)
            if (r.ok) log("rotation du système bloquée en paysage (rotation $rotation)")
            else {
                log("rotation du système non bloquée : ${r.err.trim().ifEmpty { "code ${r.code}" }}")
                done = false
            }
        }.apply {
            isDaemon = true
            name = "toque-rotation"
        }.start()
    }
}
