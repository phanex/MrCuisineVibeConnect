package app.toque.core.robot

import app.toque.core.i18n.tr
import app.toque.core.i18n.trKey
import app.toque.core.machine.Accessory
import app.toque.core.protocol.BoardCodec
import app.toque.core.protocol.ConnectCodec
import app.toque.core.protocol.Mcc
import app.toque.core.protocol.Smart
import app.toque.core.protocol.SmartCodec

/**
 * Ce qu'un robot sait faire. Les écrans ne proposent que cela, et une recette n'est montrée que
 * si chacune de ses étapes en fait partie ([RecipeCompat]).
 */
enum class Feature(private val fr: String) {
    MANUAL(trKey("Cuisson manuelle")),
    /** Chauffer lame à l'arrêt (vitesse 0 avec une température). */
    HEAT_NO_STIR(trKey("Chauffe sans mélange")),
    KNEAD(trKey("Pétrir")),
    STEAM(trKey("Vapeur")),
    TOAST(trKey("Rissoler")),
    TURBO(trKey("Turbo")),
    /** Nettoyage du bol par Toque (vitesses 4 à 10, sans chauffe). */
    CLEANING(trKey("Nettoyage")),
    MIXER(trKey("Mixer")),
    SMOOTHIE(trKey("Smoothie")),
    EGGS(trKey("Cuire des œufs")),
    SOUS_VIDE(trKey("Sous-vide")),
    SLOW_COOK(trKey("Mijoter")),
    FERMENT(trKey("Fermenter")),
    RICE(trKey("Cuire du riz")),
    KETTLE(trKey("Chauffer l'eau")),
    PREWASH(trKey("Prélaver")),
    LONG_CLEAN(trKey("Nettoyage long"));

    val label: String get() = tr(fr)
}

data class Capabilities(
    val features: Set<Feature>,
    /** Températures possibles en cuisson manuelle, en °C (sans 0). */
    val temperaturesC: List<Int>,
    val maxSpeed: Int = 10,
    val reverse: Boolean = true,
    val accessories: Set<Accessory> = setOf(Accessory.BLADE, Accessory.WHISK),
    val scale: Boolean = true,
    /** La base mesure le régime réel du moteur (sinon : délai d'arrêt avant de repartir). */
    val measuresSpeed: Boolean = true,
    /** La base mesure toujours la température (le Smart : seulement après une chauffe). */
    val measuresTemperature: Boolean = true,
) {
    fun has(f: Feature): Boolean = f in features
}

/**
 * Fiche d'un modèle de robot : comment lui parler (port, vitesse, protocole), quelle appli
 * d'origine laisser de côté, ce qu'il sait faire et quelles recettes Lidl lui sont destinées.
 * Ajouter un robot (une autre marque un jour) = une fiche de plus et son [BoardCodec].
 */
class RobotProfile(
    val id: String,
    val brand: String,
    val name: String,
    /** Références sur l'étiquette sous le robot. */
    val models: List<String>,
    val serialDevice: String,
    val baud: Int,
    /** Appli d'origine (ouverte par « Ouvrir l'appli d'origine », arrêtée pour libérer le port). */
    val vendorPackage: String,
    /** Autres applis qui peuvent tenir le port (arrêtées aussi). */
    val extraStopPackages: List<String> = emptyList(),
    /** Fin du nom du service de l'appli d'origine qui ouvre le port (désactivé en « Machine réelle »). */
    val vendorHardwareService: String,
    val capabilities: Capabilities,
    /** Type d'appareil du site monsieur-cuisine.com (« MC2.0 », « MC3.0 »). */
    val lidlDevice: String,
    /** La base de l'appli d'origine se copie dans Toque (format connu). */
    val stockImport: Boolean,
    /** Essayé par le projet sur de vrais robots ; sinon « en test », retours des utilisateurs attendus. */
    val tested: Boolean,
    private val codecFactory: () -> BoardCodec,
    /** Auteurs du protocole de ce robot, cités dans les Réglages (null : celui de Toque, d'après le SDK). */
    val credit: String? = null,
    /**
     * Une trame toutes les… (ms), comme l'appli d'origine. Le Smart répond ~100 ms après chaque
     * trame : à 100 ms, la commande suivante arriverait pendant sa réponse.
     */
    val framePeriodMs: Long = 100,
) {
    /** Nouveau codec (avec son propre compteur de séquence) pour une liaison. */
    fun newCodec(): BoardCodec = codecFactory()

    /** Codec sans liaison, pour vérifier qu'un réglage passerait ([BoardCodec.accepts] ne dépend d'aucun état). */
    val validator: BoardCodec by lazy { codecFactory() }

    val displayName: String get() = "$brand $name"
}

