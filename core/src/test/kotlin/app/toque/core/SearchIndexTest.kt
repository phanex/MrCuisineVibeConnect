package app.toque.core

import app.toque.core.companion.CatalogFilter
import app.toque.core.companion.CatalogQuery
import app.toque.core.companion.CatalogSearch
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeChip
import app.toque.core.recipes.RecipeFilter
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.SearchIndex
import app.toque.core.recipes.SearchWords
import app.toque.core.recipes.Step
import app.toque.core.recipes.TextNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Index précalculé (mots découpés une fois, listes compactes) : les mêmes résultats que l'index
 * d'avant ([LegacySearchIndex]) sur un catalogue inventé, et ses deux temps (titres, puis texte complet).
 */
class SearchIndexTest {
    private val dishes = listOf("Soupe", "Velouté", "Gâteau", "Crème", "Risotto", "Purée", "Tarte", "Compote", "Pâte", "Gratin",
        "Houmous", "Curry", "Salade", "Bœuf", "Crêpes", "Pain", "Sauce", "Œufs", "Brioche", "Flan")
    private val things = listOf("potiron", "champignons", "chocolat", "pommes", "carottes", "poulet", "lentilles corail", "épinards",
        "riz", "poireaux", "citron", "noix de coco", "fraises", "pois chiches", "tomates", "courgettes", "saumon", "fromage râpé")
    private val cats = listOf("Soupes", "Desserts", "Plats", "Entrées", "Sauces", "Pâtisserie", "")
    private val verbs = listOf("Ajouter", "Mixer", "Cuire", "Hacher", "Mélanger", "Verser", "Réserver", "Émincer", "Pétrir")

    /** Catalogue inventé, titres tous différents (numérotés) : l'ordre des résultats est entièrement fixé. */
    private fun catalogue(n: Int, seed: Int = 7): List<Recipe> {
        val rnd = Random(seed)
        return (0 until n).map { i ->
            val main = things[rnd.nextInt(things.size)]
            val title = "${dishes[rnd.nextInt(dishes.size)]} ${if (rnd.nextBoolean()) "de" else "aux"} $main n° $i"
            val ingredients = (0 until 2 + rnd.nextInt(6)).map { Ingredient("${10 * (1 + rnd.nextInt(30))}", "g", things[rnd.nextInt(things.size)]) }
            val steps = (0 until 1 + rnd.nextInt(5)).map {
                Step("${verbs[rnd.nextInt(verbs.size)]} les ${things[rnd.nextInt(things.size)]} et ${verbs[rnd.nextInt(verbs.size)].lowercase()} 2 min.")
            }
            Recipe(id = if (i % 25 == 0) "perso-$i" else "r$i", title = title, category = cats[rnd.nextInt(cats.size)],
                tags = if (rnd.nextInt(4) == 0) listOf("Végétarien") else emptyList(), totalMinutes = rnd.nextInt(90),
                ingredients = listOf(IngredientGroup(items = ingredients)), steps = steps, favorite = rnd.nextInt(9) == 0,
                source = if (i % 25 == 0) RecipeSource.USER else RecipeSource.IMPORTED, image = "photo-$i.jpg")
        }
    }

    private val queries = listOf("", "soupe", "Soupe de potiron", "POTIRON", "veloute", "gateau chocolat", "chocolta", "champinons",
        "lentilles", "lentille corail", "pois chiche", "noix coco", "fraise", "risoto", "hacher", "melanger", "creme", "oeufs",
        "boeuf", "crepe", "épinard", "vegetarien", "n 12", "12", "xyzzy", "a", "de", "tomates courgettes", "saumon fromage", "pat")
    private val filters = listOf(RecipeFilter(), RecipeFilter(category = "Soupes"), RecipeFilter(favoritesOnly = true),
        RecipeFilter(source = RecipeSource.USER), RecipeFilter(maxMinutes = 30))

