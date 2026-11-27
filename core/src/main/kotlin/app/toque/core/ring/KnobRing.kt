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
    /**
     * Programme en pause : pause demandée, couvercle ouvert, bol retiré. Ce mélange s'appelait « orange » ;
     * vu sur un vrai anneau, c'est un jaune (le même que [RingLook.Tint.YELLOW]), et il le reste.
     */
    YELLOW(Rgb(79, 30, 0)),
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
                st == JobState.PAUSED -> YELLOW
                // Terminé, arrêté à la main ou annulé : tant que la barre du bas le montre avec « OK ».
                st == JobState.FINISHED || st == JobState.ABORTED -> GREEN
                cookWaiting -> GREEN
                s.running || s.turboActive -> CYAN
                else -> WHITE
            }
        }
    }
}

/**
 * Réglages de l'anneau choisis par l'utilisateur (Réglages › Molette et sons) : une luminosité quand
 * l'anneau est allumé et une pour la veille de l'écran, et, quand le code couleur est coupé, une couleur
 * pour chacune. L'anneau n'a pas de LED bleue : les couleurs sont des mélanges de rouge, de vert et de blanc.
 */
object RingLook {
    /**
     * Intensité de chaque niveau sur l'échelle des LED, du niveau 1 (la veilleuse) au niveau 5 (la plus
     * forte, [Rgb.MAX]). Le niveau 4 est le blanc d'origine du robot.
     */
    val LEVELS = intArrayOf(3, 10, 25, 50, Rgb.MAX)
    val MAX_LEVEL = LEVELS.size
    /** Niveau de l'anneau allumé tant que rien n'a été réglé : celui du blanc d'origine. */
    const val DEFAULT_LEVEL = 4

    /**
     * Couleurs proposées quand le code couleur est coupé, à leur intensité la plus forte ([full]).
     * L'orange, le jaune et le rose ont été réglés à l'œil sur un vrai anneau (SKMC 1100 A1). [CUSTOM] :
     * le mélange que l'utilisateur compose lui-même ; son [full] ne sert que tant qu'il n'a rien composé.
     */
    enum class Tint(val key: String, val full: Rgb) {
        WHITE("white", Rgb(0, 0, 79)),
        RED("red", Rgb(79, 0, 0)),
        ORANGE("orange", Rgb(79, 12, 0)),
        YELLOW("yellow", Rgb(79, 30, 0)),
        GREEN("green", Rgb(0, 79, 0)),
        CYAN("cyan", Rgb(0, 79, 79)),
        PINK("pink", Rgb(79, 0, 43)),
        CUSTOM("custom", Rgb(0, 0, 79));

        companion object {
            fun of(key: String?): Tint = values().firstOrNull { it.key == key } ?: WHITE
        }
    }

    /**
     * Le mélange [full] (une couleur à sa plus forte intensité) au niveau [level] (0 : éteint). Une LED
     * allumée dans la couleur le reste au plus faible niveau.
     */
    fun rgb(full: Rgb, level: Int): Rgb {
        if (level <= 0) return Rgb(0, 0, 0)
        val raw = LEVELS[level.coerceAtMost(MAX_LEVEL) - 1]
        fun scale(v: Int) = if (v == 0) 0 else maxOf(1, Math.round(v * raw / Rgb.MAX.toDouble()).toInt())
        return Rgb(scale(full.red), scale(full.green), scale(full.white))
    }

    /** [tint], dans son mélange d'origine, au niveau [level]. */
    fun rgb(tint: Tint, level: Int): Rgb = rgb(tint.full, level)

    /** Un mélange que l'anneau peut montrer : dans l'échelle, et pas tout éteint. */
    fun usable(mix: Rgb): Boolean = mix.valid && (mix.red > 0 || mix.green > 0 || mix.white > 0)

    /** Un mélange gardé en texte (« 79 30 0 », voir [Rgb.toString]) ; null s'il est illisible ou inutilisable. */
    fun parseMix(s: String?): Rgb? {
        val n = s.orEmpty().trim().split(' ').map { it.toIntOrNull() ?: return null }
        if (n.size != 3) return null
        return Rgb(n[0], n[1], n[2]).takeIf { usable(it) }
    }

