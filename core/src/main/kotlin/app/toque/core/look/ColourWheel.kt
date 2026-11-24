package app.toque.core.look

/**
 * La roue des couleurs d'accent (Réglages › Apparence) : l'angle est la teinte, la
 * distance au centre va du pastel au vif. Elle ne propose que des couleurs sur lesquelles des traits
 * foncés restent lisibles (la règle de [onAccent]) et qui se lisent en texte sur la surface sombre.
 *
 * Les calculs sont ceux de la maquette validée (une page web, hors du dépôt), mêmes constantes, en OKLCH :
 * pour chaque teinte, la clarté où la couleur est la plus vive, gardée entre 0,72 et 0,88, montée
 * ensuite si besoin jusqu'à passer [accepts] ; l'intensité va de 0,04 au bord intérieur à la plus
 * grande que le sRGB sait montrer au bord extérieur, et le pastel a une clarté de 0,88.
 *
 * Couleurs en entiers ARGB, opaques. Kotlin pur, sans rien après Java 7 : tourne sur Android 6.
 */
object ColourWheel {
    /** Le bout pastel de la roue (bord intérieur) : clarté et intensité OKLCH. */
    const val L_PASTEL = 0.88
    const val C_PASTEL = 0.04
    /** Bornes de la clarté du bout vif, avant la montée pour la lisibilité. */
    const val L_VIVID_MIN = 0.72
    const val L_VIVID_MAX = 0.88
    /** Une ligne de la table par degré de teinte. */
    const val HUES = 360
    /** Contraste minimal d'un texte (WCAG AA). */
    const val MIN_CONTRAST = 4.5
    /** La surface sombre des cartes (Ui.SURFACE du thème sombre) : l'accent doit s'y lire en texte. */
    const val DARK_SURFACE = 0xFF131D2E.toInt()

    /** Une place sur la roue : [hue] en degrés (0 à 360), [intensity] de 0 (pastel) à 1 (vif). */
    class Spot(val hue: Double, val intensity: Double) {
        override fun toString() = "Spot(hue=$hue, intensity=$intensity)"
    }

    // Par teinte entière : la clarté et l'intensité du bout vif. Gardées en Float, comme les
    // Float32Array de la maquette (ses couleurs reviennent ainsi au même octet près).
    private var vividL: FloatArray? = null
    private var vividC: FloatArray? = null

    /** Calcule une fois la table des bouts vifs (quelques dizaines de millisecondes sur le robot). */
    @Synchronized
    private fun ensure() {
        if (vividL != null) return
        val lh = FloatArray(HUES)
        val cm = FloatArray(HUES)
        for (i in 0 until HUES) {
            val h = i.toDouble()
            var best = 0.0
            var bl = 0.74
            // Mêmes pas que la maquette (0,62 + 0,01 + 0,01…, avec leurs arrondis).
            var l = 0.62
            while (l <= 0.93) {
                val c = cmax(l, h)
                if (c > best) { best = c; bl = l }
                l += 0.01
            }
            var lv = Math.min(L_VIVID_MAX, Math.max(L_VIVID_MIN, bl))
            var tries = 0
            while (true) {
                val col = rgbOf(lv, cmax(lv, h), h)
                if (passes(col) || tries++ > 20) break
                lv += 0.01
            }
            lh[i] = lv.toFloat()
            cm[i] = cmax(lv, h).toFloat()
        }
        vividC = cm
        vividL = lh
    }

    private fun row(hue: Double): Int = ((jsRound(hue) % HUES) + HUES) % HUES

    /** La couleur de la roue à la teinte [hue] (degrés) et à l'intensité [intensity] (0 pastel, 1 vif). */
    fun colour(hue: Double, intensity: Double): Int {
        ensure()
        val h = ((hue % 360.0) + 360.0) % 360.0
        val t = Math.min(1.0, Math.max(0.0, intensity))
        val i = row(h)
        val l = L_PASTEL + (vividL!![i] - L_PASTEL) * t
        val c = C_PASTEL + (vividC!![i] - C_PASTEL) * t
        return rgbOf(l, Math.min(c, cmax(l, h)), h)
    }

    /**
     * Où [color] se place sur la roue (la poignée d'un raccourci, de la couleur en cours) : sa
     * teinte OKLCH, et son intensité rapportée à celle du bout vif de cette teinte.
     * Une couleur qui n'est pas de la roue y est placée au plus près ; elle reste la couleur choisie.
     */
    fun place(color: Int): Spot {
        ensure()
        val lch = toLch(color)
        val i = jsRound(lch[2]) % HUES
        val t = Math.min(1.0, Math.max(0.0, (lch[1] - C_PASTEL) / Math.max(0.001, vividC!![i] - C_PASTEL)))
        return Spot(lch[2], t)
    }

