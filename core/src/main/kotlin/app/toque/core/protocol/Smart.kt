/*
 * Protocole série de la carte de puissance du Monsieur Cuisine Smart (SKMS 1200A1).
 *
 * Source : analyse de l'appli d'origine com.tecpal.device.mc30 2.21.0.0 et 5 captures réelles
 * du 2026-11-20 (voir docs/SMART.md et core/src/test/resources/smart/).
 *
 * Liaison : /dev/ttyMT1, 9600 bauds 8N1 brut. Android émet une trame toutes les ~200 ms ; la
 * carte répond ~100 ms après avec le même numéro de séquence et n'émet jamais d'elle-même.
 * La carte ne décompte pas la durée : c'est Android qui renvoie la commande pendant tout le
 * programme puis envoie STOP.
 *
 * Trame : AA, L (16 bits gros-boutiste, = indice de la somme), en-tête, séquence, charge utile,
 * somme des octets 1..L-1. Toque n'envoie que les commandes 08 02 (réglage / STOP) et 08 03
 * (tare). Les commandes FE xx (mise à jour du micrologiciel de la carte) sont impossibles à
 * produire avec ce code : l'octet de commande principal est toujours 08.
 */
package app.toque.core.protocol

import app.toque.core.i18n.tr

object Smart {
    const val TX_SIZE = 38
    const val RX_SIZE = 53
    const val TX_L = TX_SIZE - 1
    const val RX_L = RX_SIZE - 1
    const val START = 0xAA
    const val CMD_MAIN = 0x08
    const val CMD_ACCESS = 0x01
    const val CMD_SET = 0x02
    const val CMD_TARE = 0x03
    /** La séquence 102 est sautée par l'appli d'origine (r7.a) : on fait pareil. */
    const val SKIPPED_SEQ = 102

    /** En-têtes fixes, octets 3..6. */
    private val TX_HEADER = intArrayOf(0x02, 0x4F, 0x42, 0x00)
    private val RX_HEADER = intArrayOf(0x01, 0x0F, 0x00, 0x00)

    /** Niveaux de température du Smart (index = niveau envoyé) : 21 niveaux, avec 40 °C. */
    val TEMPERATURE_LEVELS: IntArray = intArrayOf(
        0, 37, 40, 45, 50, 55, 60, 65, 70, 75, 80,
        85, 90, 95, 100, 105, 110, 115, 120, 125, 130,
    )

    /** Codes de fonction (octet 19 TX, 28 RX), vus dans les captures du 2026-11-20. */
    const val FN_STOP = 0
    const val FN_MANUAL = 1
    const val FN_STEAM = 3
    const val FN_TOAST = 4
    const val FN_KNEAD = 5
    const val FN_TURBO = 0xC6

    /**
     * Turbo : trame courte de l'ancien format (`o7.a` de l'appli d'origine), 35 octets.
     * Modules : CMD 08 02, température (6 × 0), moteur (00 00 0A), fonction C6, balance (2 × 0),
     * minuterie 9 octets (durée fixe 00:00:05), lumière 0, bouton 0, puis un octet 06.
     * Elle a son propre compteur de séquence et est renvoyée telle quelle pendant tout l'appui.
     */
    const val TURBO_SIZE = 35
    const val TURBO_L = TURBO_SIZE - 1
    const val TURBO_MOTOR_BYTE = 18
    const val TURBO_MOTOR_VALUE = 10
    const val TURBO_SECONDS = 5
    const val TURBO_TAIL_BYTE = 33
    const val TURBO_TAIL_VALUE = 6

    /** Niveaux Smart des programmes capturés (index de [TEMPERATURE_LEVELS]). */
    const val LEVEL_120C = 18
    const val LEVEL_130C = 20

