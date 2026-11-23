package app.toque.core.protocol

/**
 * Format des trames d'une carte moteur. Toque-Smart en connaît deux :
 *  - [ConnectCodec] : Monsieur Cuisine Connect (SKMC 1200), protocole d'origine de Toque ([Mcc]) ;
 *  - [SmartCodec] : Monsieur Cuisine Smart (SKMS 1200A1), voir [Smart].
 *
 * Le contrôleur ne manipule que des réglages (opération, vitesse 0..10, niveau de température de
 * la table de Toque, sens) ; le codec les traduit en octets et décode les états reçus en
 * [Telemetry].
 */
interface BoardCodec {
    /** Nom court, pour le journal. */
    val id: String

    /** Découpeur du flux reçu de la carte (un par liaison). */
    fun newRxParser(): FrameParser

    /** Décode une trame d'état complète et valide (renvoyée par le découpeur). */
    @Throws(MccProtocolException::class)
    fun decodeRx(raw: ByteArray): Telemetry

    /**
     * Trame de commande. [durationS] = durée totale du programme en cours (0 au repos) : le
     * Smart la reçoit à titre d'information, le Connect l'ignore. Lève IllegalArgumentException
     * pour une valeur hors plage ou une opération non prise en charge.
     *
     * [program] et [smartLevel] n'existent que sur le Smart (programme automatique capturé de
     * l'appli d'origine, niveau de température absent de la table de Toque comme 40 °C) : les
     * autres cartes refusent tout réglage qui en contient.
     */
    fun encodeTx(
        operation: Operation,
        speed: Int,
        temperatureLevel: Int,
        direction: Direction,
        tare: Boolean,
        durationS: Int,
        program: SmartProgram? = null,
        smartLevel: Int? = null,
        programCelsius: Int? = null,
    ): ByteArray

    /** Trame d'arrêt, utilisable depuis n'importe quel fil (fermeture, plantage). */
    fun stopFrame(direction: Direction = Direction.CLOCKWISE): ByteArray

    /** Opérations que Toque a le droit d'envoyer à cette carte. */
    fun supports(operation: Operation): Boolean

    /**
     * Ce réglage complet est-il accepté par [encodeTx] ? Vérifié au démarrage d'un programme, pour
     * refuser tout de suite au lieu de s'arrêter en faute pendant la cuisson.
     */
    fun accepts(
        operation: Operation, speed: Int, temperatureLevel: Int, direction: Direction,
        program: SmartProgram? = null, smartLevel: Int? = null, programCelsius: Int? = null,
    ): Boolean = supports(operation) && program == null && smartLevel == null && programCelsius == null

    /**
     * Délai minimal entre le dernier état « moteur en marche » et un nouveau démarrage ou un
     * changement de sens. Utile quand la carte ne mesure pas le régime réel (Smart).
     */
    val stopSettleMs: Long

    /** La tare n'est envoyée qu'au repos (sur le Smart, la trame de tare est une trame STOP). */
    val tareOnlyAtRest: Boolean

    /** Message lisible pour un code d'erreur de la carte. */
    fun errorMessage(code: Int): String

    /**
     * La température réelle renvoyée par la carte est-elle toujours mesurée ? Sur le Smart, elle
     * reste à 0 tant qu'aucune chauffe n'a été lancée depuis l'allumage : un contenu chaud peut
     * paraître froid. Si faux, le contrôleur traite le contenu comme chaud pour les règles de
     * sécurité (confirmation de mixage, montée progressive de la vitesse).
     */
    val temperatureReliable: Boolean get() = true

    /**
     * Codec dont viennent les règles du robot (programmes, plages, refroidissement du pétrin) :
     * lui-même, sauf en démo d'un autre robot que le Connect (`DemoCodec`).
     */
    val rules: BoardCodec get() = this
}

/** Découpage d'un flux d'octets en trames complètes et vérifiées. */
interface FrameParser {
    fun feed(data: ByteArray, length: Int = data.size, out: MutableList<ByteArray> = ArrayList(2)): MutableList<ByteArray>
    fun reset()
    val invalidFrames: Long
    val discardedBytes: Long
}

/** Monsieur Cuisine Connect : protocole d'origine de Toque, inchangé. */
object ConnectCodec : BoardCodec {
    override val id: String get() = "connect"
    override fun newRxParser(): FrameParser = MccStreamParser(Mcc.RX_SIZE)
    override fun decodeRx(raw: ByteArray): Telemetry = Mcc.decodeRx(raw)
    override fun encodeTx(
        operation: Operation, speed: Int, temperatureLevel: Int, direction: Direction, tare: Boolean, durationS: Int,
        program: SmartProgram?, smartLevel: Int?, programCelsius: Int?,
    ): ByteArray {
        require(program == null && smartLevel == null && programCelsius == null) { "réglage propre au Monsieur Cuisine Smart" }
        return Mcc.encodeTx(operation, speed, temperatureLevel, direction, tare)
    }

    override fun stopFrame(direction: Direction): ByteArray = Mcc.encodeTx(Operation.STOP, 0, 0, direction, false)
    override fun supports(operation: Operation): Boolean = true
    override val stopSettleMs: Long get() = 0
    override val tareOnlyAtRest: Boolean get() = false
    override fun errorMessage(code: Int): String = Mcc.boardErrorMessage(code)
}