    /**
     * La garde : [color] peut servir d'accent. Le texte posé dessus ([onAccent]) s'y lit, et elle se lit
     * en texte sur la surface sombre ([DARK_SURFACE]), à [MIN_CONTRAST] chaque fois. Toutes les couleurs
     * de la roue et les dix couleurs d'origine passent ; les couleurs sombres (marine, bordeaux, noir)
     * non : il leur faudrait des traits blancs, un autre style. Le blanc et les gris très clairs passent :
     * ils sont lisibles, simplement sans couleur.
     */
    fun accepts(color: Int): Boolean = passes(color or OPAQUE)

    private fun passes(c: Int): Boolean =
        contrast(onAccent(c), c) >= MIN_CONTRAST && contrast(c, DARK_SURFACE) >= MIN_CONTRAST

    /** Texte ou icône posé sur l'accent : 12 % de chaque canal, la règle du robot (Ui.setAccent). */
    fun onAccent(color: Int): Int {
        fun dark(shift: Int) = (((color shr shift) and 0xFF) * 0.12f).toInt() shl shift
        return OPAQUE or dark(16) or dark(8) or dark(0)
    }

    /**
     * L'encre de [color] : la même teinte, assombrie en OKLCH (pas de 0,01 de clarté, comme la maquette)
     * jusqu'à [MIN_CONTRAST] sur chacun des [backs]. La couleur elle-même si elle s'y lit déjà.
     */
    fun ink(color: Int, backs: IntArray): Int {
        val c = color or OPAQUE
        fun readable(x: Int): Boolean {
            for (b in backs) if (contrast(x, b) < MIN_CONTRAST) return false
            return true
        }
        if (readable(c)) return c
        val lch = toLch(c)
        var l = lch[0]
        var out = c
        var n = 0
        while (n < 100 && !readable(out)) {
            l -= 0.01
            out = rgbOf(l, Math.min(lch[1], cmax(l, lch[2])), lch[2])
            n++
        }
        return out
    }

    /**
     * Table pour dessiner la roue : [steps] + 1 intensités par teinte entière, de 0 à 1 ;
     * la couleur de la teinte h et du pas k est à l'indice h × (steps + 1) + k.
     */
    fun table(steps: Int): IntArray {
        val out = IntArray(HUES * (steps + 1))
        for (h in 0 until HUES) for (k in 0..steps) out[h * (steps + 1) + k] = colour(h.toDouble(), k.toDouble() / steps)
        return out
    }

    // ------------------------------------------------------------------ le dessin de l'anneau

    /** Bords de l'anneau, en part du rayon de l'image (maquette : 37 % de 98,5 %, et 98,5 %). */
    const val RING_OUTER = 0.985
    const val RING_INNER = 0.37 * RING_OUTER
    /** Pas d'intensité de la table du dessin. */
    const val DRAW_STEPS = 32

    /** Rayon (en part du rayon de l'image) où se pose la poignée d'intensité [intensity]. */
    fun radiusOf(intensity: Double): Double = RING_INNER + (RING_OUTER - RING_INNER) * intensity

    /**
     * La place sous un doigt à ([dx], [dy]) du centre d'une roue de rayon [half] : l'angle (0° à droite,
     * sens des aiguilles d'une montre sur l'écran) et l'intensité, bornée entre 0 et 1.
     */
    fun spotAt(dx: Double, dy: Double, half: Double): Spot {
        var h = Math.toDegrees(Math.atan2(dy, dx))
        if (h < 0) h += 360.0
        val rn = Math.sqrt(dx * dx + dy * dy) / half
        return Spot(h, Math.min(1.0, Math.max(0.0, (rn - RING_INNER) / (RING_OUTER - RING_INNER))))
    }