    /** Niveau Smart pour un niveau de la table de Toque (celle du Connect, sans 40 °C). */
    fun levelForToqueLevel(toqueLevel: Int): Int {
        require(toqueLevel in 0..19) { "niveau de température hors plage 0..19" }
        val c = Mcc.celsiusForLevel(toqueLevel)
        val i = TEMPERATURE_LEVELS.indexOf(c)
        check(i >= 0)
        return i
    }

    fun celsiusForLevel(level: Int): Int = if (level in TEMPERATURE_LEVELS.indices) TEMPERATURE_LEVELS[level] else 0

    /**
     * Niveau de la table de Toque qui représente un niveau Smart pour les règles de sécurité : le
     * même en °C, ou le plus proche en dessous (40 °C, propre au Smart, est traité comme 37 °C).
     */
    fun toqueLevelFor(smartLevel: Int): Int {
        require(smartLevel in TEMPERATURE_LEVELS.indices) { "niveau de température hors plage 0..20" }
        return Mcc.levelAtMost(celsiusForLevel(smartLevel))
    }

    /** Somme des octets 1..end-1, sur 8 bits. */
    fun checksum(raw: ByteArray, end: Int): Int {
        var t = 0
        for (i in 1 until end) t = (t + (raw[i].toInt() and 0xFF)) and 0xFF
        return t
    }

    fun nextSeq(seq: Int): Int {
        val n = (seq + 1) and 0xFF
        return if (n == SKIPPED_SEQ) n + 1 else n
    }

    /**
     * Construit une trame de commande 38 octets. Toutes les valeurs sont vérifiées ; rien
     * n'est corrigé en silence.
     */
    fun buildTx(
        seq: Int,
        cmd: Int = CMD_SET,
        temperatureLevel: Int = 0,
        speedHalfSteps: Int = 0,
        reverse: Boolean = false,
        function: Int = FN_STOP,
        tare: Boolean = false,
        hours: Int = 0,
        minutes: Int = 0,
        seconds: Int = 0,
        /** Octet 12 : température en °C des programmes automatiques (œufs, sous vide, riz…). */
        celsius: Int = 0,
    ): ByteArray {
        require(seq in 0..255) { "séquence hors plage" }
        require(cmd == CMD_SET || cmd == CMD_TARE || cmd == CMD_ACCESS) { "commande non autorisée" }
        require(temperatureLevel in TEMPERATURE_LEVELS.indices) { "niveau de température hors plage 0..20" }
        require(celsius in 0..130) { "température en °C hors plage 0..130" }
        require(speedHalfSteps in 0..20) { "vitesse hors plage 0..20 (demi-pas)" }
        require(function in 0..15) { "fonction hors plage" }
        require(hours in 0..99 && minutes in 0..59 && seconds in 0..59) { "durée hors plage" }
        val r = ByteArray(TX_SIZE)
        r[0] = START.toByte()
        r[1] = 0
        r[2] = TX_L.toByte()
        for (i in 0..3) r[3 + i] = TX_HEADER[i].toByte()
        r[7] = seq.toByte()
        r[8] = CMD_MAIN.toByte()
        r[9] = cmd.toByte()
        r[10] = temperatureLevel.toByte()
        r[12] = celsius.toByte()
        r[16] = speedHalfSteps.toByte()
        r[17] = (if (reverse) 1 else 0).toByte()
        r[19] = function.toByte()
        r[21] = (if (tare) 1 else 0).toByte()
        r[25] = hours.toByte()
        r[26] = minutes.toByte()
        r[27] = seconds.toByte()
        r[TX_L] = checksum(r, TX_L).toByte()
        return r
    }

