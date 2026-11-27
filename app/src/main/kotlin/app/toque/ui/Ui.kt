package app.toque.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.look.ColourWheel

/**
 * Thème et utilitaires de mise en page, à l'échelle d'un écran de référence 1024×600.
 * Couleurs de base (fond, texte…) selon [light] ; accent choisi via [setAccent].
 */
object Ui {
    /** Couleurs d'accent (Réglages › Couleur). La première est celle d'origine. */
    val ACCENTS = intArrayOf(
        0xFFFF8A3D.toInt(), // orange
        0xFFFFB547.toInt(), // safran
        0xFFE8D44D.toInt(), // citron
        0xFF8BD85F.toInt(), // pistache
        0xFF3DDCB0.toInt(), // menthe
        0xFF4FC3F7.toInt(), // ciel
        0xFF6E9BFF.toInt(), // bleuet
        0xFFB48CFF.toInt(), // lavande
        0xFFF2639A.toInt(), // framboise
        0xFFFF6F61.toInt(), // corail
    )

    var light = false
        private set

    var BG = 0xFF0A1322.toInt()
        private set
    var SURFACE = 0xFF131D2E.toInt()
        private set
    var SURFACE2 = 0xFF1A2639.toInt()
        private set
    var SURFACE3 = 0xFF223048.toInt()
        private set
    var STROKE = 0xFF2A3A55.toInt()
        private set
    var TEXT = 0xFFF3F5F9.toInt()
        private set
    var TEXT2 = 0xFF9CA8BC.toInt()
        private set
    var TEXT3 = 0xFF66738A.toInt()
        private set
    var SCRIM = 0xCC000000.toInt()
        private set
    private var bgGradient = intArrayOf(0xFF16294A.toInt(), 0xFF0D1A31.toInt(), 0xFF070E1B.toInt())
    private var rippleColor = 0x33FFFFFF
    private var placeholders = intArrayOf(
        0xFF3A2416.toInt(), 0xFF16302A.toInt(), 0xFF2B2140.toInt(), 0xFF3B1E26.toInt(),
        0xFF1C2B3E.toInt(), 0xFF35301A.toInt(), 0xFF1E3320.toInt(), 0xFF3A2A1C.toInt(),
    )
    private var placeholderPairs = makePlaceholderPairs(placeholders)

    private fun makePlaceholderPairs(palette: IntArray): Array<IntArray> =
        Array(palette.size) { i -> intArrayOf(palette[i], palette[(i + 3) % palette.size]) }

    /**
     * Couleurs d'état : information, réussi, avertissement, danger. Claires sur le thème sombre ;
     * sur le thème clair, des teintes foncées, lisibles en texte et en icône sur un fond clair
     * (les mêmes servent aux jauges et aux boutons, avec [ON_COOL] et [ON_WARN] pour le texte posé dessus).
     */
    var COOL = COOL_ON_DARK
        private set
    var OK = OK_ON_DARK
        private set
    var WARN = WARN_ON_DARK
        private set
    var DANGER = DANGER_ON_DARK
        private set
    /** Texte posé sur un fond [COOL] (bouton) ou [WARN] (Turbo maintenu). */
    var ON_COOL = 0xFF04131C.toInt()
        private set
    var ON_WARN = 0xFF1A1405.toInt()
        private set

    /** Les couleurs d'état du thème sombre, pour ce qui reste sombre quel que soit le thème (veille). */
    const val COOL_ON_DARK = 0xFF5CC8FF.toInt()
    const val OK_ON_DARK = 0xFF3DDC97.toInt()
    const val WARN_ON_DARK = 0xFFFFC14D.toInt()
    const val DANGER_ON_DARK = 0xFFFF5A5F.toInt()
    /** Texte posé sur le voile sombre d'une photo (pastille « Perso », langue de la recette) : clair dans les deux thèmes. */
    const val ON_PHOTO = 0xFFF3F5F9.toInt()

