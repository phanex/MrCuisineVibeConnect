package app.toque.core.recipes

/**
 * Les ingrédients de la liste qu'une étape nomme (« Éplucher l'oignon et couper le potimarron »
 * → oignon, potimarron), pour les montrer à côté de l'étape avec leur quantité.
 *
 * Un ingrédient est reconnu par le mot principal de son nom (le premier mot significatif, ou le
 * suivant après un contenant : « gousses d'ail » → ail, « cube de bouillon » → bouillon), écrit dans
 * l'étape au singulier ou au pluriel. Quand plusieurs ingrédients partagent ce mot (« sucre » et
 * « sucre vanillé »), celui dont tous les mots sont dans l'étape l'emporte ; à défaut, aucun n'est
 * montré : mieux vaut rien qu'un ingrédient faux.
 */
object StepIngredients {
    /** Mots vides et adjectifs de taille, dans les langues du catalogue. */
    private val STOP = setOf(
        // français
        "de", "du", "des", "la", "le", "les", "un", "une", "et", "au", "aux", "en", "ou", "pour", "avec", "sans", "env", "environ",
        "frais", "fraiche", "fraiches", "froide", "froid", "chaud", "chaude", "bien", "type", "non", "traite", "nature", "bio",
        "petit", "petite", "petits", "petites", "gros", "grosse", "grosses", "grand", "grande", "grands", "grandes",
        "moyen", "moyenne", "moyens", "moyennes", "quelques", "bon", "bonne", "belle", "beau",
        // anglais
        "of", "the", "and", "or", "for", "with", "fresh", "small", "medium", "large", "big", "some", "to", "taste",
        // espagnol, catalan
        "del", "el", "los", "las", "con", "sin", "para", "fresco", "fresca", "frescos", "frescas", "pequeno", "pequena",
        "mediano", "mediana", "gran", "grande", "els", "amb", "sense", "per", "fresc", "petita", "mitja", "mitjana",
        // allemand
        "der", "die", "das", "den", "dem", "und", "oder", "mit", "ohne", "fur", "frisch", "frische", "frischer", "frisches",
        "etwas", "von", "zum", "zur", "klein", "kleine", "kleiner", "gross", "grosse", "grosser", "mittelgross", "mittelgrosse",
        // polonais
        "ze", "na", "do", "od", "po", "bez", "lub", "albo", "oraz", "dla", "ok", "okolo", "smaku", "swiezy", "swieza", "swieze",
        "maly", "mala", "male", "duzy", "duza", "duze", "sredni", "srednia", "srednie", "kilka",
    )

    /** Contenants et mesures : le mot qui compte vient après (« gousse d'ail », « pincée de sel »). */
    private val CONTAINERS = setOf(
        "gousse", "pincee", "brin", "branche", "feuille", "tranche", "morceau", "sachet", "boite", "cuillere", "cube",
        "bouquet", "botte", "filet", "noix", "zeste", "trait", "verre", "tasse", "pot", "paquet", "cs", "cc",
        "clove", "pinch", "sprig", "leaf", "slice", "piece", "can", "tin", "spoon", "tablespoon", "teaspoon", "tbsp", "tsp", "cup", "dash",
        "diente", "pizca", "rama", "hoja", "rodaja", "trozo", "lata", "cucharada", "cucharadita", "taza", "chorrito",
        "zehe", "prise", "zweig", "blatt", "scheibe", "stuck", "dose", "essloffel", "teeloffel", "el", "tl", "tasse", "spritzer",
        "gra", "grans", "pessic", "branca", "fulla", "llesca", "tros", "llauna", "cullerada", "culleradeta", "tassa", "raig",
        "zabek", "zabki", "szczypta", "szczypty", "galazka", "galazki", "lisc", "liscie", "plaster", "plasterek", "plasterki",
        "kawalek", "kawalki", "puszka", "puszki", "lyzka", "lyzki", "lyzeczka", "lyzeczki", "szklanka", "szklanki",
        "opakowanie", "kostka", "kostki", "peczek", "torebka", "torebki", "garsc",
    )

    /** Lettres qu'on ne trouve qu'en polonais : elles activent la comparaison des mots déclinés. */
    private const val POLISH_LETTERS = "ąćęłńśźżĄĆĘŁŃŚŹŻ"

    /** Mots polonais dont la déclinaison change le radical (« mąkę », « soli », « cukru »), ramenés au nom de la liste. */
    private val POLISH_IRREGULAR = mapOf(
        "make" to "maka", "maki" to "maka", "soli" to "sol", "sola" to "sol", "cukru" to "cukier", "cukrem" to "cukier",
        "ryzu" to "ryz", "ryzem" to "ryz", "sera" to "ser", "serem" to "ser", "serze" to "ser", "wode" to "woda", "wody" to "woda",
        "wodzie" to "woda", "jaja" to "jajko", "jaj" to "jajko", "marchwi" to "marchew", "marchewki" to "marchewka",
    )

    private fun norm(s: String): String = QuantityLinks.norm(s.replace("ß", "ss"))

    private fun polish(s: String): Boolean = s.any { it in POLISH_LETTERS }

