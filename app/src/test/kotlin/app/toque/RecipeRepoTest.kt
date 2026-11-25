package app.toque

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeAdjustments
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeFilter
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.SearchIndex
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.core.recipes.SearchWords
import app.toque.core.robot.RobotProfiles
import app.toque.recipes.RecipeRepo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Catalogue du robot : base au format d'avant (recettes en JSON seulement) reprise sans rien perdre,
 * liste prête avant l'index complet, favoris et recettes perso sans reconstruction, migration
 * interrompue ou ratée sans conséquence. Sans activité ni service machine (seulement le dépôt).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecipeRepoTest {
    private lateinit var app: Application
    private val repos = ArrayList<RecipeRepo>()

    private val dishes = listOf("Soupe", "Velouté", "Gâteau", "Crème", "Risotto", "Purée", "Tarte", "Compote", "Gratin", "Curry")
    private val things = listOf("potiron", "champignons", "chocolat", "pommes", "carottes", "poulet", "lentilles", "épinards",
        "riz", "poireaux", "citron", "coco", "fraises", "tomates", "courgettes", "saumon")

    /** Recettes inventées, titres numérotés (tous différents) ; une sur 20 est une recette perso. */
    private fun catalogue(n: Int): List<Recipe> {
        val rnd = Random(11)
        return (0 until n).map { i ->
            val user = i % 20 == 7
            Recipe(id = if (user) "perso-r$i" else "r$i", title = "${dishes[rnd.nextInt(dishes.size)]} de ${things[rnd.nextInt(things.size)]} n° $i",
                category = listOf("Soupes", "Desserts", "Plats", "")[rnd.nextInt(4)], totalMinutes = rnd.nextInt(80), image = "img-$i.jpg",
                ingredients = listOf(IngredientGroup(items = (0 until 3).map { Ingredient("100", "g", things[rnd.nextInt(things.size)]) })),
                steps = listOf(Step("Mixer les ${things[rnd.nextInt(things.size)]} puis cuire.")),
                source = if (user) RecipeSource.USER else RecipeSource.IMPORTED)
        }
    }

    /** Base telle que l'écrit la version d'avant : recipes, favorites, adjustments, cooked, sans fiches. */
    private fun oldDatabase(recipes: List<Recipe>, favorites: List<String>, adjustments: Map<String, RecipeAdjustments>, cooked: Map<String, Int>) {
        val f = app.getDatabasePath("recettes.db")
        f.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        db.version = 1
        db.execSQL("CREATE TABLE recipes (id TEXT PRIMARY KEY, title TEXT NOT NULL, json TEXT NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE favorites (id TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE adjustments (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE cooked (id TEXT PRIMARY KEY, count INTEGER NOT NULL, last INTEGER NOT NULL)")
        for (r in recipes) db.insert("recipes", null, ContentValues().apply {
            put("id", r.id); put("title", r.title); put("json", RecipeCodec.encode(r)); put("updated", 1_700_000_000_000L)
        })
        // Une ligne illisible, ignorée comme avant.
        db.insert("recipes", null, ContentValues().apply { put("id", "casse"); put("title", "Casse"); put("json", "{pas du json"); put("updated", 1L) })
        for (id in favorites) db.insert("favorites", null, ContentValues().apply { put("id", id) })
        for ((id, a) in adjustments) db.insert("adjustments", null, ContentValues().apply { put("id", id); put("json", a.encode()) })
        for ((i, e) in cooked.entries.withIndex()) db.insert("cooked", null, ContentValues().apply {
            put("id", e.key); put("count", e.value); put("last", 1_700_000_000_000L + i)
        })
        db.close()
    }

    /** Contenu des tables de la personne, pour vérifier que rien n'a changé. */
    private fun ownerData(): List<String> {
        val db = SQLiteDatabase.openDatabase(app.getDatabasePath("recettes.db").path, null, SQLiteDatabase.OPEN_READONLY)
        val out = ArrayList<String>()
        for ((table, cols) in listOf("recipes" to "id, title, json, updated", "favorites" to "id", "adjustments" to "id, json", "cooked" to "id, count, last")) {
            db.rawQuery("SELECT $cols FROM $table ORDER BY id", null).use { c ->
                while (c.moveToNext()) out += table + ":" + (0 until c.columnCount).joinToString("|") { c.getString(it) ?: "null" }
            }
        }
        db.close()
        return out
    }

    private fun summaryRows(): Long {
        val db = SQLiteDatabase.openDatabase(app.getDatabasePath("recettes.db").path, null, SQLiteDatabase.OPEN_READONLY)
        val n = db.rawQuery("SELECT COUNT(*) FROM ${RecipeRepo.SUMMARIES} WHERE ok = 1", null).use { c -> c.moveToFirst(); c.getLong(0) }
        db.close()
        return n
    }

    private fun newRepo(): RecipeRepo = RecipeRepo(app, Logbook(app)).also { repos += it }

    private fun closeAll() {
        for (r in repos) r.close()
        repos.clear()
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            assertTrue("attente trop longue : $what", System.currentTimeMillis() < end)
            Thread.sleep(10)
        }
    }

    private fun ids(h: List<SearchIndex.Hit>) = h.map { it.entry.id }

    /** Index de référence : toutes les recettes lues comme avant, avec ces favoris. */
    private fun reference(recipes: List<Recipe>, favorites: Set<String>) = SearchIndex(recipes.map { it.copy(favorite = it.id in favorites) })

    private val queries = listOf("", "soupe", "potiron", "veloute", "chocolta", "n 42", "42", "mixer", "cuire", "lentille", "xyzzy")

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.deleteDatabase("recettes.db")
    }

    @After fun tearDown() {
        closeAll()
        RobotProfiles.active = RobotProfiles.MC_CONNECT
    }

    @Test fun migrationKeepsOwnerDataAndGivesTheSameSearch() {
        val list = catalogue(260)
        val favs = setOf("r3", "perso-r7", "r100")
        oldDatabase(list, favs.toList(), mapOf("r5" to RecipeAdjustments(note = "moins salé"), "perso-r27" to RecipeAdjustments(servings = 6)),
            mapOf("r8" to 3, "perso-r47" to 1))
        val before = ownerData()
        val repo = newRepo()
        repo.load()
        waitFor("index complet") { repo.loaded && repo.index.full }
        assertFalse(repo.usedFallback)
        assertEquals(before, ownerData())
        assertEquals(list.size.toLong(), summaryRows())
        assertEquals(list.size, repo.count)
        assertNull(repo.index.entry("casse"))
        val ref = reference(list, favs)
        for (q in queries) for (f in listOf(RecipeFilter(), RecipeFilter(favoritesOnly = true), RecipeFilter(source = RecipeSource.USER))) {
            assertEquals("« $q » $f", ids(ref.search(q, f)), ids(repo.index.search(q, f)))
        }
        assertEquals("moins salé", repo.adjustmentsOf("r5").note)
        assertEquals(6, repo.adjustmentsOf("perso-r27").servings)
        assertEquals(3, repo.usage("r8")?.count)
        assertEquals(listOf("perso-r47", "r8"), repo.recentIds())
        assertTrue(repo.isFavorite("perso-r7"))
        assertEquals(list[42].title, repo.get("r42")?.title)
        // Deuxième démarrage : plus rien à préparer, mêmes données.
        closeAll()
        val again = newRepo()
        again.load()
        waitFor("index complet") { again.loaded && again.index.full }
        assertEquals(before, ownerData())
        assertEquals(ids(ref.search("cuire")), ids(again.index.search("cuire")))
    }

    @Test fun listBeforeFullIndexThenSameSearch() {
        val list = catalogue(120)
        oldDatabase(list, listOf("r9"), emptyMap(), emptyMap())
        val repo = newRepo()
        val gate = CountDownLatch(1)
        repo.beforeFullLoad = { gate.await(20, TimeUnit.SECONDS) }
        repo.load()
        waitFor("liste") { repo.loaded }
        val ref = reference(list, setOf("r9"))
        assertFalse(repo.index.full)
        assertEquals(list.size, repo.count)
        assertEquals(ids(ref.search("")), ids(repo.index.search("")))
        // Par titre : déjà comme l'index complet ; par un mot des étapes seulement : pas encore.
        assertEquals(ids(ref.search("n 42")), ids(repo.index.search("n 42")))
        assertTrue(repo.index.search("mixer").isEmpty())
        // L'écran Recettes est prévenu quand l'index complet arrive, et refait sa recherche.
        var changes = 0
        repo.onChange { changes++ }
        gate.countDown()
        waitFor("index complet") { repo.index.full }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(changes >= 1)
        for (q in queries) assertEquals("« $q »", ids(ref.search(q)), ids(repo.index.search(q)))
        assertEquals(ids(ref.withIngredients(listOf("riz", "citron"))), ids(repo.index.withIngredients(listOf("riz", "citron"))))
    }

    @Test fun loadTwiceBuildsOnce() {
        oldDatabase(catalogue(50), emptyList(), emptyMap(), emptyMap())
        val repo = newRepo()
        repo.load()
        repo.load()
        waitFor("index complet") { repo.index.full }
        repo.load()
        Thread.sleep(300)
        // Une liste courte puis l'index complet, une seule fois.
        assertEquals(2, repo.builds.get())
    }

    @Test fun favoritesInstantWithoutRebuild() {
        val list = catalogue(80)
        oldDatabase(list, emptyList(), emptyMap(), emptyMap())
        val repo = newRepo()
        repo.load()
        waitFor("index complet") { repo.index.full }
        val builds = repo.builds.get()
        val favorites = RecipeFilter(favoritesOnly = true)
        repo.setFavorite("r12", true)
        assertEquals(listOf("r12"), ids(repo.index.search("", favorites)))
        assertEquals("r12", repo.index.search("").first().entry.id)
        repo.setFavorite("r30", true)
        repo.setFavorite("r12", false)
        assertEquals(listOf("r30"), ids(repo.index.search("", favorites)))
        assertEquals(1, repo.index.entries.count { it.favorite })
        Thread.sleep(300)
        assertEquals(builds, repo.builds.get())
        // Écrits dans la base.
        closeAll()
        val again = newRepo()
        again.load()
        waitFor("liste") { again.loaded }
        assertEquals(listOf("r30"), ids(again.index.search("", favorites)))
    }

    /** Deux appuis rapprochés sur la même étoile : l'écriture du premier, finie en retard, ne revient pas sur le second. */
    @Test fun aLateWriteNeverComesBackOnANewerStar() {
        oldDatabase(catalogue(40), emptyList(), emptyMap(), emptyMap())
        val repo = newRepo()
        repo.load()
        waitFor("index complet") { repo.index.full }
        repeat(300) { i ->
            repo.setFavorite("r12", true)
            repo.setFavorite("r12", false)
            assertFalse("tour $i", repo.isFavorite("r12"))
            assertFalse("tour $i", repo.index.entry("r12")!!.favorite)
            repo.setFavorites(mapOf("r3" to false, "r5" to true))
            repo.setFavorite("r3", true)
            assertTrue("tour $i", repo.isFavorite("r3") && repo.index.entry("r3")!!.favorite)
        }
        assertEquals(setOf("r3", "r5"), ids(repo.index.search("", RecipeFilter(favoritesOnly = true))).toSet())
        // Et dans la base, une fois toutes les écritures finies.
        closeAll()
        val again = newRepo()
        again.load()
        waitFor("liste") { again.loaded }
        assertEquals(setOf("r3", "r5"), ids(again.index.search("", RecipeFilter(favoritesOnly = true))).toSet())
    }

    /**
     * Recettes de la communauté (toqueapp.org, Toque 0.8.1) dans le catalogue rapide : leur origine survit aux
     * fiches courtes, la synchro Lidl ne les retire pas, et celles retirées du site partent, sauf les favorites.
     */
    @Test fun communityRecipesKeepTheirOriginAndTheirOwnRules() {
        val list = catalogue(40)
        oldDatabase(list, emptyList(), emptyMap(), emptyMap())
        val repo = newRepo()
        repo.load()
        waitFor("index complet") { repo.index.full }
        val shared = listOf("Soupe du voisin" to "toqueapp-12", "Gâteau du voisin" to "toqueapp-13").map { (title, id) ->
            Recipe(id = id, title = title, category = "Plats", totalMinutes = 20, image = "https://toqueapp.org/uploads/recipes/$id.webp",
                steps = listOf(Step("Mélanger puis cuire.")), source = RecipeSource.COMMUNITY)
        }
        repo.upsertAll(shared, rebuild = false)
        repo.refresh()
        val community = RecipeFilter(source = RecipeSource.COMMUNITY)
        assertEquals(setOf("toqueapp-12", "toqueapp-13"), ids(repo.index.search("", community)).toSet())
        assertEquals(RecipeSource.COMMUNITY, repo.get("toqueapp-12")?.source)
        assertEquals(setOf("toqueapp-12", "toqueapp-13"), repo.communityIds())
        // Favoris du compte : d'un bloc, visibles tout de suite, sans reconstruire l'index.
        val builds = repo.builds.get()
        repo.setFavorites(mapOf("toqueapp-12" to true, "r3" to true))
        assertEquals(setOf("toqueapp-12"), repo.communityFavorites())
        assertTrue(repo.isFavorite("r3"))
        assertEquals(setOf("toqueapp-12", "r3"), ids(repo.index.search("", RecipeFilter(favoritesOnly = true))).toSet())
        assertEquals(builds, repo.builds.get())
        // La synchro Lidl ne garde que dix recettes Lidl : celles de la communauté et les recettes perso restent.
        repo.removeAllExcept(list.filter { it.source == RecipeSource.IMPORTED }.take(10).map { it.id }.toSet())
        assertEquals(2, repo.index.search("", community).size)
        assertEquals(2, repo.index.search("", RecipeFilter(source = RecipeSource.USER)).size)
        // Retirées du site : elles partent, sauf la favorite.
        repo.removeCommunityExcept(emptySet())
        assertEquals(listOf("toqueapp-12"), ids(repo.index.search("", community)))
        // La photo reçue du site est connue sans relire le dossier, puis oubliée quand elle est effacée.
        val photo = java.io.File(repo.imagesDir, "toqueapp-12.webp")
        photo.writeBytes(byteArrayOf(1, 2, 3))
        repo.photoSaved(photo.name)
        assertEquals(photo, repo.imageFile("https://toqueapp.org/uploads/recipes/toqueapp-12.webp"))
        photo.delete()
        repo.photoRemoved(photo.name)
        assertNull(repo.imageFile("https://toqueapp.org/uploads/recipes/toqueapp-12.webp"))
        // Un nouveau démarrage relit les fiches courtes : même origine, même favori.
        closeAll()
        val again = newRepo()
        again.load()
        waitFor("liste") { again.loaded }
        assertEquals(listOf("toqueapp-12"), ids(again.index.search("", community)))
        assertTrue(again.isFavorite("toqueapp-12"))
    }

    @Test fun savingOwnRecipeNeverWaitsForTheIndex() {
        val list = catalogue(60)
        oldDatabase(list, emptyList(), emptyMap(), emptyMap())
        val repo = newRepo()
        val gate = CountDownLatch(1)
        repo.beforeFullLoad = { gate.await(20, TimeUnit.SECONDS) }
        repo.load()
        waitFor("liste") { repo.loaded }
        // Le fil des recettes est bloqué dans la lecture complète : enregistrer ne l'attend pas.
        val mine = Recipe(id = "perso-panais", title = "Velouté de panais", category = "Soupes", source = RecipeSource.USER,
            ingredients = listOf(IngredientGroup(items = listOf(Ingredient("400", "g", "panais")))), steps = listOf(Step("Mixer.")))
        val t0 = System.nanoTime()
        repo.saveUser(mine)
        repo.deleteUser("perso-r7")
        assertTrue("enregistrement trop long", (System.nanoTime() - t0) / 1_000_000 < 1_000)
        assertEquals("Velouté de panais", repo.get("perso-panais")?.title)
        assertNull(repo.get("perso-r7"))
        waitFor("recette perso dans la liste") { repo.index.entry("perso-panais") != null && repo.index.entry("perso-r7") == null }
        assertEquals(listOf("perso-panais"), ids(repo.index.search("panais")))
        gate.countDown()
        waitFor("index complet") { repo.index.full }
        assertEquals(listOf("perso-panais"), ids(repo.index.search("panais")))
        assertNull(repo.index.entry("perso-r7"))
        assertEquals(list.size, repo.count)
        closeAll()
        val again = newRepo()
        again.load()
        waitFor("index complet") { again.index.full }
        assertEquals("Velouté de panais", again.get("perso-panais")?.title)
        assertNull(again.get("perso-r7"))
        assertEquals(listOf("perso-panais"), ids(again.index.search("panais")))
    }

    @Test fun interruptedMigrationLeavesAWorkingCatalogue() {
        val list = catalogue(450)
        oldDatabase(list, listOf("r1"), mapOf("r2" to RecipeAdjustments(note = "x")), mapOf("r3" to 2))
        val before = ownerData()
        val repo = newRepo()
        // Coupure (ou erreur) après le premier lot de fiches.
        repo.afterBackfillChunk = { if (it >= 200) throw IllegalStateException("coupure simulée") }
        repo.load()
        waitFor("liste") { repo.loaded }
        assertTrue(repo.usedFallback)
        assertTrue(repo.index.full)
        val ref = reference(list, setOf("r1"))
        for (q in queries) assertEquals("« $q »", ids(ref.search(q)), ids(repo.index.search(q)))
        assertEquals(before, ownerData())
        closeAll()
        assertEquals(200L, summaryRows())
        // Démarrage suivant : la préparation reprend où elle s'était arrêtée.
        val again = newRepo()
        again.load()
        waitFor("index complet") { again.loaded && again.index.full }
        assertFalse(again.usedFallback)
        assertEquals(list.size.toLong(), summaryRows())
        for (q in queries) assertEquals("« $q »", ids(ref.search(q)), ids(again.index.search(q)))
        assertEquals(before, ownerData())
    }

    @Test fun writesByAnOlderVersionAreCaughtUp() {
        val list = catalogue(40)
        oldDatabase(list, emptyList(), emptyMap(), emptyMap())
        val repo = newRepo()
        repo.load()
        waitFor("index complet") { repo.index.full }
        // Synchro : la fiche suit la recette.
        repo.upsertAll(listOf(list[4].copy(title = "Gaspacho glacé")))
        assertEquals("Gaspacho glacé", repo.index.entry("r4")?.title)
        closeAll()
        // Une version plus ancienne de Toque écrit la recette sans fiche : le déclencheur efface la fiche périmée.
        val db = SQLiteDatabase.openDatabase(app.getDatabasePath("recettes.db").path, null, SQLiteDatabase.OPEN_READWRITE)
        db.insertWithOnConflict("recipes", null, ContentValues().apply {
            put("id", "r5"); put("title", "Tajine d'agneau"); put("json", RecipeCodec.encode(list[5].copy(title = "Tajine d'agneau"))); put("updated", 2L)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        db.delete("recipes", "id = ?", arrayOf("r6"))
        db.close()
        assertEquals(list.size.toLong() - 2, summaryRows())
        val again = newRepo()
        again.load()
        waitFor("index complet") { again.index.full }
        assertEquals("Tajine d'agneau", again.index.entry("r5")?.title)
        assertNull(again.index.entry("r6"))
        assertNotNull(again.index.entry("r4"))
        assertEquals(listOf("r5"), ids(again.index.search("tajine")))
        assertEquals(list.size - 1, again.count)
    }

    /**
     * Recettes filtrées par robot (comme la 0.8.0) avec les fiches courtes : la fiche dit quels robots
     * savent faire la recette, calculé à son écriture. Le découpage des mots a changé (VERSION 2) : la
     * table de la version 1 est effacée et refaite une fois, les tables de la personne restent intactes.
     */
    @Test fun recipesFollowTheChosenRobotAndOldSummariesAreRebuilt() {
        assertEquals(2, SearchWords.VERSION)
        val list = catalogue(30) + listOf(
            Recipe(id = "smart-site", title = "Soupe pour le Smart", devices = listOf("MC3.0"), steps = listOf(Step("Servir."))),
            Recipe(id = "smart-mixer", title = "Purée mixée", steps = listOf(Step("Mixer.", StepMachine(StepProgram.MIXER, 30)))),
            Recipe(id = "connect-site", title = "Soupe pour le Connect", devices = listOf("MC2.0"), steps = listOf(Step("Servir."))),
        )
        oldDatabase(list, listOf("smart-site", "r2"), emptyMap(), mapOf("connect-site" to 2))
        // Fiches d'une version précédente du découpage, avec leur déclencheur.
        val f = app.getDatabasePath("recettes.db")
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TABLE summaries_v1 (id TEXT PRIMARY KEY, ok INTEGER NOT NULL, title TEXT NOT NULL)")
            db.execSQL("INSERT INTO summaries_v1 (id, ok, title) VALUES ('r1', 1, 'ancienne fiche')")
            db.execSQL("CREATE TRIGGER summaries_v1_del AFTER DELETE ON recipes BEGIN DELETE FROM summaries_v1 WHERE id = OLD.id; END")
        }
        val before = ownerData()

        RobotProfiles.active = RobotProfiles.MC_CONNECT
        val repo = newRepo()
        repo.load()
        waitFor("index complet") { repo.loaded && repo.index.full }
        assertFalse(repo.usedFallback)
        assertEquals(before, ownerData())
        assertEquals(list.size.toLong(), summaryRows())
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name FROM sqlite_master WHERE name LIKE 'summaries_v%' ORDER BY name", null).use { c ->
                val names = ArrayList<String>()
                while (c.moveToNext()) names += c.getString(0)
                assertTrue("ancienne table effacée : $names", names.none { it.startsWith("summaries_v1") })
                assertTrue(names.contains(RecipeRepo.SUMMARIES))
            }
        }
        // Connect : ni la recette du site pour le Smart, ni celle avec un programme du Smart.
        assertEquals(31, repo.count)
        assertEquals(2, repo.hiddenCount)
        assertNull(repo.index.entry("smart-site"))
        assertNull(repo.index.entry("smart-mixer"))
        assertNotNull(repo.index.entry("connect-site"))

        // Changement de robot : les fiches suffisent, aucune recette n'est relue.
        RobotProfiles.active = RobotProfiles.MC_SMART
        repo.refresh()
        assertEquals(32, repo.count)
        assertEquals(1, repo.hiddenCount)
        assertNotNull(repo.index.entry("smart-site"))
        assertNotNull(repo.index.entry("smart-mixer"))
        assertNull(repo.index.entry("connect-site"))
        assertTrue(repo.isFavorite("smart-site"))
        assertEquals(listOf("smart-site"), ids(repo.index.search("smart")))
        assertEquals(before, ownerData())

        // Démarrage suivant sur le Connect : rien à refaire, même filtre.
        closeAll()
        RobotProfiles.active = RobotProfiles.MC_CONNECT
        val again = newRepo()
        again.load()
        waitFor("index complet") { again.loaded && again.index.full }
        assertEquals(31, again.count)
        assertEquals(before, ownerData())
    }
}