    /**
     * Trame Turbo, octet pour octet comme l'appli d'origine (capture 08-turbo du 2026-11-20).
     * Aucun paramètre : la vitesse, la fonction et la durée sont fixes.
     */
    fun buildTurbo(seq: Int): ByteArray {
        require(seq in 0..255) { "séquence hors plage" }
        val r = ByteArray(TURBO_SIZE)
        r[0] = START.toByte()
        r[1] = 0
        r[2] = TURBO_L.toByte()
        for (i in 0..3) r[3 + i] = TX_HEADER[i].toByte()
        r[7] = seq.toByte()
        r[8] = CMD_MAIN.toByte()
        r[9] = CMD_SET.toByte()
        r[TURBO_MOTOR_BYTE] = TURBO_MOTOR_VALUE.toByte()
        r[19] = FN_TURBO.toByte()
        r[27] = TURBO_SECONDS.toByte()
        r[TURBO_TAIL_BYTE] = TURBO_TAIL_VALUE.toByte()
        r[TURBO_L] = checksum(r, TURBO_L).toByte()
        return r
    }

    /** Vrai pour une trame Turbo complète et conforme (taille, en-tête, contenu fixe, somme). */
    fun isTurbo(raw: ByteArray): Boolean {
        if (raw.size != TURBO_SIZE) return false
        return try {
            validate(raw, TURBO_SIZE)
            raw.contentEquals(buildTurbo(u(raw, 7)))
        } catch (_: MccProtocolException) {
            false
        }
    }

    private fun u(r: ByteArray, i: Int) = r[i].toInt() and 0xFF

    /** Vérifie une trame complète (longueur, en-tête, commande, somme). */
    fun validate(raw: ByteArray, size: Int) {
        val l = size - 1
        if (raw.size != size) throw MccProtocolException("longueur de trame")
        if (u(raw, 0) != START) throw MccProtocolException("octet de début")
        if (((u(raw, 1) shl 8) or u(raw, 2)) != l) throw MccProtocolException("octet de longueur")
        val header = if (size == RX_SIZE) RX_HEADER else TX_HEADER
        for (i in 0..3) if (u(raw, 3 + i) != header[i]) throw MccProtocolException("en-tête")
        if (u(raw, 8) != CMD_MAIN) throw MccProtocolException("commande inconnue")
        if (u(raw, l) != checksum(raw, l)) throw MccProtocolException("somme de contrôle")
    }

    /** Décodage d'une trame de commande (tests, base simulée). */
    class Command(
        val seq: Int, val cmd: Int, val temperatureLevel: Int, val speedHalfSteps: Int, val reverse: Boolean,
        val function: Int, val tare: Boolean, val hours: Int, val minutes: Int, val seconds: Int,
        /** Octets qui devraient être nuls et ne le sont pas (indices). */
        val unexpectedBytes: List<Int>,
        /** Octet 12 : température en °C (programmes automatiques), 0 sinon. */
        val celsius: Int = 0,
    )

    /** Décode une trame de commande de 38 octets (pas la trame Turbo : voir [isTurbo]). */
    fun decodeTx(raw: ByteArray): Command {
        validate(raw, TX_SIZE)
        val known = setOf(10, 12, 16, 17, 19, 21, 25, 26, 27)
        val odd = (9 until TX_L).filter { it != 9 && it !in known && raw[it].toInt() != 0 }
        return Command(
            seq = u(raw, 7), cmd = u(raw, 9), temperatureLevel = u(raw, 10), speedHalfSteps = u(raw, 16),
            reverse = u(raw, 17) == 1, function = u(raw, 19), tare = u(raw, 21) == 1,
            hours = u(raw, 25), minutes = u(raw, 26), seconds = u(raw, 27), unexpectedBytes = odd,
            celsius = u(raw, 12),
        )
    }

    fun rxSeq(raw: ByteArray): Int = u(raw, 7)

