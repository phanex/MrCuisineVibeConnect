package app.toque.ui.screens

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.Prefs
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.recipes.ChipSelection
import app.toque.core.recipes.RecipeChip
import app.toque.core.recipes.RecipeSort
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.SearchIndex
import app.toque.core.recipes.UserRecipes
import app.toque.ui.Icon
import app.toque.ui.ImageLoader
import app.toque.ui.KnobFocus
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.TouchWatch
import app.toque.ui.Ui
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Catalogue des recettes Lidl : recherche instantanée tolérante (accents, pluriels, fautes de
 * frappe), filtres combinables, tri, et recherche par ingrédients (séparés par des virgules).
 * Molette : un cadre orange parcourt les recettes (la grille suit) ; avant la première, le tri puis
 * les pastilles ; l'appui ouvre la recette, allume la pastille ou ouvre le choix du tri.
 */
class RecipesScreen(
    a: MainActivity,
    private val focusSearch: Boolean = false,
    private val favorites: Boolean = false,
    initialUsage: Usage = Usage.NONE,
) : Screen(a) {
    /** Listes tirées des cuissons : dernières recettes cuisinées, ou les plus cuisinées. */
    enum class Usage { NONE, RECENT, MOST }

    private var recipeCount = -1
    override val title = when {
        favorites -> tr("Favoris")
        initialUsage == Usage.RECENT -> tr("Récentes")
        initialUsage == Usage.MOST -> tr("Fréquentes")
        else -> tr("Recettes")
    }
    override val titleCounter: String?
        get() = if (recipeCount > 0) {
            java.text.NumberFormat.getIntegerInstance(app.toque.core.i18n.I18n.locale).format(recipeCount)
        } else null

    /**
     * Liste ouverte depuis « Recettes » ou la recherche : elle reprend les pastilles et le tri de la
     * dernière fois. Les raccourcis (Favoris, Récentes, Fréquentes) partent de leur pastille.
     */
    private val remembers = !favorites && initialUsage == Usage.NONE
    private val kept = keptState(a)

    /** Pastilles allumées (plusieurs filtres, ou une vue par les cuissons), voir ChipSelection. */
    private var selection = when {
        initialUsage == Usage.RECENT -> ChipSelection.of(RecipeChip.RECENT)
        initialUsage == Usage.MOST -> ChipSelection.of(RecipeChip.FREQUENT)
        favorites -> ChipSelection.of(RecipeChip.FAVORITES)
        else -> kept.selection
    }
    private var sort = kept.sort
    private var query = ""
    private var results: List<SearchIndex.Hit> = emptyList()
    /** Les résultats affichés correspondent au catalogue chargé (sinon : rien n'est encore connu). */
    private var resultsLoaded = false
    private var generation = 0
    private var focusedOnce = false
    private var compactCards = false
    private lateinit var rootBox: LinearLayout
    private lateinit var searchBar: LinearLayout
    private lateinit var searchField: LinearLayout
    private lateinit var clearBtn: View
    private lateinit var input: EditText
    private lateinit var chipScroller: HorizontalScrollView
    private lateinit var chips: LinearLayout
    private var filtersRow: View? = null
    private lateinit var grid: GridView
    private lateinit var sortButton: LinearLayout
    private lateinit var sortLabel: TextView
    private lateinit var empty: LinearLayout
    private lateinit var emptyIcon: View
    private lateinit var emptyText: TextView
    private var refreshBtn: View? = null
    private lateinit var newRecipeBtn: View
    private val adapter = Adapter()
    private var unsub: (() -> Unit)? = null
    private val runSearch = Runnable { search() }
    private val focusInput = Runnable { a.showKeyboard(input) }
    private val knob by lazy { KnobFocus(view, ListKnob()) }
    /** Recette encadrée par la molette (position dans [results]), -1 : aucune. */
    private var framedCard = -1

    override fun build(): View {
        val root = Ui.vertical(c)
        rootBox = root
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), 0)
        // La page prend le focus que le champ de recherche rend : la molette revient alors à la liste
        // (un champ qui a le focus garde les touches de la molette pour lui).
        root.isFocusableInTouchMode = true
        root.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS

        val bar = Ui.horizontal(c)
        searchBar = bar
        val field = Ui.horizontal(c)
        searchField = field
        field.setPadding(Ui.u(20), 0, Ui.u(10), 0)
        field.background = Ui.round(Ui.SURFACE, 32f, Ui.STROKE, 1f)
        field.addView(Icon(c, Icon.Kind.SEARCH, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(26), Ui.u(26)).apply { rightMargin = Ui.u(12) })
        val clear = Icon(c, Icon.Kind.CLOSE, Ui.TEXT2).apply {
            setPadding(Ui.u(12), Ui.u(12), Ui.u(12), Ui.u(12))
            visibility = View.GONE
            setOnClickListener { input.setText("") }
        }
        clearBtn = clear
        input = EditText(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.uf(21f))
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT3)
            hint = tr("Recette, ingrédient… (ex. : poulet, curry)")
            background = null
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c1: Int, a1: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c1: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    clearBtn.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                    refreshSort()
                    view.removeCallbacks(runSearch)
                    view.postDelayed(runSearch, 120)
                }
            })
            setOnEditorActionListener { _, _, _ -> leaveField(); true }
        }
        field.addView(input, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))
        field.addView(clear, LinearLayout.LayoutParams(Ui.u(52), Ui.u(52)))
        bar.addView(field, LinearLayout.LayoutParams(0, Ui.u(64), 1f))
        if (favorites) {
            // Favoris : reprendre tout de suite ceux du compte toqueapp.org (et les recettes du site).
            refreshBtn = Ui.button(c, tr("Actualiser"), Ui.Style.SECONDARY, Icon.Kind.SYNC, height = 64, textSize = 18f) { refreshFavorites() }
                .also { bar.addView(it, Ui.lp(Ui.WRAP, Ui.WRAP, l = 16)) }
        }
        newRecipeBtn = Ui.button(c, tr("Nouvelle recette"), Ui.Style.SECONDARY, Icon.Kind.PLUS, height = 64, textSize = 18f) {
            a.hideKeyboard()
            a.push(RecipeEditScreen(a, UserRecipes.blank(), isNew = true))
        }.also { bar.addView(it, Ui.lp(Ui.WRAP, Ui.WRAP, l = 16)) }
        root.addView(bar)

        // Pastilles (elles défilent), puis le tri, toujours visible à droite.
        val filters = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        filtersRow = filters
        chipScroller = HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // Les pastilles s'estompent au bord au lieu d'être coupées net : on voit qu'il y en a d'autres.
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(Ui.u(36))
        }
        chips = Ui.horizontal(c)
        chipScroller.addView(chips)
        filters.addView(chipScroller, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        sortButton = sortButton()
        filters.addView(sortButton, Ui.lp(Ui.WRAP, Ui.u(56), l = 12))
        root.addView(filters, Ui.lp(t = 10, b = 10))

        val body = FrameLayout(c)
        grid = GridView(c).apply {
            numColumns = 4
            horizontalSpacing = Ui.u(14)
            verticalSpacing = Ui.u(14)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = ColorDrawable(0)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 0, 0, Ui.u(20))
            clipToPadding = false
            adapter = this@RecipesScreen.adapter
            setOnItemClickListener { _, _, pos, _ -> open(pos) }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView?, state: Int) {
                    // Au doigt seulement : la grille qui suit le cadre de la molette ne touche pas au champ.
                    if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) leaveField()
                }
                override fun onScroll(v: AbsListView?, first: Int, visible: Int, total: Int) {}
            })
        }
        body.addView(grid, Ui.flp())
        empty = Ui.vertical(c, Gravity.CENTER)
        emptyIcon = Icon(c, Icon.Kind.BOOK, Ui.TEXT3).also {
            empty.addView(it, LinearLayout.LayoutParams(Ui.u(64), Ui.u(64)))
        }
        emptyText = Ui.text(c, "", 21f, Ui.TEXT2).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }
        empty.addView(emptyText, Ui.lp(Ui.u(640), Ui.WRAP, t = 16))
        body.addView(empty, Ui.flp())
        root.addView(body, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        // Un toucher de l'écran efface le cadre de la molette.
        val watch = TouchWatch(c) { knob.hide() }
        watch.addView(root, Ui.flp())
        return watch
    }

    /** Fin de la saisie (touche Rechercher, grille défilée au doigt) : clavier rangé, la molette revient à la liste. */
    private fun leaveField() {
        a.hideKeyboard()
        if (input.hasFocus()) input.clearFocus()
    }

    override fun onKeyboard(visible: Boolean) {
        filtersRow?.visibility = if (visible) View.GONE else View.VISIBLE
        if (visible == compactCards) return
        compactCards = visible
        rootBox.setPadding(Ui.u(24), Ui.u(if (visible) 2 else 6), Ui.u(24), 0)
        val barH = Ui.u(if (visible) 54 else 64)
        (searchField.layoutParams as LinearLayout.LayoutParams).height = barH
        refreshBtn?.minimumHeight = barH
        newRecipeBtn.minimumHeight = barH
        searchBar.requestLayout()
        emptyIcon.visibility = if (visible) View.GONE else View.VISIBLE
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.uf(if (visible) 18f else 21f))
        (emptyText.layoutParams as LinearLayout.LayoutParams).topMargin = Ui.u(if (visible) 0 else 16)
        grid.numColumns = if (visible) 2 else 4
        grid.horizontalSpacing = Ui.u(if (visible) 12 else 14)
        grid.verticalSpacing = Ui.u(if (visible) 8 else 14)
        grid.setPadding(0, Ui.u(if (visible) 4 else 0), 0, Ui.u(if (visible) 8 else 20))
        adapter.notifyDataSetChanged()
        if (visible && knob.shown && knob.index <= chips.childCount) knob.hide()
        if (knob.shown && framedCard >= 0) grid.post { follow(framedCard) }
    }

    /** « Trier : A à Z ⌄ » : ouvre le choix de l'ordre. */
    private fun sortButton(): LinearLayout {
        val b = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        b.setPadding(Ui.u(20), 0, Ui.u(16), 0)
        b.background = Ui.pressable(Ui.round(Ui.SURFACE2, 28f, Ui.STROKE, 1f), 28f)
        b.addView(Ui.text(c, tr("Trier :"), 17f, Ui.TEXT2), Ui.lp(Ui.WRAP, Ui.WRAP, r = 6))
        sortLabel = Ui.text(c, "", 17f, Ui.TEXT, Ui.MEDIUM)
        b.addView(sortLabel)
        val chevron = Icon(c, Icon.Kind.CHEVRON, Ui.TEXT2)
        chevron.rotation = 90f
        b.addView(chevron, LinearLayout.LayoutParams(Ui.u(20), Ui.u(20)).apply { leftMargin = Ui.u(8) })
        b.isClickable = true
        b.setOnClickListener { openSort(byKnob = false) }
        return b
    }

    private fun refreshSort() {
        sortLabel.text = sort.label(query.isNotBlank())
        // Récentes et Fréquentes ont leur propre ordre (les cuissons).
        Ui.setButtonEnabled(sortButton, !selection.byUsage)
    }

    /** Fenêtre du choix de l'ordre ; à la molette, le cadre y est tout de suite sur l'ordre actuel. */
    private fun openSort(byKnob: Boolean) {
        a.hideKeyboard()
        val card = Ui.card(c, 28f, Ui.SURFACE2)
        card.setPadding(Ui.u(28), Ui.u(24), Ui.u(28), Ui.u(24))
        card.addView(Ui.text(c, tr("Trier les recettes"), 24f, Ui.TEXT, Ui.MEDIUM), Ui.lp(b = 14))
        val options = RecipeSort.values()
        val rows = ArrayList<View>()
        for (o in options) {
            val row = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
            row.minimumHeight = Ui.u(64)
            row.setPadding(Ui.u(20), 0, Ui.u(20), 0)
            val on = o == sort
            row.background = Ui.pressable(Ui.round(if (on) Ui.ACCENT_DIM else Ui.SURFACE3, 32f), 32f)
            row.addView(Ui.text(c, o.label(query.isNotBlank()), 20f, if (on) Ui.ACCENT_INK else Ui.TEXT, Ui.MEDIUM), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            if (on) row.addView(Icon(c, Icon.Kind.CHECK, Ui.ACCENT_INK), LinearLayout.LayoutParams(Ui.u(26), Ui.u(26)))
            row.isClickable = true
            row.setOnClickListener { chooseSort(o) }
            card.addView(row, Ui.lp(b = 10))
            rows += row
        }
        // Le bouton Stop de la barre machine reste à portée pendant un programme.
        a.showOverlay(card, 480, spareMachineBar = true)
        val focus = KnobFocus(card, object : KnobFocus.Slots {
            override val size: Int get() = rows.size
            override fun start(): Int = options.indexOf(sort)
            override fun usable(i: Int): Boolean = i in rows.indices
            override fun mark(i: Int, on: Boolean) = KnobFocus.frame(rows[i], on, 32f)
            override fun activate(i: Int) = chooseSort(options[i])
        })
        a.overlayKnob = MainActivity.OverlayKnob({ focus.turn(it) }, { focus.press() })
        if (byKnob) focus.turn(0)
    }

    private fun chooseSort(o: RecipeSort) {
        a.closeOverlay()
        if (o == sort) return
        sort = o
        keep()
        refreshSort()
        search()
    }

    /** Garde les pastilles et le tri pour la prochaine ouverture de la liste (tant que l'appli tourne). */
    private fun keep() {
        kept.sort = sort
        if (remembers) kept.selection = selection
    }

    private fun buildChips() {
        val before = chips.childCount
        chips.removeAllViews()
        for (ch in selection.chips(a.app.recipes.index)) {
            chips.addView(Ui.toggleChip(c, ch.label, selection.isOn(ch)) { tapChip(ch) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        }
        // Pastilles ajoutées ou retirées (catalogue chargé) : le cadre repart de zéro ; sinon il reste sur la sienne.
        if (chips.childCount != before && knob.index >= 0) knob.reset()
        else if (knob.shown && knob.index < chips.childCount) KnobFocus.frame(chips.getChildAt(knob.index), true, 40f)
        refreshSort()
    }

    /** Une pastille allumée hors de vue (catégorie gardée de la dernière fois) est amenée à l'écran. */
    private fun showLitChip() {
        chipScroller.post {
            val lit = selection.chips(a.app.recipes.index).indexOfLast { selection.isOn(it) }
            val chip = chips.getChildAt(lit) ?: return@post
            val right = chip.right + Ui.u(24) - chipScroller.width
            if (right > chipScroller.scrollX) chipScroller.scrollTo(right, 0)
        }
    }

    private fun tapChip(ch: RecipeChip) {
        selection = selection.tap(ch)
        keep()
        buildChips()
        search()
    }

    private fun search() {
        val gen = ++generation
        val q = query
        val sel = selection
        val order = sort
        val repo = a.app.recipes
        val index = repo.index
        val loaded = repo.loaded
        refreshSort()
        searchExec.execute {
            val hits = sel.select(index, q, order, { repo.recentIds() }, { repo.mostCookedIds() })
            view.post {
                if (gen != generation) return@post
                // Autre liste : un cadre posé sur une recette repart de la première visible. La même
                // (retour d'une recette) : il revient sur celle qu'on vient de quitter.
                if (knob.index > chips.childCount && !sameRecipes(results, hits)) {
                    knob.reset()
                    framedCard = -1
                }
                results = hits
                resultsLoaded = loaded
                adapter.notifyDataSetChanged()
                refreshEmpty()
            }
        }
    }

    private fun sameRecipes(x: List<SearchIndex.Hit>, y: List<SearchIndex.Hit>): Boolean {
        if (x.size != y.size) return false
        for (i in x.indices) if (x[i].entry.id != y[i].entry.id) return false
        return true
    }

    private fun refreshEmpty() {
        val repo = a.app.recipes
        val none = resultsLoaded && repo.count == 0
        recipeCount = results.size
        a.refreshTitle()
        empty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
        // Ingrédients et étapes pas encore dans l'index (les deux secondes après le démarrage) : on attend sa réponse.
        val waitingFull = query.isNotBlank() && !repo.index.full
        emptyText.text = when {
            !resultsLoaded -> tr("Chargement…")
            none && a.prefs.mode == Prefs.Mode.HARDWARE ->
                tr("Les recettes Lidl apparaîtront après la première synchronisation.\nRéglages › Recettes › Lidl › Synchroniser.")
            none -> tr("Les recettes Lidl apparaîtront après la première synchronisation, une fois Toque installé sur le robot.")
            waitingFull -> tr("Recherche dans les ingrédients…")
            selection.byUsage && query.isBlank() -> tr("Aucune recette cuisinée pour l'instant.\nLes recettes apparaissent ici dès leur première cuisson.")
            selection == ChipSelection.of(RecipeChip.FAVORITES) && query.isBlank() -> tr("Aucun favori pour l'instant.\nTouchez l'étoile d'une recette pour l'ajouter.")
            selection == ChipSelection.of(RecipeChip.COMMUNITY) && query.isBlank() ->
                tr("Aucune recette de la communauté pour l'instant.\nRéglages › Recettes › toqueapp.org › Synchroniser.")
            selection == ChipSelection.of(RecipeChip.USER) && query.isBlank() ->
                tr("Aucune recette perso pour l'instant.\nTouchez « Nouvelle recette », ou le crayon d'une recette Lidl pour en faire votre version.")
            selection.hasFilters -> Ui.guillemets(tr("Aucune recette avec ces filtres.\nTouchez « Toutes » pour les retirer."))
            else -> tr("Aucune recette ne correspond.\nAstuce : séparez plusieurs ingrédients par des virgules.")
        }
    }

    /** Bouton Actualiser des favoris : synchro avec toqueapp.org (compte relié ou recettes de la communauté). */
    private fun refreshFavorites() {
        val site = a.app.site
        if (!a.prefs.siteLinked && !a.prefs.siteCommunity) {
            a.toast(tr("Reliez un compte Toque (Réglages › Recettes › toqueapp.org) pour retrouver ici vos favoris du site."), Ui.WARN)
            return
        }
        if (site.busy) {
            a.toast(tr("Actualisation…"))
            return
        }
        refreshBtn?.apply { isEnabled = false; alpha = 0.5f }
        a.toast(tr("Actualisation…"))
        site.sync { r ->
            refreshBtn?.apply { isEnabled = true; alpha = 1f }
            if (r != null) a.toast(r.message, if (r.ok) Ui.OK else Ui.WARN)
        }
    }

    override fun onShow() {
        buildChips()
        showLitChip()
        search()
        unsub = a.app.recipes.onChange { buildChips(); search() }
        if (focusSearch && !focusedOnce) {
            focusedOnce = true
            view.postDelayed(focusInput, 150)
        }
    }

    override fun onHide() {
        view.removeCallbacks(focusInput)
        view.removeCallbacks(runSearch)
        knob.hide()
        unsub?.invoke()
    }

    override fun onKnob(delta: Int): Boolean {
        knob.turn(delta)
        return true
    }

    override fun onKnobPress(): Boolean {
        knob.press()
        return true
    }

    /**
     * La molette dans la liste : les pastilles, puis le tri, puis les recettes. Au premier cran le
     * cadre se pose sur la première recette visible ; avant elle, on remonte au tri puis aux pastilles.
     */
    private inner class ListKnob : KnobFocus.Slots {
        private val chipCount: Int get() = chips.childCount
        private val sortSlot: Int get() = chipCount
        private val filtersShown: Boolean get() = filtersRow?.visibility == View.VISIBLE
        override val size: Int get() = chipCount + 1 + results.size
        override fun start(): Int = when {
            results.isNotEmpty() -> sortSlot + 1 + grid.firstVisiblePosition.coerceIn(0, results.size - 1)
            filtersShown -> 0
            else -> -1
        }
        override fun usable(i: Int): Boolean = when {
            i < 0 -> false
            i < chipCount -> filtersShown
            i == sortSlot -> filtersShown && sortButton.isEnabled
            else -> i - sortSlot - 1 < results.size
        }
        override fun mark(i: Int, on: Boolean) {
            when {
                i < chipCount -> {
                    val chip = chips.getChildAt(i)
                    KnobFocus.frame(chip, on, 40f)
                    if (on) showChip(chip)
                }
                i == sortSlot -> KnobFocus.frame(sortButton, on, 28f)
                else -> {
                    val pos = i - sortSlot - 1
                    framedCard = if (on) pos else -1
                    refreshFrames()
                    if (on) follow(pos)
                }
            }
        }
        override fun activate(i: Int) {
            when {
                i < chipCount -> (selection.chips(a.app.recipes.index).getOrNull(i))?.let { tapChip(it) }
                i == sortSlot -> openSort(byKnob = true)
                else -> open(i - sortSlot - 1)
            }
        }
    }

    /** Fait défiler les pastilles pour que [chip] soit entière à l'écran. */
    private fun showChip(chip: View) {
        val left = chip.left - Ui.u(24)
        val right = chip.right + Ui.u(24) - chipScroller.width
        val x = chipScroller.scrollX
        if (left < x) chipScroller.smoothScrollTo(maxOf(0, left), 0)
        else if (right > x) chipScroller.smoothScrollTo(right, 0)
    }

    /**
     * La grille suit le cadre : la case [pos] entière à l'écran, par le plus court chemin (une rangée
     * à la fois au fil des crans, sans animation : rien ne traîne derrière la molette).
     */
    private fun follow(pos: Int) {
        var lastTop = Int.MIN_VALUE
        for (guard in 0 until 400) {
            val first = grid.firstVisiblePosition
            val child = grid.getChildAt(pos - first)
            val limit = grid.height - grid.paddingBottom
            if (child == null) {
                val row = (grid.getChildAt(0)?.height ?: Ui.u(if (compactCards) 60 else 232)) + grid.verticalSpacing
                val top = grid.getChildAt(0)?.top ?: 0
                if (top == lastTop) return
                lastTop = top
                grid.scrollListBy(if (pos < first) -row else row)
                continue
            }
            if (child.top < 0) grid.scrollListBy(child.top)
            else if (child.bottom > limit) grid.scrollListBy(child.bottom - limit)
            return
        }
    }

    /** Cadre sur la case affichée de la recette encadrée (la grille recycle ses cases). */
    private fun refreshFrames() {
        val radius = if (compactCards) 18f else 22f
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i) ?: continue
            KnobFocus.frame(child, grid.firstVisiblePosition + i == framedCard, radius)
        }
    }

    private fun open(pos: Int) {
        val hit = results.getOrNull(pos) ?: return
        val r = a.app.recipes.get(hit.entry.id)
        if (r != null) a.push(RecipeScreen(a, r))
    }

    /** « Aujourd'hui », « Hier », « Il y a 3 jours », puis la date. */
    private fun ago(ms: Long): String {
        val day = 86_400_000L
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        val midnight = cal.timeInMillis
        return when {
            ms >= midnight -> tr("Aujourd'hui")
            ms >= midnight - day -> tr("Hier")
            ms >= midnight - 6 * day -> tr("Il y a %d jours", ((midnight - ms) / day + 1).toInt())
            else -> java.text.SimpleDateFormat(tr("d MMM"), I18n.locale).format(java.util.Date(ms))
        }
    }

    /** Pastilles et tri gardés tant que l'appli tourne (pas d'un démarrage à l'autre). */
    private class Kept(app: Any) {
        val app = WeakReference(app)
        var selection = ChipSelection()
        var sort = RecipeSort.TITLE
    }

    private companion object {
        /** Un seul fil de recherche partagé par tous les écrans Recettes. */
        val searchExec = Executors.newSingleThreadExecutor()
        private var kept: Kept? = null

        fun keptState(a: MainActivity): Kept {
            val k = kept
            if (k != null && k.app.get() === a.app) return k
            return Kept(a.app).also { kept = it }
        }
    }

    private val metaSb = StringBuilder(48)

    private fun metaOf(e: SearchIndex.Entry): String {
        val sb = metaSb
        sb.setLength(0)
        fun add(s: String) {
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append(s)
        }
        val u = a.app.recipes.usage(e.id)
        val usage = selection.usage
        if (u != null && usage == RecipeChip.Kind.FREQUENT) add(if (u.count == 1) tr("1 fois") else tr("%d fois", u.count))
        if (u != null && usage == RecipeChip.Kind.RECENT) add(ago(u.lastMs))
        // Le critère du tri en tête : la note (avec le nombre de notes), ou la durée.
        val byRating = sort == RecipeSort.RATING && usage == null
        val byTime = sort == RecipeSort.DURATION && usage == null
        if (byTime && e.totalMinutes > 0) {
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append(e.totalMinutes).append(" min")
        }
        if (e.ratingCount > 0) {
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append("★ ").append(String.format(I18n.locale, "%.1f", e.rating))
            if (byRating) sb.append(" (").append(e.ratingCount).append(')')
        }
        if (!byTime && e.totalMinutes > 0) {
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append(e.totalMinutes).append(" min")
        }
        if (e.category.isNotBlank()) add(e.category)
        return sb.toString()
    }

    /** « Perso », « Mealie », ou la langue (« DE ») d'une recette venue d'une autre langue. */
    private fun badgeOf(e: SearchIndex.Entry): String {
        val lang = e.language.substringBefore('-')
        return if (e.source == RecipeSource.USER) tr("Perso")
        else if (e.source == RecipeSource.MEALIE) "Mealie"
        else if (e.source == RecipeSource.COMMUNITY && lang == I18n.code.substringBefore('-')) ""
        else lang.uppercase()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = results.size
        override fun getItem(position: Int) = results[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (compactCards) 1 else 0

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder = (convertView?.tag as? CardHolder)?.takeIf { it.compact == compactCards }
                ?: if (compactCards) CompactHolder(c) else Holder(c)
            holder.bind(results[position].entry)
            KnobFocus.frame(holder.root, position == framedCard, holder.frameRadius)
            return holder.root
        }
    }

    private abstract inner class CardHolder(c: Context, val compact: Boolean) {
        val root = if (compact) Ui.horizontal(c, Gravity.CENTER_VERTICAL) else Ui.vertical(c)
        val imageBox = FrameLayout(c)
        val image = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val initials = Ui.text(
            c, "", if (compact) 16f else 44f,
            if (compact) (if (Ui.light) 0x661A2332 else 0x77FFFFFF) else (if (Ui.light) 0x551A2332 else 0x55FFFFFF),
            if (compact) Ui.MEDIUM else Ui.THIN,
        )
        val title = Ui.ellipsize(Ui.text(c, "", if (compact) 17f else 18f, Ui.TEXT, Ui.MEDIUM), if (compact) 1 else 2)
        val meta = Ui.ellipsize(Ui.text(c, "", if (compact) 14f else 15f, Ui.TEXT2))
        val star = Icon(c, Icon.Kind.STAR_FILLED, if (compact) Ui.ACCENT_INK else Ui.ACCENT)
        val badge = Ui.text(c, "", if (compact) 12f else 13f, if (compact) Ui.ACCENT_INK else Ui.ON_PHOTO, Ui.MEDIUM)
        val frameRadius = if (compact) 18f else 22f
        private val imgSize = Ui.u(if (compact) 96 else 260)
        private val placeholderBg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0, 0)).apply {
            if (compact) setCornerRadius(Ui.uf(12f))
        }
        private var lastBucket = -1

        init {
            root.tag = this
            root.background = Ui.round(Ui.SURFACE, frameRadius)
            root.layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, Ui.u(if (compact) 60 else 232))
            imageBox.addView(initials, Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
            imageBox.addView(image, Ui.flp())
            imageBox.clipToOutline = true
            imageBox.background = placeholderBg
        }

        fun bind(e: SearchIndex.Entry) {
            title.text = e.title
            meta.text = metaOf(e)
            star.visibility = if (a.app.recipes.isFavorite(e.id)) View.VISIBLE else View.GONE
            val tag = badgeOf(e)
            badge.text = tag
            badge.visibility = if (tag.isEmpty()) View.GONE else View.VISIBLE
            val bucket = Ui.placeholderBucket(e.title)
            if (bucket != lastBucket) {
                lastBucket = bucket
                placeholderBg.colors = Ui.placeholderPair(bucket)
            }
            initials.text = Ui.initials(e.title)
            ImageLoader.load(image, a.app.recipes.imageFile(e.image), imgSize)
        }
    }

    /** Ligne compacte affichée au-dessus du clavier pendant la saisie : vignette, titre lisible et durée. */
    private inner class CompactHolder(c: Context) : CardHolder(c, compact = true) {
        init {
            root.setPadding(Ui.u(8), 0, Ui.u(12), 0)
            badge.setPadding(Ui.u(8), Ui.u(2), Ui.u(8), Ui.u(2))
            badge.background = Ui.round(Ui.ACCENT_DIM, 12f)
            imageBox.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, Ui.uf(12f))
            }
            root.addView(imageBox, LinearLayout.LayoutParams(Ui.u(44), Ui.u(44)))
            val texts = Ui.vertical(c)
            texts.addView(title)
            texts.addView(meta, Ui.lp(t = 2))
            root.addView(texts, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.u(12); rightMargin = Ui.u(8) })
            root.addView(badge, Ui.lp(Ui.WRAP, Ui.WRAP, r = 6))
            root.addView(star, LinearLayout.LayoutParams(Ui.u(20), Ui.u(20)))
        }
    }

    private inner class Holder(c: Context) : CardHolder(c, compact = false) {
        init {
            badge.setPadding(Ui.u(10), Ui.u(3), Ui.u(10), Ui.u(3))
            badge.background = Ui.round(0xB3000000.toInt(), 20f)
            imageBox.addView(star, Ui.flp(Ui.u(26), Ui.u(26), Gravity.TOP or Gravity.END).apply { setMargins(0, Ui.u(10), Ui.u(10), 0) })
            imageBox.addView(badge, Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.START).apply { setMargins(Ui.u(10), Ui.u(10), 0, 0) })
            imageBox.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height + Ui.u(22), Ui.uf(22f))
            }
            root.addView(imageBox, LinearLayout.LayoutParams(Ui.MATCH, Ui.u(128)))
            root.addView(title, Ui.lp(l = 14, t = 10, r = 12))
            root.addView(meta, Ui.lp(l = 14, t = 4, r = 12))
        }
    }
}
