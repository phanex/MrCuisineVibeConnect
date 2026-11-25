package app.toque.core

import app.toque.core.i18n.I18n
import app.toque.core.recipes.ChipSelection
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeChip
import app.toque.core.recipes.RecipeFilter
import app.toque.core.recipes.RecipeSort
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.SearchIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plusieurs pastilles à la fois et tri de la liste du robot (recettes inventées). */
class ChipSelectionTest {
    private fun r(id: String, title: String, cat: String, min: Int, rating: Double = 0.0, votes: Int = 0, fav: Boolean = false,
                  user: Boolean = false, ingredients: List<String> = emptyList()) =
        Recipe(id = id, title = title, category = cat, totalMinutes = min, rating = rating, ratingCount = votes, favorite = fav,
            source = if (user) RecipeSource.USER else RecipeSource.IMPORTED,
            ingredients = listOf(IngredientGroup(items = ingredients.map { Ingredient("1", "", it) })))

    private val recipes = listOf(
        r("1", "Pâte à pizza", "Pâtes", 7, 4.2, 30, ingredients = listOf("farine", "eau")),
        r("2", "Risotto aux champignons", "Plats", 40, 4.8, 12, ingredients = listOf("riz", "champignons")),
        r("3", "Compote de pommes", "Desserts", 20, 4.7, 80, fav = true, ingredients = listOf("pommes", "eau")),
        r("4", "Soupe de légumes", "Soupes", 30, ingredients = listOf("carottes", "eau")),
        r("5", "Crème anglaise", "Desserts", 15, 4.8, 126, fav = true, ingredients = listOf("lait", "oeufs")),
        r("6", "Riz au lait", "Desserts", 45, 4.5, 40, ingredients = listOf("riz", "lait")),
        r("7", "Soupe de potiron", "Soupes", 0, 4.8, 12, ingredients = listOf("potiron")),
        r("8", "Houmous", "Entrées", 10, 3.9, 5),
        r("9", "Mousse au chocolat", "Desserts", 25, 4.7, 80),
        r("10", "Flan pâtissier", "Desserts", 0),
        r("perso-veloute", "Velouté de potimarron", "Soupes", 35, user = true, ingredients = listOf("potimarron", "eau")),
        r("perso-tarte", "Tarte aux pommes", "Desserts", 25, fav = true, user = true, ingredients = listOf("pommes", "farine")),
    )
    private val index = SearchIndex(recipes)
    private val recent = listOf("6", "perso-veloute", "1")
    private val frequent = listOf("1", "6")

    private fun ids(s: ChipSelection, q: String = "", sort: RecipeSort = RecipeSort.TITLE) =
        s.select(index, q, sort, { recent }, { frequent }).map { it.entry.id }

    private val desserts = RecipeChip.category("Desserts")

    @Test fun tapsCombineFiltersAndKeepUsageViewsAlone() {
        var s = ChipSelection()
        assertTrue(s.isAll)
        assertTrue(s.isOn(RecipeChip.ALL))
        s = s.tap(desserts)
        s = s.tap(RecipeChip.QUICK)
        assertEquals(ChipSelection(category = "Desserts", quick = true), s)
        assertFalse(s.isOn(RecipeChip.ALL))
        assertTrue(s.isOn(desserts) && s.isOn(RecipeChip.QUICK))
        assertFalse(s.isOn(RecipeChip.category("Soupes")))
        // Une seule catégorie : une autre remplace la première, la même l'éteint.
        assertEquals("Soupes", s.tap(RecipeChip.category("Soupes")).category)
        assertEquals(ChipSelection(quick = true), s.tap(desserts))
        // Favoris et Mes recettes s'ajoutent, puis s'éteignent.
        s = s.tap(RecipeChip.FAVORITES).tap(RecipeChip.USER)
        assertEquals(ChipSelection("Desserts", quick = true, favorites = true, user = true), s)
        assertEquals(ChipSelection("Desserts", quick = true, user = true), s.tap(RecipeChip.FAVORITES))
        // Toutes efface tout.
        assertEquals(ChipSelection(), s.tap(RecipeChip.ALL))
        // Récentes : seule, les filtres s'effacent ; un filtre ensuite quitte la vue ; un second appui revient à Toutes.
        val rec = s.tap(RecipeChip.RECENT)
        assertEquals(ChipSelection(usage = RecipeChip.Kind.RECENT), rec)
        assertTrue(rec.byUsage && rec.isOn(RecipeChip.RECENT) && !rec.isOn(RecipeChip.FREQUENT) && !rec.isOn(RecipeChip.ALL))
        assertEquals(ChipSelection(usage = RecipeChip.Kind.FREQUENT), rec.tap(RecipeChip.FREQUENT))
        assertEquals(ChipSelection(favorites = true), rec.tap(RecipeChip.FAVORITES))
        assertEquals(ChipSelection(category = "Desserts"), rec.tap(desserts))
        assertEquals(ChipSelection(), rec.tap(RecipeChip.RECENT))
        // Éteindre le dernier filtre : retour à Toutes.
        assertTrue(ChipSelection(favorites = true).tap(RecipeChip.FAVORITES).isAll)
    }

