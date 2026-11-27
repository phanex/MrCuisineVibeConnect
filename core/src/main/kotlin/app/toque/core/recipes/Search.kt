package app.toque.core.recipes

import java.text.Normalizer

object TextNorm {
    private val MARKS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{Nd}]+")
    /**
     * Apostrophe entre deux lettres cyrilliques (М'ясо → мясо). Plage explicite : Android 6 refuse
     * `\p{IsCyrillic}` (U_ILLEGAL_ARGUMENT_ERROR). Compilée une fois, pas à chaque titre.
     */
    private val CYRILLIC_APOSTROPHE = Regex("(?<=[\u0400-\u04FF])['’ʼ`](?=[\u0400-\u04FF])")
    private val STOP = hashSetOf(
        // Français (FR)
        "de", "du", "des", "la", "le", "les", "l", "d", "et", "a", "au", "aux", "en", "un", "une",
        "pour", "sur", "par", "ou", "son", "sa", "ses", "mon", "ma", "mes", "y", "recette", "recettes",
        // Deutsch (DE)
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einer", "eines", "einem", "einen",
        "und", "oder", "mit", "fur", "von", "im", "am", "an", "auf", "aus", "zu", "zum", "zur", "rezept", "rezepte",
        // English (EN)
        "the", "of", "with", "for", "in", "on", "at", "and", "or", "to", "by", "from", "recipe", "recipes",
        // Español (ES)
        "el", "los", "las", "unos", "unas", "del", "con", "por", "para", "al", "receta", "recetas",
        // Polski (PL)
        "w", "z", "na", "do", "dla", "i", "oraz", "o", "po", "ze", "za", "od", "pod", "nad", "przepis", "przepisy",
        // Українська (UK)
        "і", "та", "в", "у", "на", "з", "із", "зі", "до", "для", "по", "за", "про", "від", "під", "над",
        "як", "що", "або", "чи", "не", "це", "рецепт", "рецепти",
    )

    /** Caractères (après minuscules) dont la forme simplifiée est calculée une fois : latin, grec, cyrillique… */
    private const val FOLDED = 0x2500
    private val folds = arrayOfNulls<String>(FOLDED)

    /**
     * Minuscules, sans accents, tout ce qui n'est ni lettre (tout alphabet) ni chiffre remplacé par une espace.
     * Chaque caractère passe une seule fois par Normalizer (puis sa forme est gardée) : l'index normalise
     * des dizaines de milliers de textes. Même résultat que la version à expressions régulières ([slow]),
     * qui sert encore pour les textes aux caractères rares (emoji…).
     */
    fun normalize(s: String): String {
        val low = s.lowercase()
        val out = StringBuilder(low.length)
        var gap = false
        for (i in low.indices) {
            val c = low[i]
            if (c in 'a'..'z' || c in '0'..'9') {
                if (gap && out.isNotEmpty()) out.append(' ')
                gap = false
                out.append(c)
            } else if (c in APOSTROPHES && cyrillic(low, i - 1) && cyrillic(low, i + 1)) {
                // М'ясо → мясо : l'apostrophe entre deux lettres cyrilliques ne coupe pas le mot.
                continue
            } else if (c < '\u0080') {
                gap = true
            } else if (c.code < FOLDED) {
                for (f in fold(c)) {
                    if (f == ' ') gap = true
                    else {
                        if (gap && out.isNotEmpty()) out.append(' ')
                        gap = false
                        out.append(f)
                    }
                }
            } else {
                return slow(low)
            }
        }
        return out.toString()
    }

    private const val APOSTROPHES = "'’ʼ`"

    private fun cyrillic(s: String, i: Int): Boolean = i in s.indices && s[i] in '\u0400'..'\u04FF'

    /** Forme simplifiée d'un caractère : lettres (tout alphabet) et chiffres, espace pour le reste, rien pour un accent seul. */
    private fun fold(c: Char): String {
        folds[c.code]?.let { return it }
        val d = when (c) {
            'œ' -> "oe"
            'æ' -> "ae"
            'ß' -> "ss"
            'ł' -> "l"
            else -> Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        }
        val b = StringBuilder(d.length)
        for (x in d) {
            if (Character.getType(x) == Character.NON_SPACING_MARK.toInt()) continue
            b.append(if (Character.isLetter(x) || Character.getType(x) == Character.DECIMAL_DIGIT_NUMBER.toInt()) x else ' ')
        }
        val f = b.toString()
        folds[c.code] = f
        return f
    }

    private fun slow(low: String): String {
        var t = low.replace("œ", "oe").replace("æ", "ae").replace("ß", "ss")
            .replace("ł", "l")
            .replace(CYRILLIC_APOSTROPHE, "")
        t = Normalizer.normalize(t, Normalizer.Form.NFD)
        t = MARKS.replace(t, "")
        t = NON_ALNUM.replace(t, " ")
        return t.trim()
    }

    /** Pluriels français simples : pommes -> pomme, choux -> chou, gâteaux -> gateau. */
    fun stem(w: String): String = when {
        w.length > 4 && w.endsWith("aux") -> w.dropLast(1)
        w.length > 3 && (w.endsWith("s") || w.endsWith("x")) && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    fun tokens(s: String, keepStopWords: Boolean = false): List<String> {
        val n = normalize(s)
        if (n.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (w in n.split(' ')) {
            if (w.isEmpty()) continue
            if (!keepStopWords && w in STOP) continue
            out += stem(w)
        }
        return out
    }

    /** Distance de Damerau-Levenshtein (transpositions adjacentes) bornée par [max]. */
    fun distance(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        val m = b.length
        return distance(a, b, max, IntArray(m + 1), IntArray(m + 1), IntArray(m + 1))
    }

    /** Variante sans allocation : réutilise trois tampons [buf0], [buf1], [buf2] de taille ≥ b.length + 1. */
    internal fun distance(a: String, b: String, max: Int, buf0: IntArray, buf1: IntArray, buf2: IntArray): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        val n = a.length
        val m = b.length
        var prev2 = buf0
        var prev = buf1
        var cur = buf2
        for (j in 0..m) prev[j] = j
        for (i in 1..n) {
            cur[0] = i
            var rowMin = i
            val ai = a[i - 1]
            for (j in 1..m) {
                val cost = if (ai == b[j - 1]) 0 else 1
                var v = prev[j] + 1
                val ins = cur[j - 1] + 1
                if (ins < v) v = ins
                val sub = prev[j - 1] + cost
                if (sub < v) v = sub
                if (i > 1 && j > 1 && ai == b[j - 2] && a[i - 2] == b[j - 1]) {
                    val tr = prev2[j - 2] + 1
                    if (tr < v) v = tr
                }
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prev2
            prev2 = prev
            prev = cur
            cur = t
        }
        return prev[m]
    }
}

/** Filtres de la liste de recettes. */
data class RecipeFilter(
    val category: String = "",
    val favoritesOnly: Boolean = false,
    val source: RecipeSource? = null,
    val maxMinutes: Int = 0,
)

/**
 * Une recette telle que l'index la garde : sa fiche courte et ses mots pondérés, calculés une fois
 * ([SearchWords.of]) et enregistrés avec elle sur le robot. [head] : mots du titre, de la catégorie
 * et des étiquettes ; [body] : ceux des ingrédients et des étapes qui ne sont pas déjà dans [head].
 * Format : une ligne par poids, « 10 pizza pate », « 4 farine eau ».
 */
class IndexedRecipe(val entry: SearchIndex.Entry, val head: String, val body: String)

object SearchWords {
    /**
     * Change quand le découpage des mots change : les mots enregistrés sont alors recalculés.
     * 2 : lettres de tout alphabet, mots vides allemands, anglais, espagnols, polonais et ukrainiens.
     */
    const val VERSION = 2

    fun entry(r: Recipe): SearchIndex.Entry =
        SearchIndex.Entry(r.id, r.title, r.category, r.tags, r.favorite, r.source, r.totalMinutes, r.image,
            r.machineSteps, TextNorm.normalize(r.title), r.rating, r.ratingCount, r.language)

    fun of(r: Recipe): IndexedRecipe {
        // terme -> poids le plus fort (titre > catégorie/étiquettes > ingrédients > étapes)
        val w = LinkedHashMap<String, Int>()
        fun add(text: String, weight: Int) {
            for (t in TextNorm.tokens(text)) {
                val old = w[t]
                if (old == null || weight > old) w[t] = weight
            }
        }
        add(r.title, SearchIndex.W_TITLE)
        add(r.category, SearchIndex.W_TAG)
        for (t in r.tags) add(t, SearchIndex.W_TAG)
        for (g in r.ingredients) for (i in g.items) add(i.name, SearchIndex.W_INGREDIENT)
        for (s in r.steps) add(s.text, SearchIndex.W_STEP)
        return IndexedRecipe(entry(r), encode(w, SearchIndex.W_TAG, Int.MAX_VALUE), encode(w, 0, SearchIndex.W_TAG - 1))
    }

    private fun encode(w: Map<String, Int>, min: Int, max: Int): String {
        val b = StringBuilder()
        for (weight in SearchIndex.WEIGHTS) {
            if (weight < min || weight > max) continue
            var first = true
            for ((t, v) in w) {
                if (v != weight) continue
                if (first) {
                    if (b.isNotEmpty()) b.append('\n')
                    b.append(weight)
                    first = false
                }
                b.append(' ').append(t)
            }
        }
        return b.toString()
    }

    /** Parcourt les mots enregistrés ([IndexedRecipe.head] ou [IndexedRecipe.body]) avec leur poids. */
    fun forEach(words: String, action: (String, Int) -> Unit) {
        val n = words.length
        var i = 0
        while (i < n) {
            var end = words.indexOf('\n', i)
            if (end < 0) end = n
            // Le poids en tête de ligne, puis les termes séparés par une espace.
            var weight = 0
            var j = i
            while (j < end && words[j] in '0'..'9') weight = weight * 10 + (words[j++] - '0')
            while (j < end) {
                while (j < end && words[j] == ' ') j++
                var k = j
                while (k < end && words[k] != ' ') k++
                if (k > j && weight > 0) action(words.substring(j, k), weight)
                j = k
            }
            i = end + 1
        }
    }
}

/**
 * Index de recherche en mémoire : tolérant aux accents, aux pluriels et aux fautes de frappe,
 * pondéré (titre > catégorie/tags > ingrédients > étapes). Quelques millisecondes pour
 * des milliers de recettes, même sur la tablette d'origine.
 *
 * Sur le robot il est construit en deux temps ([of]) : d'abord titres, catégories et étiquettes
 * ([full] faux), assez pour la liste et la recherche par titre, puis le texte complet.
 */
class SearchIndex private constructor(parts: Parts) {

    class Entry(
        val id: String,
        val title: String,
        val category: String,
        val tags: List<String>,
        favorite: Boolean,
        val source: RecipeSource,
        val totalMinutes: Int,
        val image: String,
        val machineSteps: Int,
        val normTitle: String,
        val rating: Double = 0.0,
        val ratingCount: Int = 0,
        /** Recette dans une autre langue que celle de l'interface (« de-DE ») ; vide sinon. */
        val language: String = "",
    ) {
        /** Modifiable sur place : une étoile touchée ne reconstruit pas l'index. */
        @Volatile var favorite: Boolean = favorite
    }

    data class Hit(val entry: Entry, val score: Double, val matched: Int)

    /** Recettes de l'index ; une place vide pour une recette retirée par [withChanges]. */
    private val docs: Array<Entry?> = parts.docs
    /** terme -> (n° de recette × 16 + poids), n° croissants */
    private val postings: HashMap<String, IntArray> = parts.postings
    private val vocabulary: Array<String> = parts.vocabulary
    /** Vocabulaire regroupé par longueur de mot : accélère la recherche floue sans balayer tout le dictionnaire. */
    private val byLen: Array<Array<String>> = bucketByLength(parts.vocabulary)
    private val byId: HashMap<String, Int> = parts.byId
    /** Ingrédients et étapes compris ; sinon titres, catégories et étiquettes seulement. */
    val full: Boolean = parts.full

    val entries: List<Entry> = docs.filterNotNull()
    /** Entrées pré-triées par [Entry.normTitle] : évite un tri O(N log N) à chaque recherche vide. */
    private val entriesByTitle: List<Entry> by lazy { entries.sortedBy { it.normTitle } }

    constructor(recipes: List<Recipe>) : this(build(recipes.map { SearchWords.of(it) }, true))

    private class Parts(
        val docs: Array<Entry?>,
        val postings: HashMap<String, IntArray>,
        val vocabulary: Array<String>,
        val byId: HashMap<String, Int>,
        val full: Boolean,
    )

    /** Liste d'entiers qui grandit, pour construire les listes de chaque terme sans objets Integer. */
    private class Ints {
        var a = IntArray(4)
        var n = 0
        fun add(v: Int) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
    }

    companion object {
        const val W_TITLE = 10
        const val W_TAG = 5
        const val W_INGREDIENT = 4
        const val W_STEP = 1
        /** Poids possibles, du plus fort au plus faible (moins de 16 : ils tiennent dans 4 bits). */
        val WEIGHTS = intArrayOf(W_TITLE, W_TAG, W_INGREDIENT, W_STEP)

        /** Index des recettes déjà découpées ; [full] faux : mots des titres, catégories et étiquettes seulement. */
        fun of(recipes: List<IndexedRecipe>, full: Boolean): SearchIndex = SearchIndex(build(recipes, full))

        private fun bucketByLength(vocab: Array<String>): Array<Array<String>> {
            var maxLen = 0
            for (w in vocab) if (w.length > maxLen) maxLen = w.length
            val counts = IntArray(maxLen + 1)
            for (w in vocab) counts[w.length]++
            val buckets = Array(maxLen + 1) { len -> arrayOfNulls<String>(counts[len]) }
            val pos = IntArray(maxLen + 1)
            for (w in vocab) {
                val l = w.length
                buckets[l][pos[l]++] = w
            }
            @Suppress("UNCHECKED_CAST")
            return buckets as Array<Array<String>>
        }

        private fun build(recipes: List<IndexedRecipe>, full: Boolean): Parts {
            val docs = arrayOfNulls<Entry>(recipes.size)
            val byId = HashMap<String, Int>(recipes.size * 2)
            val lists = HashMap<String, Ints>()
            var curDoc = 0
            val add: (String, Int) -> Unit = { t, w ->
                var l = lists[t]
                if (l == null) {
                    l = Ints()
                    lists[t] = l
                }
                l.add((curDoc shl 4) or w)
            }
            for ((doc, r) in recipes.withIndex()) {
                curDoc = doc
                docs[doc] = r.entry
                byId[r.entry.id] = doc
                SearchWords.forEach(r.head, add)
                if (full) SearchWords.forEach(r.body, add)
            }
            val postings = HashMap<String, IntArray>(lists.size * 2)
            for ((t, l) in lists) postings[t] = l.a.copyOf(l.n)
            val vocabulary = postings.keys.toTypedArray().also { java.util.Arrays.sort(it) }
            return Parts(docs, postings, vocabulary, byId, full)
        }
    }

    /** L'entrée de la recette [id], ou null. */
    fun entry(id: String): Entry? = byId[id]?.let { docs[it] }

    /**
     * Un nouvel index avec ces recettes ajoutées ou remplacées et celles-là retirées, sans tout
     * reconstruire : les listes des termes non concernés sont partagées avec celui-ci.
     */
    fun withChanges(upserts: List<IndexedRecipe>, removed: Collection<String> = emptyList()): SearchIndex {
        if (upserts.isEmpty() && removed.isEmpty()) return this
        val list = ArrayList<Entry?>(docs.size + upserts.size)
        list.addAll(docs)
        val ids = HashMap(byId)
        for (id in removed) ids.remove(id)?.let { list[it] = null }
        for (u in upserts) ids.remove(u.entry.id)?.let { list[it] = null }
        val post = HashMap(postings)
        var newTerms = false
        for (u in upserts) {
            val doc = list.size
            list += u.entry
            ids[u.entry.id] = doc
            val add = { t: String, w: Int ->
                val old = post[t]
                if (old == null) newTerms = true
                val a = if (old == null) IntArray(1) else old.copyOf(old.size + 1)
                a[a.size - 1] = (doc shl 4) or w
                post[t] = a
            }
            SearchWords.forEach(u.head, add)
            if (full) SearchWords.forEach(u.body, add)
        }
        val vocab = if (newTerms) post.keys.toTypedArray().also { java.util.Arrays.sort(it) } else vocabulary
        return SearchIndex(Parts(list.toTypedArray(), post, vocab, ids, full))
    }

    /** Applique les favoris [ids] à toutes les entrées. */
    fun applyFavorites(ids: Set<String>) {
        for (e in docs) if (e != null) e.favorite = e.id in ids
    }

    val categories: List<String> by lazy {
        entries.map { it.category }.filter { it.isNotBlank() }.distinct().sortedBy { TextNorm.normalize(it) }
    }

    private fun prefixMatches(token: String): List<String> {
        var lo = java.util.Arrays.binarySearch(vocabulary, token)
        if (lo < 0) lo = -lo - 1
        val out = ArrayList<String>()
        var i = lo
        while (i < vocabulary.size && vocabulary[i].startsWith(token) && out.size < 64) {
            out += vocabulary[i]
            i++
        }
        return out
    }

    /** Termes candidats pour un mot de la requête, avec leur qualité de correspondance. */
    private fun candidates(token: String): Map<String, Double> {
        val c = HashMap<String, Double>()
        if (postings.containsKey(token)) c[token] = 1.0
        if (token.length >= 2) for (t in prefixMatches(token)) if (t != token) c[t] = maxOf(c[t] ?: 0.0, 0.75)
        if (token.length >= 4) {
            val max = if (token.length >= 8) 2 else 1
            val minLen = maxOf(1, token.length - max)
            val maxLen = minOf(byLen.size - 1, token.length + max)
            val cap = maxLen + 1
            val b0 = IntArray(cap)
            val b1 = IntArray(cap)
            val b2 = IntArray(cap)
            val checkFirst = token.length < 6
            val firstChar = token[0]
            for (len in minLen..maxLen) {
                for (t in byLen[len]) {
                    if (checkFirst && t[0] != firstChar) continue
                    if (c.containsKey(t)) continue
                    val d = TextNorm.distance(token, t, max, b0, b1, b2)
                    if (d <= max) c[t] = if (d == 1) 0.55 else 0.35
                }
            }
        }
        return c
    }

    private fun accepts(e: Entry, f: RecipeFilter): Boolean {
        if (f.category.isNotEmpty() && e.category != f.category) return false
        if (f.favoritesOnly && !e.favorite) return false
        if (f.source != null && e.source != f.source) return false
        if (f.maxMinutes > 0 && (e.totalMinutes <= 0 || e.totalMinutes > f.maxMinutes)) return false
        return true
    }

    fun search(query: String, filter: RecipeFilter = RecipeFilter(), limit: Int = 0): List<Hit> {
        val normQuery = TextNorm.normalize(query)
        val tokens = if (normQuery.isEmpty()) emptyList() else TextNorm.tokens(query).distinct()
        if (tokens.isEmpty()) {
            val sorted = entriesByTitle
            val cap = if (limit > 0) minOf(limit, sorted.size) else sorted.size
            val out = ArrayList<Hit>(cap)
            for (e in sorted) {
                if (!e.favorite || !accepts(e, filter)) continue
                out += Hit(e, 0.0, 0)
                if (limit > 0 && out.size >= limit) return out
            }
            if (!filter.favoritesOnly) {
                for (e in sorted) {
                    if (e.favorite || !accepts(e, filter)) continue
                    out += Hit(e, 0.0, 0)
                    if (limit > 0 && out.size >= limit) return out
                }
            }
            return out
        }
        val n = docs.size
        val totals = DoubleArray(n)
        val counts = IntArray(n)
        val scores = DoubleArray(n)
        for (tok in tokens) {
            java.util.Arrays.fill(scores, 0.0)
            for ((term, quality) in candidates(tok)) {
                val p = postings[term] ?: continue
                for (v in p) {
                    val doc = v ushr 4
                    val s = quality * (v and 15)
                    if (s > scores[doc]) scores[doc] = s
                }
            }
            for (doc in 0 until n) if (scores[doc] > 0.0) {
                totals[doc] += scores[doc]
                counts[doc]++
            }
        }
        val hits = ArrayList<Hit>()
        for (doc in 0 until n) {
            if (counts[doc] == 0) continue
            val e = docs[doc] ?: continue
            if (!accepts(e, filter)) continue
            val matched = counts[doc]
            var score = totals[doc]
            if (matched == tokens.size) score += 100.0
            if (e.normTitle.startsWith(normQuery)) score += 20.0 else if (e.normTitle.contains(normQuery)) score += 10.0
            if (e.favorite) score += 1.0
            hits += Hit(e, score, matched)
        }
        val full = hits.filter { it.matched == tokens.size }
        val result = if (full.isNotEmpty()) full else hits
        val sorted = result.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.entry.normTitle })
        return if (limit > 0) sorted.take(limit) else sorted
    }

    /** « Qu'est-ce que je peux faire avec… » : classe par nombre d'ingrédients présents. */
    fun withIngredients(list: List<String>, filter: RecipeFilter = RecipeFilter()): List<Hit> {
        val wanted = list.map { it.trim() }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return emptyList()
        val counts = IntArray(docs.size)
        val seen = BooleanArray(docs.size)
        for (w in wanted) {
            java.util.Arrays.fill(seen, false)
            for (tok in TextNorm.tokens(w)) for ((term, q) in candidates(tok)) {
                if (q < 0.5) continue
                val p = postings[term] ?: continue
                for (v in p) if ((v and 15) >= W_INGREDIENT) seen[v ushr 4] = true
            }
            for (d in seen.indices) if (seen[d]) counts[d]++
        }
        val hits = ArrayList<Hit>()
        for (d in counts.indices) {
            if (counts[d] == 0) continue
            val e = docs[d] ?: continue
            if (accepts(e, filter)) hits += Hit(e, counts[d].toDouble(), counts[d])
        }
        return hits.sortedWith(compareByDescending<Hit> { it.matched }.thenBy { it.entry.normTitle })
    }
}
