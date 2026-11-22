package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.machine.Accessory
import app.toque.core.machine.Snapshot
import app.toque.core.recipes.UserRecipes
import app.toque.ui.HoldButton
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui
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
    private lateinit var weightValue: TextView
    private lateinit var tempValue: TextView
    private lateinit var tempIcon: Icon
    private lateinit var lidIcon: Icon
    private lateinit var faultIcon: Icon
    private lateinit var modeIcon: Icon
    private var unsub: (() -> Unit)? = null
    private val tick = object : Runnable {
        override fun run() {
            refreshClock()
            view.postDelayed(this, 10_000)
        }
    }

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(28), Ui.u(20), Ui.u(24), Ui.u(24))

        val left = Ui.vertical(c).apply {
            clipChildren = false
            clipToPadding = false
        }
        val headerRow = Ui.horizontal(c, Gravity.CENTER_VERTICAL).apply {
            minimumHeight = Ui.u(38)
        }
        greeting = Ui.text(c, "", 24f, Ui.TEXT2, Ui.LIGHT)
        headerRow.addView(greeting, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))

        // Cluster de statut compact réservé (température, couvercle, etc.)
        val statusCluster = Ui.horizontal(c, Gravity.CENTER_VERTICAL).apply {
            minimumHeight = Ui.u(38)
        }
        val tempChip = Ui.horizontal(c, Gravity.CENTER_VERTICAL).apply {
            setPadding(Ui.u(10), Ui.u(4), Ui.u(10), Ui.u(4))
            background = Ui.round(Ui.SURFACE2, 14f, Ui.STROKE, 1f)
            tempIcon = Icon(c, Icon.Kind.THERMO, Ui.TEXT2)
            addView(tempIcon, LinearLayout.LayoutParams(Ui.u(18), Ui.u(18)).apply { rightMargin = Ui.u(6) })
            tempValue = Ui.text(c, "0 °C", 15f, Ui.TEXT2, Ui.REGULAR)
            addView(tempValue)
        }
        statusCluster.addView(tempChip)
        lidIcon = Icon(c, Icon.Kind.LID, Ui.WARN).apply {
            visibility = View.GONE
        }
        statusCluster.addView(lidIcon, LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)).apply { leftMargin = Ui.u(8) })

        faultIcon = Icon(c, Icon.Kind.WARNING, Ui.DANGER).apply {
            visibility = View.GONE
            isClickable = true
            setOnClickListener {
                val s = a.snapshot
                val msg = if (s.hasFault) s.faultMessage else when (s.link) {
                    app.toque.core.machine.LinkState.LOST -> tr("Connexion perdue")
                    app.toque.core.machine.LinkState.OFFLINE -> tr("Base non connectée")
                    app.toque.core.machine.LinkState.ARMING -> tr("Connexion…")
                    else -> tr("Alerte")
                }
                a.toast(msg, if (s.link == app.toque.core.machine.LinkState.ARMING) Ui.WARN else Ui.DANGER)
            }
        }
        statusCluster.addView(faultIcon, LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)).apply { leftMargin = Ui.u(8) })

        modeIcon = Icon(c, Icon.Kind.INFO, Ui.COOL).apply {
            visibility = View.GONE
            isClickable = true
            setOnClickListener {
                val mode = a.prefs.mode
                val msg = when (mode) {
                    app.toque.Prefs.Mode.DEMO -> tr("Mode démo")
                    app.toque.Prefs.Mode.TWIN -> tr("Mode jumeau (externe)")
                    else -> tr("Mode combiné")
                }
                a.toast(msg, Ui.COOL)
            }
        }
        statusCluster.addView(modeIcon, LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)).apply { leftMargin = Ui.u(8) })

        headerRow.addView(statusCluster, LinearLayout.LayoutParams(Ui.WRAP, Ui.u(38)))
        left.addView(headerRow, Ui.lp(Ui.MATCH, Ui.WRAP, t = 4))

        clock = Ui.text(c, "", 132f, Ui.TEXT, Ui.THIN)
        left.addView(clock, Ui.lp(t = 2))
        date = Ui.text(c, "", 22f, Ui.TEXT2, Ui.LIGHT)
        left.addView(date, Ui.lp(t = 2))

        // Rappel tant que le bouton rotatif n'est ni réglé ni vu en marche.
        knobChip = Ui.chip(c, tr("Bouton rotatif à régler"), Icon.Kind.WARNING, Ui.WARN, 0x33FFC14D, 18f)
        knobChip.isClickable = true
        knobChip.setOnClickListener { a.push(KnobSetupScreen(a)) }
        left.addView(knobChip, Ui.lp(Ui.WRAP, Ui.WRAP, t = 12))

        // Rangée 1 : Champ de recherche et boutons d'accès rapide
        val shortcuts = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        val searchBar = Ui.horizontal(c, Gravity.CENTER_VERTICAL).apply {
            minimumHeight = Ui.u(50)
            setPadding(Ui.u(16), 0, Ui.u(16), 0)
            background = Ui.pressable(Ui.round(Ui.SURFACE, 22f, Ui.STROKE, 1f), 22f)
            addView(Icon(c, Icon.Kind.SEARCH, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(22), Ui.u(22)).apply { rightMargin = Ui.u(12) })
            addView(Ui.text(c, tr("Recherche") + "...", 16f, Ui.TEXT2, Ui.REGULAR))
            isClickable = true
            setOnClickListener { a.push(RecipesScreen(a, focusSearch = true)) }
        }
        shortcuts.addView(searchBar, LinearLayout.LayoutParams(0, Ui.u(50), 1f).apply { rightMargin = Ui.u(10) })

        val recentBtn = Ui.horizontal(c, Gravity.CENTER).apply {
            background = Ui.pressable(Ui.round(Ui.SURFACE2, 22f, Ui.STROKE, 1f), 22f)
            addView(Icon(c, Icon.Kind.CLOCK, Ui.TEXT), LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)))
            isClickable = true
            setOnClickListener { a.push(RecipesScreen(a, initialUsage = RecipesScreen.Usage.RECENT)) }
        }
        shortcuts.addView(recentBtn, LinearLayout.LayoutParams(Ui.u(50), Ui.u(50)).apply { rightMargin = Ui.u(8) })

        val freqBtn = Ui.horizontal(c, Gravity.CENTER).apply {
            background = Ui.pressable(Ui.round(Ui.SURFACE2, 22f, Ui.STROKE, 1f), 22f)
            addView(Icon(c, Icon.Kind.FLAME, Ui.TEXT), LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)))
            isClickable = true
            setOnClickListener { a.push(RecipesScreen(a, initialUsage = RecipesScreen.Usage.MOST)) }
        }
        shortcuts.addView(freqBtn, LinearLayout.LayoutParams(Ui.u(50), Ui.u(50)))
        left.addView(shortcuts, Ui.lp(Ui.MATCH, Ui.WRAP, t = 36))

        // Espace supérieur pour centrer le widget de pesée entre la recherche et Turbo
        left.addView(Ui.spacer(c, weight = 1f))

        // Rangée 2 : Affichage de pesée grand format (sans cadre) avec compensation de descente de police (+16dp)
        val scaleRow = Ui.horizontal(c, Gravity.BOTTOM).apply {
            clipChildren = false
            clipToPadding = false
        }
        scaleRow.addView(Icon(c, Icon.Kind.SCALE, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(38), Ui.u(38)).apply {
            rightMargin = Ui.u(14)
            bottomMargin = Ui.u(-2)
        })
        val weightBox = Ui.horizontal(c, Gravity.BOTTOM).apply {
            clipChildren = false
            clipToPadding = false
            translationY = Ui.uf(16f)
        }
        weightValue = Ui.text(c, "0", 72f, Ui.TEXT, Ui.THIN).apply {
            includeFontPadding = false
        }
        weightBox.addView(weightValue)
        val weightUnit = Ui.text(c, "g", 22f, Ui.TEXT2, Ui.REGULAR).apply {
            includeFontPadding = false
        }
        weightBox.addView(weightUnit, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
            leftMargin = Ui.u(6)
            bottomMargin = Ui.u(8)
        })
        scaleRow.addView(weightBox, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))

        val tareBtn = Ui.vertical(c, Gravity.CENTER).apply {
            setPadding(Ui.u(14), Ui.u(8), Ui.u(14), Ui.u(8))
            background = Ui.pressable(Ui.round(Ui.SURFACE2, 16f, Ui.STROKE, 1f), 16f)
            addView(Icon(c, Icon.Kind.SYNC, Ui.TEXT), LinearLayout.LayoutParams(Ui.u(20), Ui.u(20)))
            addView(Ui.text(c, tr("Tare"), 13f, Ui.TEXT, Ui.MEDIUM), Ui.lp(Ui.WRAP, Ui.WRAP, t = 2))
            isClickable = true
            setOnClickListener { a.machine.tare() }
        }
        scaleRow.addView(tareBtn, LinearLayout.LayoutParams(Ui.u(64), Ui.WRAP))
        scaleRow.isClickable = true
        scaleRow.setOnClickListener { a.push(ScaleScreen(a)) }
        left.addView(scaleRow, Ui.lp(Ui.MATCH, Ui.WRAP))

        // Espace inférieur pour centrer le widget de pesée
        left.addView(Ui.spacer(c, weight = 1f))

        resumeCard = Ui.horizontal(c)
        resumeCard.setPadding(Ui.u(22), Ui.u(16), Ui.u(18), Ui.u(16))
        resumeCard.background = Ui.pressable(Ui.round(Ui.ACCENT_DIM, 24f), 24f)
        resumeCard.addView(Icon(c, Icon.Kind.PLAY, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(26), Ui.u(26)).apply { rightMargin = Ui.u(14) })
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
        left.addView(resumeCard, Ui.lp(Ui.MATCH, Ui.WRAP, t = 12))

        // Espace flexible pour caler le bouton Turbo tout en bas de la colonne.
        left.addView(Ui.spacer(c, weight = 1f))

        // Rangée 3 : Bouton Turbo instantané à maintien (calé en bas)
        val turbo = HoldButton(c, tr("Turbo"), onDown = {
            a.machine.turboDown(Accessory.BLADE) { v -> if (!v.ok) a.refusal(v) }
        }, onUp = { a.machine.turboUp() })
        left.addView(turbo, Ui.lp(Ui.MATCH, Ui.u(54)))
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 0.42f).apply { rightMargin = Ui.u(28) })

        val grid = Ui.vertical(c)
        val row1 = Ui.horizontal(c)
        val row2 = Ui.horizontal(c)
        val (t1, s1) = tile(Icon.Kind.BOOK, tr("Recettes"), tr("1 118 recettes")) { a.push(RecipesScreen(a)) }
        recipesSub = s1
        row1.addView(t1, tileLp(true))
        row1.addView(tile(Icon.Kind.POT, tr("Cuisson\nmanuelle"), tr("Contrôle manuel")) { a.push(ManualScreen(a)) }.first, tileLp(true))
        row1.addView(tile(Icon.Kind.KNEAD, tr("Programmes"), tr("Préréglages")) { a.push(ProgramsScreen(a)) }.first, tileLp(false))
        row2.addView(tile(Icon.Kind.SCALE, tr("Balance"), tr("Peser au gramme")) { a.push(ScaleScreen(a)) }.first, tileLp(true))
        val (t5, s5) = tile(Icon.Kind.STAR, tr("Favoris"), tr("Aucun favori")) { a.push(RecipesScreen(a, favorites = true)) }
        favSub = s5
        row2.addView(t5, tileLp(true))
        val (t6, s6) = tile(Icon.Kind.SETTINGS, tr("Réglages"), tr("Système et appareil")) { a.push(SettingsScreen(a)) }
        settingsSub = s6
        row2.addView(t6, tileLp(false))
        grid.addView(row1, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { bottomMargin = Ui.u(16) })
        grid.addView(row2, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        root.addView(grid, LinearLayout.LayoutParams(0, Ui.MATCH, 0.58f))
        return root
    }

    private fun tileLp(gap: Boolean) = LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (gap) rightMargin = Ui.u(16) }

    private fun tile(icon: Icon.Kind, title: String, sub: String = "", hasSub: Boolean = true, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        // Tuile géométriquement alignée : pastille centrée exactement entre le haut et le titre, zone de texte calée en bas.
        val t = Ui.vertical(c, Gravity.CENTER_HORIZONTAL)
        t.setPadding(Ui.u(14), Ui.u(14), Ui.u(14), Ui.u(14))
        t.background = Ui.pressable(Ui.round(Ui.SURFACE, 26f, Ui.STROKE, 1f), 26f)

        // Secteur supérieur : pastille 84x84 strictement centrée entre le bord haut et le titre
        val topSector = android.widget.FrameLayout(c)
        val badge = android.widget.FrameLayout(c)
        badge.background = Ui.round(Ui.ACCENT_DIM, 44f)
        badge.addView(Icon(c, icon, Ui.ACCENT), Ui.flp(Ui.u(48), Ui.u(48), Gravity.CENTER))
        topSector.addView(badge, Ui.flp(Ui.u(84), Ui.u(84), Gravity.CENTER))
        t.addView(topSector, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        // Secteur inférieur : zone de texte à hauteur fixe pour aligner horizontalement tous les titres et sous-titres
        val text = Ui.vertical(c, Gravity.CENTER_HORIZONTAL or Gravity.TOP)
        text.addView(Ui.text(c, title, 21f, Ui.TEXT, Ui.MEDIUM).apply { gravity = Gravity.CENTER }, Ui.lp(Ui.WRAP, Ui.WRAP))
        val s = Ui.ellipsize(Ui.text(c, sub, 14f, Ui.TEXT2).apply { gravity = Gravity.CENTER }, 2)
        if (hasSub) text.addView(s, Ui.lp(Ui.WRAP, Ui.WRAP, t = 4))
        t.addView(text, LinearLayout.LayoutParams(Ui.MATCH, Ui.u(76)))

        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t to s
    }

    private fun refreshClock() {
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
        val lidl = n - perso
        recipesSub.text = when {
            !repo.loaded -> tr("Chargement…")
            n == 0 -> tr("Aucune recette Lidl pour l'instant")
            else -> listOfNotNull(
                when (lidl) { 0 -> null; 1 -> tr("1 recette Lidl"); else -> tr("%d recettes Lidl", lidl) },
                when (perso) { 0 -> null; 1 -> tr("1 recette perso"); else -> tr("%d recettes perso", perso) },
            ).joinToString("\n")
        }
        val favs = repo.index.entries.count { it.favorite }
        favSub.text = if (favs == 0) tr("Aucun favori") else if (favs == 1) tr("1 favori") else tr("%d favoris", favs)
        knobChip.visibility = if (a.prefs.knobReady) View.GONE else View.VISIBLE
        val update = a.updates.available
        if (update != null) {
            settingsSub.text = tr("Version %s disponible", update.version)
            settingsSub.setTextColor(Ui.ACCENT)
        } else {
            settingsSub.text = tr("Système et appareil")
            settingsSub.setTextColor(Ui.TEXT2)
        }
        settingsSub.visibility = View.VISIBLE
        val guided = a.prefs.guidedRecipe
        val title = if (guided.isEmpty()) null else repo.index.entries.firstOrNull { it.id == guided }?.title
        resumeCard.visibility = if (title != null) View.VISIBLE else View.GONE
        if (title != null) resumeText.text = tr("Reprendre « %s » — étape %d", title, a.prefs.guidedStep + 1)
    }

    override fun onShow() {
        refreshClock()
        refresh()
        unsub = a.app.recipes.onChange { refresh() }
        a.updates.onChange = { refresh() }
        view.removeCallbacks(tick)
        view.postDelayed(tick, 10_000)
    }

    override fun onHide() {
        unsub?.invoke()
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

    override fun onSnapshot(s: Snapshot) {
        if (!::weightValue.isInitialized) return
        weightValue.text = if (!s.hasSample) "—" else "${s.weightG}"
        if (::tempValue.isInitialized) {
            tempValue.text = "${s.actualTempC} °C"
            val col = if (s.actualTempC >= 40) Ui.tempColor(s.actualTempC) else Ui.TEXT2
            tempValue.setTextColor(col)
            tempIcon.color = col
        }
        if (::lidIcon.isInitialized) {
            lidIcon.visibility = if (s.hasSample && s.lidOpen) View.VISIBLE else View.GONE
        }
        if (::faultIcon.isInitialized) {
            val hasFaultOrLost = s.hasFault || s.link == app.toque.core.machine.LinkState.LOST || s.link == app.toque.core.machine.LinkState.OFFLINE
            val arming = s.link == app.toque.core.machine.LinkState.ARMING
            if (hasFaultOrLost) {
                faultIcon.color = Ui.DANGER
                faultIcon.visibility = View.VISIBLE
            } else if (arming) {
                faultIcon.color = Ui.WARN
                faultIcon.visibility = View.VISIBLE
            } else {
                faultIcon.visibility = View.GONE
            }
        }
        if (::modeIcon.isInitialized) {
            when (a.prefs.mode) {
                app.toque.Prefs.Mode.DEMO -> {
                    modeIcon.set(Icon.Kind.INFO)
                    modeIcon.color = Ui.COOL
                    modeIcon.visibility = View.VISIBLE
                }
                app.toque.Prefs.Mode.TWIN -> {
                    modeIcon.set(Icon.Kind.LINK)
                    modeIcon.color = Ui.COOL
                    modeIcon.visibility = View.VISIBLE
                }
                app.toque.Prefs.Mode.HARDWARE -> {
                    modeIcon.visibility = View.GONE
                }
            }
        }
    }
}
