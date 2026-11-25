package app.toque.core

import app.toque.core.companion.ApiError
import app.toque.core.companion.CatalogChipWire
import app.toque.core.companion.CatalogFilter
import app.toque.core.companion.CatalogPageWire
import app.toque.core.companion.CatalogQuery
import app.toque.core.companion.CatalogSearch
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeChip
import app.toque.core.recipes.RecipeFilter
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.SearchIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pastilles de la liste de recettes : les mêmes, et les mêmes recettes, sur le robot et le téléphone. */
class RecipeChipsTest {
    private fun r(id: String, title: String, cat: String, min: Int, fav: Boolean = false, user: Boolean = false, ingredients: List<String> = emptyList()) =
        Recipe(id = id, title = title, category = cat, totalMinutes = min, favorite = fav,
            source = if (user) RecipeSource.USER else RecipeSource.IMPORTED,
            ingredients = listOf(app.toque.core.recipes.IngredientGroup(items = ingredients.map { app.toque.core.recipes.Ingredient("1", "", it) })))

    private val recipes = listOf(
        r("1", "Pâte à pizza", "Pâtes", 7, ingredients = listOf("farine", "eau")),
        r("2", "Risotto aux champignons", "Plats", 40, ingredients = listOf("riz", "champignons")),
        r("3", "Compote de pommes", "Desserts", 20, fav = true, ingredients = listOf("pommes", "eau")),
        r("4", "Soupe de légumes", "Soupes", 30, ingredients = listOf("carottes", "eau")),
        r("5", "Crème anglaise", "Desserts", 15, fav = true, ingredients = listOf("lait", "oeufs")),
        r("6", "Riz au lait", "Desserts", 45, ingredients = listOf("riz", "lait")),
        r("7", "Soupe de potiron", "Soupes", 0, ingredients = listOf("potiron")),
        r("8", "Houmous", "Entrées", 10),
        r("9", "Gâteau sans catégorie", "", 25),
        r("perso-veloute", "Velouté de potimarron", "Soupes", 35, user = true, ingredients = listOf("potimarron", "eau")),
    )
    private val index = SearchIndex(recipes)
    private val recent = listOf("6", "perso-veloute", "1", "inconnue")
    private val frequent = listOf("1", "6")

    /** La sélection telle que RecipesScreen la faisait avant RecipeChip (référence du robot). */
    private fun robotBefore(q: String, f: RecipeFilter, usage: String): List<String> {
        val found = if (q.contains(',')) index.withIngredients(q.split(','), f) else index.search(q, f)
        if (usage == "") return found.map { it.entry.id }
        val ids = if (usage == "recent") recent else frequent
        val byId = index.entries.associateBy { it.id }
        val keep = if (q.isBlank()) null else found.mapTo(HashSet()) { it.entry.id }
        return ids.filter { keep == null || it in keep }.mapNotNull { id -> byId[id]?.let { SearchIndex.Hit(it, 0.0, 0) } }.map { it.entry.id }
    }

    private fun before(chip: RecipeChip, q: String): List<String> = when (chip.kind) {
        RecipeChip.Kind.ALL -> robotBefore(q, RecipeFilter(), "")
        RecipeChip.Kind.RECENT -> robotBefore(q, RecipeFilter(), "recent")
        RecipeChip.Kind.FREQUENT -> robotBefore(q, RecipeFilter(), "frequent")
        RecipeChip.Kind.FAVORITES -> robotBefore(q, RecipeFilter(favoritesOnly = true), "")
        RecipeChip.Kind.USER -> robotBefore(q, RecipeFilter(source = RecipeSource.USER), "")
        RecipeChip.Kind.COMMUNITY -> robotBefore(q, RecipeFilter(source = RecipeSource.COMMUNITY), "")
        RecipeChip.Kind.QUICK -> robotBefore(q, RecipeFilter(maxMinutes = 30), "")
        RecipeChip.Kind.CATEGORY -> robotBefore(q, RecipeFilter(category = chip.category), "")
    }

    private val texts = listOf("", "  ", "soupe", "riz", "lait, riz", "eau,", "pomes", "xyzzy", "Soupe de")

