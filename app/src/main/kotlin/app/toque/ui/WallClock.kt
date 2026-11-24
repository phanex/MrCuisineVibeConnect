package app.toque.ui

import android.content.Context
import app.toque.TimeSync

/**
 * Heure affichée sur l'accueil, la barre du haut et la veille. Après une coupure de courant la
 * tablette se croit en 2010 : tant que l'heure n'est pas réglée et le Wi-Fi connecté
 * ([TimeSync.showClock]), on montre « --:-- » plutôt qu'une heure fausse, et les écrans regardent
 * toutes les [UNKNOWN_TICK_MS] pour afficher la vraie heure dès qu'elle est réglée.
 */
object WallClock {
    const val UNKNOWN = "--:--"
    const val UNKNOWN_TICK_MS = 2_000L

    fun known(c: Context): Boolean = TimeSync.showClock(c)

    fun text(c: Context): String = if (known(c)) MainActivity.now() else UNKNOWN

    /** Délai avant le prochain rafraîchissement : [normalMs], ou bien plus court tant que l'heure n'est pas réglée. */
    fun nextMs(c: Context, normalMs: Long): Long = if (known(c)) normalMs else minOf(normalMs, UNKNOWN_TICK_MS)
}
