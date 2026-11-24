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
    /** Mots vides et adjectifs de taille, dans les cinq langues du catalogue. */
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
    )

    /** Contenants et mesures : le mot qui compte vient après (« gousse d'ail », « pincée de sel »). */
    private val CONTAINERS = setOf(
        "gousse", "pincee", "brin", "branche", "feuille", "tranche", "morceau", "sachet", "boite", "cuillere", "cube",
        "bouquet", "botte", "filet", "noix", "zeste", "trait", "verre", "tasse", "pot", "paquet", "cs", "cc",
        "clove", "pinch", "sprig", "leaf", "slice", "piece", "can", "tin", "spoon", "tablespoon", "teaspoon", "tbsp", "tsp", "cup", "dash",
        "diente", "pizca", "rama", "hoja", "rodaja", "trozo", "lata", "cucharada", "cucharadita", "taza", "chorrito",
        "zehe", "prise", "zweig", "blatt", "scheibe", "stuck", "dose", "essloffel", "teeloffel", "el", "tl", "tasse", "spritzer",
        "gra", "grans", "pessic", "branca", "fulla", "llesca", "tros", "llauna", "cullerada", "culleradeta", "tassa", "raig",
    )

    private fun norm(s: String): String = QuantityLinks.norm(s.replace("ß", "ss"))

    /** Formes comparables d'un mot : lui-même, sans « s »/« x » de pluriel, sans « es », sans « n »/« en » (Zwiebeln). */
    private fun forms(w: String): Set<String> {
        val out = HashSet<String>()
        out += w
        if (w.length > 3) {
            if (w.endsWith("s") || w.endsWith("x") || w.endsWith("n")) out += w.dropLast(1)
            if (w.endsWith("es") || w.endsWith("en")) out += w.dropLast(2)
        }
        return out
    }

    private fun container(w: String): Boolean = forms(w).any { it in CONTAINERS }

    private fun same(a: String, b: String): Boolean = forms(a).any { it.length >= 3 && it in forms(b) }

    /** Mots significatifs du nom (avant la virgule et la parenthèse), dans l'ordre. */
    fun keywords(name: String): List<String> =
        norm(name.substringBefore(',').substringBefore('(')).split(' ')
            .filter { it.length > 1 && it !in STOP && !it.all(Char::isDigit) }

    /** Le mot par lequel l'ingrédient est reconnu ; null si le nom n'en a pas d'assez long. */
    fun head(name: String): String? {
        val kw = keywords(name)
        val main = kw.firstOrNull { !container(it) } ?: kw.firstOrNull()
        return main?.takeIf { it.length >= 3 }
    }

    /**
     * Indices (dans [ingredients]) des ingrédients nommés par [text], dans l'ordre de la liste.
     * Un ingrédient écrit deux fois dans la liste (deux groupes) apparaît deux fois : chaque ligne a sa quantité.
     */
    fun mentioned(ingredients: List<Ingredient>, text: String): List<Int> {
        val words = norm(text).split(' ').filter { it.length >= 3 }
        if (words.isEmpty()) return emptyList()
        fun inText(w: String) = words.any { same(it, w) }
        // Ingrédients dont le mot principal est dans l'étape, regroupés par ce mot.
        val byHead = LinkedHashMap<String, MutableList<Int>>()
        for ((k, i) in ingredients.withIndex()) {
            val h = head(i.name) ?: continue
            if (!inText(h)) continue
            val key = byHead.keys.firstOrNull { same(it, h) } ?: h
            byHead.getOrPut(key) { ArrayList() } += k
        }
        val picked = HashSet<Int>()
        for (cands in byHead.values) {
            if (cands.size == 1) { picked += cands[0]; continue }
            // Plusieurs : ceux dont tous les mots sont dans l'étape ; aucun s'il n'y en a pas.
            fun own(k: Int) = keywords(ingredients[k].name).filter { !container(it) }
            val full = cands.filter { k -> own(k).all { inText(it) } }
            if (full.isEmpty()) continue
            // « sucre » et « sucre vanillé » tous deux complets pour « ajouter le sucre vanillé » : seulement le
            // plus précis, sauf si le mot commun est écrit plusieurs fois (« le sucre et le sucre vanillé »).
            val head = head(ingredients[full[0]].name) ?: continue
            val times = words.count { same(it, head) }
            for (k in full) {
                val mine = own(k).toSet()
                val covered = full.any { o -> o != k && own(o).size > mine.size && own(o).containsAll(mine) }
                if (!covered || times > 1) picked += k
            }
        }
        return picked.sorted()
    }
}
