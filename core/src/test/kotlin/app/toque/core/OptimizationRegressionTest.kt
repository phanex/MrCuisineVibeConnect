package app.toque.core

import app.toque.core.json.Json
import app.toque.core.machine.JobState
import app.toque.core.machine.LinkState
import app.toque.core.machine.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests de non-régression pour les correctifs et optimisations du cœur. */
class OptimizationRegressionTest {

    @Test
    fun companionStateReportsSmart40CFromJobTempC() {
        val f = HubFixture()
        val token = f.pair()
        val phone = f.hub.pairing.authenticate(token)!!
        // Sur Smart, 40 °C correspond à jobTempLevel = 1 (37 °C dans la table Connect), mais jobTempC = 40.
        val snap = Snapshot(
            link = LinkState.READY,
            hasSample = true,
            actualTempC = 38,
            bowlDetected = true,
            jobActive = true,
            jobKind = "MANUAL",
            jobLabel = "Cuisson manuelle",
            jobState = JobState.RUNNING.name,
            phaseCount = 1,
            totalMs = 60_000,
            remainingMs = 50_000,
            elapsedMs = 10_000,
            jobSpeed = 2,
            jobTempLevel = 1,
            jobTempC = 40,
        )
        f.hub.updateMachine(snap)
        val state = f.hub.state(phone)
        assertNotNull(state.job)
        assertEquals(40, state.job!!.tempC)
    }

    @Test
    fun jsonFastPathsHandleEscapesUnicodeAndBoundaryIntegers() {
        val payload = linkedMapOf<String, Any?>(
            "plain" to "Soupe de potimarron",
            "escaped" to "Ligne 1\n\"citation\"\t\\fin\u0007\u001F",
            "zero" to 0L,
            "neg" to -42L,
            "max18" to 999_999_999_999_999_999L,
            "min18" to -999_999_999_999_999_999L,
            "maxLong" to Long.MAX_VALUE,
            "minLong" to Long.MIN_VALUE,
            "real" to 3.14159,
            "list" to listOf(1, 2, 3),
        )
        val written = Json.write(payload)
        assertTrue(written.contains("\\u0007"))
        assertTrue(written.contains("\\u001f"))

        @Suppress("UNCHECKED_CAST")
        val parsed = Json.parse(written) as Map<String, Any?>
        assertEquals("Soupe de potimarron", parsed["plain"])
        assertEquals("Ligne 1\n\"citation\"\t\\fin\u0007\u001F", parsed["escaped"])
        assertEquals(0L, parsed["zero"])
        assertEquals(-42L, parsed["neg"])
        assertEquals(999_999_999_999_999_999L, parsed["max18"])
        assertEquals(-999_999_999_999_999_999L, parsed["min18"])
        assertEquals(Long.MAX_VALUE, parsed["maxLong"])
        assertEquals(Long.MIN_VALUE, parsed["minLong"])
        assertEquals(3.14159, parsed["real"])

        // Échappement \uXXXX en minuscules et majuscules
        assertEquals("é€", Json.parse("\"\\u00e9\\u20AC\""))
    }
}