    @Test fun chipListOrderAndLabels() {
        val chips = RecipeChip.list(index)
        assertEquals(listOf("all", "recent", "frequent", "favorites", "user", "quick", "category:Desserts", "category:Entrées",
            "category:Pâtes", "category:Plats", "category:Soupes"), chips.map { it.key })
        assertEquals(listOf("Toutes", "Récentes", "Fréquentes", "Favoris", "Mes recettes", "30 min max", "Desserts", "Entrées",
            "Pâtes", "Plats", "Soupes"), chips.map { it.label })
        // Sans recette perso : pas de « Mes recettes », sauf si c'est la pastille choisie.
        val lidl = SearchIndex(recipes.filter { it.source != RecipeSource.USER })
        assertFalse(RecipeChip.USER in RecipeChip.list(lidl))
        assertEquals(4, RecipeChip.list(lidl, RecipeChip.USER).indexOf(RecipeChip.USER))
        // 30 catégories au plus, dans l'ordre alphabétique (accents ignorés).
        val many = SearchIndex((1..40).map { r("c$it", "Recette $it", "Catégorie %02d".format(it), 10) })
        val cats = RecipeChip.list(many).filter { it.kind == RecipeChip.Kind.CATEGORY }.map { it.category }
        assertEquals((1..30).map { "Catégorie %02d".format(it) }, cats)
        assertEquals(35, RecipeChip.list(many).size)
        assertEquals(36, RecipeChip.list(many, RecipeChip.USER).size)
    }

    /** Recettes venues de toqueapp.org (0.8.1) : leur pastille, entre « Mes recettes » et « 30 min max ». */
    @Test fun communityChipComesWithCommunityRecipes() {
        // Sans recette de la communauté : pas de pastille, sauf si c'est celle choisie.
        assertFalse(RecipeChip.COMMUNITY in RecipeChip.list(index))
        assertEquals(5, RecipeChip.list(index, RecipeChip.COMMUNITY).indexOf(RecipeChip.COMMUNITY))
        val both = SearchIndex(recipes + Recipe(id = "toqueapp-12", title = "Soupe du voisin", category = "Soupes", totalMinutes = 20,
            source = RecipeSource.COMMUNITY))
        assertEquals(listOf("all", "recent", "frequent", "favorites", "user", "community", "quick"), RecipeChip.list(both).take(7).map { it.key })
        assertEquals("Communauté", RecipeChip.COMMUNITY.label)
        assertEquals(listOf("toqueapp-12"), RecipeChip.select(both, RecipeChip.COMMUNITY, "").map { it.entry.id })
        assertEquals(listOf("toqueapp-12"), RecipeChip.select(both, RecipeChip.COMMUNITY, "soupe").map { it.entry.id })
        // « Mes recettes » ne les montre pas ; « Toutes » et la catégorie, si.
        assertFalse("toqueapp-12" in RecipeChip.select(both, RecipeChip.USER, "").map { it.entry.id })
        assertTrue("toqueapp-12" in RecipeChip.select(both, RecipeChip.ALL, "").map { it.entry.id })
        assertTrue("toqueapp-12" in RecipeChip.select(both, RecipeChip.category("Soupes"), "").map { it.entry.id })
        // Sur le fil : la même clé dans les deux sens, et le filtre que le téléphone renvoie.
        assertEquals(RecipeChip.COMMUNITY, CatalogChipWire.of(RecipeChip.COMMUNITY).toChip())
        assertEquals(CatalogFilter.COMMUNITY, CatalogFilter.of(RecipeChip.COMMUNITY))
        assertEquals(RecipeChip.COMMUNITY, CatalogQuery(filter = CatalogFilter.COMMUNITY).chip)
        // Un téléphone plus ancien ne connaît pas ce filtre : il laisse tomber la pastille, sans erreur.
        assertNull(CatalogChipWire("inconnu").toChip())
    }