    /**
     * Décode une trame d'état 53 octets en [Telemetry] (mêmes champs que pour le Connect).
     * [toqueError] : erreur constatée par Toque sur cette réponse ([SmartCodec.responseError]),
     * signalée comme une erreur de la base si la base n'en signale pas elle-même (octet 15).
     */
    fun decodeRx(raw: ByteArray, toqueError: Int = 0): Telemetry {
        validate(raw, RX_SIZE)
        fun word(i: Int) = (u(raw, i) shl 8) or u(raw, i + 1)
        fun s16(i: Int) = word(i).toShort().toInt()
        val status = u(raw, 12)
        val errorCode = u(raw, 15).let { if (it != 0) it else toqueError }
        val levelEcho = u(raw, 17)
        val speedEcho = u(raw, 25)
        val functionEcho = u(raw, 28)
        // Turbo : la vitesse [25] reste à 0, c'est la « touche turbo » [27] qui vaut 10 (capture 08-turbo).
        val turboEcho = u(raw, 27)
        val motorOn = functionEcho != FN_STOP && (speedEcho > 0 || turboEcho > 0)
        val heaterOn = functionEcho != FN_STOP && levelEcho > 0
        val warnings = ArrayList<String>(0)
        if (status and 0x0F != 0) warnings += "Bits d'état 0-3 inattendus"
        if (status and 0x10 != 0) warnings += "Bit d'erreur"
        if (levelEcho !in TEMPERATURE_LEVELS.indices) warnings += "Niveau de température hors table"
        if (speedEcho > 20) warnings += "Vitesse hors 0..20"
        if (turboEcho != 0 && turboEcho != TURBO_MOTOR_VALUE) warnings += "Touche turbo inattendue"
        if (u(raw, 9) !in intArrayOf(CMD_ACCESS, CMD_SET, CMD_TARE)) warnings += "Commande répondue inconnue"
        for (i in 47..51) if (raw[i].toInt() != 0) { warnings += "Octets 47-51 non nuls"; break }
        val weight = (u(raw, 30) shl 24) or (u(raw, 31) shl 16) or (u(raw, 32) shl 8) or u(raw, 33)
        return Telemetry(
            raw = raw.copyOf(),
            status = status,
            motorHeaterState = when {
                motorOn && heaterOn -> 1
                heaterOn -> 2
                motorOn -> 3
                else -> 0
            },
            motorOn = motorOn,
            heaterOn = heaterOn,
            // Le Smart ne signale pas la stabilité de la balance : poids toujours utilisable.
            scaleStable = true,
            direction = if (u(raw, 26) == 1) Direction.COUNTER_CLOCKWISE else Direction.CLOCKWISE,
            errorDetected = errorCode != 0 || status and 0x10 != 0,
            errorCode = errorCode,
            androidErrorCode = errorCode,
            aboveTargetTemperature = status and 0x20 != 0,
            // Bit 6 = bol ABSENT sur le Smart (l'inverse du Connect).
            bowlNtcDetected = status and 0x40 == 0,
            lidOpen = status and 0x80 != 0,
            extendedState = functionEcho,
            targetSpeedLevel = if (turboEcho != 0) 10 else speedEcho / 2,
            targetRpm = 0,
            actualRpm = 0,
            targetTemperatureLevel = levelEcho,
            targetTemperature = celsiusForLevel(levelEcho),
            actualTemperature = s16(20),
            weight = weight,
            firmwareVersion = word(10),
            scaleStateRaw = u(raw, 34),
            scaleState = "normal",
            calibrationStep = u(raw, 35),
            unknownByte24 = 0,
            warnings = warnings,
            reportedSpeedLevel = if (turboEcho != 0) 10 else speedEcho / 2,
        )
    }

    /** Octet 16 de la réponse : code retour de la base (`returnCode` de l'appli d'origine), 0 = commande acceptée. */
    const val RX_RETURN_CODE = 16

    /** Bits de l'octet d'état 12 : couvercle ouvert (0x80), bol absent (0x40). */
    const val STATUS_LID_OR_BOWL = 0xC0

    /**
     * Codes d'erreur propres à Toque, au-delà de ceux de la base (0..255) : la base a refusé la
     * commande (+ son code retour), n'a pas repris la commande envoyée, ou répond à des
     * séquences que Toque n'a pas envoyées.
     */
    const val ERR_REFUSED = 0x100
    const val ERR_ECHO = 0x200
    const val ERR_FOREIGN = 0x201