    private fun sig(h: List<SearchIndex.Hit>) = h.map { Triple(it.entry.id, it.score, it.matched) }
    private fun sigOld(h: List<LegacySearchIndex.Hit>) = h.map { Triple(it.entry.id, it.score, it.matched) }

    @Test fun sameResultsAsBefore() {
        val list = catalogue(400)
        val old = LegacySearchIndex(list)
        val built = SearchIndex(list)
        val stored = SearchIndex.of(list.map { SearchWords.of(it) }, true)
        for (q in queries) for (f in filters) for (limit in listOf(500, 7)) {
            val expected = sigOld(old.search(q, f, limit))
            assertEquals("« $q » $f", expected, sig(built.search(q, f, limit)))
            assertEquals("« $q » $f (précalculé)", expected, sig(stored.search(q, f, limit)))
        }
        for (wanted in listOf(listOf("riz", "citron"), listOf("pomme", " fraises", ""), listOf("saumon"), listOf("xyz"))) for (f in filters) {
            val expected = sigOld(old.withIngredients(wanted, f))
            assertEquals("$wanted $f", expected, sig(built.withIngredients(wanted, f)))
            assertEquals("$wanted $f", expected, sig(stored.withIngredients(wanted, f)))
        }
        assertEquals(old.categories, built.categories)
        assertEquals(old.entries.map { it.id }, built.entries.map { it.id })
        // Favoris d'abord, puis par titre, comme avant.
        assertEquals(old.search("").map { it.entry.id }, built.search("").map { it.entry.id })
        assertTrue(built.search("").first().entry.favorite)
    }

    @Test fun normalisationUnchanged() {
        // Chaque caractère du plan de base (sauf les demi-caractères isolés), seul puis entouré de lettres.
        for (code in 0 until 0x10000) {
            if (code in 0xD800..0xDFFF) continue
            val c = code.toChar()
            for (s in listOf("$c", "a${c}b", "É$c É", " $ćx")) assertEquals("U+%04X".format(code), LegacyTextNorm.normalize(s), TextNorm.normalize(s))
        }
        val rnd = Random(3)
        val alphabet = "aàâäeéèêëiîïoôöuùûüçœæßÀÉÈÇŒÆ AZaz09-_'’.,;:!?()/«»°½×̧́  \t\n😀ǅﬁＡ"
        repeat(20_000) {
            val s = String(CharArray(rnd.nextInt(24)) { alphabet[rnd.nextInt(alphabet.length)] })
            assertEquals(s, LegacyTextNorm.normalize(s), TextNorm.normalize(s))
            assertEquals(s, LegacyTextNorm.tokens(s), TextNorm.tokens(s))
        }
    }

    @Test fun lightIndexThenFull() {
        val list = catalogue(120)
        val words = list.map { SearchWords.of(it) }
        val light = SearchIndex.of(words, false)
        val full = SearchIndex.of(words, true)
        assertFalse(light.full)
        assertTrue(full.full)
        assertEquals(full.entries.map { it.id }, light.entries.map { it.id })
        assertEquals(full.categories, light.categories)
        // Par titre : les mêmes recettes ; par un mot des étapes seulement : rien avant l'index complet.
        val byTitle = full.search("n 42").map { it.entry.id }
        assertEquals(byTitle, light.search("n 42").map { it.entry.id })
        assertTrue(full.search("hacher").isNotEmpty())
        assertTrue(light.search("hacher").isEmpty())
        assertEquals(sig(full.search("")), sig(light.search("")))
    }