    @Test fun everyChipSelectsLikeTheRobotDid() {
        val chips = RecipeChip.list(index) + RecipeChip.category("Inconnue")
        for (chip in chips) for (q in texts) {
            val now = RecipeChip.select(index, chip, q, { recent }, { frequent }).map { it.entry.id }
            assertEquals("${chip.key} « $q »", before(chip, q), now)
            // Le téléphone : même liste (catalogue de moins de 500 recettes), page par page.
            val query = CatalogQuery(q, CatalogFilter.of(chip), 0, 50, chip.category)
            assertEquals(chip, query.chip)
            assertEquals("${chip.key} « $q » (téléphone)", now, CatalogSearch.page(index, query, recent, frequent).items.map { it.id })
        }
        // Quelques contrôles lisibles.
        assertEquals(listOf("3", "5", "6"), RecipeChip.select(index, RecipeChip.category("Desserts"), "").map { it.entry.id })
        assertEquals(listOf("6"), RecipeChip.select(index, RecipeChip.category("Desserts"), "riz").map { it.entry.id })
        assertEquals(listOf("6", "perso-veloute", "1"), RecipeChip.select(index, RecipeChip.RECENT, "", { recent }).map { it.entry.id })
        assertEquals(listOf("6"), RecipeChip.select(index, RecipeChip.RECENT, "riz", { recent }).map { it.entry.id })
        assertEquals(setOf("1", "3", "4", "5", "8", "9"), RecipeChip.select(index, RecipeChip.QUICK, "").map { it.entry.id }.toSet())
        assertEquals(listOf("perso-veloute"), RecipeChip.select(index, RecipeChip.USER, "").map { it.entry.id })
    }

    @Test fun pagesCarryTheChipsOnTheFirstPageOnly() {
        val first = CatalogSearch.page(index, CatalogQuery(limit = 3))
        assertEquals(RecipeChip.list(index), first.filters!!.map { it.toChip() })
        assertNull(CatalogSearch.page(index, CatalogQuery(offset = 3, limit = 3)).filters)
        // Aller-retour JSON ; une page sans « filters » (robot plus ancien) se relit sans pastilles.
        val back = CatalogPageWire.fromMap(app.toque.core.json.Json.parse(app.toque.core.json.Json.write(first.toMap())) as Map<String, Any?>)
        assertEquals(first, back)
        assertNull(CatalogPageWire.fromMap(mapOf("total" to 0, "offset" to 0, "items" to emptyList<Any>())).filters)
        assertEquals(mapOf("filter" to "category", "category" to "Soupes"), CatalogChipWire.of(RecipeChip.category("Soupes")).toMap())
        assertEquals(mapOf("filter" to "quick"), CatalogChipWire.of(RecipeChip.QUICK).toMap())
        // Pastille inconnue (robot plus récent) ou catégorie sans nom : ignorée.
        assertNull(CatalogChipWire("seasonal").toChip())
        assertNull(CatalogChipWire("category", "").toChip())
    }

    @Test fun apiAcceptsEveryChipAndRejectsBadValues() {
        val f = HubFixture()
        val t = f.pair()
        fun page(q: Map<String, String>) = CatalogPageWire.fromMap(f.json(f.req("GET", "/api/recipes", t, query = q)))
        fun status(q: Map<String, String>) = f.req("GET", "/api/recipes", t, query = q)
        val data = CompanionData.index
        // La première page porte les pastilles du robot.
        assertEquals(RecipeChip.list(data), page(emptyMap()).filters!!.map { it.toChip() })
        assertNull(page(mapOf("offset" to "20")).filters)
        val soupes = page(mapOf("filter" to "category", "category" to "Soupes", "limit" to "50"))
        assertEquals(RecipeChip.select(data, RecipeChip.category("Soupes"), "").map { it.entry.id }, soupes.items.map { it.id })
        assertTrue(soupes.items.all { it.category == "Soupes" })
        assertEquals(13, soupes.total)
        assertEquals(RecipeChip.select(data, RecipeChip.QUICK, "").size, page(mapOf("filter" to "quick", "limit" to "50")).total)
        assertEquals(0, page(mapOf("filter" to "user")).total)
        assertEquals(0, page(mapOf("filter" to "category", "category" to "Inconnue")).total)
        // Anciens paramètres : inchangés.
        assertEquals(25, page(mapOf("filter" to "all")).total)
        assertEquals(25, page(mapOf("filter" to "")).total)
        assertTrue(page(mapOf("filter" to "favorites")).items.all { it.favorite })
        // Valeurs refusées.
        for (q in listOf(
            mapOf("filter" to "category"),
            mapOf("filter" to "category", "category" to ""),
            mapOf("filter" to "category", "category" to "  "),
            mapOf("filter" to "category", "category" to "x".repeat(101)),
            mapOf("filter" to "category", "category" to "a\nb"),
            mapOf("filter" to "all", "category" to "Soupes"),
            mapOf("category" to "Soupes"),
            mapOf("filter" to "Category", "category" to "Soupes"),
            mapOf("filter" to "categories"),
        )) {
            val r = status(q)
            assertEquals("$q", 400, r.status)
            assertEquals("bad_request", ApiError.fromMap(f.json(r)).code)
        }
    }
}
