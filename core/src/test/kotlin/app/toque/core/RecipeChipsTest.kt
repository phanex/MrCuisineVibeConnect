package app.toque.core

import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeChip
import app.toque.core.recipes.RecipeFilter
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.SearchIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pastilles de la liste de recettes : leur liste, et une pastille seule montre ce que l'écran montrait avant RecipeChip. */
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
    }

    @Test fun everyChipSelectsLikeTheRobotDid() {
        val chips = RecipeChip.list(index) + RecipeChip.category("Inconnue")
        for (chip in chips) for (q in texts) {
            val now = RecipeChip.select(index, chip, q, { recent }, { frequent }).map { it.entry.id }
            assertEquals("${chip.key} « $q »", before(chip, q), now)
        }
        // Quelques contrôles lisibles.
        assertEquals(listOf("3", "5", "6"), RecipeChip.select(index, RecipeChip.category("Desserts"), "").map { it.entry.id })
        assertEquals(listOf("6"), RecipeChip.select(index, RecipeChip.category("Desserts"), "riz").map { it.entry.id })
        assertEquals(listOf("6", "perso-veloute", "1"), RecipeChip.select(index, RecipeChip.RECENT, "", { recent }).map { it.entry.id })
        assertEquals(listOf("6"), RecipeChip.select(index, RecipeChip.RECENT, "riz", { recent }).map { it.entry.id })
        assertEquals(setOf("1", "3", "4", "5", "8", "9"), RecipeChip.select(index, RecipeChip.QUICK, "").map { it.entry.id }.toSet())
        assertEquals(listOf("perso-veloute"), RecipeChip.select(index, RecipeChip.USER, "").map { it.entry.id })
    }
}