    /**
     * La réponse [rx] reprend-elle la commande [tx] (même séquence) ? Règles vérifiées sur toutes
     * les captures (plus de 21 000 réponses, appli d'origine et Toque) :
     *  - commande (02 réglage, 03 tare), niveau de température et vitesse en demi-pas : identiques ;
     *  - fonction : la base renvoie 1 pour toute fonction active, 0 à l'arrêt ;
     *  - sens : seulement lame en marche (à l'arrêt, la base garde le dernier sens) ;
     *  - Turbo : touche turbo à 10, vitesse et niveau à 0, fonction active.
     * Comparaison faite comme Camybrith Cooking (Tidusneo24150, issue #59).
     */
    fun echoes(tx: ByteArray, rx: ByteArray): Boolean {
        if (u(rx, 9) != u(tx, 9)) return false
        if (tx.size == TURBO_SIZE)
            return u(rx, 27) == TURBO_MOTOR_VALUE && u(rx, 25) == 0 && u(rx, 17) == 0 && u(rx, 28) != FN_STOP
        if (u(rx, 17) != u(tx, 10) || u(rx, 25) != u(tx, 16)) return false
        if ((u(rx, 28) != FN_STOP) != (u(tx, 19) != FN_STOP)) return false
        return u(tx, 16) == 0 || u(rx, 26) == u(tx, 17)
    }

    fun errorMessage(code: Int): String = when {
        code == ERR_ECHO -> tr("La base n'a pas repris la commande envoyée : tout est arrêté")
        code == ERR_FOREIGN -> tr("La base répond à des commandes qui ne viennent pas de Toque : tout est arrêté")
        code in ERR_REFUSED until ERR_ECHO -> tr("La base a refusé la commande (code retour %d) : tout est arrêté", code - ERR_REFUSED)
        else -> tr("Erreur de la base (code %d)", code)
    }
}

/**
 * Découpe le flux reçu (ou émis) en trames Smart : réassemble les fragments, accepte les
 * trames collées, se resynchronise après des octets corrompus.
 */
class SmartStreamParser(private val size: Int = Smart.RX_SIZE) : FrameParser {
    init {
        require(size == Smart.RX_SIZE || size == Smart.TX_SIZE)
    }

    private val buffer = ByteArray(size)
    private var count = 0
    override var discardedBytes = 0L
        private set
    override var invalidFrames = 0L
        private set

    override fun reset() {
        count = 0
    }

    private fun dropFirst() {
        System.arraycopy(buffer, 1, buffer, 0, count - 1)
        count--
        discardedBytes++
    }

    override fun feed(data: ByteArray, length: Int, out: MutableList<ByteArray>): MutableList<ByteArray> {
        val l = size - 1
        for (i in 0 until length) {
            buffer[count++] = data[i]
            while (count > 0) {
                if ((buffer[0].toInt() and 0xFF) != Smart.START) { dropFirst(); continue }
                if (count < 2) break
                if (buffer[1].toInt() != (l shr 8)) { invalidFrames++; dropFirst(); continue }
                if (count < 3) break
                if ((buffer[2].toInt() and 0xFF) != (l and 0xFF)) { invalidFrames++; dropFirst(); continue }
                if (count < size) break
                val candidate = buffer.copyOf(size)
                try {
                    Smart.validate(candidate, size)
                } catch (e: MccProtocolException) {
                    invalidFrames++
                    dropFirst()
                    continue
                }
                out.add(candidate)
                count = 0
            }
        }
        return out
    }
}

