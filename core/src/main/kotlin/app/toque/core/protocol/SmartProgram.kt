package app.toque.core.protocol

/**
 * Programmes automatiques de l'appli d'origine du Monsieur Cuisine Smart (com.tecpal.device.mc30
 * 2.21.0.0) dont **toute** la trame est capturée (`protocole-2026-11-20/captures5/`, 2026-11-20) et
 * qui n'envoient qu'une seule trame pendant tout le programme (une seule étape dans le code
 * d'origine, classes `s5.*` et `y2.f0`).
 *
 * Chaque entrée reprend tels quels les octets 10 (niveau), 12 (°C), 16 (vitesse × 2), 17 (sens) et
 * 19 (fonction) de la trame d'origine. Seule la minuterie (octets 25-27) suit la durée réglée,
 * codée comme l'appli d'origine (`l7.u` : heures, minutes, secondes).
 *
 * Programmes à étapes (phase R2, capture complète du 2026-11-21 `16-etapes-mc30`) : mijoter,
 * fermenter, saisir, malaxer pâte ferme et molle, prélavage rapide et nettoyage long enchaînent
 * plusieurs trames. Chacune de ces trames a ici son entrée (`MIJOTER_BRASSE`, `MIJOTER_REPOS`…),
 * avec les octets capturés tels quels ; l'enchaînement (durées, minuterie) est dans
 * `Programs.etapes…`. Pendant un repos, l'appli d'origine garde la fonction (et la chauffe) et
 * met seulement la vitesse à 0 : ces repos sont des trames actives, pas des STOP.
 *
 * Les durées par défaut, minimales et maximales sont celles de l'appli d'origine (`b3.d1`).
 */
