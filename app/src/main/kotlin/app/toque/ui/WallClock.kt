package app.toque.ui

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

    /** L'horloge est plausible (remplaçable dans les essais). */
    @Volatile var plausible: () -> Boolean = { TimeSync.plausible() }

    /** Le robot est connecté au Wi-Fi ; posé par l'activité (remplaçable dans les essais). */
    @Volatile var online: () -> Boolean = { true }

    val known: Boolean get() = plausible() && online()

    fun text(): String = if (known) MainActivity.now() else UNKNOWN

    /** Délai avant le prochain rafraîchissement : [normalMs], ou bien plus court tant que l'heure n'est pas réglée. */
    fun nextMs(normalMs: Long): Long = if (known) normalMs else minOf(normalMs, UNKNOWN_TICK_MS)
}
