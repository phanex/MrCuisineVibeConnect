package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.companion.RobotCompanion
import app.toque.core.companion.WaitingEnd
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.machine.Snapshot
import app.toque.core.recipes.CommunityRecipes
import app.toque.core.recipes.Portions
import app.toque.core.recipes.UserRecipes
import app.toque.ui.Icon
import app.toque.ui.KnobFocus
import app.toque.ui.MachineBar
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.TouchWatch
import app.toque.ui.Ui
import app.toque.ui.WallClock
import java.text.SimpleDateFormat
import java.util.Date

class HomeScreen(a: MainActivity) : Screen(a) {
    // Pas de titre sur l'accueil : la barre du haut ne garde que l'état de la machine et l'heure.
    override val title = ""
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var greeting: TextView
    private lateinit var recipesSub: TextView
    private lateinit var favSub: TextView
    private lateinit var settingsSub: TextView
    private lateinit var resumeCard: LinearLayout
    private lateinit var resumeText: TextView
    private lateinit var knobChip: LinearLayout
    private lateinit var shortcuts: LinearLayout
    private lateinit var receivedCard: LinearLayout
    private lateinit var receivedTitle: TextView
    private lateinit var receivedLabel: TextView
    private lateinit var searchHint: TextView
    /** Éléments que la molette parcourt, dans l'ordre de lecture, avec le rayon de leurs coins. */
    private val knobTargets = ArrayList<Pair<View, Float>>()
    private lateinit var recipesTile: View
    private val knob by lazy { KnobFocus(view, HomeKnob()) }
    /** Titre de la recette guidée cherché dans la base (catalogue pas encore chargé) : une fois par recette. */
    private var titleLookup = ""
    private var unsub: (() -> Unit)? = null
    private var unsubCompanion: (() -> Unit)? = null
    /** Ce qui s'adapte dans une tuile quand la barre du bas lui prend de la hauteur. */
    private class TileParts(val top: View, val text: View, val sub: TextView)
    private val tiles = ArrayList<TileParts>()
    private var compact = false
    private val tick = object : Runnable {
        override fun run() {
            refreshClock()
            // Heure pas encore réglée : on regarde souvent, pour l'afficher dès qu'elle l'est.
            view.postDelayed(this, WallClock.nextMs(10_000))
        }
    }

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(28), Ui.u(10), Ui.u(24), Ui.u(24))

        val left = Ui.vertical(c)
        greeting = Ui.text(c, "", 24f, Ui.TEXT2, Ui.LIGHT)
        left.addView(greeting, Ui.lp(t = 8))
        clock = Ui.text(c, "", 132f, Ui.TEXT, Ui.THIN)
        left.addView(clock, Ui.lp(t = 2))
        date = Ui.text(c, "", 22f, Ui.TEXT2, Ui.LIGHT)
        left.addView(date, Ui.lp(t = 2))

        // Rappel tant que le bouton rotatif n'est ni réglé ni vu en marche.
        knobChip = Ui.chip(c, tr("Bouton rotatif à régler"), Icon.Kind.WARNING, Ui.WARN, 0x33FFC14D, 18f)
        knobChip.isClickable = true
        knobChip.setOnClickListener { a.push(KnobSetupScreen(a)) }
        left.addView(knobChip, Ui.lp(Ui.WRAP, Ui.WRAP, t = 12))

        val search = Ui.horizontal(c)
        search.setPadding(Ui.u(24), 0, Ui.u(24), 0)
        search.background = Ui.pressable(Ui.round(Ui.SURFACE, 36f, Ui.STROKE, 1f), 36f)
        search.addView(Icon(c, Icon.Kind.SEARCH, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(28), Ui.u(28)).apply { rightMargin = Ui.u(14) })
        searchHint = Ui.ellipsize(Ui.text(c, tr("Rechercher une recette…"), 21f, Ui.TEXT3))
        search.addView(searchHint, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        search.isClickable = true
        search.setOnClickListener { a.push(RecipesScreen(a, focusSearch = true)) }
        left.addView(search, Ui.lp(Ui.MATCH, Ui.u(72), t = 34))

        // Raccourcis sous la recherche : dernières recettes cuisinées, et les plus cuisinées.
        shortcuts = Ui.horizontal(c)
        val recent = Ui.button(c, tr("Récentes"), Ui.Style.SECONDARY, Icon.Kind.CLOCK, height = 56, textSize = 18f) {
            a.push(RecipesScreen(a, initialUsage = RecipesScreen.Usage.RECENT))
        }
        shortcuts.addView(recent, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(12) })
        val most = Ui.button(c, tr("Fréquentes"), Ui.Style.SECONDARY, Icon.Kind.FLAME, height = 56, textSize = 18f) {
            a.push(RecipesScreen(a, initialUsage = RecipesScreen.Usage.MOST))
        }
        shortcuts.addView(most, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        left.addView(shortcuts, Ui.lp(Ui.MATCH, Ui.WRAP, t = 14))

        // Recette envoyée par un téléphone : elle attend ici, rien ne démarre.
        receivedCard = receivedCard()
        left.addView(receivedCard, Ui.lp(Ui.MATCH, Ui.WRAP, t = 16))

        resumeCard = Ui.horizontal(c)
        resumeCard.setPadding(Ui.u(22), Ui.u(16), Ui.u(18), Ui.u(16))
        resumeCard.background = Ui.pressable(Ui.round(Ui.ACCENT_DIM, 24f), 24f)
        resumeCard.addView(Icon(c, Icon.Kind.PLAY, Ui.ACCENT_INK), LinearLayout.LayoutParams(Ui.u(26), Ui.u(26)).apply { rightMargin = Ui.u(14) })
        resumeText = Ui.ellipsize(Ui.text(c, "", 19f, Ui.TEXT, Ui.MEDIUM), 2)
        resumeCard.addView(resumeText, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        // Zone de toucher plus grande que l'icône (au doigt, sur la carte).
        val cancel = android.widget.FrameLayout(c)
        cancel.addView(Icon(c, Icon.Kind.CLOSE, Ui.TEXT2), Ui.flp(Ui.u(24), Ui.u(24), Gravity.CENTER))
        cancel.background = Ui.pressable(Ui.round(0, 28f), 28f)
        cancel.isClickable = true
        cancel.contentDescription = tr("Abandonner la recette")
        cancel.setOnClickListener { abandonGuided() }
        resumeCard.addView(cancel, LinearLayout.LayoutParams(Ui.u(56), Ui.u(56)).apply { leftMargin = Ui.u(8) })
        resumeCard.isClickable = true
        resumeCard.setOnClickListener {
            val r = a.app.recipes.getAdjusted(a.prefs.guidedRecipe)
            if (r != null) a.push(CookScreen(a, r, a.prefs.guidedStep)) else { a.prefs.guidedRecipe = ""; refresh() }
        }
        knobTargets += search to 36f
        knobTargets += recent to 28f
        knobTargets += most to 28f
        knobTargets += receivedCard to 24f
        knobTargets += resumeCard to 24f
        left.addView(resumeCard, Ui.lp(Ui.MATCH, Ui.WRAP, t = 16))
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 0.42f).apply { rightMargin = Ui.u(28) })

        val grid = Ui.vertical(c)
        val row1 = Ui.horizontal(c)
        val row2 = Ui.horizontal(c)
        val (t1, s1) = tile(Icon.Kind.BOOK, tr("Recettes")) { a.push(RecipesScreen(a)) }
        recipesSub = s1
        recipesTile = t1
        row1.addView(t1, tileLp(true))
        row1.addView(tile(Icon.Kind.POT, tr("Cuisson\nmanuelle"), hasSub = false) { a.push(ManualScreen(a)) }.first, tileLp(true))
        row1.addView(tile(Icon.Kind.KNEAD, tr("Programmes"), tr("Pétrir, vapeur, rissoler, nettoyage")) { a.push(ProgramsScreen(a)) }.first, tileLp(false))
        row2.addView(tile(Icon.Kind.SCALE, tr("Balance"), tr("Peser au gramme")) { a.push(ScaleScreen(a)) }.first, tileLp(true))
        val (t5, s5) = tile(Icon.Kind.STAR, tr("Favoris")) { a.push(RecipesScreen(a, favorites = true)) }
        favSub = s5
        row2.addView(t5, tileLp(true))
        // Sous-titre seulement quand une nouvelle version de Toque attend d'être installée.
        val (t6, s6) = tile(Icon.Kind.SETTINGS, tr("Réglages")) { a.push(SettingsScreen(a)) }
        settingsSub = s6
        settingsSub.setTextColor(Ui.ACCENT_INK)
        row2.addView(t6, tileLp(false))
        grid.addView(row1, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { bottomMargin = Ui.u(16) })
        grid.addView(row2, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        root.addView(grid, LinearLayout.LayoutParams(0, Ui.MATCH, 0.58f))
        // Un toucher de l'écran efface le cadre de la molette.
        val watch = TouchWatch(c) { knob.hide() }
        watch.addView(root, Ui.flp())
        return watch
    }

    /** La molette sur l'accueil : recherche, raccourcis, cartes, puis les six tuiles. */
    private inner class HomeKnob : KnobFocus.Slots {
        override val size: Int get() = knobTargets.size
        /** Au premier cran : la recette en cours s'il y en a une, sinon « Recettes ». */
        override fun start(): Int {
            val resume = knobTargets.indexOfFirst { it.first === resumeCard }
            if (usable(resume)) return resume
            return knobTargets.indexOfFirst { it.first === recipesTile }
        }
        override fun usable(i: Int): Boolean {
            if (i !in knobTargets.indices) return false
            var v: View? = knobTargets[i].first
            while (v != null && v !== view) {
                if (v.visibility != View.VISIBLE) return false
                v = v.parent as? View
            }
            return knobTargets[i].first.isEnabled
        }
        override fun mark(i: Int, on: Boolean) = KnobFocus.frame(knobTargets[i].first, on, knobTargets[i].second)
        override fun activate(i: Int) {
            knobTargets[i].first.performClick()
        }
    }

    override fun onKnob(delta: Int): Boolean {
        knob.turn(delta)
        return true
    }

    override fun onKnobPress(): Boolean {
        knob.press()
        return true
    }

    private fun receivedCard(): LinearLayout {
        val card = Ui.horizontal(c)
        card.setPadding(Ui.u(22), Ui.u(16), Ui.u(12), Ui.u(16))
        card.background = Ui.pressable(Ui.round(Ui.ACCENT_DIM, 24f), 24f)
        card.addView(Icon(c, Icon.Kind.PHONE, Ui.ACCENT_INK), LinearLayout.LayoutParams(Ui.u(26), Ui.u(26)).apply { rightMargin = Ui.u(14) })
        val col = Ui.vertical(c)
        receivedLabel = Ui.ellipsize(Ui.text(c, tr("Reçue du téléphone"), 15f, Ui.TEXT2))
        col.addView(receivedLabel)
        receivedTitle = Ui.ellipsize(Ui.text(c, "", 19f, Ui.TEXT, Ui.MEDIUM))
        col.addView(receivedTitle, Ui.lp(t = 2))
        card.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        card.addView(Icon(c, Icon.Kind.CHEVRON, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)).apply { leftMargin = Ui.u(14) })
        val remove = android.widget.FrameLayout(c)
        remove.addView(Icon(c, Icon.Kind.CLOSE, Ui.TEXT2), Ui.flp(Ui.u(24), Ui.u(24), Gravity.CENTER))
        remove.background = Ui.pressable(Ui.round(0, 28f), 28f)
        remove.isClickable = true
        remove.contentDescription = tr("Retirer la recette reçue")
        remove.setOnClickListener { a.app.companion?.hub?.clearWaiting(WaitingEnd.REMOVED) }
        card.addView(remove, LinearLayout.LayoutParams(Ui.u(56), Ui.u(56)).apply { leftMargin = Ui.u(8) })
        card.isClickable = true
        card.setOnClickListener { openReceived() }
        return card
    }

    private fun openReceived() {
        val hub = a.app.companion?.hub ?: return
        val w = hub.waitingRecipe ?: return
        val r = a.app.recipes.get(w.recipeId)
        if (r != null) a.push(RecipeScreen(a, r))
        else {
            // Supprimée depuis l'envoi (synchronisation) : rien à cuisiner.
            a.toast(tr("Recette introuvable"), Ui.WARN)
            hub.clearWaiting(WaitingEnd.REMOVED)
        }
    }

    private fun tileLp(gap: Boolean) = LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (gap) rightMargin = Ui.u(16) }

    private fun tile(icon: Icon.Kind, title: String, sub: String = "", hasSub: Boolean = true, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        // Tuile centrée : grande pastille d'icône, titre et sous-titre dessous. La pastille est à la
        // même hauteur dans toutes les tuiles, quel que soit le texte (une ou deux lignes, avec ou
        // sans sous-titre) : espace fixe au-dessus, texte accroché sous la pastille.
        val t = Ui.vertical(c, Gravity.CENTER_HORIZONTAL)
        t.setPadding(Ui.u(14), Ui.u(14), Ui.u(14), Ui.u(14))
        t.background = Ui.pressable(Ui.round(Ui.SURFACE, 26f, Ui.STROKE, 1f), 26f)
        t.addView(View(c), LinearLayout.LayoutParams(1, 0, 1f))
        val badge = android.widget.FrameLayout(c)
        // Le rond prend la couleur choisie (Réglages › Apparence) et l'icône se dessine dessus en foncé :
        // elle reste nette avec toutes les couleurs, sur le thème sombre comme sur le clair.
        badge.background = Ui.round(Ui.ACCENT, 44f)
        badge.addView(Icon(c, icon, Ui.ON_ACCENT), Ui.flp(Ui.u(46), Ui.u(46), Gravity.CENTER))
        t.addView(badge, LinearLayout.LayoutParams(Ui.u(84), Ui.u(84)))
        val text = Ui.vertical(c, Gravity.CENTER_HORIZONTAL or Gravity.TOP)
        text.addView(Ui.text(c, title, 22f, Ui.TEXT, Ui.MEDIUM).apply { gravity = Gravity.CENTER }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 12))
        val s = Ui.ellipsize(Ui.text(c, sub, 15f, Ui.TEXT2).apply { gravity = Gravity.CENTER }, 2)
        if (hasSub) text.addView(s, Ui.lp(Ui.WRAP, Ui.WRAP, t = 4))
        // Deux tiers de la place libre au texte : deux lignes de sous-titre y tiennent en entier.
        t.addView(text, LinearLayout.LayoutParams(Ui.MATCH, 0, 2f))
        t.isClickable = true
        t.setOnClickListener { onClick() }
        tiles += TileParts(t.getChildAt(0), text, s)
        knobTargets += t to 26f
        return t to s
    }

    /**
     * La barre du bas (programme, Turbo, alerte) prend de la hauteur aux tuiles : la pastille remonte
     * et le sous-titre tient sur une ligne, pour que le texte ne soit pas rogné. La pastille reste à
     * la même hauteur dans toutes les tuiles.
     */
    private fun setCompact(on: Boolean) {
        if (on == compact) return
        compact = on
        for (p in tiles) {
            (p.text.layoutParams as LinearLayout.LayoutParams).weight = if (on) 8f else 2f
            p.sub.maxLines = if (on) 1 else 2
            p.text.requestLayout()
        }
    }

    override fun onSnapshot(s: Snapshot) = setCompact(MachineBar.shownFor(s))

    private fun refreshClock() {
        // Après une coupure de courant, la tablette se croit en 2010 : rien de faux tant que l'heure n'est pas réglée.
        if (!WallClock.known) {
            clock.text = WallClock.UNKNOWN
            date.text = tr("Réglage de l'heure…")
            // Salut neutre : « Bonjour » se traduit ailleurs par « Good morning ».
            greeting.text = tr("Bonjour !")
            return
        }
        clock.text = MainActivity.now()
        date.text = SimpleDateFormat(tr("EEEE d MMMM"), I18n.locale).format(Date()).replaceFirstChar { it.uppercaseChar() }
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        greeting.text = when (h) {
            in 5..11 -> tr("Bonjour")
            in 12..17 -> tr("Bon après-midi")
            else -> tr("Bonsoir")
        }
    }

    private fun refresh() {
        val repo = a.app.recipes
        val n = repo.count
        val perso = repo.index.entries.count { UserRecipes.isUser(it.id) }
        val community = repo.index.entries.count { CommunityRecipes.isCommunity(it.id) }
        val lidl = n - perso - community
        recipesSub.text = when {
            !repo.loaded -> tr("Chargement…")
            n == 0 -> tr("Aucune recette pour l'instant")
            else -> {
                // Deux lignes au plus sous la tuile : s'il y a aussi des recettes perso, celles de la communauté
                // sont comptées avec les autres (leur nombre reste dans Réglages › Recettes de la communauté).
                val merged = perso > 0 && community > 0
                val others = if (merged) lidl + community else lidl
                listOfNotNull(
                    when (others) { 0 -> null; 1 -> tr("1 recette"); else -> tr("%d recettes", others) },
                    when (perso) { 0 -> null; 1 -> tr("1 recette perso"); else -> tr("%d recettes perso", perso) },
                    if (merged) null else when (community) { 0 -> null; 1 -> tr("1 de la communauté"); else -> tr("%d de la communauté", community) },
                ).joinToString("\n")
            }
        }
        val favs = repo.index.entries.count { it.favorite }
        favSub.text = when {
            !repo.loaded -> tr("Chargement…")
            favs == 0 -> tr("Aucun favori")
            favs == 1 -> tr("1 favori")
            else -> tr("%d favoris", favs)
        }
        searchHint.text = if (repo.loaded) tr("Rechercher une recette…") else tr("Catalogue en préparation…")
        knobChip.visibility = if (a.prefs.knobReady) View.GONE else View.VISIBLE
        val update = a.updates.available
        settingsSub.text = if (update != null) tr("Version %s disponible", update.version) else ""
        settingsSub.visibility = if (update != null) View.VISIBLE else View.GONE
        val title = guidedTitle()
        resumeCard.visibility = if (title != null) View.VISIBLE else View.GONE
        if (title != null) resumeText.text = tr("Reprendre « %s » — étape %d", title, a.prefs.guidedStep + 1)
        refreshReceived()
    }

    /**
     * Titre de la recette guidée en cours, ou null (aucune, ou retirée du catalogue). Catalogue pas
     * encore chargé : le titre gardé dans les préférences, sinon lu dans la base à part (une ligne),
     * pour que la carte « Reprendre » soit là tout de suite.
     */
    private fun guidedTitle(): String? {
        val guided = a.prefs.guidedRecipe
        if (guided.isEmpty()) return null
        val repo = a.app.recipes
        if (repo.loaded) {
            val title = repo.index.entry(guided)?.title ?: return null
            if (a.prefs.guidedTitle != "$guided\t$title") a.prefs.guidedTitle = "$guided\t$title"
            return title
        }
        val kept = a.prefs.guidedTitle
        if (kept.startsWith("$guided\t")) return kept.substring(guided.length + 1)
        if (titleLookup != guided) {
            titleLookup = guided
            Thread {
                val title = try { repo.get(guided)?.title } catch (_: Exception) { null }
                if (title != null) {
                    a.prefs.guidedTitle = "$guided\t$title"
                    view.post { if (a.current === this) refresh() }
                }
            }.apply { name = "toque-reprise" }.start()
        }
        return null
    }

    /**
     * Carte de la recette reçue, à la place de celle de la recette en cours. Les deux à la fois ne
     * tiennent pas sous les raccourcis : les raccourcis « Récentes » et « Fréquentes » (aussi dans
     * Recettes) laissent alors leur place, la recette reçue au-dessus, la recette en cours dessous.
     */
    private fun refreshReceived() {
        val w = a.app.companion?.hub?.waitingRecipe
        receivedCard.visibility = if (w != null) View.VISIBLE else View.GONE
        if (w != null) {
            receivedTitle.text = w.title
            // Le nombre de personnes choisi (sur la page de la recette ou depuis un téléphone).
            val unit = a.app.recipes.get(w.recipeId)?.servingUnit.orEmpty()
            receivedLabel.text = if (w.servings > 0) tr("Reçue du téléphone") + " · " + Portions.label(w.servings, unit) else tr("Reçue du téléphone")
        }
        val both = w != null && resumeCard.visibility == View.VISIBLE
        shortcuts.visibility = if (both) View.GONE else View.VISIBLE
        // Les deux cartes plus serrées, la recette en cours sur une ligne ; le rappel du bouton rotatif
        // (aussi dans Réglages › Outils) revient dès qu'une carte s'en va.
        if (both) knobChip.visibility = View.GONE
        val pad = if (both) 10 else 16
        receivedCard.setPadding(Ui.u(22), Ui.u(pad), Ui.u(12), Ui.u(pad))
        resumeCard.setPadding(Ui.u(22), Ui.u(pad), Ui.u(18), Ui.u(pad))
        resumeText.maxLines = if (both) 1 else 2
        (resumeCard.layoutParams as LinearLayout.LayoutParams).topMargin = Ui.u(if (both) 10 else 16)
        resumeCard.requestLayout()
    }

    override fun onShow() {
        refreshClock()
        refresh()
        unsub = a.app.recipes.onChange { refresh() }
        unsubCompanion = a.app.companion?.onChange { ch -> if (ch == RobotCompanion.Change.WAITING) refreshReceived() }
        a.updates.onChange = { refresh() }
        view.removeCallbacks(tick)
        view.postDelayed(tick, WallClock.nextMs(10_000))
    }

    override fun onHide() {
        // De retour sur l'accueil, le premier cran repart de la recette en cours (ou de « Recettes »).
        knob.reset()
        unsub?.invoke()
        unsubCompanion?.invoke()
        unsubCompanion = null
        a.updates.onChange = null
        view.removeCallbacks(tick)
    }

    /** Retire la recette en cours de l'accueil (sans toucher à un programme qui tourne). */
    private fun abandonGuided() {
        if (a.snapshot.busy) {
            a.toast(tr("Arrêtez d'abord le programme en cours"), Ui.WARN)
            return
        }
        a.confirm(tr("Abandonner la recette"), tr("Elle ne sera plus proposée sur l'accueil. Vous pourrez la recommencer depuis les recettes."),
            tr("Abandonner"), icon = Icon.Kind.CLOSE) {
            a.prefs.guidedRecipe = ""
            a.prefs.guidedStep = 0
            refresh()
        }
    }
}
