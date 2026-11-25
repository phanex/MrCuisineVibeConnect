package app.toque.core.recipes

import app.toque.core.i18n.tr

/**
 * Ordre de la liste de recettes du robot. [TITLE] : l'ordre habituel (favoris puis titre, ou
 * pertinence quand un texte est tapé). Pas d'ordre « les plus récentes » : le catalogue ne donne pas
 * de date de publication fiable (seulement la date de dernière modification de la fiche).
 */
enum class RecipeSort(val key: String) {
    TITLE("title"), RATING("rating"), DURATION("duration");

    /** Texte du choix ; [searching] : un texte est tapé, l'ordre habituel est alors la pertinence. */
    fun label(searching: Boolean): String = when (this) {
        TITLE -> if (searching) tr("Pertinence") else tr("A à Z")
        RATING -> tr("Les mieux notées")
        DURATION -> tr("Les plus rapides")
    }

    companion object {
        /** Les mieux notées : note, puis nombre de notes ; sans note à la fin ; à égalité, par titre. */
        val byRating: Comparator<SearchIndex.Hit> = Comparator { x, y ->
            val a = x.entry
            val b = y.entry
            val ra = a.ratingCount > 0
            val rb = b.ratingCount > 0
            when {
                ra != rb -> if (ra) -1 else 1
                ra && a.rating != b.rating -> b.rating.compareTo(a.rating)
                ra && a.ratingCount != b.ratingCount -> b.ratingCount.compareTo(a.ratingCount)
                else -> a.normTitle.compareTo(b.normTitle)
            }
        }

        /** Les plus rapides : durée croissante ; durée inconnue à la fin ; à égalité, par titre. */
        val byDuration: Comparator<SearchIndex.Hit> = Comparator { x, y ->
            val a = x.entry.totalMinutes
            val b = y.entry.totalMinutes
            when {
                (a > 0) != (b > 0) -> if (a > 0) -1 else 1
                a > 0 && a != b -> a.compareTo(b)
                else -> x.entry.normTitle.compareTo(y.entry.normTitle)
            }
        }

        /** [hits] dans l'ordre [sort] ([TITLE] : tels quels). */
        fun apply(hits: List<SearchIndex.Hit>, sort: RecipeSort): List<SearchIndex.Hit> = when (sort) {
            TITLE -> hits
            RATING -> hits.sortedWith(byRating)
            DURATION -> hits.sortedWith(byDuration)
        }
    }
}

/**
 * Pastilles choisies dans la liste de recettes du robot : plusieurs filtres à la fois (une catégorie,
 * « 30 min max », Favoris, Mes recettes ou Communauté), ou une vue par les cuissons (Récentes, Fréquentes),
 * qui est un ordre et reste seule. Aucune pastille : « Toutes ». Le téléphone, lui, envoie une seule pastille
 * ([RecipeChip.select]) ; [of] donne la sélection équivalente.
 */
