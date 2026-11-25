package app.toque.core.recipes

import app.toque.core.i18n.tr

/**
 * Une pastille de la liste de recettes (« Toutes », « Favoris », « Soupes »…). Les mêmes sur le
 * robot et sur le téléphone : la liste ([list]) et ce qu'elle montre ([select]) ne se calculent
 * qu'ici. [category] n'a de sens que pour [Kind.CATEGORY].
 */
data class RecipeChip(val kind: Kind, val category: String = "") {
    enum class Kind(val key: String) {
        ALL("all"), RECENT("recent"), FREQUENT("frequent"), FAVORITES("favorites"), USER("user"), COMMUNITY("community"), QUICK("quick"), CATEGORY("category"),
        /** Recettes d'un serveur Mealie (ajoutée après les autres : son rang ne déplace aucune clé). */
        MEALIE("mealie");

        companion object {
            fun of(key: String): Kind? = values().firstOrNull { it.key == key }
        }
    }

    /** Clé stable : « all », « quick », « category:Soupes ». */
    val key: String get() = if (kind == Kind.CATEGORY) "category:$category" else kind.key

    /** Texte de la pastille : traduit pour les filtres fixes, tel quel pour une catégorie (donnée du catalogue). */
    val label: String get() = when (kind) {
        Kind.ALL -> tr("Toutes")
        Kind.RECENT -> tr("Récentes")
        Kind.FREQUENT -> tr("Fréquentes")
        Kind.FAVORITES -> tr("Favoris")
        Kind.USER -> tr("Mes recettes")
        Kind.COMMUNITY -> tr("Communauté")
        Kind.MEALIE -> "Mealie"
        Kind.QUICK -> tr("30 min max")
        Kind.CATEGORY -> category
    }

    /** Récentes et Fréquentes : l'ordre vient des cuissons, la recherche ne fait que filtrer. */
    val byUsage: Boolean get() = kind == Kind.RECENT || kind == Kind.FREQUENT

    /** Filtre de recherche équivalent. */
    val filter: RecipeFilter get() = when (kind) {
        Kind.FAVORITES -> RecipeFilter(favoritesOnly = true)
        Kind.USER -> RecipeFilter(source = RecipeSource.USER)
        Kind.COMMUNITY -> RecipeFilter(source = RecipeSource.COMMUNITY)
        Kind.MEALIE -> RecipeFilter(source = RecipeSource.MEALIE)
        Kind.QUICK -> RecipeFilter(maxMinutes = QUICK_MINUTES)
        Kind.CATEGORY -> RecipeFilter(category = category)
        else -> RecipeFilter()
    }

    companion object {
        val ALL = RecipeChip(Kind.ALL)
        val RECENT = RecipeChip(Kind.RECENT)
        val FREQUENT = RecipeChip(Kind.FREQUENT)
        val FAVORITES = RecipeChip(Kind.FAVORITES)
        val USER = RecipeChip(Kind.USER)
        val COMMUNITY = RecipeChip(Kind.COMMUNITY)
        val MEALIE = RecipeChip(Kind.MEALIE)
        val QUICK = RecipeChip(Kind.QUICK)
        fun category(name: String) = RecipeChip(Kind.CATEGORY, name)

        /** Les pastilles toujours là (et les seules d'un robot qui ne donne pas sa liste). */
        val FIXED: List<RecipeChip> = listOf(ALL, RECENT, FREQUENT, FAVORITES)
        const val QUICK_MINUTES = 30
        const val MAX_CATEGORIES = 30

        /**
         * Les pastilles, dans l'ordre de l'écran : les fixes, « Mes recettes » (s'il y a une recette
         * perso, ou si c'est la pastille choisie), « Communauté » (de même, pour les recettes venues de
         * toqueapp.org), « Mealie » (de même), « 30 min max », puis les 30 premières catégories.
         */
        fun list(index: SearchIndex, selected: RecipeChip? = null): List<RecipeChip> {
            val out = ArrayList<RecipeChip>(FIXED.size + 4 + MAX_CATEGORIES)
            out += FIXED
            if (selected?.kind == Kind.USER || index.entries.any { it.source == RecipeSource.USER }) out += USER
            if (selected?.kind == Kind.COMMUNITY || index.entries.any { it.source == RecipeSource.COMMUNITY }) out += COMMUNITY
            if (selected?.kind == Kind.MEALIE || index.entries.any { it.source == RecipeSource.MEALIE }) out += MEALIE
            out += QUICK
            for (cat in index.categories.take(MAX_CATEGORIES)) out += category(cat)
            return out
        }

        /**
         * Ce que montre la pastille [chip] avec le texte [query] : recherche tolérante (ou par
         * ingrédients séparés par des virgules) dans le filtre de la pastille ; pour Récentes et
         * Fréquentes, l'ordre des cuissons, que le texte ne fait que filtrer. [limit] borne la
         * recherche ; le robot montre tout (sa grille recycle ses cases), le téléphone charge page par page.
         */
        fun select(
            index: SearchIndex,
            chip: RecipeChip,
            query: String,
            recentIds: () -> List<String> = { emptyList() },
            frequentIds: () -> List<String> = { emptyList() },
            limit: Int = Int.MAX_VALUE,
        ): List<SearchIndex.Hit> {
            val f = chip.filter
            val found = if (query.contains(',')) index.withIngredients(query.split(','), f) else index.search(query, f, limit)
            if (!chip.byUsage) return found
            val ids = if (chip.kind == Kind.RECENT) recentIds() else frequentIds()
            val byId = HashMap<String, SearchIndex.Entry>()
            for (e in index.entries) byId[e.id] = e
            val keep = if (query.isBlank()) null else found.mapTo(HashSet()) { it.entry.id }
            val seen = HashSet<String>()
            return ids.filter { (keep == null || it in keep) && seen.add(it) }.mapNotNull { id -> byId[id]?.let { SearchIndex.Hit(it, 0.0, 0) } }
        }
    }
}
