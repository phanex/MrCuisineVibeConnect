package app.toque.core.machine

import app.toque.core.protocol.BoardCodec
import app.toque.core.protocol.ConnectCodec
import app.toque.core.protocol.Direction
import app.toque.core.protocol.Mcc
import app.toque.core.protocol.Operation
import app.toque.core.protocol.Smart
import app.toque.core.protocol.SmartProgram

/**
 * Démo d'un robot autre que le Connect. La base simulée ([SimBoard]) parle le protocole du
 * Connect, mais Toque doit accepter et refuser exactement ce que le robot choisi accepterait :
 * les règles viennent du codec de ce robot ([robot]), les octets de [ConnectCodec].
 *
 * Un programme du Smart est simulé par la cuisson la plus proche (fonction de la base, vitesse,
 * température, sens) : la démo montre le déroulé dans Toque, pas le comportement exact de la base.
 * Rien ici n'est envoyé à une vraie base : ce codec ne sert qu'avec [SimTransport].
 */
class DemoCodec(val robot: BoardCodec) : BoardCodec by ConnectCodec {
    override val id: String get() = "demo-" + robot.id
    override val rules: BoardCodec get() = robot

    override fun supports(operation: Operation): Boolean = robot.supports(operation)

    override fun accepts(
        operation: Operation, speed: Int, temperatureLevel: Int, direction: Direction,
        program: SmartProgram?, smartLevel: Int?, programCelsius: Int?,
    ): Boolean = robot.accepts(operation, speed, temperatureLevel, direction, program, smartLevel, programCelsius)

    override val stopSettleMs: Long get() = robot.stopSettleMs
    override val tareOnlyAtRest: Boolean get() = robot.tareOnlyAtRest
    override val temperatureReliable: Boolean get() = robot.temperatureReliable

    override fun encodeTx(
        operation: Operation, speed: Int, temperatureLevel: Int, direction: Direction, tare: Boolean, durationS: Int,
        program: SmartProgram?, smartLevel: Int?, programCelsius: Int?,
    ): ByteArray {
        if (program != null) {
            val level = Mcc.levelAtMost(programCelsius ?: program.targetCelsius)
            return Mcc.encodeTx(program.operation, program.speed.coerceIn(0, 10), level, program.direction, tare)
        }
        val level = if (smartLevel != null) Smart.toqueLevelFor(smartLevel) else temperatureLevel
        return Mcc.encodeTx(operation, speed, level, direction, tare)
    }

    companion object {
        /** Codec de la base simulée pour le robot dont [robotCodec] est le codec. */
        fun forRobot(robotCodec: BoardCodec): BoardCodec =
            if (robotCodec === ConnectCodec) ConnectCodec else DemoCodec(robotCodec)
    }
}
