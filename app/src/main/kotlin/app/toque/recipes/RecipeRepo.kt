package app.toque.recipes

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.toque.Logbook
import app.toque.core.recipes.CommunityRecipes
import app.toque.core.recipes.IndexedRecipe
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeAdjustments
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeSource
import app.toque.core.robot.RecipeCompat
import app.toque.core.robot.RobotProfiles
import app.toque.core.recipes.SearchIndex
import app.toque.core.recipes.SearchWords
import app.toque.core.recipes.UserRecipes
import app.toque.core.recipes.adjusted
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Recettes Lidl copiées localement (SQLite) + index de recherche en mémoire.
 * L'appli fonctionne entièrement hors ligne ; seule la synchro passe par le réseau.
 *
 * Démarrage en deux temps. Chaque recette a sa fiche courte (titre, catégorie, durée, photo…) et
 * ses mots de recherche déjà découpés dans la table [SUMMARIES], écrite en même temps que la
 * recette : la liste et la recherche par titre sont prêtes en lisant cette petite table, sans
 * relire le JSON de chaque recette ; les mots des ingrédients et des étapes suivent juste après.
 * Des déclencheurs SQLite effacent la fiche d'une recette modifiée par n'importe qui (une version
 * plus ancienne de Toque comprise) : une fiche absente est refaite au démarrage suivant.
 */
class RecipeRepo(private val context: Context, private val log: Logbook? = null) {
    /** Table des fiches utilisable (créée sans erreur). Sinon : l'ancien chargement, recette par recette. */
    @Volatile private var summariesOk = false