    @Test fun filterOfEveryCombination() {
        for (cat in listOf("", "Desserts")) for (quick in listOf(false, true)) for (fav in listOf(false, true)) for (user in listOf(false, true)) {
            val s = ChipSelection(cat, quick, fav, user)
            assertEquals(RecipeFilter(cat, fav, if (user) RecipeSource.USER else null, if (quick) 30 else 0), s.filter)
            // Chaque recette montrée passe tous les filtres ; aucune qui les passe ne manque.
            val expected = recipes.filter { x ->
                (cat.isEmpty() || x.category == cat) && (!quick || (x.totalMinutes in 1..30)) && (!fav || x.favorite) &&
                    (!user || x.source == RecipeSource.USER)
            }.map { it.id }.toSet()
            assertEquals("$s", expected, ids(s).toSet())
        }
        // Le dessert rapide bien noté.
        assertEquals(listOf("5", "3", "9", "perso-tarte"), ids(ChipSelection("Desserts", quick = true), sort = RecipeSort.RATING))
        assertEquals(listOf("perso-tarte"), ids(ChipSelection("Desserts", favorites = true, user = true)))
        assertEquals(emptyList<String>(), ids(ChipSelection("Entrées", user = true)))
    }

    @Test fun oneChipSelectsLikeThePhone() {
        val texts = listOf("", "soupe", "riz", "lait, riz", "pomes", "xyzzy")
        for (chip in RecipeChip.list(index) + RecipeChip.category("Inconnue")) for (q in texts) {
            val phone = RecipeChip.select(index, chip, q, { recent }, { frequent }).map { it.entry.id }
            assertEquals("${chip.key} « $q »", phone, ids(ChipSelection.of(chip), q))
            assertTrue(ChipSelection.of(chip).isOn(chip))
        }
    }

    @Test fun sortByRatingThenVotesUnratedLast() {
        // 4,8 : 126 notes, puis 12 notes (à égalité : par titre) ; sans note à la fin, par titre.
        assertEquals(listOf("5", "2", "7", "3", "9", "6", "1", "8", "10", "4", "perso-tarte", "perso-veloute"),
            ids(ChipSelection(), sort = RecipeSort.RATING))
    }

    @Test fun sortByDurationUnknownLast() {
        assertEquals(listOf("1", "8", "5", "3", "9", "perso-tarte", "4", "perso-veloute", "2", "6", "10", "7"),
            ids(ChipSelection(), sort = RecipeSort.DURATION))
        // Ensemble avec un filtre.
        assertEquals(listOf("5", "3", "9", "perso-tarte", "6", "10"), ids(ChipSelection("Desserts"), sort = RecipeSort.DURATION))
    }

