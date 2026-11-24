package app.toque.core.ring

import app.toque.core.machine.JobState
import app.toque.core.machine.PauseReason
import app.toque.core.machine.Snapshot

/**
 * Valeurs des trois LED de l'anneau de la molette : « red » (rouge), « green » (vert) et « blue »,
 * qui allume du blanc (pas de LED bleue). L'échelle monte de 1 à 79 puis repart de très faible à 80
 * et à 160 : [MAX] est la plus forte, on n'écrit jamais au-dessus.
 */
data class Rgb(val red: Int, val green: Int, val white: Int) {
    /** Toutes les valeurs dans 0..[MAX] : seule une telle couleur est écrite. */
    val valid: Boolean get() = red in 0..MAX && green in 0..MAX && white in 0..MAX

    override fun toString(): String = "$red $green $white"

    companion object {
        const val MAX = 79
        /** Le blanc habituel du robot (SKMC 1100 A1), quand celui lu au démarrage est inutilisable. */
        val USUAL_WHITE = Rgb(0, 0, 50)
    }
}

/** Code couleur de l'anneau, du plus prioritaire au moins prioritaire. */
enum class RingColour(val rgb: Rgb?) {
    /** Un problème : défaut de la machine, ou programme interrompu par la sécurité. */
    RED(Rgb(79, 0, 0)),
    /** Programme en pause : pause demandée, couvercle ouvert, bol retiré. */
    ORANGE(Rgb(79, 30, 0)),
    /** À vous : programme terminé ou arrêté encore affiché (il attend « OK »), ou recette guidée qui attend un geste. */
    GREEN(Rgb(0, 79, 0)),
    /** Un programme tourne (cuisson, programme, Turbo), à froid ou à chaud. */
    CYAN(Rgb(0, 79, 79)),
    /** Rien de tout ça : le blanc propre au robot, lu au démarrage de Toque (pas de valeur fixe). */
    WHITE(null);

    companion object {
        /**
         * Couleur à montrer pour [s]. [cookWaiting] : une recette guidée affichée attend la
         * personne (pesée, étape à la main, étape à lancer, mixage à chaud à confirmer, fin).
         * Le bol chaud n'y figure pas : c'est le bandeau de l'écran qui le montre.
         */
        fun of(s: Snapshot, cookWaiting: Boolean = false): RingColour {
            val st = s.state
            return when {
                s.hasFault -> RED
                st == JobState.PAUSED && s.pauseReason == PauseReason.SAFETY.name -> RED
                st == JobState.PAUSED -> ORANGE
                // Terminé, arrêté à la main ou annulé : tant que la barre du bas le montre avec « OK ».
                st == JobState.FINISHED || st == JobState.ABORTED -> GREEN
                cookWaiting -> GREEN
                s.running || s.turboActive -> CYAN
                else -> WHITE
            }
        }
    }
}

/** Accès aux LED de l'anneau (root sur le robot ; une imitation dans les tests). */
interface RingLeds {
    /**
     * Valeurs à considérer comme l'état propre de l'anneau : celles notées par [write] si une note
     * existe (Toque arrêté alors que l'anneau était coloré ou en veilleuse), sinon celles des LED.
     * null : LED absentes ou root refusé.
     */
    fun read(): Rgb?

    /**
     * Écrit [colour] sur les trois LED. [restore] non nul : note ces valeurs comme celles à remettre
     * plus tard (gardée si Toque s'arrête entre-temps) ; null : efface la note. false en cas d'échec.
     */
    fun write(colour: Rgb, restore: Rgb?): Boolean
}

/**
 * Seul maître de l'anneau : code couleur et veille de l'écran passent tous deux par ici, pour ne
 * pas se contredire. On n'écrit que quand la couleur à montrer change (des écritures fréquentes
 * rendent l'anneau irrégulier). Appels à faire depuis un seul fil.
 *
 * Le blanc est celui que l'anneau avait au démarrage de Toque, lu une seule fois. Si les LED sont
 * absentes ou le root refusé, plus rien n'est tenté et c'est noté une fois dans le journal.
 */
class KnobRing(private val leds: RingLeds, private val log: (String) -> Unit = {}) {
    private var white: Rgb? = null
    private var broken = false
    /** Ce qui est sur l'anneau, si Toque l'a écrit (null : inconnu). */
    private var shown: Rgb? = null
    /** Couleur demandée par le code couleur ; null : code couleur coupé (anneau laissé tel quel). */
    private var wanted: RingColour? = null
    private var asleep = false
    /** Code couleur coupé : ce qu'avait l'anneau avant la veille, remis au réveil. */
    private var beforeSleep: Rgb? = null

    val available: Boolean get() = !broken

    /**
     * Couleur du code couleur ([colour]), ou null quand le code couleur est coupé : l'anneau reprend
     * alors son blanc et n'est plus touché qu'en veille. En veille, retenue pour le réveil.
     */
    fun show(colour: RingColour?) {
        if (broken) return
        val was = wanted
        wanted = colour
        if (asleep) return
        if (colour == null) {
            if (was != null) restoreWhite()
            return
        }
        apply(rgbOf(colour) ?: return)
    }

    /** Veille de l'écran : veilleuse blanche d'intensité [level] (0 : éteinte). */
    fun standby(level: Int) {
        if (broken || asleep) return
        val night = Rgb(0, 0, level.coerceIn(0, Rgb.MAX))
        val restore = if (wanted != null) baseWhite() else leds.read()?.takeIf { it.valid }
        if (restore == null) return fail("lecture de l'anneau impossible (LED absentes ou root refusé)")
        asleep = true
        beforeSleep = if (wanted == null) restore else null
        write(night, restore)
    }

    /** Réveil : l'anneau montre la couleur du moment (ou, code couleur coupé, ce qu'il avait). */
    fun wake() {
        if (broken || !asleep) return
        asleep = false
        val colour = wanted
        if (colour != null) {
            apply(rgbOf(colour) ?: return)
        } else {
            val back = beforeSleep ?: baseWhite() ?: return
            write(back, null)
            shown = null
        }
        beforeSleep = null
    }

    /** Toque rend l'écran (appli d'origine, Toque masqué) : le blanc du robot revient. */
    fun release() {
        if (broken) return
        if (asleep) wake()
        if (wanted != null) restoreWhite()
        wanted = null
    }

    private fun restoreWhite() {
        val w = baseWhite() ?: return
        if (shown != w) write(w, null)
        shown = null
    }

    private fun apply(c: Rgb) {
        if (c == shown) return
        val w = baseWhite() ?: return
        write(c, if (c == w) null else w)
    }

    private fun rgbOf(c: RingColour): Rgb? = c.rgb ?: baseWhite()

    private fun baseWhite(): Rgb? {
        white?.let { return it }
        if (broken) return null
        val r = leds.read() ?: run {
            fail("LED de l'anneau absentes ou root refusé : code couleur désactivé")
            return null
        }
        val w = if (r.valid) r else {
            log("blanc de l'anneau inattendu ($r) : blanc habituel ${Rgb.USUAL_WHITE} à la place")
            Rgb.USUAL_WHITE
        }
        white = w
        return w
    }

    private fun write(c: Rgb, restore: Rgb?) {
        if (!c.valid || (restore != null && !restore.valid)) return
        if (leds.write(c, restore)) shown = c
        else fail("écriture sur l'anneau refusée : code couleur désactivé")
    }

    private fun fail(why: String) {
        if (broken) return
        broken = true
        log(why)
    }
}