    /** L'accent en fond : boutons primaires, puces choisies, pastilles pleines, jauges. */
    var ACCENT = ACCENTS[0]
        private set
    var ACCENT_DIM = 0x33FF8A3D
        private set
    /** Texte posé sur l'accent (boutons primaires, puces sélectionnées). */
    var ON_ACCENT = 0xFF1A0E05.toInt()
        private set
    /**
     * L'accent en texte, en icône fine ou en trait (cadre d'une carte choisie) sur les surfaces du
     * thème : l'accent lui-même sur le sombre ; sur le clair, la même teinte assombrie jusqu'à 4,5:1
     * sur le fond et les surfaces (une couleur vive y est trop pâle pour du texte ou un trait).
     * Les fonds gardent [ACCENT].
     */
    var ACCENT_INK = ACCENTS[0]
        private set

    /** [color] peut être l'accent : une des dix, ou une couleur qui passe la garde de la roue (ColourWheel). */
    fun accepts(color: Int): Boolean = color in ACCENTS || ColourWheel.accepts(color)

    /** Choisit l'accent ; une couleur refusée ([accepts]) laisse l'orange d'origine. */
    fun setAccent(color: Int) {
        val opaque = color or (0xFF shl 24)
        val c = if (color in ACCENTS) color else if (accepts(opaque)) opaque else ACCENTS[0]
        ACCENT = c
        ACCENT_DIM = (c and 0x00FFFFFF) or 0x33000000
        fun dark(ch: Int) = (((c shr ch) and 0xFF) * 0.12f).toInt() shl ch
        ON_ACCENT = (0xFF shl 24) or dark(16) or dark(8) or dark(0)
        ACCENT_INK = inkOf(c)
    }

    /**
     * L'encre de [color] sur le thème en cours (voir [ACCENT_INK]) : lisible sur le fond, les surfaces, et
     * sur le fond discret de l'accent ([ACCENT_DIM] posé sur SURFACE2 : puces, numéros d'étape).
     */
    fun inkOf(color: Int): Int {
        if (!light) return color
        fun dim(ch: Int): Int {
            val b = (SURFACE2 shr ch) and 0xFF
            return Math.round(b + (((color shr ch) and 0xFF) - b) * (0x33 / 255f)) shl ch
        }
        val dimBack = (0xFF shl 24) or dim(16) or dim(8) or dim(0)
        return ColourWheel.ink(color, intArrayOf(BG, SURFACE, SURFACE2, SURFACE3, dimBack))
    }

    /** Applique le thème clair ou sombre (fond, surfaces, texte). L'accent est inchangé. */
    fun setTheme(lightTheme: Boolean) {
        light = lightTheme
        if (lightTheme) {
            BG = 0xFFF3F5F8.toInt()
            SURFACE = 0xFFFFFFFF.toInt()
            SURFACE2 = 0xFFEBEEF3.toInt()
            SURFACE3 = 0xFFDEE3EB.toInt()
            STROKE = 0xFFC5CDD9.toInt()
            TEXT = 0xFF1A2332.toInt()
            TEXT2 = 0xFF5A6578.toInt()
            TEXT3 = 0xFF8B95A8.toInt()
            SCRIM = 0x99000000.toInt()
            bgGradient = intArrayOf(0xFFF7F8FB.toInt(), 0xFFEEF1F6.toInt(), 0xFFE4E9F1.toInt())
            rippleColor = 0x22000000
            COOL = 0xFF0B6FA8.toInt()
            OK = 0xFF10794D.toInt()
            WARN = 0xFF9A5B00.toInt()
            DANGER = 0xFFC62828.toInt()
            ON_COOL = Color.WHITE
            ON_WARN = Color.WHITE
            placeholders = intArrayOf(
                0xFFE8D5C4.toInt(), 0xFFC5DDD4.toInt(), 0xFFD4CDE4.toInt(), 0xFFE4CCD2.toInt(),
                0xFFC8D5E4.toInt(), 0xFFE0DBC4.toInt(), 0xFFC9DEC9.toInt(), 0xFFE4D4C4.toInt(),
            )
        } else {
            BG = 0xFF0A1322.toInt()
            SURFACE = 0xFF131D2E.toInt()
            SURFACE2 = 0xFF1A2639.toInt()
            SURFACE3 = 0xFF223048.toInt()
            STROKE = 0xFF2A3A55.toInt()
            TEXT = 0xFFF3F5F9.toInt()
            TEXT2 = 0xFF9CA8BC.toInt()
            TEXT3 = 0xFF66738A.toInt()
            SCRIM = 0xCC000000.toInt()
            bgGradient = intArrayOf(0xFF16294A.toInt(), 0xFF0D1A31.toInt(), 0xFF070E1B.toInt())
            rippleColor = 0x33FFFFFF
            placeholders = intArrayOf(
                0xFF3A2416.toInt(), 0xFF16302A.toInt(), 0xFF2B2140.toInt(), 0xFF3B1E26.toInt(),
                0xFF1C2B3E.toInt(), 0xFF35301A.toInt(), 0xFF1E3320.toInt(), 0xFF3A2A1C.toInt(),
            )
            COOL = COOL_ON_DARK
            OK = OK_ON_DARK
            WARN = WARN_ON_DARK
            DANGER = DANGER_ON_DARK
            ON_COOL = 0xFF04131C.toInt()
            ON_WARN = 0xFF1A1405.toInt()
        }
        placeholderPairs = makePlaceholderPairs(placeholders)
        ACCENT_INK = inkOf(ACCENT)
    }