    @Test fun textKeepsRelevanceUnlessASortIsChosen() {
        // Sans tri choisi : la pertinence, comme avant.
        assertEquals(index.search("pommes", RecipeFilter(), Int.MAX_VALUE).map { it.entry.id }, ids(ChipSelection(), "pommes"))
        // Avec un tri : les mêmes recettes, dans l'ordre du tri.
        val found = ids(ChipSelection(), "pommes").toSet()
        val rated = ids(ChipSelection(), "pommes", RecipeSort.RATING)
        assertEquals(found, rated.toSet())
        assertEquals(listOf("3", "perso-tarte"), rated)
        // Par ingrédients : idem.
        assertEquals(listOf("5", "6"), ids(ChipSelection(), "lait, oeufs", RecipeSort.DURATION))
    }

    @Test fun usageViewsKeepTheirOrder() {
        val rec = ChipSelection.of(RecipeChip.RECENT)
        for (sort in RecipeSort.values()) assertEquals(recent, ids(rec, sort = sort))
        assertEquals(frequent, ids(ChipSelection.of(RecipeChip.FREQUENT), sort = RecipeSort.DURATION))
    }

    @Test fun chipsKeepTheChosenOnes() {
        val lidl = SearchIndex(recipes.filter { it.source != RecipeSource.USER })
        assertFalse(RecipeChip.USER in ChipSelection().chips(lidl))
        assertTrue(RecipeChip.USER in ChipSelection(user = true).chips(lidl))
        // Une catégorie choisie qui n'existe plus reste visible pour pouvoir l'éteindre.
        assertEquals(RecipeChip.category("Disparue"), ChipSelection("Disparue").chips(index).last())
        assertEquals(RecipeChip.list(index), ChipSelection("Desserts").chips(index))
    }

    /** « Mes recettes » ou « Communauté » : une recette n'a qu'une origine, une pastille éteint l'autre. */
    @Test fun communityAndMineAreOneOrigin() {
        var s = ChipSelection().tap(RecipeChip.USER).tap(desserts)
        s = s.tap(RecipeChip.COMMUNITY)
        assertEquals(ChipSelection(category = "Desserts", community = true), s)
        assertTrue(s.isOn(RecipeChip.COMMUNITY) && s.isOn(desserts))
        assertFalse(s.isOn(RecipeChip.USER) || s.isAll)
        assertEquals(RecipeFilter(category = "Desserts", source = RecipeSource.COMMUNITY), s.filter)
        assertEquals(ChipSelection(category = "Desserts", user = true), s.tap(RecipeChip.USER))
        assertTrue(ChipSelection.of(RecipeChip.COMMUNITY).tap(RecipeChip.COMMUNITY).isAll)
        // Une vue par les cuissons efface tout, la communauté comme le reste.
        assertEquals(ChipSelection(usage = RecipeChip.Kind.RECENT), s.tap(RecipeChip.RECENT))
        // Allumée sans recette de la communauté (elles viennent de partir) : la pastille reste, pour l'éteindre.
        assertFalse(RecipeChip.COMMUNITY in ChipSelection().chips(index))
        assertTrue(RecipeChip.COMMUNITY in ChipSelection(community = true).chips(index))
        val both = SearchIndex(recipes + Recipe(id = "toqueapp-7", title = "Tiramisu du voisin", category = "Desserts", totalMinutes = 20,
            source = RecipeSource.COMMUNITY))
        assertEquals(listOf("toqueapp-7"), ChipSelection.of(RecipeChip.COMMUNITY).select(both, "").map { it.entry.id })
        assertEquals(listOf("toqueapp-7"), s.select(both, "").map { it.entry.id })
        assertEquals(emptyList<String>(), s.tap(RecipeChip.category("Soupes")).select(both, "").map { it.entry.id })
    }

    @Test fun sortLabels() {
        assertEquals(listOf("A à Z", "Les mieux notées", "Les plus rapides"), RecipeSort.values().map { it.label(false) })
        assertEquals("Pertinence", RecipeSort.TITLE.label(true))
        assertEquals("Les mieux notées", RecipeSort.RATING.label(true))
        I18n.select("fr")
    }
}
