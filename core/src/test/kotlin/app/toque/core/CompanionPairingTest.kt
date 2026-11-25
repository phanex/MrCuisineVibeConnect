package app.toque.core

import app.toque.core.companion.MemoryStore
import app.toque.core.companion.PairingManager
import app.toque.core.companion.PairingPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class CompanionPairingTest {
    private val clock = FakeCompanionClock()
    private val store = MemoryStore()
    private fun manager(seed: Long = 1) = PairingManager(clock, Random(seed), store)

    @Test fun pairsWithTheCodeOnceAndGivesEachPhoneItsOwnToken() {
        val m = manager()
        var pairingEvents = 0
        var phoneEvents = 0
        m.onPairingChanged { pairingEvents++ }
        m.onPhonesChanged { phoneEvents++ }
        val o = m.open()
        assertEquals(6, o.code.length)
        assertTrue(o.code.all { it.isDigit() })
        assertEquals(32, o.secret.length)
        val r = m.pairWithCode(o.code)
        assertTrue(r.message, r.ok)
        assertEquals(64, r.token!!.length)
        assertEquals("Téléphone 1", r.phone!!.name)
        assertEquals("code", r.phone!!.method)
        // Le code est consommé.
        assertEquals("pairing_closed", m.pairWithCode(o.code).error)
        assertFalse(m.isOpen)
        // Deuxième téléphone, par le secret du QR code.
        val o2 = m.open()
        val r2 = m.pairWithSecret(o2.secret)
        assertTrue(r2.ok)
        assertEquals("qr", r2.phone!!.method)
        assertEquals(2, r2.phone!!.number)
        assertNotEquals(r.token, r2.token)
        assertEquals("pairing_closed", m.pairWithSecret(o2.secret).error)
        assertEquals(r.phone!!.id, m.authenticate(r.token)!!.id)
        assertEquals(r2.phone!!.id, m.authenticate(r2.token)!!.id)
        assertEquals(2, phoneEvents)
        assertTrue(pairingEvents >= 4)
    }

    @Test fun codesExpireAfterTwoMinutes() {
        val m = manager()
        val o = m.open()
        clock.advance(119_000)
        assertTrue(m.isOpen)
        clock.advance(1_000)
        assertFalse(m.isOpen)
        assertEquals("code_expired", m.pairWithCode(o.code).error)
        // « Nouveau code » : un autre code, valable de nouveau.
        val o2 = m.open()
        assertTrue(m.pairWithCode(o2.code).ok)
    }

    @Test fun fiveWrongAttemptsKillTheCode() {
        val m = manager()
        val o = m.open()
        val wrong = if (o.code == "000000") "000001" else "000000"
        repeat(4) { assertEquals("wrong_code", m.pairWithCode(wrong).error) }
        assertEquals("code_dead", m.pairWithCode(wrong).error)
        assertEquals("code_dead", m.pairWithCode(o.code).error)
        assertEquals("code_dead", m.pairWithSecret(o.secret).error)
        assertFalse(m.isOpen)
        // Les essais faux sur le secret comptent aussi.
        val o2 = m.open()
        repeat(5) { m.pairWithSecret("0".repeat(32)) }
        assertFalse(m.pairWithSecret(o2.secret).ok)
    }

    @Test fun tokensAreCheckedByHashAndPhonesCanBeRemoved() {
        val m = manager()
        val t1 = m.pairWithCode(m.open().code).token!!
        val t2 = m.pairWithCode(m.open().code).token!!
        assertNull(m.authenticate(null))
        assertNull(m.authenticate(""))
        assertNull(m.authenticate("0".repeat(64)))
        assertNull(m.authenticate(t1.uppercase()))
        assertNull(m.authenticate(t1 + "0"))
        // Le jeton n'est jamais enregistré, seulement son empreinte.
        val saved = store.text!!
        assertFalse(saved.contains(t1))
        assertTrue(saved.contains(PairingManager.sha256Hex(t1)))
        val p1 = m.authenticate(t1)!!
        assertTrue(m.remove(p1.id))
        assertNull(m.authenticate(t1))
        assertNotNull(m.authenticate(t2))
        // Le numéro libéré est réutilisé.
        val r3 = m.pairWithCode(m.open().code)
        assertEquals(1, r3.phone!!.number)
        assertTrue(PairingManager.constantTimeEquals("abc", "abc"))
        assertFalse(PairingManager.constantTimeEquals("abc", "abd"))
        assertFalse(PairingManager.constantTimeEquals("abc", "abcd"))
    }

    @Test fun phonesAndRobotIdSurviveARestart() {
        val m = manager()
        val id = m.robotId
        assertEquals(12, id.length)
        val t = m.pairWithCode(m.open().code).token!!
        val again = manager(seed = 99)
        assertEquals(id, again.robotId)
        assertNotNull(again.authenticate(t))
        assertEquals(1, again.list.size)
        // Un stockage illisible ne fait pas tomber l'appli.
        store.text = "{pas du json"
        val broken = manager()
        assertTrue(broken.list.isEmpty())
        assertEquals(12, broken.robotId.length)
    }

    @Test fun limitsTheNumberOfPhones() {
        val m = PairingManager(clock, Random(3), store, maxPhones = 2)
        assertTrue(m.pairWithCode(m.open().code).ok)
        assertTrue(m.pairWithCode(m.open().code).ok)
        assertEquals("too_many_phones", m.pairWithCode(m.open().code).error)
    }

    @Test fun robotQrPayloadLeavesTheNameOut() {
        val p = PairingPayload("192.168.0.12", 8088, "0123456789abcdef0123456789abcdef", "a1b2c3d4e5f6", "")
        val text = p.format()
        assertEquals("toque://pair?v=1&h=192.168.0.12&p=8088&s=0123456789abcdef0123456789abcdef&r=a1b2c3d4e5f6", text)
        assertEquals(p, PairingPayload.parse(text))
        // Adresse et port les plus longs : toujours un QR code version 5 au niveau L (37 modules).
        val longest = p.copy(host = "255.255.255.255", port = 65535).format()
        assertTrue(longest.length <= 95)
        assertEquals(5, app.toque.core.qr.QrCode.encode(longest, app.toque.core.qr.QrCode.ErrorCorrection.L).version)
        // Celui du hub, tel qu'il s'affiche sur le robot.
        val f = HubFixture()
        f.hub.updateRobot(app.toque.core.companion.RobotInfo(name = "Monsieur Cuisine Connect"))
        f.hub.pairing.open()
        f.hub.start(0, java.net.InetAddress.getLoopbackAddress())
        try {
            val qr = f.hub.pairingPayload("192.168.0.12")!!
            assertTrue(qr, !qr.contains("&n="))
            val parsed = PairingPayload.parse(qr)!!
            assertEquals("", parsed.name)
            assertEquals(f.hub.pairing.robotId, parsed.robotId)
            assertEquals(f.hub.pairing.current!!.secret, parsed.secret)
        } finally {
            f.hub.stop()
        }
    }

    @Test fun qrPayloadIsShortAndRoundTrips() {
        val p = PairingPayload("192.168.0.12", 8088, "0123456789abcdef0123456789abcdef", "a1b2c3d4e5f6", "Monsieur Cuisine Connect")
        val text = p.format()
        assertEquals("toque://pair?v=1&h=192.168.0.12&p=8088&s=0123456789abcdef0123456789abcdef&r=a1b2c3d4e5f6&n=Monsieur%20Cuisine%20Connect", text)
        assertTrue(text.length <= 130)
        assertEquals(p, PairingPayload.parse(text))
        // Nom long, accentué, avec des caractères réservés : tronqué, toujours sous 200 octets.
        val odd = p.copy(name = "Cuisine d'Élodie & Hugo + amis #1 — robot de la maison")
        val t2 = odd.format()
        assertTrue(t2.length <= PairingPayload.MAX_LENGTH)
        assertEquals(odd.name.take(PairingPayload.MAX_NAME), PairingPayload.parse(t2)!!.name)
        for (bad in listOf("", "http://192.168.0.12/", text.replace("v=1", "v=x"), text.replace("192.168.0.12", "example.com"),
            text.replace("8088", "70000"), text.replace("&s=0123", "&s=zz23"), text.replace("&r=a1b2c3d4e5f6", ""),
            text.replace("192.168.0.12", "192.168.0.300"), text + "x".repeat(200)))
            assertNull(bad, PairingPayload.parse(bad))
    }
}