enum class SmartProgram(
    val function: Int,
    val level: Int,
    val celsius: Int,
    val speedHalfSteps: Int,
    val reverse: Boolean,
    val defaultS: Int,
    val minS: Int,
    val maxS: Int,
    /** Capture de référence (fichier de `captures5/`, heure de la première trame). */
    val capture: String,
    /**
     * Opération du contrôleur qui porte cette trame : STANDARD pour les programmes automatiques,
     * KNEAD pour les étapes de Malaxer (fonction 5), TOAST pour celles de Saisir (fonction 4).
     */
    val operation: Operation = Operation.STANDARD,
    /**
     * Plage de températures (octet 12) que l'appli d'origine laisse choisir pour ce programme
     * (tables `y2.*`, relevées par le portage Camybrith Cooking) ; [celsius] seul sinon. Seuls
     * les programmes « niveau 1 + température exacte » en ont une : sous-vide, mijoter, fermenter.
     */
    val minCelsius: Int = celsius,
    val maxCelsius: Int = celsius,
) {
    /** Mixer (« purée », fonction 12) : vitesse 8, 30 s (30 s à 2 min). */
    MIXER(12, 0, 0, 16, false, 30, 30, 120, "12-mixer 19:11:17"),

    /** Smoothie (fonction 13) : vitesse 10, 1 min (30 s à 2 min). */
    SMOOTHIE(13, 0, 0, 20, false, 60, 30, 120, "14-eau 19:28:33"),

    /** Cuire des œufs (fonction 8) : 130 °C (octet 12), vitesse 1, 8 min (1 à 20 min). */
    EGGS(8, 1, 130, 2, false, 8 * 60, 60, 20 * 60, "14-eau 19:23:20"),

    /** Sous-vide (fonction 9) : 60 °C (octet 12), vitesse 1 inverse, 6 h (15 min à 12 h). */
    SOUS_VIDE(9, 1, 60, 2, true, 6 * 3600, 15 * 60, 12 * 3600, "14-eau 19:24:40", minCelsius = 40, maxCelsius = 85),

    /** Cuire du riz (fonction 1, niveau 100 °C et 100 dans l'octet 12) : vitesse 1 inverse, 30 min (20 à 40 min). */
    RICE(1, 14, 100, 2, true, 30 * 60, 20 * 60, 40 * 60, "14-eau 19:30:31"),

    /** Chauffer l'eau (fonction 3, niveau 100 °C et 100 dans l'octet 12) : vitesse 1 inverse, 10 min (1 à 20 min). */
    KETTLE(3, 14, 100, 2, true, 10 * 60, 60, 20 * 60, "14-eau 19:31:24"),

    // ------------------------------------------------------------------ étapes (capture 16)

    /** Mijoter (fonction 10, 90 °C) : brassage vitesse 1 inverse. 4 h (15 min à 8 h). */
    MIJOTER_BRASSE(10, 1, 90, 2, true, 4 * 3600, 15 * 60, 8 * 3600, "16-etapes-mc30 16:45:54.531", minCelsius = 60, maxCelsius = 100),
    /** Mijoter : repos, lame à l'arrêt, chauffe gardée. */
    MIJOTER_REPOS(10, 1, 90, 0, true, 4 * 3600, 15 * 60, 8 * 3600, "16-etapes-mc30 16:46:24.683", minCelsius = 60, maxCelsius = 100),

    /** Fermenter (fonction 11, 40 °C) : brassage vitesse 1 inverse. 8 h (30 min à 12 h). */
    FERMENTER_BRASSE(11, 1, 40, 2, true, 8 * 3600, 30 * 60, 12 * 3600, "16-etapes-mc30 16:58:48.374", minCelsius = 30, maxCelsius = 50),
    /** Fermenter : repos, lame à l'arrêt, chauffe gardée. */
    FERMENTER_REPOS(11, 1, 40, 0, true, 8 * 3600, 30 * 60, 12 * 3600, "16-etapes-mc30 17:03:47.870", minCelsius = 30, maxCelsius = 50),

    /** Saisir (fonction 4, niveau 130 °C) : 5 s vitesse 1 inverse. 7 min (1 à 14 min). */
    SAISIR_BRASSE(4, 20, 0, 2, true, 7 * 60, 60, 14 * 60, "16-etapes-mc30 16:43:45.794", Operation.TOAST),
    /** Saisir : 30 s lame à l'arrêt, chauffe gardée. */
    SAISIR_REPOS(4, 20, 0, 0, true, 7 * 60, 60, 14 * 60, "16-etapes-mc30 16:43:50.773", Operation.TOAST),

    /** Malaxer (fonction 5, sans chauffe) : vitesse 4, sens normal. 2 min (30 s à 2 min). */
    MALAXER_V4(5, 0, 0, 8, false, 120, 30, 120, "16-etapes-mc30 16:39:01.858", Operation.KNEAD),
    /** Malaxer : vitesse 4, sens inverse. */
    MALAXER_V4_INVERSE(5, 0, 0, 8, true, 120, 30, 120, "16-etapes-mc30 16:40:02.443", Operation.KNEAD),
    /** Malaxer : repos (vitesse 0), sens normal. */
    MALAXER_REPOS(5, 0, 0, 0, false, 120, 30, 120, "16-etapes-mc30 16:39:47.452", Operation.KNEAD),
    /** Malaxer : repos (vitesse 0), sens inverse. */
    MALAXER_REPOS_INVERSE(5, 0, 0, 0, true, 120, 30, 120, "16-etapes-mc30 16:40:52.695", Operation.KNEAD),

    /** Prélavage rapide (fonction 6, niveau 37 °C) : vitesse 10. 1 min 30 fixe. */
    PRELAVAGE_V10(6, 1, 0, 20, false, 90, 90, 90, "16-etapes-mc30 17:06:31.913"),
    /** Prélavage rapide : vitesse 4 inverse. */
    PRELAVAGE_V4_INVERSE(6, 1, 0, 8, true, 90, 90, 90, "16-etapes-mc30 17:07:03.193"),

    /** Nettoyage long (fonction 7, niveau 37 °C) : vitesse 10. Au plus 17 min 15, fixe. */
    NETTOYAGE_V10(7, 1, 0, 20, false, NETTOYAGE_MAX_S, NETTOYAGE_MAX_S, NETTOYAGE_MAX_S, "16-etapes-mc30 17:09:44.227"),
    /** Nettoyage long : préchauffe à 40 °C (niveau 2), vitesse 1, jusqu'à « température atteinte ». */
    NETTOYAGE_CHAUFFE(7, 2, 0, 2, false, NETTOYAGE_MAX_S, NETTOYAGE_MAX_S, NETTOYAGE_MAX_S, "16-etapes-mc30 17:10:14.393"),
    /** Nettoyage long : vitesse 4 inverse (37 °C). */
    NETTOYAGE_V4_INVERSE(7, 1, 0, 8, true, NETTOYAGE_MAX_S, NETTOYAGE_MAX_S, NETTOYAGE_MAX_S, "16-etapes-mc30 17:11:04.780"),
    /** Nettoyage long : dernière étape, vitesse 10 sans chauffe. */
    NETTOYAGE_FIN(7, 0, 0, 20, false, NETTOYAGE_MAX_S, NETTOYAGE_MAX_S, NETTOYAGE_MAX_S, "16-etapes-mc30 17:12:00.150"),
    ;

    /** Étape d'un programme de nettoyage (prélavage, nettoyage long) : eau à 40 °C au plus. */
    val nettoyage: Boolean get() = function == 6 || function == 7

    val speed: Int get() = speedHalfSteps / 2
    val direction: Direction get() = if (reverse) Direction.COUNTER_CLOCKWISE else Direction.CLOCKWISE

    /** Température visée en °C (octet 12 s'il est rempli, sinon le niveau). */
    val targetCelsius: Int get() = if (celsius > 0) celsius else Smart.celsiusForLevel(level)

    /** Niveau de la table de Toque qui a la même température (règles de sécurité, affichage). */
    val toqueLevel: Int get() = toqueLevelFor(targetCelsius)

    /** Température exacte [c] choisie (recette) : dans la plage de l'appli d'origine ? */
    fun allowsCelsius(c: Int): Boolean = celsius > 0 && c in minCelsius..maxCelsius

    /** Niveau de la table de Toque pour une température de ce programme. */
    fun toqueLevelFor(c: Int): Int = if (c == 0) 0
        // 40 °C (fermenter, préchauffe du nettoyage) n'est pas dans la table de Toque : 37 °C.
        else Mcc.levelForCelsius(c).let { if (it > 0) it else Mcc.levelAtMost(c) }.also { check(it > 0) }
}

/**
 * Nettoyage long de l'appli d'origine (`y2.f0` f, g, h, i, j) : 30 s + préchauffe de 15 min au plus
 * + 2 × (25 s + 15 s) + 25 s.
 */
const val NETTOYAGE_MAX_S = 30 + 15 * 60 + 2 * 40 + 25