object RobotProfiles {
    val MC_CONNECT = RobotProfile(
        id = "mc-connect",
        brand = "Monsieur Cuisine",
        name = "Connect",
        models = listOf("SKMC 1200", "SKMC 1100"),
        serialDevice = "/dev/ttyMT0",
        baud = 4800,
        vendorPackage = "de.silpion.mc2",
        vendorHardwareService = "HardwareMachineService",
        capabilities = Capabilities(
            features = setOf(Feature.MANUAL, Feature.HEAT_NO_STIR, Feature.KNEAD, Feature.STEAM, Feature.TOAST,
                Feature.TURBO, Feature.CLEANING),
            temperaturesC = Mcc.TEMPERATURE_LEVELS.filter { it > 0 },
        ),
        lidlDevice = "MC2.0",
        stockImport = true,
        tested = true,
        codecFactory = { ConnectCodec },
    )

    /**
     * Monsieur Cuisine Smart (SKMS 1200) : protocole relevé par les portages de Flav974
     * (toque-smart-ratatouille) et de Tidusneo24150 (Camybrith Cooking). Pas essayé par le projet.
     * Chauffe sans mélange en cuisson manuelle : pas encore vue sur ce robot, pas proposée.
     */
    val MC_SMART = RobotProfile(
        id = "mc-smart",
        brand = "Monsieur Cuisine",
        name = "Smart",
        models = listOf("SKMS 1200"),
        serialDevice = "/dev/ttyMT1",
        baud = 9600,
        vendorPackage = "com.tecpal.device.mc30",
        extraStopPackages = listOf("com.tecpal.testapp.mc30"),
        vendorHardwareService = "SerialPortMC30Service",
        capabilities = Capabilities(
            features = setOf(Feature.MANUAL, Feature.KNEAD, Feature.STEAM, Feature.TOAST, Feature.TURBO, Feature.CLEANING,
                Feature.MIXER, Feature.SMOOTHIE, Feature.EGGS, Feature.SOUS_VIDE, Feature.SLOW_COOK, Feature.FERMENT,
                Feature.RICE, Feature.KETTLE, Feature.PREWASH, Feature.LONG_CLEAN),
            temperaturesC = Smart.TEMPERATURE_LEVELS.filter { it > 0 },
            measuresSpeed = false,
            measuresTemperature = false,
        ),
        lidlDevice = "MC3.0",
        stockImport = false,
        tested = false,
        codecFactory = { SmartCodec() },
        credit = "Flav974 (toque-smart-ratatouille), Tidusneo24150 (Camybrith Cooking)",
        // Appli d'origine et Camybrith Cooking : 200 ms (retour de Tidusneo24150, issue #59).
        framePeriodMs = 200,
    )

    val ALL: List<RobotProfile> = listOf(MC_CONNECT, MC_SMART)

    fun byId(id: String?): RobotProfile? = ALL.firstOrNull { it.id == id }

    /**
     * Robot choisi (confirmé par la personne), lu par tout le cœur : recettes, réglages proposés.
     * Le Connect tant que rien n'est choisi : c'est le comportement de Toque avant les fiches.
     */
    @Volatile var active: RobotProfile = MC_CONNECT
}

/** Ce que Toque peut observer sur la tablette, sans rien envoyer à la base. */
data class DeviceFacts(
    val installedPackages: Set<String>,
    val sdkInt: Int,
    /** Fichiers de périphérique présents parmi ceux des fiches (« /dev/ttyMT1 »). */
    val serialDevices: Set<String> = emptySet(),
)

class Detection(val profile: RobotProfile?, val reason: String) {
    val found: Boolean get() = profile != null
}

/**
 * Reconnaît le robot sans jamais lui parler : on n'envoie aucune trame pour « essayer », une trame
 * d'un autre protocole pourrait être mal comprise par la base. La personne confirme ensuite.
 */
object RobotDetector {
    fun detect(f: DeviceFacts): Detection {
        val byApp = RobotProfiles.ALL.filter { p -> p.vendorPackage in f.installedPackages }
        if (byApp.size == 1) {
            val p = byApp[0]
            return Detection(p, tr("appli d'origine %s installée", p.vendorPackage))
        }
        if (byApp.size > 1) return Detection(null, tr("plusieurs applis d'origine installées"))
        // Sans appli d'origine (désinstallée) : la version d'Android de la tablette d'origine.
        return when {
            f.sdkInt == 23 && RobotProfiles.MC_CONNECT.serialDevice in f.serialDevices ->
                Detection(RobotProfiles.MC_CONNECT, tr("Android 6, port %s", RobotProfiles.MC_CONNECT.serialDevice))
            f.sdkInt == 27 && RobotProfiles.MC_SMART.serialDevice in f.serialDevices ->
                Detection(RobotProfiles.MC_SMART, tr("Android 8.1, port %s", RobotProfiles.MC_SMART.serialDevice))
            else -> Detection(null, tr("aucune appli d'origine connue"))
        }
    }
}