    /**
     * Ajoute à [out] les formes comparables de [w] : lui-même, sans « s »/« x » de pluriel, sans « es », sans
     * « n »/« en » (Zwiebeln) ; en polonais ([pl]), aussi le radical sans la terminaison de cas.
     */
    private fun addForms(w: String, out: MutableSet<String>, pl: Boolean = false) {
        out += w
        if (pl) {
            // Polonais : le radical sans la terminaison de cas (cebula, cebulę, cebuli → cebul), au moins 4 lettres.
            POLISH_IRREGULAR[w]?.let { out += it }
            if (w.length >= 5) {
                if (w.last() in "aeiyuo") out += w.dropLast(1)
                if (w.endsWith("ek")) out += w.dropLast(2) + "k"
                if (w.length >= 6) {
                    if (w.endsWith("ie") || w.endsWith("ow") || w.endsWith("om") || w.endsWith("em") || w.endsWith("ej")) out += w.dropLast(2)
                    if (w.endsWith("ami") || w.endsWith("ach")) out += w.dropLast(3)
                }
            }
        }
        if (w.length > 3) {
            if (w.endsWith("s") || w.endsWith("x") || w.endsWith("n")) out += w.dropLast(1)
            if (w.endsWith("es") || w.endsWith("en")) out += w.dropLast(2)
        }
    }

    /** Toutes les formes de [w] (polonais seulement : ailleurs, [container] et [inSet] n'allouent rien). */
    private fun formsOf(w: String, pl: Boolean): Set<String> = HashSet<String>(8).also { addForms(w, it, pl) }

    private fun container(w: String, pl: Boolean = false): Boolean {
        if (pl) return formsOf(w, true).any { it in CONTAINERS }
        if (w in CONTAINERS) return true
        if (w.length > 3) {
            if ((w.endsWith("s") || w.endsWith("x") || w.endsWith("n")) && w.dropLast(1) in CONTAINERS) return true
            if ((w.endsWith("es") || w.endsWith("en")) && w.dropLast(2) in CONTAINERS) return true
        }
        return false
    }

    /** Une forme de [w] d'au moins 3 lettres est dans [set] (les formes d'un autre mot, ou de tous ceux d'une étape). */
    private fun inSet(w: String, set: Set<String>, pl: Boolean = false): Boolean {
        if (pl) return formsOf(w, true).any { it.length >= 3 && it in set }
        if (w.length >= 3 && w in set) return true
        if (w.length > 3) {
            if (w.endsWith("s") || w.endsWith("x") || w.endsWith("n")) {
                val d1 = w.dropLast(1)
                if (d1.length >= 3 && d1 in set) return true
            }
            if (w.endsWith("es") || w.endsWith("en")) {
                val d2 = w.dropLast(2)
                if (d2.length >= 3 && d2 in set) return true
            }
        }
        return false
    }

    private fun same(a: String, b: String, pl: Boolean = false): Boolean {
        if (a == b && a.length >= 3) return true
        return inSet(a, formsOf(b, pl), pl)
    }

    /** Mots significatifs du nom (avant la virgule et la parenthèse), dans l'ordre. */
    fun keywords(name: String): List<String> =
        norm(name.substringBefore(',').substringBefore('(')).split(' ')
            .filter { it.length > 1 && it !in STOP && !it.all(Char::isDigit) }

    /** Le mot par lequel l'ingrédient est reconnu ; null si le nom n'en a pas d'assez long. */
    fun head(name: String, pl: Boolean = false): String? {
        val kw = keywords(name)
        val main = kw.firstOrNull { !container(it, pl) } ?: kw.firstOrNull()
        return main?.takeIf { it.length >= 3 }
    }

    /**
     * Indices (dans [ingredients]) des ingrédients nommés par [text], dans l'ordre de la liste.
     * Un ingrédient écrit deux fois dans la liste (deux groupes) apparaît deux fois : chaque ligne a sa quantité.
     */
    fun mentioned(ingredients: List<Ingredient>, text: String): List<Int> {
        val words = norm(text).split(' ').filter { it.length >= 3 }
        if (words.isEmpty()) return emptyList()
        // Polonais : reconnu à ses lettres (ł, ą, ę…), dans l'étape ou dans la liste.
        val pl = polish(text) || ingredients.any { polish(it.name) }
        val wordForms = HashSet<String>(words.size * 2)
        for (w in words) addForms(w, wordForms, pl)
        fun inText(w: String) = inSet(w, wordForms, pl)
        // Ingrédients dont le mot principal est dans l'étape, regroupés par ce mot.
        val byHead = LinkedHashMap<String, MutableList<Int>>()
        for ((k, i) in ingredients.withIndex()) {
            val h = head(i.name, pl) ?: continue
            if (!inText(h)) continue
            val key = byHead.keys.firstOrNull { same(it, h, pl) } ?: h
            byHead.getOrPut(key) { ArrayList() } += k
        }
        val picked = HashSet<Int>()
        for (cands in byHead.values) {
            if (cands.size == 1) { picked += cands[0]; continue }
            // Plusieurs : ceux dont tous les mots sont dans l'étape ; aucun s'il n'y en a pas.
            val ownCache = HashMap<Int, Set<String>>(cands.size * 2)
            fun own(k: Int): Set<String> = ownCache.getOrPut(k) {
                keywords(ingredients[k].name).filterTo(HashSet()) { !container(it, pl) }
            }
            val full = cands.filter { k -> own(k).all { inText(it) } }
            if (full.isEmpty()) continue
            // « sucre » et « sucre vanillé » tous deux complets pour « ajouter le sucre vanillé » : seulement le
            // plus précis, sauf si le mot commun est écrit plusieurs fois (« le sucre et le sucre vanillé »).
            val h = head(ingredients[full[0]].name, pl) ?: continue
            val hForms = formsOf(h, pl)
            val times = words.count { inSet(it, hForms, pl) }
            for (k in full) {
                val mine = own(k)
                val covered = full.any { o -> o != k && own(o).size > mine.size && own(o).containsAll(mine) }
                if (!covered || times > 1) picked += k
            }
        }
        return picked.sorted()
    }
}