/**
 * Codec du Smart, avec la numérotation de l'appli d'origine : chaque nouvelle trame prend la
 * séquence suivante (102 sautée) ; pendant un programme, la même trame est renvoyée telle
 * quelle (même séquence) tant que le réglage ne change pas. Au repos, chaque STOP est une
 * nouvelle trame.
 *
 * Opérations autorisées, chacune avec les seuls paramètres vus dans les captures de l'appli
 * d'origine (2026-11-20, voir docs PROTOCOLE-SERIE-SMART.md § 3) :
 *  - STOP ; STANDARD (manuel : vitesse 1..10, niveau de température, sens), avec en plus les
 *    21 niveaux du Smart par [smartLevel] (40 °C) ;
 *  - STANDARD + [SmartProgram] : programme automatique capturé (mixer, smoothie, œufs, sous-vide,
 *    riz, chauffer l'eau), seulement avec la vitesse, le sens et la température de sa capture ;
 *    de même chaque étape capturée des programmes à étapes (mijoter, fermenter, prélavage,
 *    nettoyage long ; KNEAD et TOAST pour celles de Malaxer et de Saisir), repos compris
 *    (vitesse 0, fonction et chauffe gardées) ;
 *  - KNEAD (Malaxer) : fonction 5, vitesse 4, sans chauffe, l'un ou l'autre sens ;
 *  - STEAM (Cuisson vapeur) : fonction 3, 120 °C, vitesse 1, sens normal ;
 *  - TOAST (Saisir) : fonction 4, 130 °C, vitesse 1, sens inverse ;
 *  - TURBO : trame courte fixe [Smart.buildTurbo], avec son propre compteur de séquence.
 * Toute autre combinaison est refusée (IllegalArgumentException), jamais corrigée en silence.
 */
class SmartCodec(initialSeq: Int = 0, initialTurboSeq: Int = 0) : BoardCodec {
    private var seq = initialSeq and 0xFF
    private var turboSeq = initialTurboSeq and 0xFF
    private var lastActive: ByteArray? = null

    /** Dernières trames émises (la réponse arrive au plus 3 trames après la sienne dans les captures). */
    private val sent = arrayOfNulls<ByteArray>(SENT_HISTORY)
    private var sentNext = 0
    private var sentCount = 0
    private var unknownInARow = 0

    override val id: String get() = "smart"
    override fun newRxParser(): FrameParser = SmartStreamParser(Smart.RX_SIZE)

    /** Décode la réponse et vérifie qu'elle correspond à ce que Toque a envoyé ([responseError]). */
    override fun decodeRx(raw: ByteArray): Telemetry {
        Smart.validate(raw, Smart.RX_SIZE)
        return Smart.decodeRx(raw, responseError(raw))
    }

    /**
     * Erreur constatée sur une réponse valide, 0 si tout va bien (même règle que Camybrith
     * Cooking, retour de Tidusneo24150 sur l'issue #59) :
     *  - code retour (octet 16) non nul : la base a refusé la commande ;
     *  - réponse qui ne reprend pas la commande de même séquence ([Smart.echoes]) ;
     *  - 3 réponses de suite à des séquences que Toque n'a pas envoyées (une seule est tolérée :
     *    une réponse en retard juste après l'ouverture de la liaison).
     * Couvercle ouvert ou bol absent : rien n'est vérifié, la base refuse d'elle-même (code 3)
     * et le contrôleur met déjà le programme en pause.
     */
    @Synchronized
    fun responseError(rx: ByteArray): Int {
        if (rx[12].toInt() and Smart.STATUS_LID_OR_BOWL != 0) {
            unknownInARow = 0
            return 0
        }
        val rc = rx[Smart.RX_RETURN_CODE].toInt() and 0xFF
        if (rc != 0) return Smart.ERR_REFUSED + rc
        val s = rx[7]
        var known = false
        for (k in 1..sentCount) {
            val tx = sent[(sentNext - k + SENT_HISTORY) % SENT_HISTORY] ?: continue
            if (tx[7] != s) continue
            known = true
            if (Smart.echoes(tx, rx)) {
                unknownInARow = 0
                return 0
            }
        }
        if (known) {
            unknownInARow = 0
            return Smart.ERR_ECHO
        }
        if (sentCount == 0) return 0
        unknownInARow++
        return if (unknownInARow >= 3) Smart.ERR_FOREIGN else 0
    }

