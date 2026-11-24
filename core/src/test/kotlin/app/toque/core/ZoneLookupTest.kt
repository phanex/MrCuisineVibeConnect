package app.toque.core

import app.toque.core.time.ZoneLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneLookupTest {
    @Test
    fun readsTheZoneOfEachProvider() {
        assertEquals("Europe/Paris", ZoneLookup.zoneOf("""{"success":true,"timezone":{"id":"Europe/Paris","abbr":"CEST","utc":"+02:00"}}"""))
        assertEquals("Europe/Brussels", ZoneLookup.zoneOf("""{"ip":"1.2.3.4","country":"Belgium","timezone":"Europe/Brussels"}"""))
        assertEquals("Indian/Reunion", ZoneLookup.zoneOf("""{"status":"success","timezone":"Indian/Reunion"}"""))
        assertEquals("America/Argentina/Buenos_Aires", ZoneLookup.zoneOf("""{"timezone":"America/Argentina/Buenos_Aires"}"""))
    }

    @Test
    fun rejectsFailuresAndNonsense() {
        assertNull(ZoneLookup.zoneOf("""{"success":false,"message":"Reserved range"}"""))
        assertNull(ZoneLookup.zoneOf("""{"status":"fail","timezone":"Europe/Paris"}"""))
        assertNull(ZoneLookup.zoneOf("""{"timezone":"; rm -rf /"}"""))
        assertNull(ZoneLookup.zoneOf("""{"timezone":"Europe/Paris'; reboot"}"""))
        assertNull(ZoneLookup.zoneOf("pas du json"))
        assertNull(ZoneLookup.zoneOf("""{"timezone":""}"""))
    }

    @Test
    fun defersHttpsProvidersUntilTheClockIsPlausible() {
        val now = 0L // 1er janvier 1970 : pas de pile d'horloge
        assertTrue(ZoneLookup.PROVIDERS.any { !it.url.startsWith("https:") })
        for (p in ZoneLookup.PROVIDERS) {
            assertEquals("${p.name} à l'horloge fausse", !p.url.startsWith("https:"), ZoneLookup.usable(p.url, now))
        }
    }

    @Test
    fun usesEveryProviderOnceTheClockIsPlausible() {
        val now = 1_800_000_000_000L
        for (p in ZoneLookup.PROVIDERS) assertTrue("${p.name} à l'horloge juste", ZoneLookup.usable(p.url, now))
    }
}