    val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val THIN: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    /** Facteur d'échelle : 1 unité = 1 px sur un écran 1024×600. */
    var scale = 1f

    fun init(widthPx: Int, heightPx: Int) {
        val w = maxOf(widthPx, heightPx)
        val h = minOf(widthPx, heightPx)
        scale = minOf(w / 1024f, h / 600f)
    }

    fun u(v: Float): Int = Math.round(v * scale)
    fun u(v: Int): Int = Math.round(v * scale)
    fun uf(v: Float): Float = v * scale

    fun round(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            // Le rayon avant la couleur : sous Android 6, setCornerRadius ne recalcule pas l'opacité.
            // Dans l'autre ordre, la forme se dit opaque sur tout son rectangle, et l'effet d'appui
            // dont elle est le masque (voir pressable) éclaire un carré au lieu du bouton arrondi.
            setCornerRadius(uf(radius))
            setColor(color)
            if (strokeWidth > 0) setStroke(u(strokeWidth), strokeColor)
        }

    fun pressable(base: Drawable, radius: Float, ripple: Int = rippleColor): Drawable =
        RippleDrawable(ColorStateList.valueOf(ripple), base, round(Color.WHITE, radius))

    fun text(c: Context, s: CharSequence = "", size: Float = 18f, color: Int = TEXT, face: Typeface = REGULAR): TextView =
        TextView(c).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(size))
            setTextColor(color)
            typeface = face
            includeFontPadding = false
        }

    /** Espaces insécables à l'intérieur des guillemets : un retour à la ligne ne sépare plus « du mot qui suit, ni » de celui qui précède. */
    fun guillemets(s: String): String = s.replace("« ", "« ").replace(" »", " »")

    /** Ne modifie le texte d'un TextView que s'il a réellement changé (évite des passes de mise en page à chaque tick). */
    fun setText(tv: TextView, s: CharSequence) {
        if (tv.text.toString() != s.toString()) tv.text = s
    }

    /** Texte d'une seule ligne, rapetissé de [size] jusqu'à [min] pour tenir dans sa largeur (jamais de retour à la ligne). */
    fun fitText(c: Context, size: Float, min: Float, color: Int = TEXT, face: Typeface = REGULAR): TextView =
        object : TextView(c) {
            private val measurePaint = android.graphics.Paint()
            private var lastMeasuredText = ""
            private var lastMeasuredAvail = -1

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val avail = View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
                if (View.MeasureSpec.getMode(widthMeasureSpec) != View.MeasureSpec.UNSPECIFIED && avail > 0) {
                    val s = text.toString()
                    if (s != lastMeasuredText || avail != lastMeasuredAvail) {
                        lastMeasuredText = s
                        lastMeasuredAvail = avail
                        val maxPx = uf(size)
                        val minPx = uf(min)
                        measurePaint.set(paint)
                        measurePaint.textSize = maxPx
                        val px = if (measurePaint.measureText(s) <= avail || maxPx <= minPx) {
                            maxPx
                        } else {
                            var lo = minPx
                            var hi = maxPx
                            repeat(10) {
                                val mid = (lo + hi) * 0.5f
                                measurePaint.textSize = mid
                                if (measurePaint.measureText(s) <= avail) lo = mid else hi = mid
                            }
                            lo
                        }
                        if (px != textSize) setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
                    }
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }

            override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
                super.onTextChanged(text, start, lengthBefore, lengthAfter)
                lastMeasuredAvail = -1
                requestLayout()
            }
        }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(size))
            setTextColor(color)
            typeface = face
            includeFontPadding = false
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }

    fun ellipsize(t: TextView, lines: Int = 1): TextView {
        t.maxLines = lines
        t.ellipsize = TextUtils.TruncateAt.END
        return t
    }

    fun vertical(c: Context, gravity: Int = Gravity.START): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setGravity(gravity)
    }

    fun horizontal(c: Context, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        setGravity(gravity)
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f,
           l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(u(l), u(t), u(r), u(b)) }

    fun flp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.MATCH_PARENT,
            gravity: Int = Gravity.NO_GRAVITY): FrameLayout.LayoutParams = FrameLayout.LayoutParams(w, h, gravity)

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun pad(v: View, all: Int): View {
        v.setPadding(u(all), u(all), u(all), u(all))
        return v
    }

    fun pad(v: View, h: Int, vert: Int): View {
        v.setPadding(u(h), u(vert), u(h), u(vert))
        return v
    }

    fun scroll(c: Context, child: View): ScrollView = ScrollView(c).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(child)
    }

    fun spacer(c: Context, w: Int = 0, h: Int = 0, weight: Float = 0f): View =
        View(c).apply { layoutParams = LinearLayout.LayoutParams(if (w == 0 && weight > 0) 0 else u(w), u(h), weight) }

    fun divider(c: Context): View = View(c).apply {
        background = ColorDrawable(STROKE)
        layoutParams = LinearLayout.LayoutParams(MATCH, maxOf(1, u(1)))
    }

    enum class Style { PRIMARY, SECONDARY, DANGER, GHOST, COOL }

    /** Bouton arrondi avec icône facultative. */
    fun button(c: Context, label: String, style: Style = Style.SECONDARY, icon: Icon.Kind? = null,
               height: Int = 64, textSize: Float = 20f, onClick: (View) -> Unit): LinearLayout {
        val (bg, fg) = when (style) {
            Style.PRIMARY -> ACCENT to ON_ACCENT
            Style.SECONDARY -> SURFACE3 to TEXT
            Style.DANGER -> DANGER to Color.WHITE
            Style.GHOST -> Color.TRANSPARENT to TEXT2
            Style.COOL -> COOL to ON_COOL
        }
        val row = horizontal(c, Gravity.CENTER)
        row.minimumHeight = u(height)
        row.setPadding(u(22), 0, u(22), 0)
        val base = if (style == Style.GHOST) round(Color.TRANSPARENT, height / 2f, STROKE, 1.5f) else round(bg, height / 2f)
        row.background = pressable(base, height / 2f)
        if (icon != null) {
            row.addView(Icon(c, icon, fg), LinearLayout.LayoutParams(u(textSize * 1.3f), u(textSize * 1.3f)).apply {
                if (label.isNotEmpty()) rightMargin = u(10)
            })
        }
        if (label.isNotEmpty()) row.addView(text(c, label, textSize, fg, MEDIUM))
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { onClick(it) }
        return row
    }

    fun setButtonEnabled(b: View, enabled: Boolean) {
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.35f
    }

    /**
     * Carte modale de saisie avec en-tête (titre + bouton « Masquer le clavier »), enveloppe défilable
     * aux coins arrondis et ouverture du clavier sur [input]. Elle s'ouvre en haut de l'écran, là où le
     * clavier la laisse : elle ne bouge donc pas quand il arrive, et son bouton garde sa place d'avance.
     * Le clavier refermé, elle glisse au milieu de l'écran ; elle remonte dès qu'on touche son champ.
     */
    fun showInputOverlay(a: MainActivity, title: String, input: EditText, body: LinearLayout.() -> Unit) {
        val card = vertical(a)
        card.setPadding(u(26), u(16), u(26), u(16))
        val head = horizontal(a)
        head.addView(text(a, title, 22f, TEXT, MEDIUM), LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = u(12) })
        val hideBtn = FrameLayout(a).apply {
            addView(Icon(a, Icon.Kind.KEYBOARD_HIDE, TEXT), flp(u(24), u(24), Gravity.CENTER))
            background = pressable(round(SURFACE3, 14f, STROKE, 1f), 14f)
            contentDescription = tr("Masquer le clavier")
            isClickable = true
            visibility = View.INVISIBLE
            setOnClickListener { a.hideKeyboard() }
        }
        head.addView(hideBtn, LinearLayout.LayoutParams(u(52), u(52)))
        card.addView(head)
        card.body()
        val box = FrameLayout(a).apply {
            background = round(SURFACE2, 28f)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, uf(28f))
            }
            addView(ScrollView(a).apply {
                isVerticalScrollBarEnabled = true
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(card)
            }, flp(MATCH, WRAP))
        }
        a.showOverlay(box, 680, dismissOnScrim = false, spareMachineBar = true)
        (box.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = u(14)
        }
        box.requestLayout()
        // La carte est placée une fois pour toutes en haut ; au milieu de l'écran, elle n'est que décalée.
        // Un glissement ne refait donc aucune mise en page, et il part dès qu'on sait où va le clavier
        // (le champ touché, le clavier masqué) sans attendre qu'Android annonce son arrivée ou son départ.
        var up = true
        fun settle() {
            val target = if (up) 0f else ((a.overlayRoom() - box.height) / 2f - u(14)).coerceAtLeast(0f)
            box.animate().translationY(target).setDuration(CARD_SLIDE_MS).setInterpolator(cardSlide).start()
        }
        fun move(toTop: Boolean) {
            if (up == toTop) return
            up = toTop
            settle()
        }
        box.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (!up && bottom - top != oldBottom - oldTop) settle()
        }
        input.setOnFocusChangeListener { _, has -> if (has) move(true) }
        a.overlayOnKeyboard = {
            hideBtn.visibility = if (it) View.VISIBLE else View.INVISIBLE
            move(it)
        }
        a.showKeyboard(input) { if (a.keyboardHeight > 0) hideBtn.visibility = View.VISIBLE }
    }

    /** Durée du glissement d'une carte de saisie entre le haut de l'écran et son milieu. */
    const val CARD_SLIDE_MS = 180L
    private val cardSlide = DecelerateInterpolator()

    private fun yIn(ancestor: View, v: View): Int {
        var y = v.top
        var p = v.parent as? View
        while (p != null && p !== ancestor) {
            y += p.top
            p = p.parent as? View
        }
        return y
    }

    /**
     * Fait défiler [scroll] pour garder [keepTop] et le bas de [revealBottom] visibles au-dessus du clavier
     * sans jamais tronquer [keepTop].
     */
    fun revealInScroll(scroll: ScrollView, keepTop: View, revealBottom: View, bottomPad: Int = 8) {
        fun apply() {
            if (!scroll.isAttachedToWindow) return
            val content = scroll.getChildAt(0) ?: return
            val top = yIn(content, keepTop)
            val targetBottom = yIn(content, revealBottom) + revealBottom.height + u(bottomPad)
            val maxScroll = (top - u(8)).coerceAtLeast(0)
            scroll.scrollTo(0, minOf((targetBottom - scroll.height).coerceAtLeast(0), maxScroll))
        }
        val vto = scroll.viewTreeObserver
        if (scroll.isAttachedToWindow && scroll.rootView.isLayoutRequested && vto.isAlive) {
            val l = object : ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener {
                override fun onGlobalLayout() {
                    cleanup()
                    scroll.post { apply() }
                }
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) = cleanup()
                private fun cleanup() {
                    if (vto.isAlive) vto.removeOnGlobalLayoutListener(this)
                    scroll.removeOnAttachStateChangeListener(this)
                }
            }
            vto.addOnGlobalLayoutListener(l)
            scroll.addOnAttachStateChangeListener(l)
        } else {
            scroll.post { apply() }
        }
    }

    /** Pastille d'information (icône + texte). */
    fun chip(c: Context, label: String, icon: Icon.Kind? = null, color: Int = TEXT2, bg: Int = SURFACE2, size: Float = 16f): LinearLayout {
        val row = horizontal(c, Gravity.CENTER_VERTICAL)
        row.setPadding(u(12), u(6), u(14), u(6))
        row.background = round(bg, 40f)
        if (icon != null) row.addView(Icon(c, icon, color), LinearLayout.LayoutParams(u(size * 1.2f), u(size * 1.2f)).apply { rightMargin = u(6) })
        row.addView(text(c, label, size, color, MEDIUM))
        return row
    }

    fun chipText(chip: LinearLayout): TextView = chip.getChildAt(chip.childCount - 1) as TextView

    /** Puce sélectionnable (filtres, choix). */
    fun toggleChip(c: Context, label: String, selected: Boolean, onClick: () -> Unit): TextView {
        val t = text(c, label, 17f, if (selected) ON_ACCENT else TEXT, MEDIUM)
        t.setPadding(u(18), u(10), u(18), u(10))
        t.background = pressable(if (selected) round(ACCENT, 40f) else round(SURFACE2, 40f, STROKE, 1f), 40f)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    /**
     * Ligne de réglage à interrupteur : [label] à gauche, à droite une piste arrondie dont la pastille
     * est à gauche (non) ou à droite (oui). Toute la ligne se touche.
     */
    fun switchRow(c: Context, label: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = horizontal(c)
        row.addView(text(c, label, 18f, TEXT), LinearLayout.LayoutParams(0, WRAP, 1f))
        var state = initial
        val sw = FrameLayout(c)
        val knob = View(c)
        sw.addView(knob, flp(u(34), u(34), Gravity.CENTER_VERTICAL))
        sw.setPadding(u(5), 0, u(5), 0)
        fun paint() {
            sw.background = round(if (state) ACCENT else SURFACE3, 22f, if (state) ACCENT else STROKE, 1f)
            knob.background = round(if (state) ON_ACCENT else TEXT2, 17f)
            (knob.layoutParams as FrameLayout.LayoutParams).gravity =
                Gravity.CENTER_VERTICAL or (if (state) Gravity.END else Gravity.START)
            knob.requestLayout()
            sw.contentDescription = if (state) tr("Oui") else tr("Non")
        }
        paint()
        val flip = { _: View -> state = !state; paint(); onChange(state) }
        sw.setOnClickListener(flip)
        row.setOnClickListener(flip)
        row.minimumHeight = u(52)
        row.addView(sw, LinearLayout.LayoutParams(u(76), u(44)).apply { leftMargin = u(14) })
        return row
    }

    fun card(c: Context, radius: Float = 24f, color: Int = SURFACE): LinearLayout = vertical(c).apply {
        background = round(color, radius)
        setPadding(u(20), u(18), u(20), u(18))
    }

    fun clock(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format("%02d:%02d", s / 60, s % 60)
    }

    fun duration(seconds: Int): String = when {
        seconds < 60 -> "$seconds s"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else -> "${seconds / 60} min ${seconds % 60} s"
    }

    /** Couleur liée à une température : froid -> bleu, tiède -> orange, chaud -> rouge. */
    fun tempColor(c: Int): Int = when {
        c >= 90 -> DANGER
        c >= 60 -> ACCENT_INK
        c >= 40 -> WARN
        else -> COOL
    }

    /** Indice d'une couleur de repli stable pour une vignette de recette sans photo. */
    fun placeholderBucket(seed: String): Int = (seed.hashCode() and 0x7FFFFFFF) % placeholderPairs.size

    fun placeholderPair(bucket: Int): IntArray = placeholderPairs[bucket]

    /** Couleur de repli stable pour une vignette de recette sans photo. */
    fun placeholderColors(seed: String): IntArray = placeholderPairs[placeholderBucket(seed)]

    fun initials(title: String): String {
        var c1 = '\u0000'
        var c2 = '\u0000'
        var i = 0
        val n = title.length
        while (i < n) {
            while (i < n && (title[i] == ' ' || title[i] == '-' || title[i] == '\'')) i++
            val start = i
            while (i < n && title[i] != ' ' && title[i] != '-' && title[i] != '\'') i++
            if (i - start > 2) {
                val ch = title[start].uppercaseChar()
                if (c1 == '\u0000') c1 = ch else { c2 = ch; break }
            }
        }
        return when {
            c2 != '\u0000' -> "$c1$c2"
            c1 != '\u0000' -> c1.toString()
            n > 0 -> title[0].uppercaseChar().toString()
            else -> ""
        }
    }

    /** Fond de l'appli : dégradé selon le thème. */
    fun background(): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, bgGradient.copyOf())

    fun gradient(colors: IntArray, radius: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { setCornerRadius(uf(radius)) }
}