    /** Note une trame émise (même tableau, pas de copie : la boucle ne les modifie pas). */
    private fun remember(frame: ByteArray): ByteArray {
        sent[sentNext] = frame
        sentNext = (sentNext + 1) % SENT_HISTORY
        if (sentCount < SENT_HISTORY) sentCount++
        return frame
    }

    override fun supports(operation: Operation): Boolean = operation in SUPPORTED

    /** Vrai si [encodeTx] accepte ce réglage actif (mêmes règles, sans rien émettre). */
    override fun accepts(
        operation: Operation, speed: Int, temperatureLevel: Int, direction: Direction,
        program: SmartProgram?, smartLevel: Int?, programCelsius: Int?,
    ): Boolean = refusal(operation, speed, temperatureLevel, direction, program, smartLevel, programCelsius) == null

    private fun refusal(
        operation: Operation, speed: Int, temperatureLevel: Int, direction: Direction,
        program: SmartProgram? = null, smartLevel: Int? = null, programCelsius: Int? = null,
    ): String? {
        if (operation !in SUPPORTED) return "opération non prise en charge par le Smart : $operation"
        if (speed !in 0..10) return "vitesse hors plage 0..10"
        if (temperatureLevel !in 0..19) return "niveau de température hors plage 0..19"
        val cw = direction == Direction.CLOCKWISE
        if (program != null) {
            // Rien d'autre que la trame capturée : vitesse, sens et température du programme.
            if (operation != program.operation || smartLevel != null) return "programme ${program.name} : réglage incohérent"
            if (programCelsius != null && !program.allowsCelsius(programCelsius))
                return "programme ${program.name} : ${programCelsius} °C hors de la plage de l'appli d'origine"
            if (speed != program.speed || direction != program.direction ||
                temperatureLevel != program.toqueLevelFor(programCelsius ?: program.targetCelsius))
                return "programme ${program.name} : vitesse ${program.speed}, sens et température de l'appli d'origine seulement"
            return null
        }
        if (programCelsius != null) return "température exacte seulement avec un programme"
        if (smartLevel != null) {
            if (operation != Operation.STANDARD) return "niveau du Smart seulement en cuisson manuelle"
            if (smartLevel !in TEMPERATURE_LEVELS_RANGE) return "niveau de température hors plage 0..20"
            if (Smart.toqueLevelFor(smartLevel) != temperatureLevel) return "niveau du Smart incohérent avec le réglage"
            return if (speed > 0 || smartLevel > 0) null else "réglage sans effet"
        }
        return when (operation) {
            Operation.STOP -> null
            Operation.STANDARD -> if (speed > 0 || temperatureLevel > 0) null else "réglage sans effet"
            Operation.KNEAD -> if (speed == KNEAD_SPEED && temperatureLevel == 0) null
                else "Malaxer : vitesse $KNEAD_SPEED sans chauffe seulement"
            Operation.STEAM -> if (speed == 1 && temperatureLevel == TOQUE_LEVEL_120C && cw) null
                else "Vapeur : 120 °C, vitesse 1, sens normal seulement"
            Operation.TOAST -> if (speed == 1 && temperatureLevel == TOQUE_LEVEL_130C && !cw) null
                else "Saisir : 130 °C, vitesse 1, sens inverse seulement"
            Operation.TURBO -> if (speed == 10 && temperatureLevel == 0 && cw) null
                else "Turbo : vitesse 10, sans chauffe, sens normal seulement"
            else -> "opération non prise en charge par le Smart : $operation"
        }
    }
    /** Pas de mesure du régime : on laisse la lame s'arrêter avant de repartir ou d'inverser. */
    override val stopSettleMs: Long get() = 2_000
    override val tareOnlyAtRest: Boolean get() = true
    override fun errorMessage(code: Int): String = Smart.errorMessage(code)
    override val temperatureReliable: Boolean get() = false