data class ChipSelection(
    val category: String = "",
    val quick: Boolean = false,
    val favorites: Boolean = false,
    val user: Boolean = false,
    /** [RecipeChip.Kind.RECENT] ou [RecipeChip.Kind.FREQUENT] ; null : pas de vue par les cuissons. */
    val usage: RecipeChip.Kind? = null,
    /** Recettes de la communauté (toqueapp.org) ; jamais avec [user] : une recette n'a qu'une origine. */
    val community: Boolean = false,
    /** Recettes d'un serveur Mealie ; jamais avec [user] ni [community]. */
    val mealie: Boolean = false,
) {
    /** Aucun filtre ni vue : « Toutes ». */
    val isAll: Boolean get() = usage == null && !hasFilters

    val hasFilters: Boolean get() = category.isNotEmpty() || quick || favorites || user || community || mealie

    val byUsage: Boolean get() = usage != null

    /** La pastille [chip] est allumée. */
    fun isOn(chip: RecipeChip): Boolean = when (chip.kind) {
        RecipeChip.Kind.ALL -> isAll
        RecipeChip.Kind.RECENT, RecipeChip.Kind.FREQUENT -> usage == chip.kind
        RecipeChip.Kind.FAVORITES -> favorites
        RecipeChip.Kind.USER -> user
        RecipeChip.Kind.COMMUNITY -> community
        RecipeChip.Kind.MEALIE -> mealie
        RecipeChip.Kind.QUICK -> quick
        RecipeChip.Kind.CATEGORY -> category.isNotEmpty() && category == chip.category
    }

    /**
     * Ce que fait un appui sur [chip] : « Toutes » efface tout ; Récentes et Fréquentes remplacent tout
     * (un second appui revient à « Toutes ») ; un filtre s'allume ou s'éteint, en quittant une vue par les
     * cuissons ; une seule catégorie à la fois (une recette n'en a qu'une), et une seule origine parmi
     * « Mes recettes », « Communauté » et « Mealie » (une recette n'a qu'une origine).
     */
    fun tap(chip: RecipeChip): ChipSelection {
        val filters = if (usage != null) ChipSelection() else this
        return when (chip.kind) {
            RecipeChip.Kind.ALL -> ChipSelection()
            RecipeChip.Kind.RECENT, RecipeChip.Kind.FREQUENT -> if (usage == chip.kind) ChipSelection() else ChipSelection(usage = chip.kind)
            RecipeChip.Kind.FAVORITES -> filters.copy(favorites = !filters.favorites)
            RecipeChip.Kind.USER -> filters.copy(user = !filters.user, community = false, mealie = false)
            RecipeChip.Kind.COMMUNITY -> filters.copy(community = !filters.community, user = false, mealie = false)
            RecipeChip.Kind.MEALIE -> filters.copy(mealie = !filters.mealie, user = false, community = false)
            RecipeChip.Kind.QUICK -> filters.copy(quick = !filters.quick)
            RecipeChip.Kind.CATEGORY -> filters.copy(category = if (filters.category == chip.category) "" else chip.category)
        }
    }

    /** Filtre de recherche : tous les filtres allumés à la fois. */
    val filter: RecipeFilter get() = RecipeFilter(
        category = category,
        favoritesOnly = favorites,
        source = if (user) RecipeSource.USER else if (community) RecipeSource.COMMUNITY else if (mealie) RecipeSource.MEALIE else null,
        maxMinutes = if (quick) RecipeChip.QUICK_MINUTES else 0,
    )

    /** Les pastilles à montrer (celles de [RecipeChip.list]), la pastille d'origine allumée gardée. */
    fun chips(index: SearchIndex): List<RecipeChip> {
        val origin = if (user) RecipeChip.USER else if (community) RecipeChip.COMMUNITY else if (mealie) RecipeChip.MEALIE else null
        val out = ArrayList(RecipeChip.list(index, origin))
        // Catégorie choisie disparue du catalogue (ou au-delà des premières) : reste visible pour l'éteindre.
        if (category.isNotEmpty() && out.none { it.kind == RecipeChip.Kind.CATEGORY && it.category == category }) out += RecipeChip.category(category)
        return out
    }

    /**
     * Les recettes montrées avec le texte [query], dans l'ordre [sort]. Récentes et Fréquentes gardent
     * l'ordre des cuissons (le tri ne s'y applique pas), comme [RecipeChip.select].
     */
    fun select(
        index: SearchIndex,
        query: String,
        sort: RecipeSort = RecipeSort.TITLE,
        recentIds: () -> List<String> = { emptyList() },
        frequentIds: () -> List<String> = { emptyList() },
    ): List<SearchIndex.Hit> {
        val u = usage
        if (u != null) return RecipeChip.select(index, RecipeChip(u), query, recentIds, frequentIds)
        val f = filter
        val found = if (query.contains(',')) index.withIngredients(query.split(','), f) else index.search(query, f, Int.MAX_VALUE)
        return RecipeSort.apply(found, sort)
    }

    companion object {
        /** La sélection d'une seule pastille (celle du téléphone, ou d'un raccourci de l'accueil). */
        fun of(chip: RecipeChip): ChipSelection = ChipSelection().tap(chip)
    }
}