    /**
     * Les pixels ARGB (non prémultipliés) d'une image carrée de [d] px montrant l'anneau, comme la
     * maquette : table teinte × intensité, interpolée entre deux pas, bords adoucis sur un pixel, le
     * reste transparent. À faire hors du fil principal : quelques centaines de millisecondes sur le robot.
     */
    fun ringPixels(d: Int): IntArray {
        val tab = table(DRAW_STEPS)
        val px = IntArray(d * d)
        val half = d / 2.0
        for (y in 0 until d) for (x in 0 until d) {
            val dx = x + 0.5 - half
            val dy = y + 0.5 - half
            val rn = Math.sqrt(dx * dx + dy * dy) / half
            if (rn > RING_OUTER + 0.004 || rn < RING_INNER - 0.004) continue
            var hd = Math.toDegrees(Math.atan2(dy, dx))
            if (hd < 0) hd += 360.0
            val tt = Math.min(1.0, Math.max(0.0, (rn - RING_INNER) / (RING_OUTER - RING_INNER))) * DRAW_STEPS
            val k0 = Math.min(DRAW_STEPS - 1, tt.toInt())
            val f = tt - k0
            val o = (jsRound(hd) % HUES) * (DRAW_STEPS + 1) + k0
            val a = tab[o]
            val b = tab[o + 1]
            val rgb = (mix(a, b, 16, f) shl 16) or (mix(a, b, 8, f) shl 8) or mix(a, b, 0, f)
            val edge = Math.min(RING_OUTER - rn, rn - RING_INNER) * half
            val alpha = jsRound(255 * Math.min(1.0, Math.max(0.0, edge + 1)))
            px[y * d + x] = (alpha shl 24) or rgb
        }
        return px
    }

    /** Le canal [shift] de [a] mené vers celui de [b] à [f] (0 à 1), arrondi. */
    private fun mix(a: Int, b: Int, shift: Int, f: Double): Int {
        val p = (a shr shift) and 0xFF
        val q = (b shr shift) and 0xFF
        return jsRound(p + (q - p) * f)
    }

    // ------------------------------------------------------------------ OKLCH et contraste

    private const val OPAQUE = 0xFF shl 24

    /** Math.round de JavaScript (moitié vers le haut), pour retomber sur les mêmes lignes de la table. */
    private fun jsRound(x: Double): Int = Math.floor(x + 0.5).toInt()

    /** OKLCH → sRGB linéaire (non borné). */
    private fun lin(l0: Double, c: Double, h: Double): DoubleArray {
        val a = c * Math.cos(h * Math.PI / 180)
        val b = c * Math.sin(h * Math.PI / 180)
        var l = l0 + 0.3963377774 * a + 0.2158037573 * b
        var m = l0 - 0.1055613458 * a - 0.0638541728 * b
        var s = l0 - 0.0894841775 * a - 1.2914855480 * b
        l = l * l * l; m = m * m * m; s = s * s * s
        return doubleArrayOf(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)
    }

    private fun inside(v: DoubleArray): Boolean {
        for (x in v) if (x <= -0.0005 || x >= 1.0005) return false
        return true
    }

    private fun enc(x0: Double): Int {
        val x = Math.min(1.0, Math.max(0.0, x0))
        return jsRound(255 * (if (x <= 0.0031308) 12.92 * x else 1.055 * Math.pow(x, 1 / 2.4) - 0.055))
    }

    private fun rgbOf(l: Double, c: Double, h: Double): Int {
        val v = lin(l, c, h)
        return OPAQUE or (enc(v[0]) shl 16) or (enc(v[1]) shl 8) or enc(v[2])
    }

    /** La plus grande intensité que le sRGB montre à la clarté [l] et à la teinte [h]. */
    private fun cmax(l: Double, h: Double): Double {
        var lo = 0.0
        var hi = 0.4
        for (i in 0 until 16) {
            val mid = (lo + hi) / 2
            if (inside(lin(l, mid, h))) lo = mid else hi = mid
        }
        return lo
    }

    private fun dec(ch: Int): Double {
        val c = ch / 255.0
        return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    /** sRGB → OKLCH : clarté, intensité, teinte (degrés, 0 à 360). */
    fun toLch(color: Int): DoubleArray {
        val r = dec((color shr 16) and 0xFF)
        val g = dec((color shr 8) and 0xFF)
        val b = dec(color and 0xFF)
        val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val ll = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        return doubleArrayOf(ll, Math.sqrt(a * a + bb * bb), (Math.atan2(bb, a) * 180 / Math.PI + 360) % 360)
    }

    /** Luminance relative (WCAG 2.x, seuil 0,04045 comme la maquette). */
    fun luminance(color: Int): Double =
        0.2126 * dec((color shr 16) and 0xFF) + 0.7152 * dec((color shr 8) and 0xFF) + 0.0722 * dec(color and 0xFF)

    /** Rapport de contraste (1 à 21) entre deux couleurs opaques. */
    fun contrast(a: Int, b: Int): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05)
    }
}
