package app.toque.core.recipes

import app.toque.core.json.Json
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.json.str

/**
 * Noms des catégories du site dans la langue des recettes, pour les recettes prises dans une autre
 * langue (option « Recettes des autres langues »).
 *
 * Chaque langue du site a ses propres identifiants de catégorie : « Sauces » est 218 en anglais,
 * 219 en français… 225 en espagnol. Une famille regroupe ces identifiants ; la catégorie d'une
 * recette étrangère prend le nom de sa sœur dans la liste de la langue des recettes
 * (`/api/v1/categories`, une seule requête). Sans sœur (revues « Mon Robot & Moi »,
 * « ZauberTopf »…) ou identifiant inconnu, le nom d'origine est gardé.
 *
 * Identifiants relevés sur l'API en novembre 2026, pour en, fr, de, it, pl, pt, nl, es, cs et sk.
 */
object McCategories {
    fun url(): String = "${McOnline.API}/api/v1/categories"

    private fun ids(range: IntRange, vararg more: Int): Set<Long> = (range.toList() + more.toList()).map { it.toLong() }.toSet()
    private fun ids(vararg all: Int): Set<Long> = all.map { it.toLong() }.toSet()

    /** Les huit premières langues suivent un pas de 8 (en, fr, de, it, pl, pt, nl, es) ; puis cs, sk. */
    private val FAMILIES: List<Set<Long>> = listOf(
        ids(218..225, 520, 715), // sauces
        ids(226..233, 521, 727), // soupes
        ids(234..241, 535, 711), // salades
        ids(242..249, 522, 719), // accompagnements
        ids(250..257, 523, 723), // snacks
        ids(258..265, 524, 695), // plats principaux
        ids(266..273, 525, 651), // bébé
        ids(274..281, 526, 671), // boissons
        ids(282..289, 527, 703), // one-pot
        ids(306..313, 529, 665), // desserts
        ids(314..321, 530, 655), // pâtisserie
        ids(322..329, 531, 659), // petit-déjeuner
        ids(330..337, 532, 667), // dîner
        ids(338..345, 533, 747), // entrées
        ids(346..353, 534, 691), // confitures
        ids(457, 462, 466, 471, 474, 478, 482, 486, 591, 683), // cuisine allemande
        ids(458, 463, 467, 472, 475, 479, 483, 487, 593, 679), // cuisine française
        ids(460, 464, 468, 473, 476, 480, 484, 488, 594, 687), // cuisine italienne
        ids(461, 469, 477, 481, 485, 489, 490, 498, 592, 707), // cuisine polonaise
        ids(465, 492, 495, 499, 503, 505, 507, 509, 595, 743), // cuisine espagnole
        ids(553, 554, 555, 556, 557, 559, 560, 561, 558, 675), // food processor
        ids(563, 564, 567, 569, 570, 751), // extracteur de jus à vapeur
        ids(572..579, 580, 755), // végan
        ids(581..588, 589, 759), // végétarien
    )

    private val familyOf: Map<Long, Set<Long>> = HashMap<Long, Set<Long>>().apply {
        for (f in FAMILIES) for (id in f) put(id, f)
    }

    /** Liste des catégories d'une langue (réponse de [url]) : identifiant → nom. */
    fun parse(json: String): Map<Long, String> {
        val out = HashMap<Long, String>()
        for (any in Json.parse(json).obj()["data"].obj()["categories"].list()) {
            val c = any.obj()
            val id = (c["id"] as? Number)?.toLong() ?: c.str("id")?.toLongOrNull() ?: continue
            val name = c.str("name")?.trim().orEmpty()
            if (name.isNotEmpty()) out[id] = name
        }
        return out
    }

    /** Nom de la catégorie [id] dans la langue de [local] (voir [parse]), ou null si elle n'y a pas d'équivalent. */
    fun localName(id: Long, local: Map<Long, String>): String? {
        local[id]?.let { return it }
        return familyOf[id]?.firstNotNullOfOrNull { local[it] }
    }
}