    @Test fun changesWithoutRebuild() {
        val list = catalogue(150)
        val base = SearchIndex(list)
        val extra = Recipe(id = "perso-neuf", title = "Velouté de panais", category = "Soupes",
            ingredients = listOf(IngredientGroup(items = listOf(Ingredient("400", "g", "panais"), Ingredient("1", "", "échalote")))),
            steps = listOf(Step("Faire suer l'échalote.")), source = RecipeSource.USER)
        val edited = list[3].copy(title = "Tarte renommée", ingredients = listOf(IngredientGroup(items = listOf(Ingredient("1", "", "rhubarbe")))))
        val gone = list[10].id
        val changed = base.withChanges(listOf(SearchWords.of(extra), SearchWords.of(edited)), listOf(gone))
        val expected = SearchIndex(list.filter { it.id != gone && it.id != edited.id } + extra + edited)
        for (q in queries + listOf("panais", "echalote", "rhubarbe", "renommee", list[10].title)) for (f in filters) {
            assertEquals("« $q » $f", sig(expected.search(q, f)), sig(changed.search(q, f)))
        }
        assertEquals(expected.entries.map { it.id }.toSet(), changed.entries.map { it.id }.toSet())
        assertNull(changed.entry(gone))
        assertEquals("Tarte renommée", changed.entry(edited.id)!!.title)
        // L'index de départ n'a pas bougé.
        assertEquals(list.size, base.entries.size)
        assertTrue(base.search("panais").isEmpty())
    }

    @Test fun favoritesInPlace() {
        val list = catalogue(80).map { it.copy(favorite = false) }
        val idx = SearchIndex(list)
        assertTrue(idx.search("", RecipeFilter(favoritesOnly = true)).isEmpty())
        idx.entry("r7")!!.favorite = true
        assertEquals(listOf("r7"), idx.search("", RecipeFilter(favoritesOnly = true)).map { it.entry.id })
        assertEquals("r7", idx.search("").first().entry.id)
        idx.applyFavorites(setOf("r9", "r11"))
        assertEquals(setOf("r9", "r11"), idx.search("", RecipeFilter(favoritesOnly = true)).map { it.entry.id }.toSet())
        // Comme un index reconstruit avec ces favoris.
        val rebuilt = SearchIndex(list.map { it.copy(favorite = it.id == "r9" || it.id == "r11") })
        for (q in queries) assertEquals(q, sig(rebuilt.search(q)), sig(idx.search(q)))
    }

    @Test fun robotListHasNoLimitPhonePagesUnchanged() {
        val list = catalogue(1700)
        val idx = SearchIndex(list)
        // Robot : toute la liste, avec ou sans texte.
        assertEquals(1700, RecipeChip.select(idx, RecipeChip.ALL, "").size)
        // « min » : un mot de chaque étape, donc toutes les recettes.
        val all = RecipeChip.select(idx, RecipeChip.ALL, "hacher min")
        assertTrue(all.size > 500)
        assertEquals(LegacySearchIndex(list).search("hacher min", RecipeFilter(), Int.MAX_VALUE).map { it.entry.id }, all.map { it.entry.id })
        // Téléphone : page par page, le total compte tout.
        val page = CatalogSearch.page(idx, CatalogQuery("", CatalogFilter.ALL, offset = 0, limit = 24))
        assertEquals(1700, page.total)
        assertEquals(24, page.items.size)
        val next = CatalogSearch.page(idx, CatalogQuery("", CatalogFilter.ALL, offset = 24, limit = 24))
        assertEquals(RecipeChip.select(idx, RecipeChip.ALL, "").drop(24).take(24).map { it.entry.id }, next.items.map { it.id })
    }

    @Test fun storedWordsRoundTrip() {
        val r = catalogue(30)[5]
        val ix = SearchWords.of(r)
        val seen = HashMap<String, Int>()
        SearchWords.forEach(ix.head) { t, w -> seen[t] = w }
        SearchWords.forEach(ix.body) { t, w -> assertNull("$t deux fois", seen.put(t, w)) }
        for (t in TextNorm.tokens(r.title)) assertEquals(SearchIndex.W_TITLE, seen[t])
        assertTrue(ix.head.lines().all { it.substringBefore(' ').toInt() >= SearchIndex.W_TAG })
        assertTrue(ix.body.isEmpty() || ix.body.lines().all { it.substringBefore(' ').toInt() < SearchIndex.W_TAG })
    }
}