    @Synchronized
    override fun encodeTx(
        operation: Operation, speed: Int, temperatureLevel: Int, direction: Direction, tare: Boolean, durationS: Int,
        program: SmartProgram?, smartLevel: Int?, programCelsius: Int?,
    ): ByteArray {
        refusal(operation, speed, temperatureLevel, direction, program, smartLevel, programCelsius)?.let { throw IllegalArgumentException(it) }
        if (operation == Operation.STOP) {
            lastActive = null
            seq = Smart.nextSeq(seq)
            return remember(Smart.buildTx(seq, cmd = if (tare) Smart.CMD_TARE else Smart.CMD_SET, tare = tare))
        }
        require(!tare) { "tare seulement au repos" }
        if (operation == Operation.TURBO) {
            // Comme l'appli d'origine : une trame construite au début de l'appui, renvoyée telle
            // quelle jusqu'au relâchement ; compteur de séquence à part, durée fixe 5 s.
            val last = lastActive
            if (last != null && last.size == Smart.TURBO_SIZE) return remember(last.copyOf())
            turboSeq = Smart.nextSeq(turboSeq)
            val f = Smart.buildTurbo(turboSeq)
            lastActive = f.copyOf()
            return remember(f)
        }
        val d = durationS.coerceIn(0, 99 * 3600 + 59 * 60 + 59)
        val candidate = if (program != null) Smart.buildTx(
            0,
            temperatureLevel = program.level,
            celsius = programCelsius ?: program.celsius,
            speedHalfSteps = program.speedHalfSteps,
            reverse = program.reverse,
            function = program.function,
            hours = d / 3600, minutes = d / 60 % 60, seconds = d % 60,
        ) else Smart.buildTx(
            0,
            temperatureLevel = smartLevel ?: Smart.levelForToqueLevel(temperatureLevel),
            speedHalfSteps = speed * 2,
            reverse = direction == Direction.COUNTER_CLOCKWISE,
            function = when (operation) {
                Operation.KNEAD -> Smart.FN_KNEAD
                Operation.STEAM -> Smart.FN_STEAM
                Operation.TOAST -> Smart.FN_TOAST
                else -> Smart.FN_MANUAL
            },
            hours = d / 3600, minutes = d / 60 % 60, seconds = d % 60,
        )
        val last = lastActive
        if (last != null && last.size == Smart.TX_SIZE && samePayload(last, candidate)) return remember(last.copyOf())
        seq = Smart.nextSeq(seq)
        candidate[7] = seq.toByte()
        candidate[Smart.TX_L] = Smart.checksum(candidate, Smart.TX_L).toByte()
        lastActive = candidate.copyOf()
        return remember(candidate)
    }

    @Synchronized
    override fun stopFrame(direction: Direction): ByteArray {
        lastActive = null
        seq = Smart.nextSeq(seq)
        return remember(Smart.buildTx(seq))
    }

    private fun samePayload(a: ByteArray, b: ByteArray): Boolean {
        for (i in 8 until Smart.TX_L) if (a[i] != b[i]) return false
        return true
    }

    companion object {
        private val SUPPORTED = setOf(
            Operation.STOP, Operation.STANDARD, Operation.KNEAD, Operation.STEAM, Operation.TOAST, Operation.TURBO,
        )
        /** Vitesse du programme Malaxer de l'appli d'origine (pâtes ferme, molle et liquide). */
        const val KNEAD_SPEED = 4
        /** Trames émises gardées pour vérifier les réponses. */
        private const val SENT_HISTORY = 8
        private val TEMPERATURE_LEVELS_RANGE = Smart.TEMPERATURE_LEVELS.indices

        /** Niveaux de la table de Toque (Connect) pour 120 °C et 130 °C. */
        const val TOQUE_LEVEL_120C = 17
        const val TOQUE_LEVEL_130C = 19
    }
}
