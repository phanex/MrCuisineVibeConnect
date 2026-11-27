package app.toque.core

import app.toque.core.machine.LinkState
import app.toque.core.machine.Snapshot
import app.toque.core.robot.RobotProfiles
import org.junit.Assert.assertEquals
import org.junit.Test

/** Température du bol affichée : « < 37 °C » pour le 0 d'une base qui mesure par paliers (#100). */
class BowlTempTextTest {
    private val connect = RobotProfiles.MC_CONNECT.capabilities
    private val smart = RobotProfiles.MC_SMART.capabilities

    @Test fun zeroWithLinkReadyIsBelowFirstStep() =
        assertEquals("< 37 °C", Snapshot(link = LinkState.READY, hasSample = true, actualTempC = 0).bowlTempText(connect))

    @Test fun measuredValueIsShownAsIs() =
        assertEquals("45 °C", Snapshot(link = LinkState.READY, hasSample = true, actualTempC = 45).bowlTempText(connect))

    @Test fun zeroWithoutReadyLinkStaysZero() =
        assertEquals("0 °C", Snapshot(link = LinkState.OFFLINE, actualTempC = 0).bowlTempText(connect))

    @Test fun baseThatDoesNotAlwaysMeasureKeepsRawValue() =
        assertEquals("0 °C", Snapshot(link = LinkState.READY, hasSample = true, actualTempC = 0).bowlTempText(smart))
}