    /**
     * Ce que l'utilisateur a réglé. [level] : niveau de l'anneau allumé, 1 à [MAX_LEVEL], ou 0 tant qu'il
     * n'a rien choisi (l'anneau garde alors le blanc propre au robot). [standbyLevel] : niveau pendant la
     * veille, 0 pour éteint. Les couleurs ([tint], [standbyTint]) ne comptent que si le code couleur est
     * coupé ([colourCode] faux) : avec lui, l'anneau au repos est blanc. [custom], [standbyCustom] : le
     * mélange de la couleur personnalisée ([Tint.CUSTOM]) de l'anneau allumé et de la veille, à sa plus
     * forte intensité.
     */
    data class Settings(
        val colourCode: Boolean = true,
        val level: Int = 0,
        val tint: Tint = Tint.WHITE,
        val standbyLevel: Int = DEFAULT_LEVEL,
        val standbyTint: Tint = Tint.WHITE,
        val custom: Rgb? = null,
        val standbyCustom: Rgb? = null,
    ) {
        /** La couleur de l'anneau allumé à sa plus forte intensité (code couleur coupé). */
        val onMix: Rgb get() = if (tint == Tint.CUSTOM) custom?.takeIf { usable(it) } ?: tint.full else tint.full
        /** La couleur de la veille à sa plus forte intensité (code couleur coupé). */
        val sleepMix: Rgb get() = if (standbyTint == Tint.CUSTOM) standbyCustom?.takeIf { usable(it) } ?: standbyTint.full else standbyTint.full

        /** Niveau de l'anneau allumé, réglé ou non. */
        val onLevel: Int get() = if (level in 1..MAX_LEVEL) level else DEFAULT_LEVEL
        /** Niveau pendant la veille : jamais au-dessus de celui de l'anneau allumé. */
        val sleepLevel: Int get() = standbyLevel.coerceIn(0, onLevel)
        private val onFull: Rgb get() = if (colourCode) Tint.WHITE.full else onMix
        private val sleepFull: Rgb get() = if (colourCode) Tint.WHITE.full else sleepMix

        /** Anneau allumé, rien à signaler ; null : rien de réglé, le blanc propre au robot reste. */
        val on: Rgb? get() = if (level !in 1..MAX_LEVEL && onFull == Tint.WHITE.full) null else rgb(onFull, onLevel)
        /** Anneau pendant la veille, tel qu'il est réglé. */
        val sleep: Rgb get() = rgb(sleepFull, sleepLevel)
        /** Ce que la veille doit écrire ; null : la même chose qu'allumé, l'anneau n'est pas touché. */
        val standby: Rgb? get() = if (sleepLevel == onLevel && sleepFull == onFull) null else sleep
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
    /** Anneau allumé réglé par l'utilisateur ([idle]) ; null : le blanc propre au robot. */
    private var rest: Rgb? = null
    private var asleep = false
    /** Code couleur coupé et rien de réglé : ce qu'avait l'anneau avant la veille, remis au réveil. */
    private var beforeSleep: Rgb? = null
    private var previewing = false
    /** L'anneau montre autre chose que le blanc propre au robot, noté pour être remis. */
    private var noted = false

    val available: Boolean get() = !broken

    /**
     * Couleur du code couleur ([colour]), ou null quand le code couleur est coupé : l'anneau montre
     * alors ce que l'utilisateur a réglé ([idle]) ou, sans réglage, reprend son blanc et n'est plus
     * touché qu'en veille. En veille, retenue pour le réveil.
     */
    fun show(colour: RingColour?) {
        if (broken) return
        val was = wanted
        wanted = colour
        if (asleep) return
        if (colour == null) {
            val r = rest
            if (r != null) apply(r) else if (was != null) restoreWhite()
            return
        }
        apply(rgbOf(colour) ?: return)
    }

    /**
     * Anneau allumé réglé par l'utilisateur ([colour] : luminosité, et couleur si le code couleur est
     * coupé), ou null pour le blanc propre au robot. Il est montré quand il n'y a rien à signaler : à la
     * place du blanc du code couleur, ou tout le temps si le code couleur est coupé.
     */
    fun idle(colour: Rgb?) {
        if (broken || colour == rest || (colour != null && !colour.valid)) return
        rest = colour
        // En veille ou pendant un aperçu : retenu, montré au réveil ou à la fin de l'aperçu.
        if (asleep || previewing) return
        if (wanted == null || wanted == RingColour.WHITE) atRest()
    }

    /** Veille de l'écran : veilleuse blanche d'intensité [level] (0 : éteinte). */
    fun standby(level: Int) = standby(Rgb(0, 0, level.coerceIn(0, Rgb.MAX)))

    /** Veille de l'écran : l'anneau montre [night] (tout à 0 : éteint) jusqu'au réveil. */
    fun standby(night: Rgb) {
        if (broken || asleep || !night.valid) return
        previewing = false
        val held = wanted != null || rest != null
        val restore = if (held) baseWhite() else leds.read()?.takeIf { it.valid }
        if (restore == null) return fail("lecture de l'anneau impossible (LED absentes ou root refusé)")
        asleep = true
        beforeSleep = if (held) null else restore
        write(night, restore)
    }

    /** Réveil : l'anneau montre la couleur du moment (ou, code couleur coupé et rien de réglé, ce qu'il avait). */
    fun wake() {
        if (broken || !asleep) return
        asleep = false
        val colour = wanted
        val r = rest
        if (colour != null) {
            apply(rgbOf(colour) ?: return)
        } else if (r != null) {
            apply(r)
        } else {
            val back = beforeSleep ?: baseWhite() ?: return
            write(back, null)
            shown = null
        }
        beforeSleep = null
    }

    /**
     * Aperçu d'un réglage ([colour] : la veille telle qu'elle sera, ou une couleur vue au plus fort),
     * tenu jusqu'à l'aperçu suivant ; null : fin de l'aperçu, l'anneau reprend ce qu'il doit montrer.
     * Sans effet pendant la veille.
     */
    fun preview(colour: Rgb?) {
        if (broken || asleep) return
        if (colour != null) {
            if (!colour.valid) return
            previewing = true
            apply(colour)
        } else if (previewing) {
            previewing = false
            val c = wanted
            if (c != null) apply(rgbOf(c) ?: return) else atRest()
        }
    }

    /** Toque rend l'écran (appli d'origine, Toque masqué) : le blanc du robot revient. */
    fun release() {
        if (broken) return
        if (asleep) wake()
        if (wanted != null || noted) restoreWhite()
        previewing = false
        wanted = null
    }

    /** Rien à signaler : ce que l'utilisateur a réglé, sinon le blanc propre au robot. */
    private fun atRest() {
        val r = rest
        if (r != null) apply(r) else restoreWhite()
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

    private fun rgbOf(c: RingColour): Rgb? = c.rgb ?: rest ?: baseWhite()

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
        if (leds.write(c, restore)) {
            shown = c
            noted = restore != null
        } else fail("écriture sur l'anneau refusée : code couleur désactivé")
    }

    private fun fail(why: String) {
        if (broken) return
        broken = true
        log(why)
    }
}