    private val helper = object : SQLiteOpenHelper(context, "recettes.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE recipes (id TEXT PRIMARY KEY, title TEXT NOT NULL, json TEXT NOT NULL, updated INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE favorites (id TEXT PRIMARY KEY)")
        }

        // Tables ajoutées sans changer le numéro de version de la base : une version plus ancienne
        // de l'appli, qui les ignore, peut toujours ouvrir la même base.
        override fun onOpen(db: SQLiteDatabase) {
            if (!db.isReadOnly) {
                db.execSQL("CREATE TABLE IF NOT EXISTS adjustments (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
                // Recettes cuisinées : nombre de fois et dernière fois (ms), pour « Récentes » et
                // « Les plus cuisinées ».
                db.execSQL("CREATE TABLE IF NOT EXISTS cooked (id TEXT PRIMARY KEY, count INTEGER NOT NULL, last INTEGER NOT NULL)")
                summariesOk = try {
                    createSummaries(db)
                    true
                } catch (e: Exception) {
                    log?.write("recettes", "table des fiches non créée : $e")
                    false
                }
            }
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "toque-recettes").apply { priority = Thread.NORM_PRIORITY - 1 } }
    /** Mises à jour de l'index en mémoire (recette perso enregistrée) sans attendre la base. */
    private val memo = Executors.newSingleThreadExecutor { r -> Thread(r, "toque-index") }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()

    @Volatile var index: SearchIndex = SearchIndex.of(emptyList(), false)
        private set
    @Volatile var loaded = false
        private set
    /** Recettes enregistrées mais pas faisables sur le robot choisi : pas montrées (voir [RecipeCompat]). */
    @Volatile var hiddenCount = 0
        private set
    private val favorites = HashSet<String>()
    /** Dernière demande d'étoile par recette, tant que son écriture n'est pas finie (sous le verrou de [favorites]). */
    private val favoriteAsked = HashMap<String, Long>()
    private var favoriteSeq = 0L
    /** Une recette cuisinée [count] fois, la dernière à [lastMs]. */
    class Usage(val count: Int, val lastMs: Long)
    private val cooked = ConcurrentHashMap<String, Usage>()
    /** Adaptations personnelles (note, quantités, pesées), par identifiant de recette. */
    private val adjustments = ConcurrentHashMap<String, RecipeAdjustments>()
    @Volatile private var adjustmentsLoaded = false

    /**
     * Recette perso enregistrée ou supprimée en mémoire avant que la base ne l'ait écrite ; gardée
     * jusqu'à ce qu'un index lu dans la base après cette écriture soit en place. [recipe] null : supprimée.
     */
    private class Change(val seq: Long, val id: String, val recipe: Recipe?, val indexed: IndexedRecipe?)
    private val lock = Any()
    private val pending = ArrayList<Change>()
    private var lastSeq = 0L
    /** Dernier changement écrit dans la base (fil des recettes). */
    @Volatile private var writtenSeq = 0L

    private val loadRequested = AtomicBoolean(false)
    @Volatile private var loadStartMs = 0L

    /** Noms des photos présentes (sans toucher au disque à chaque case de la grille) ; complets une fois [imagesScanned]. */
    private val imageNames: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    @Volatile private var imagesScanned = false

    /** Essais : index construits (liste courte ou complète) ; appelé avant la lecture complète ; après chaque lot de fiches. */
    internal val builds = AtomicInteger()
    internal var beforeFullLoad: (() -> Unit)? = null
    internal var afterBackfillChunk: ((Int) -> Unit)? = null
    /** Essais : le dernier chargement a dû relire toutes les recettes (fiches indisponibles). */
    @Volatile internal var usedFallback = false
        private set

    val imagesDir: File by lazy { File(context.filesDir, "images").apply { mkdirs() } }

    fun onChange(l: () -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    private fun notifyChanged() = main.post { for (l in ArrayList(listeners)) l() }

    /** Premier chargement du catalogue (une seule fois par processus, même si l'écran est recréé). */
    fun load() {
        if (!loadRequested.compareAndSet(false, true)) return
        loadStartMs = SystemClock.elapsedRealtime()
        exec.execute { firstLoad() }
    }

    private fun firstLoad() {
        val db = helper.writableDatabase
        loadUserState(db)
        val snap = writtenSeq
        val (light, prepared, error) = try {
            val prepared = prepareSummaries(db)
            Triple(SearchIndex.of(readSummaries(db, false), false), prepared, null)
        } catch (e: Exception) {
            Triple(null, 0, e)
        }
        if (light == null) {
            publish(legacyIndex(db), snap)
            usedFallback = true
            log?.write("recettes", "catalogue prêt en ${since(loadStartMs)} (${index.entries.size} recettes, index de secours : $error)")
            exec.execute { scanImages() }
            return
        }
        usedFallback = false
        publish(light, snap)
        val note = if (prepared > 0) ", $prepared fiches préparées pour la recherche" else ""
        log?.write("recettes", "catalogue prêt en ${since(loadStartMs)} (${light.entries.size} recettes$note)")
        exec.execute {
            scanImages()
            try {
                beforeFullLoad?.invoke()
                val s = writtenSeq
                val full = SearchIndex.of(readSummaries(db, true), true)
                publish(full, s)
                log?.write("recettes", "recherche complète prête en ${since(loadStartMs)} (${full.entries.size} recettes)")
            } catch (e: Exception) {
                log?.write("recettes", "recherche complète impossible : $e")
            }
        }
    }

    /** Recharge l'index après des écritures de la synchro : directement l'index complet (pas de retour aux titres seuls). */
    private fun reloadNow() {
        val db = helper.writableDatabase
        loadUserState(db)
        val snap = writtenSeq
        val built = try {
            prepareSummaries(db)
            SearchIndex.of(readSummaries(db, true), true).also { usedFallback = false }
        } catch (e: Exception) {
            log?.write("recettes", "index de secours : $e")
            usedFallback = true
            legacyIndex(db)
        }
        publish(built, snap)
        scanImages()
    }

    /** Met [built] en place avec les favoris et les changements pas encore écrits quand il a été lu. */
    private fun publish(built: SearchIndex, snap: Long) {
        builds.incrementAndGet()
        synchronized(lock) {
            val it = pending.iterator()
            while (it.hasNext()) if (it.next().seq <= snap) it.remove()
            index = withPending(built).also { ix -> ix.applyFavorites(synchronized(favorites) { HashSet(favorites) }) }
            loaded = true
        }
        notifyChanged()
    }

    /** [ix] avec les recettes perso enregistrées ou supprimées en attente (sous [lock]). */
    private fun withPending(ix: SearchIndex): SearchIndex {
        if (pending.isEmpty()) return ix
        val last = LinkedHashMap<String, Change>()
        for (c in pending) last[c.id] = c
        val upserts = ArrayList<IndexedRecipe>()
        val removed = ArrayList<String>()
        for (c in last.values) if (c.indexed != null) upserts += c.indexed else removed += c.id
        return ix.withChanges(upserts, removed)
    }

    private fun loadUserState(db: SQLiteDatabase) {
        loadAdjustments(db)
        val favs = HashSet<String>()
        db.rawQuery("SELECT id FROM favorites", null).use { c -> while (c.moveToNext()) favs += c.getString(0) }
        synchronized(favorites) {
            favorites.clear()
            favorites += favs
        }
        val usage = HashMap<String, Usage>()
        db.rawQuery("SELECT id, count, last FROM cooked", null).use { c ->
            while (c.moveToNext()) usage[c.getString(0)] = Usage(c.getInt(1), c.getLong(2))
        }
        cooked.clear()
        cooked.putAll(usage)
    }

    // ------------------------------------------------------------------ fiches courtes (table SUMMARIES)

    private fun createSummaries(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            // Tables et déclencheurs d'une version précédente du découpage des mots : refaits.
            val old = ArrayList<Pair<String, String>>()
            db.rawQuery("SELECT type, name FROM sqlite_master WHERE (type = 'table' OR type = 'trigger') AND name LIKE 'summaries_v%'", null).use { c ->
                while (c.moveToNext()) old += c.getString(0) to c.getString(1)
            }
            val current = setOf(SUMMARIES, "${SUMMARIES}_ins", "${SUMMARIES}_upd", "${SUMMARIES}_del")
            for ((type, name) in old) if (name !in current) db.execSQL("DROP ${type.uppercase(Locale.ROOT)} IF EXISTS $name")
            db.execSQL("CREATE TABLE IF NOT EXISTS $SUMMARIES (id TEXT PRIMARY KEY, ok INTEGER NOT NULL, title TEXT NOT NULL, " +
                "norm_title TEXT NOT NULL, category TEXT NOT NULL, tags TEXT NOT NULL, minutes INTEGER NOT NULL, image TEXT NOT NULL, " +
                "machine_steps INTEGER NOT NULL, rating REAL NOT NULL, rating_count INTEGER NOT NULL, language TEXT NOT NULL, " +
                "source TEXT NOT NULL, head TEXT NOT NULL, body TEXT NOT NULL, robots TEXT NOT NULL)")
            // Toute écriture dans recipes (y compris par une version plus ancienne de Toque) efface la fiche.
            db.execSQL("CREATE TRIGGER IF NOT EXISTS ${SUMMARIES}_ins AFTER INSERT ON recipes BEGIN DELETE FROM $SUMMARIES WHERE id = NEW.id; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS ${SUMMARIES}_upd AFTER UPDATE ON recipes BEGIN DELETE FROM $SUMMARIES WHERE id = OLD.id OR id = NEW.id; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS ${SUMMARIES}_del AFTER DELETE ON recipes BEGIN DELETE FROM $SUMMARIES WHERE id = OLD.id; END")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    /**
     * Fiches des recettes qui n'en ont pas (premier démarrage après la mise à jour, recette écrite
     * par une version plus ancienne) : lues par lots, chaque lot écrit dans sa transaction. Interrompu,
     * le travail fait reste et la suite se fait au démarrage suivant. Rend le nombre de fiches faites.
     */
    private fun prepareSummaries(db: SQLiteDatabase): Int {
        check(summariesOk) { "table des fiches indisponible" }
        // Les déclencheurs gardent les fiches incluses dans les recettes : autant de lignes, rien à faire.
        if (count(db, SUMMARIES) >= count(db, "recipes")) return 0
        var done = 0
        var after = -1L
        while (true) {
            val batch = ArrayList<Pair<String, Recipe?>>()
            db.rawQuery("SELECT r.rowid, r.id, r.json FROM recipes r WHERE r.rowid > CAST(? AS INTEGER) AND " +
                "NOT EXISTS (SELECT 1 FROM $SUMMARIES s WHERE s.id = r.id) ORDER BY r.rowid LIMIT $CHUNK", arrayOf(after.toString())).use { c ->
                while (c.moveToNext()) {
                    after = c.getLong(0)
                    val id = c.getString(1)
                    val r = try {
                        RecipeCodec.decode(c.getString(2), RecipeSource.IMPORTED)
                    } catch (_: Exception) {
                        null
                    }
                    batch += id to r?.copy(id = id, favorite = false)
                }
            }
            if (batch.isEmpty()) break
            db.beginTransaction()
            try {
                for ((id, r) in batch) {
                    if (r != null) writeSummary(db, SearchWords.of(r), robotsOf(r)) else writeBadSummary(db, id)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            done += batch.size
            afterBackfillChunk?.invoke(done)
        }
        return done
    }

    /**
     * Robots qui savent faire [r] ([RecipeCompat]), entre espaces : « mc-connect mc-smart ». Calculé à
     * l'écriture de la fiche, pour ne pas relire chaque recette au démarrage ni au changement de robot.
     */
    private fun robotsOf(r: Recipe): String {
        val ok = RobotProfiles.ALL.filter { p ->
            try {
                RecipeCompat.supports(r, p)
            } catch (_: Exception) {
                false
            }
        }
        return ok.joinToString(" ", prefix = " ", postfix = " ") { it.id }
    }

    private fun writeSummary(db: SQLiteDatabase, ix: IndexedRecipe, robots: String) {
        val e = ix.entry
        val v = ContentValues()
        v.put("id", e.id)
        v.put("ok", 1)
        v.put("title", e.title)
        v.put("norm_title", e.normTitle)
        v.put("category", e.category)
        v.put("tags", e.tags.joinToString("\n"))
        v.put("minutes", e.totalMinutes)
        v.put("image", e.image)
        v.put("machine_steps", e.machineSteps)
        v.put("rating", e.rating)
        v.put("rating_count", e.ratingCount)
        v.put("language", e.language)
        v.put("source", e.source.name)
        v.put("head", ix.head)
        v.put("body", ix.body)
        v.put("robots", robots)
        db.insertWithOnConflict(SUMMARIES, null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Recette illisible : une fiche vide, pour ne pas la relire à chaque démarrage (elle reste hors du catalogue, comme avant). */
    private fun writeBadSummary(db: SQLiteDatabase, id: String) {
        val v = ContentValues()
        v.put("id", id)
        v.put("ok", 0)
        for (k in listOf("title", "norm_title", "category", "tags", "image", "language", "source", "head", "body", "robots")) v.put(k, "")
        for (k in listOf("minutes", "machine_steps", "rating_count")) v.put(k, 0)
        v.put("rating", 0.0)
        db.insertWithOnConflict(SUMMARIES, null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Les fiches des recettes que le robot choisi sait faire, par lots (une fenêtre de curseur de 2 Mo
     * ne déborde pas) ; [withBody] : avec les mots du texte. Les autres sont comptées dans [hiddenCount].
     */
    private fun readSummaries(db: SQLiteDatabase, withBody: Boolean): List<IndexedRecipe> {
        val out = ArrayList<IndexedRecipe>()
        val cols = "rowid, id, title, norm_title, category, tags, minutes, image, machine_steps, rating, rating_count, language, source, head, robots" +
            if (withBody) ", body" else ""
        val robot = " " + RobotProfiles.active.id + " "
        var hidden = 0
        var after = -1L
        while (true) {
            var n = 0
            db.rawQuery("SELECT $cols FROM $SUMMARIES WHERE ok = 1 AND rowid > CAST(? AS INTEGER) ORDER BY rowid LIMIT $CHUNK",
                arrayOf(after.toString())).use { c ->
                while (c.moveToNext()) {
                    n++
                    after = c.getLong(0)
                    if (!c.getString(14).contains(robot)) {
                        hidden++
                        continue
                    }
                    val tags = c.getString(5)
                    val e = SearchIndex.Entry(c.getString(1), c.getString(2), c.getString(4),
                        if (tags.isEmpty()) emptyList() else tags.split('\n'), false,
                        SOURCES[c.getString(12)] ?: RecipeSource.IMPORTED,
                        c.getInt(6), c.getString(7), c.getInt(8), c.getString(3), c.getDouble(9), c.getInt(10), c.getString(11))
                    out += IndexedRecipe(e, c.getString(13), if (withBody) c.getString(15) else "")
                }
            }
            if (n < CHUNK) break
        }
        hiddenCount = hidden
        return out
    }

    /** L'ancien chargement : chaque recette relue et découpée (si la table des fiches ne peut pas servir). */
    private fun legacyIndex(db: SQLiteDatabase): SearchIndex {
        val list = ArrayList<Recipe>()
        val robot = RobotProfiles.active
        var hidden = 0
        var after = -1L
        while (true) {
            var n = 0
            db.rawQuery("SELECT rowid, json FROM recipes WHERE rowid > CAST(? AS INTEGER) ORDER BY rowid LIMIT $CHUNK", arrayOf(after.toString())).use { c ->
                while (c.moveToNext()) {
                    n++
                    after = c.getLong(0)
                    val r = try {
                        RecipeCodec.decode(c.getString(1), RecipeSource.IMPORTED)
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    // Seulement les recettes que ce robot sait faire (programmes, températures, robots du site).
                    val ok = try {
                        RecipeCompat.supports(r, robot)
                    } catch (_: Exception) {
                        false
                    }
                    if (!ok) {
                        hidden++
                        continue
                    }
                    list += r.copy(favorite = false)
                }
            }
            if (n < CHUNK) break
        }
        hiddenCount = hidden
        return SearchIndex(list)
    }

    /** Écrit une recette et sa fiche (dans la transaction de l'appelant). */
    private fun writeRecipe(db: SQLiteDatabase, r: Recipe, now: Long) {
        val clean = r.copy(favorite = false)
        val v = ContentValues()
        v.put("id", r.id)
        v.put("title", r.title)
        v.put("json", RecipeCodec.encode(clean))
        v.put("updated", now)
        db.insertWithOnConflict("recipes", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        if (summariesOk) {
            // Sans fiche (erreur), la recette est simplement refaite au prochain démarrage.
            try {
                writeSummary(db, SearchWords.of(clean), robotsOf(clean))
            } catch (e: Exception) {
                log?.write("recettes", "fiche de ${r.id} non écrite : $e")
            }
        }
    }

    private fun since(startMs: Long): String =
        String.format(Locale.FRANCE, "%.1f s", (SystemClock.elapsedRealtime() - startMs) / 1000.0)

    // ------------------------------------------------------------------ lecture

    val count: Int get() = index.entries.size

    /** Changement pas encore écrit pour [id], ou null. */
    private fun pendingFor(id: String): Change? = synchronized(lock) {
        val written = writtenSeq
        pending.lastOrNull { it.id == id && it.seq > written }
    }

    /** Lecture d'une recette (rapide : une seule ligne). */
    fun get(id: String): Recipe? {
        pendingFor(id)?.let { return it.recipe?.copy(favorite = isFavorite(id)) }
        helper.readableDatabase.rawQuery("SELECT json FROM recipes WHERE id = ?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) return null
            val r = try {
                RecipeCodec.decode(c.getString(0), RecipeSource.IMPORTED)
            } catch (_: Exception) {
                null
            } ?: return null
            return r.copy(favorite = isFavorite(id))
        }
    }

    /** La recette avec les adaptations de l'utilisateur (quantités et pesées corrigées). */
    fun getAdjusted(id: String): Recipe? = get(id)?.let { it.adjusted(adjustmentsOf(it.id)) }

    private fun loadAdjustments(db: SQLiteDatabase) {
        if (adjustmentsLoaded) return
        synchronized(adjustments) {
            if (adjustmentsLoaded) return
            db.rawQuery("SELECT id, json FROM adjustments", null).use { c ->
                while (c.moveToNext()) {
                    val a = RecipeAdjustments.decode(c.getString(1))
                    if (!a.isEmpty) adjustments[c.getString(0)] = a
                }
            }
            adjustmentsLoaded = true
        }
    }

    /** Adaptations de l'utilisateur pour cette recette. Conservées d'une synchronisation à l'autre. */
    fun adjustmentsOf(id: String): RecipeAdjustments {
        if (!adjustmentsLoaded) loadAdjustments(helper.writableDatabase)
        return adjustments[id] ?: RecipeAdjustments()
    }

    private val adjustedListeners = ArrayList<(String) -> Unit>()

    /** Adaptations d'une recette changées (page de la recette, ou téléphone) ; rappel sur le fil principal. */
    fun onAdjusted(l: (String) -> Unit): () -> Unit {
        synchronized(adjustedListeners) { adjustedListeners += l }
        return { synchronized(adjustedListeners) { adjustedListeners -= l } }
    }

    fun setAdjustments(id: String, a: RecipeAdjustments) {
        if (!adjustmentsLoaded) loadAdjustments(helper.writableDatabase)
        if (a.isEmpty) adjustments.remove(id) else adjustments[id] = a
        main.post { for (l in synchronized(adjustedListeners) { ArrayList(adjustedListeners) }) l(id) }
        exec.execute {
            val db = helper.writableDatabase
            if (a.isEmpty) db.delete("adjustments", "id = ?", arrayOf(id))
            else db.insertWithOnConflict("adjustments", null, ContentValues().apply {
                put("id", id)
                put("json", a.encode())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun isFavorite(id: String): Boolean = synchronized(favorites) { id in favorites }

    /** Étoile touchée : en mémoire tout de suite (liste Favoris et tuile exactes), écrite en arrière-plan, sans reconstruire l'index. */
    fun setFavorite(id: String, favorite: Boolean) {
        val asked = applyFavorites(mapOf(id to favorite), null)
        notifyChanged()
        exec.execute {
            val db = helper.writableDatabase
            if (favorite) db.execSQL("INSERT OR IGNORE INTO favorites (id) VALUES (?)", arrayOf(id))
            else db.execSQL("DELETE FROM favorites WHERE id = ?", arrayOf(id))
            // Un premier chargement passé entre-temps a relu les favoris de la base : on réapplique.
            applyFavorites(mapOf(id to favorite), asked)
        }
    }

    /**
     * Met les étoiles [changes] en mémoire (liste des favoris et index) et rend le numéro de cette demande.
     * Avec [asked], le numéro d'une demande déjà faite (son écriture vient de finir) : seulement les recettes
     * dont l'étoile n'a pas été touchée depuis, pour qu'une écriture en retard ne revienne pas sur un appui
     * plus récent.
     */
    private fun applyFavorites(changes: Map<String, Boolean>, asked: Long?): Long = synchronized(favorites) {
        val seq = asked ?: ++favoriteSeq
        for ((id, on) in changes) {
            if (asked == null) favoriteAsked[id] = seq
            else if (favoriteAsked[id] != seq) continue
            else favoriteAsked.remove(id)
            if (on) favorites += id else favorites -= id
            index.entry(id)?.favorite = on
        }
        seq
    }

    /**
     * Plusieurs favoris d'un coup (favoris du compte toqueapp.org) : en mémoire tout de suite, écrits en une
     * transaction, sans reconstruire l'index. Attend l'écriture (fil d'arrière-plan seulement).
     */
    fun setFavorites(changes: Map<String, Boolean>) {
        if (changes.isEmpty()) return
        val asked = applyFavorites(changes, null)
        notifyChanged()
        exec.submit {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                for ((id, on) in changes) {
                    if (on) db.execSQL("INSERT OR IGNORE INTO favorites (id) VALUES (?)", arrayOf(id))
                    else db.execSQL("DELETE FROM favorites WHERE id = ?", arrayOf(id))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            // Un premier chargement passé entre-temps a relu les favoris de la base : on réapplique.
            applyFavorites(changes, asked)
        }.get()
    }

    /** Favoris parmi les recettes de la communauté. */
    fun communityFavorites(): Set<String> = synchronized(favorites) { favorites.filterTo(HashSet()) { CommunityRecipes.isCommunity(it) } }

    // ------------------------------------------------------------------ écritures

    /**
     * Remplace tout le catalogue Lidl par l'import du jour (miroir de l'appli d'origine).
     * Les favoris sont conservés ; ceux marqués dans l'appli d'origine sont ajoutés.
     * Les recettes perso et celles de la communauté (toqueapp.org) restent.
     */
    fun replaceAll(recipes: List<Recipe>) {
        exec.submit { replaceAllNow(recipes) }.get()
    }

    private fun replaceAllNow(recipes: List<Recipe>) {
        val db = helper.writableDatabase
        val before = titles(db)
        db.beginTransaction()
        try {
            db.delete("recipes", "id NOT LIKE ? AND id NOT LIKE ?", arrayOf(UserRecipes.ID_PREFIX + "%", CommunityRecipes.ID_PREFIX + "%"))
            val now = System.currentTimeMillis()
            for (r in recipes) {
                writeRecipe(db, r, now)
                if (r.favorite) db.execSQL("INSERT OR IGNORE INTO favorites (id) VALUES (?)", arrayOf(r.id))
            }
            val kept = recipes.map { it.id }.toHashSet()
            carryOver(db, before.filterKeys { it !in kept && isLidl(it) })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        reloadNow()
    }

    /** Ajoute ou met à jour des recettes (favoris conservés). [rebuild] : recharger l'index tout de suite. */
    fun upsertAll(recipes: List<Recipe>, rebuild: Boolean = true) {
        exec.submit {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                val now = System.currentTimeMillis()
                for (r in recipes) writeRecipe(db, r, now)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (rebuild) reloadNow()
        }.get()
    }

    /** Identifiants de toutes les recettes enregistrées. */
    fun ids(): Set<String> = exec.submit<Set<String>> {
        val out = HashSet<String>()
        helper.readableDatabase.rawQuery("SELECT id FROM recipes", null).use { c -> while (c.moveToNext()) out += c.getString(0) }
        out
    }.get()

    /**
     * Supprime les recettes Lidl absentes de [keep] (catalogue miroir de la source) ; jamais les
     * recettes perso ni celles de la communauté.
     */
    fun removeAllExcept(keep: Set<String>, rebuild: Boolean = true) {
        exec.submit {
            val db = helper.writableDatabase
            val gone = titles(db).filterKeys { it !in keep && isLidl(it) }
            db.beginTransaction()
            try {
                for (id in gone.keys) db.delete("recipes", "id = ?", arrayOf(id))
                carryOver(db, gone)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (rebuild) reloadNow()
        }.get()
    }

    /** Identifiants des recettes de la communauté enregistrées. */
    fun communityIds(): Set<String> = ids().filterTo(HashSet()) { CommunityRecipes.isCommunity(it) }

    /**
     * Retire les recettes de la communauté absentes de [keep] (retirées du site ou dépubliées), sauf
     * les favorites : une recette mise en favori reste jusqu'à ce qu'on l'enlève des favoris.
     * Leurs notes, corrections et cuissons restent, pour le jour où elles reviennent.
     */
    fun removeCommunityExcept(keep: Set<String>) {
        exec.submit {
            val db = helper.writableDatabase
            val gone = titles(db).keys.filter { CommunityRecipes.isCommunity(it) && it !in keep && !isFavorite(it) }
            if (gone.isEmpty()) return@submit
            db.beginTransaction()
            try {
                for (id in gone) db.delete("recipes", "id = ?", arrayOf(id))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            reloadNow()
        }.get()
    }

    private fun isLidl(id: String) = !UserRecipes.isUser(id) && !CommunityRecipes.isCommunity(id)

    /**
     * Enregistre une recette perso (nouvelle ou modifiée) sans attendre : elle est lisible ([get]) et
     * dans les listes tout de suite, la base l'écrit en arrière-plan, même derrière un chargement.
     */
    fun saveUser(r: Recipe) {
        require(UserRecipes.isUser(r.id))
        val rec = r.copy(source = RecipeSource.USER)
        val ix = SearchWords.of(rec.copy(favorite = false))
        change(Change(0, rec.id, rec, ix)) { db ->
            db.beginTransaction()
            try {
                writeRecipe(db, rec, System.currentTimeMillis())
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** Supprime une recette perso, avec sa note, ses corrections, son favori et ses cuissons. */
    fun deleteUser(id: String) {
        if (!UserRecipes.isUser(id)) return
        adjustments.remove(id)
        cooked.remove(id)
        synchronized(favorites) { favorites -= id }
        change(Change(0, id, null, null)) { db ->
            for (table in listOf("recipes", "favorites", "adjustments", "cooked")) db.delete(table, "id = ?", arrayOf(id))
        }
    }

    /** Un changement de recette perso : noté en attente, appliqué à l'index en mémoire, puis écrit par [write]. */
    private fun change(c0: Change, write: (SQLiteDatabase) -> Unit) {
        synchronized(lock) {
            val c = Change(++lastSeq, c0.id, c0.recipe, c0.indexed)
            pending += c
            // Dans le verrou : les écritures partent dans l'ordre des numéros.
            exec.execute {
                try {
                    write(helper.writableDatabase)
                } catch (e: Exception) {
                    log?.crash("recettes", e)
                } finally {
                    writtenSeq = c.seq
                }
            }
        }
        memo.execute {
            synchronized(lock) {
                c0.indexed?.entry?.favorite = isFavorite(c0.id)
                index = if (c0.indexed != null) index.withChanges(listOf(c0.indexed)) else index.withChanges(emptyList(), listOf(c0.id))
            }
            notifyChanged()
        }
    }

    /** Toutes les recettes perso (pour l'export). */
    fun userRecipes(): List<Recipe> = exec.submit<List<Recipe>> {
        val out = ArrayList<Recipe>()
        helper.readableDatabase.rawQuery("SELECT json FROM recipes WHERE id LIKE ?", arrayOf(UserRecipes.ID_PREFIX + "%")).use { c ->
            while (c.moveToNext()) {
                try {
                    RecipeCodec.decode(c.getString(0), RecipeSource.USER)
                } catch (_: Exception) {
                    null
                }?.let { out += it }
            }
        }
        out.sortedBy { it.title.lowercase() }
    }.get()

    private fun titles(db: SQLiteDatabase): Map<String, String> {
        val out = HashMap<String, String>()
        db.rawQuery("SELECT id, title FROM recipes", null).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getString(1) }
        return out
    }

    /**
     * Favoris et adaptations des recettes retirées ([gone] : id → titre), reportés sur la recette
     * restante de même titre : passer des recettes de l'appli d'origine à celles du site (ou
     * l'inverse) ne les fait pas disparaître. Les anciennes lignes restent, pour un retour en arrière.
     */
    private fun carryOver(db: SQLiteDatabase, gone: Map<String, String>) {
        if (gone.isEmpty()) return
        loadAdjustments(db)
        val favs = HashSet<String>()
        db.rawQuery("SELECT id FROM favorites", null).use { c -> while (c.moveToNext()) favs += c.getString(0) }
        val usage = HashMap<String, Usage>()
        db.rawQuery("SELECT id, count, last FROM cooked", null).use { c ->
            while (c.moveToNext()) usage[c.getString(0)] = Usage(c.getInt(1), c.getLong(2))
        }
        val wanted = gone.filterKeys { it in favs || adjustments.containsKey(it) || usage.containsKey(it) }
        if (wanted.isEmpty()) return
        val byTitle = HashMap<String, String>()
        // getOrPut et non putIfAbsent : HashMap.putIfAbsent n'existe pas sur Android 6 (API 23).
        for ((id, title) in titles(db)) byTitle.getOrPut(RecipeAdjustments.titleKey(title)) { id }
        for ((id, title) in wanted) {
            val target = byTitle[RecipeAdjustments.titleKey(title)] ?: continue
            if (target == id) continue
            if (id in favs) db.execSQL("INSERT OR IGNORE INTO favorites (id) VALUES (?)", arrayOf(target))
            usage[id]?.let { u ->
                val t = usage[target]
                val merged = Usage(u.count + (t?.count ?: 0), maxOf(u.lastMs, t?.lastMs ?: 0L))
                usage[target] = merged
                db.insertWithOnConflict("cooked", null, ContentValues().apply {
                    put("id", target)
                    put("count", merged.count)
                    put("last", merged.lastMs)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                // Déplacé, pas copié : des allers-retours entre sources ne gonflent pas le compte.
                db.execSQL("DELETE FROM cooked WHERE id = ?", arrayOf(id))
                usage.remove(id)
            }
            adjustments[id]?.let { a ->
                val r = get(target) ?: return@let
                val merged = a.carriedTo(r, adjustments[target] ?: RecipeAdjustments())
                if (merged.isEmpty) return@let
                adjustments[target] = merged
                db.insertWithOnConflict("adjustments", null, ContentValues().apply {
                    put("id", target)
                    put("json", merged.encode())
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    /** Note qu'une recette vient d'être cuisinée (une fois par cuisson). */
    fun markCooked(id: String) {
        val now = System.currentTimeMillis()
        exec.execute {
            val u = cooked[id]
            val next = Usage((u?.count ?: 0) + 1, now)
            helper.writableDatabase.insertWithOnConflict("cooked", null, ContentValues().apply {
                put("id", id)
                put("count", next.count)
                put("last", next.lastMs)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            cooked[id] = next
            notifyChanged()
        }
    }

    fun usage(id: String): Usage? = cooked[id]

    /** Recettes cuisinées, de la plus récente à la plus ancienne (seulement celles du catalogue). */
    fun recentIds(limit: Int = 60): List<String> {
        val ix = index
        return cooked.entries.filter { ix.entry(it.key) != null }.sortedByDescending { it.value.lastMs }.take(limit).map { it.key }
    }

    /** Recettes les plus cuisinées (à égalité, la plus récente d'abord). */
    fun mostCookedIds(limit: Int = 60): List<String> {
        val ix = index
        return cooked.entries.filter { ix.entry(it.key) != null }
            .sortedWith(compareByDescending<Map.Entry<String, Usage>> { it.value.count }.thenByDescending { it.value.lastMs })
            .take(limit).map { it.key }
    }

    /** Recharge l'index de recherche (après des mises à jour sans [rebuild]) ; attend la fin. */
    fun refresh() {
        exec.submit { reloadNow() }.get()
    }

    /** Recharge l'index de recherche en arrière-plan, sans attendre. */
    fun refreshLater() {
        exec.execute { reloadNow() }
    }

    // ------------------------------------------------------------------ photos

    private fun scanImages() {
        val files = imagesDir.listFiles() ?: return
        for (f in files) if (f.length() > 0) imageNames += f.name
        imagesScanned = true
    }

    /** Une photo vient d'être enregistrée sous ce nom (synchro). */
    fun photoSaved(name: String) {
        imageNames += name
    }

    /** La photo de ce nom vient d'être effacée (recette de la communauté partie du robot). */
    fun photoRemoved(name: String) {
        imageNames -= name
    }

    /** Des photos sont arrivées : les écrans ouverts se redessinent. */
    fun photosChanged() = notifyChanged()

    /**
     * Fichier de la photo [name] (adresse ou nom), ou null. La synchro du site enregistre le nom nu ;
     * les photos copiées de l'appli d'origine peuvent avoir un préfixe. Sans accès disque une fois
     * le dossier lu (la grille l'appelle pour chaque case, sur le fil principal).
     */
    fun imageFile(name: String): File? {
        if (name.isBlank()) return null
        val clean = name.substringAfterLast('/').substringBefore('?')
        if (clean.isEmpty()) return null
        val candidates = arrayOf(clean, "large-$clean", "small-$clean")
        if (imagesScanned) {
            for (c in candidates) if (c in imageNames) return File(imagesDir, c)
            return null
        }
        for (c in candidates) {
            val f = File(imagesDir, c)
            if (f.exists() && f.length() > 0) return f
        }
        return null
    }

    fun runInBackground(block: () -> Unit) = exec.execute(block)

    /** Essais : arrête les fils et ferme la base. */
    internal fun close() {
        exec.shutdown()
        memo.shutdown()
        exec.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
        helper.close()
    }

    companion object {
        /** Fiches courtes et mots de recherche ; le numéro suit celui du découpage ([SearchWords.VERSION]). */
        val SUMMARIES = "summaries_v" + SearchWords.VERSION
        /** Origines par nom, pour relire les fiches (une recherche par recette). */
        private val SOURCES = RecipeSource.values().associateBy { it.name }
        /** Lignes lues par requête. */
        private const val CHUNK = 200
    }
}
