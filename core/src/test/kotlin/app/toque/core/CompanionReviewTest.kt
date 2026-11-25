package app.toque.core

import app.toque.core.companion.AdjustCommand
import app.toque.core.companion.AdjustRequest
import app.toque.core.companion.ApiError
import app.toque.core.companion.CommandResult
import app.toque.core.companion.CompanionApi
import app.toque.core.companion.CompanionHost
import app.toque.core.companion.CompanionHub
import app.toque.core.companion.CompanionProof
import app.toque.core.companion.HostCalls
import app.toque.core.companion.HttpHandler
import app.toque.core.companion.HttpLimits
import app.toque.core.companion.HttpParser
import app.toque.core.companion.HttpRequest
import app.toque.core.companion.HttpResponse
import app.toque.core.companion.HttpServer
import app.toque.core.companion.JournalPage
import app.toque.core.companion.MemoryStore
import app.toque.core.companion.PairingManager
import app.toque.core.companion.PairingStatus
import app.toque.core.companion.ProveReply
import app.toque.core.companion.StateWire
import app.toque.core.companion.client.CompanionClient
import app.toque.core.companion.client.RobotFinder
import app.toque.core.companion.client.RobotProofException
import app.toque.core.companion.client.StateFollower
import app.toque.core.json.Json
import app.toque.core.machine.EventType
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.Programs
import app.toque.core.protocol.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Régressions de la revue de sécurité du compagnon (chaque test reprend une preuve, retournée). */
class CompanionReviewTest {
    private val lo = InetAddress.getByName("127.0.0.1")
    private val okHandler = object : HttpHandler {
        override fun handle(request: HttpRequest) = HttpResponse.text(200, "ok")
    }

    private fun statusLine(input: InputStream): String {
        val sb = StringBuilder()
        try {
            while (true) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                sb.append(b.toChar())
            }
        } catch (_: IOException) {
        }
        return sb.toString().trim()
    }

    private fun from(src: String, port: Int): Socket {
        val s = Socket()
        s.bind(InetSocketAddress(InetAddress.getByName(src), 0))
        s.connect(InetSocketAddress(lo, port))
        return s
    }

    private fun get(port: Int, src: String = "127.0.0.1"): String = from(src, port).use { s ->
        s.soTimeout = 3_000
        s.getOutputStream().write("GET /x HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
        statusLine(s.getInputStream())
    }

    // ------------------------------------------------------------------ 1. délais et places réservées

    @Test fun leadingBlankLinesCountTowardsTheDeadline() {
        val server = HttpServer(okHandler, 0, lo, HttpLimits(readTimeoutMs = 800, requestDeadlineMs = 1_000)).also { it.start() }
        try {
            val s = Socket(lo, server.boundPort)
            s.soTimeout = 4_000
            val t0 = System.nanoTime()
            try {
                while ((System.nanoTime() - t0) / 1_000_000 < 3_000) {
                    s.getOutputStream().write("\r\n".toByteArray()); s.getOutputStream().flush()
                    Thread.sleep(300)
                }
                s.getOutputStream().write("GET /x HTTP/1.1\r\n\r\n".toByteArray())
            } catch (_: IOException) {
            }
            assertNotEquals("HTTP/1.1 200 OK", statusLine(s.getInputStream()))
            s.close()
            // Et elles comptent dans la taille des en-têtes.
            assertEquals(431, try {
                HttpParser.read(ByteArrayInputStream(("\r\n".repeat(5_000) + "GET / HTTP/1.1\r\n\r\n").toByteArray()), null, "x", HttpLimits())
                200
            } catch (e: app.toque.core.companion.HttpProtocolException) {
                e.status
            })
        } finally {
            server.stop()
        }
    }

    @Test fun slowHeadersHitTheDeadlineOnEveryByte() {
        val server = HttpServer(okHandler, 0, lo, HttpLimits(readTimeoutMs = 800, requestDeadlineMs = 1_000)).also { it.start() }
        try {
            val s = Socket(lo, server.boundPort)
            s.soTimeout = 4_000
            val t0 = System.nanoTime()
            try {
                for (c in "GET /x HTTP/1.1\r\nX: abcdefghijklmnop\r\n\r\n") {
                    s.getOutputStream().write(c.code); s.getOutputStream().flush()
                    Thread.sleep(120)
                }
            } catch (_: IOException) {
            }
            assertNotEquals("HTTP/1.1 200 OK", statusLine(s.getInputStream()))
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_500)
            s.close()
        } finally {
            server.stop()
        }
    }

    @Test fun aRecentlyAuthenticatedAddressAlwaysFindsASlot() {
        val limits = HttpLimits(readTimeoutMs = 2_000, requestDeadlineMs = 3_000)
        val server = HttpServer(okHandler, 0, lo, limits, trusted = { it == "127.0.0.1" }).also { it.start() }
        val held = ArrayList<Socket>()
        val stop = AtomicBoolean(false)
        try {
            for (src in listOf("127.0.0.2", "127.0.0.3")) repeat(8) { held += from(src, server.boundPort) }
            // Les attaquants se relaient : ils occupent sans cesse toutes les places non réservées.
            val attacker = Thread {
                while (!stop.get()) {
                    for (i in held.indices) try {
                        held[i].getOutputStream().write('\n'.code)
                    } catch (_: Exception) {
                        try { held[i] = from(if (i < 8) "127.0.0.2" else "127.0.0.3", server.boundPort) } catch (_: Exception) {}
                    }
                    Thread.sleep(200)
                }
            }.apply { isDaemon = true; start() }
            Thread.sleep(500)
            // Un inconnu de plus est refusé, le téléphone appairé passe, y compris après le délai des attaquants.
            assertEquals("", get(server.boundPort, "127.0.0.4"))
            assertEquals("HTTP/1.1 200 OK", get(server.boundPort))
            Thread.sleep(3_500)
            assertEquals("HTTP/1.1 200 OK", get(server.boundPort))
            stop.set(true); attacker.join()
        } finally {
            for (s in held) try { s.close() } catch (_: Exception) {}
            server.stop()
        }
    }

    @Test fun aClientThatNeverReadsIsCutOff() {
        val big = object : HttpHandler {
            override fun handle(request: HttpRequest) =
                if (request.path == "/big") HttpResponse(200, "application/octet-stream", ByteArray(16 * 1024 * 1024)) else HttpResponse.text(200, "ok")
        }
        val limits = HttpLimits(maxConnections = 2, reservedForTrusted = 1, writeDeadlineMs = 500, writeBytesPerMs = 1_000_000)
        val server = HttpServer(big, 0, lo, limits).also { it.start() }
        try {
            val stuck = Socket(lo, server.boundPort)
            stuck.receiveBufferSize = 4096
            stuck.getOutputStream().write("GET /big HTTP/1.1\r\n\r\n".toByteArray())
            Thread.sleep(300)
            // La seule place non réservée est prise par le client qui ne lit pas…
            assertEquals("", get(server.boundPort))
            Thread.sleep(1_200)
            // … puis libérée par le délai d'écriture.
            assertEquals("HTTP/1.1 200 OK", get(server.boundPort))
            stuck.close()
        } finally {
            server.stop()
        }
    }

    @Test fun authenticatedRequestsMarkTheAddressTrusted() {
        val f = HubFixture()
        val t = f.pair()
        assertFalse(f.hub.isTrusted("192.168.0.20"))
        f.req("GET", "/api/hello")
        assertFalse(f.hub.isTrusted("192.168.0.20"))
        f.req("GET", "/api/state", "0".repeat(64))
        assertFalse(f.hub.isTrusted("192.168.0.20"))
        f.req("GET", "/api/state", t)
        assertTrue(f.hub.isTrusted("192.168.0.20"))
        f.clock.advance(CompanionHub.TRUSTED_MS + 1)
        assertFalse(f.hub.isTrusted("192.168.0.20"))
    }

    // ------------------------------------------------------------------ 2. preuve avant le jeton

    /** Faux robot : note tout en-tête Authorization reçu, répond une preuve fausse. */
    private class Impostor(port: Int = 0) : AutoCloseable {
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16)
        }
        val authorization = AtomicReference<String?>()
        val requests = AtomicInteger()

        init {
            Thread {
                while (!socket.isClosed) try {
                    val c = socket.accept()
                    val inp = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    var length = 0
                    while (true) {
                        val l = inp.readLine() ?: break
                        if (l.isEmpty()) break
                        if (l.lowercase().startsWith("authorization:")) authorization.set(l)
                        if (l.lowercase().startsWith("content-length:")) length = l.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { inp.read() }
                    requests.incrementAndGet()
                    val body = ProveReply("ab".repeat(32)).toMap().let { Json.write(it) }.toByteArray()
                    c.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    c.getOutputStream().write(body)
                    c.close()
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; start() }
        }

        val port get() = socket.localPort
        override fun close() = socket.close()
    }

    @Test fun locateNeverSendsTheToken() {
        Impostor().use { imp ->
            val token = "0123456789abcdef".repeat(4)
            assertNull(RobotFinder.locate(listOf("127.0.0.1"), imp.port, token, "a1b2c3d4", "a1b2c3d4e5f6", timeoutMs = 2_000, overallMs = 4_000))
            assertTrue(imp.requests.get() >= 1)
            assertNull(imp.authorization.get())
            // Le client non plus : la preuve échoue, le jeton ne part pas.
            val c = CompanionClient("127.0.0.1", imp.port, token, "a1b2c3d4", "a1b2c3d4e5f6")
            try {
                c.state()
                fail()
            } catch (_: RobotProofException) {
            }
            assertNull(imp.authorization.get())
        }
    }

    @Test fun theProofIsBoundToThePhoneTheRobotAndTheAddress() {
        val f = HubFixture()
        val o = f.hub.pairing.open()
        val r = f.hub.pairing.pairWithCode(o.code)
        val phone = r.phone!!
        val nonce = "0123456789abcdef0123456789abcdef"
        fun prove(pid: String, local: String = "192.168.0.12"): HttpResponse =
            f.api.handle(HttpRequest("POST", "/api/prove", emptyMap(), mapOf("host" to "192.168.0.12:8088", "content-type" to "application/json"),
                Json.write(mapOf("phoneId" to pid, "nonce" to nonce)).toByteArray(), "192.168.0.20", local))
        val good = ProveReply.fromMap(f.json(prove(phone.id))).proof
        val robotId = f.hub.pairing.robotId
        assertTrue(CompanionProof.check(r.token!!, nonce, robotId, "192.168.0.12", good))
        assertFalse(CompanionProof.check(r.token!!, nonce, robotId, "192.168.0.99", good))
        assertFalse(CompanionProof.check(r.token!!, nonce, "000000000000", "192.168.0.12", good))
        assertFalse(CompanionProof.check(r.token!!, "f".repeat(32), robotId, "192.168.0.12", good))
        // Arrivé par une autre adresse locale (relais) : la preuve ne vaut pas pour l'adresse composée.
        assertFalse(CompanionProof.check(r.token!!, nonce, robotId, "192.168.0.12", ProveReply.fromMap(f.json(prove(phone.id, "10.0.0.1"))).proof))
        // Téléphone inconnu : même forme, même statut, stable, mais fausse.
        val unknown = prove("00000000")
        assertEquals(200, unknown.status)
        val p1 = ProveReply.fromMap(f.json(unknown)).proof
        assertEquals(64, p1.length)
        assertEquals(p1, ProveReply.fromMap(f.json(prove("00000000"))).proof)
        assertEquals(f.json(unknown).keys, f.json(prove(phone.id)).keys)
        // Mauvais formats.
        assertEquals(400, prove("xyz").status)
        // Rien de l'état ne fuit, même pour un téléphone connu.
        assertFalse(String(prove(phone.id).body).contains(robotId))
    }

    @Test fun theProofIsRedoneAfterANetworkError() {
        val hub = CompanionHub(FakeHost(), store = MemoryStore())
        hub.start(0, lo)
        val port = hub.port
        val c = CompanionClient("127.0.0.1", port)
        c.pairWithCode(hub.pairing.open().code)
        c.state()
        hub.stop()
        try {
            c.state()
            fail()
        } catch (e: IOException) {
            assertFalse(e is RobotProofException)
        }
        // Un autre appareil prend la même adresse et le même port : le jeton ne lui est pas envoyé.
        Impostor(port).use { imp ->
            try {
                c.state()
                fail()
            } catch (_: RobotProofException) {
            }
            assertTrue(imp.requests.get() >= 1)
            assertNull(imp.authorization.get())
        }
    }

    // ------------------------------------------------------------------ 3. JSON imbriqué

    @Test fun deeplyNestedJsonIsRefusedWithoutOverflow() {
        assertEquals(64, (Json.parse("[".repeat(64) + "]".repeat(64)) as List<*>).let { var d = 0; var x: Any? = it; while (x is List<*>) { d++; x = x.firstOrNull() }; d })
        for (text in listOf("[".repeat(65) + "]".repeat(65), "[".repeat(1_000_000), "{\"a\":".repeat(100_000)))
            try {
                Json.parse(text)
                fail()
            } catch (_: Json.ParseException) {
            }
        // Sur le robot : 400, plus de 500.
        val f = HubFixture()
        val t = f.pair()
        val r = f.api.handle(HttpRequest("POST", "/api/machine/adjust", emptyMap(),
            mapOf("authorization" to "Bearer $t", "content-type" to "application/json"), "[".repeat(16 * 1024).toByteArray(), "x"))
        assertEquals(400, r.status)
    }

    @Test fun theFollowerSurvivesHostileAnswersAndThrowingListeners() {
        val ss = ServerSocket(0, 5, lo)
        Thread {
            while (!ss.isClosed) try {
                val c = ss.accept()
                val inp = c.getInputStream()
                var st = 0
                while (st < 4) { val b = inp.read(); if (b < 0) break; st = if ((b == 13 && st % 2 == 0) || (b == 10 && st % 2 == 1)) st + 1 else 0 }
                val body = "[".repeat(1_000_000).toByteArray()
                c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                c.getOutputStream().write(body)
                c.close()
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        val uncaught = AtomicReference<Throwable?>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e -> if (th.name == "toque-companion-follow") uncaught.set(e) }
        try {
            val unreachable = AtomicInteger()
            val client = CompanionClient("127.0.0.1", ss.localPort, "a".repeat(64), "a1b2c3d4", "a1b2c3d4e5f6")
            val f = StateFollower(client, object : StateFollower.Listener {
                override fun onState(state: StateWire) {}
                override fun onUnreachable(error: Exception) {
                    unreachable.incrementAndGet()
                    throw StackOverflowError("écouteur cassé")
                }
            }, sleeper = { Thread.sleep(20) })
            f.start()
            Thread.sleep(2_000)
            assertNull(uncaught.get())
            assertTrue(f.isRunning)
            assertEquals(1, unreachable.get())
            f.stop()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            ss.close()
        }
        // Un écouteur qui lève à chaque état ne coupe pas le suivi d'un vrai robot.
        val hub = CompanionHub(FakeHost(), store = MemoryStore())
        hub.start(0, lo)
        try {
            val c = CompanionClient("127.0.0.1", hub.port)
            c.pairWithCode(hub.pairing.open().code)
            val seen = CopyOnWriteArrayList<Long>()
            val f = StateFollower(c, object : StateFollower.Listener {
                override fun onState(state: StateWire) {
                    seen += state.revision
                    throw IllegalStateException("cassé")
                }
            }, waitSeconds = 2)
            f.start()
            Thread.sleep(500)
            hub.updateRobot(app.toque.core.companion.RobotInfo(appVersion = "9"))
            val end = System.nanoTime() + 3_000_000_000L
            while (seen.size < 2 && System.nanoTime() < end) Thread.sleep(50)
            assertTrue(seen.size >= 2)
            assertTrue(f.isRunning)
            f.stop()
        } finally {
            hub.stop()
        }
    }

    // ------------------------------------------------------------------ 4. couvercle entre la pause et son premier Snapshot

    @Test fun aLidOpeningBeforeTheFirstPausedSnapshotStillBlocksResume() {
        val f = HubFixture()
        val token = f.pair()
        f.hub.updateMachine(Snaps.running())
        assertEquals(200, f.req("POST", "/api/machine/pause", token).status)
        f.hub.onMachineEvent(MachineEvent(EventType.LID_OPENED, "Couvercle ouvert"))
        f.hub.onMachineEvent(MachineEvent(EventType.LID_CLOSED, "Couvercle fermé"))
        f.hub.updateMachine(Snaps.paused("USER"))
        val r = f.req("POST", "/api/machine/resume", token)
        assertEquals(409, r.status)
        assertEquals("lid_opened_during_pause", ApiError.fromMap(f.json(r)).code)
        // Même chose vue par un Snapshot couvercle ouvert juste avant la pause.
        f.hub.updateMachine(Snaps.running())
        f.hub.updateMachine(Snaps.running().copy(lidOpen = true))
        f.hub.updateMachine(Snaps.paused("USER"))
        assertEquals("lid_opened_during_pause", StateWire.fromMap(f.json(f.req("GET", "/api/state", token))).can.resume.code)
        // Effacé par une reprise observée…
        f.hub.updateMachine(Snaps.paused("USER").copy(jobState = "RUNNING", pauseReason = ""))
        f.hub.updateMachine(Snaps.paused("USER"))
        assertTrue(StateWire.fromMap(f.json(f.req("GET", "/api/state", token))).can.resume.allowed)
        // … ou par un nouveau programme.
        f.hub.onMachineEvent(MachineEvent(EventType.LID_OPENED, "Couvercle ouvert"))
        f.hub.updateMachine(Snaps.idle())
        f.hub.updateMachine(Snaps.running().copy(jobLabel = "Autre"))
        f.hub.updateMachine(Snaps.paused("USER").copy(jobLabel = "Autre"))
        assertTrue(StateWire.fromMap(f.json(f.req("GET", "/api/state", token))).can.resume.allowed)
    }

    // ------------------------------------------------------------------ 5. délais et réglages périmés

    @Test fun aTimedOutHostCallNeverRunsLater() {
        val main = Executors.newSingleThreadScheduledExecutor()
        val ran = AtomicInteger()
        main.execute { Thread.sleep(1_000) }
        assertNull(HostCalls.call({ main.execute(it) }, 300) { ran.incrementAndGet(); "adjusted" })
        main.execute { Thread.sleep(1_000) }
        val r = HostCalls.command({ main.execute(it) }, 300) { done -> ran.incrementAndGet(); done(CommandResult.OK) }
        assertEquals("timeout", r.code)
        main.shutdown(); main.awaitTermination(5, TimeUnit.SECONDS)
        assertEquals(0, ran.get())
        // Commencée mais sans réponse : « peut-être appliquée ».
        val ex = Executors.newSingleThreadExecutor()
        val started = HostCalls.command({ ex.execute(it) }, 200) { _ -> ran.incrementAndGet() }
        assertEquals("no_answer", started.code)
        assertEquals(1, ran.get())
        ex.shutdownNow()
    }

    @Test fun twoAddedMinutesNeverCollapseAndAStaleJobIsNotAdjusted() {
        val b = Bench().ready()
        val f = HubFixture(FakeHost(b.c))
        val t = f.pair()
        assertTrue(b.c.startJob(Programs.manual(2, 0, Direction.CLOCKWISE, 300_000)).ok)
        b.step(500)
        f.hub.updateMachine(b.snap)
        // Deux « +1 min » avant que le Snapshot suivant n'arrive.
        assertEquals(200, f.req("POST", "/api/machine/adjust", t, mapOf("addMs" to 60_000)).status)
        assertEquals(200, f.req("POST", "/api/machine/adjust", t, mapOf("addMs" to 60_000)).status)
        assertEquals(420_000L, b.c.snapshot().totalMs)
        b.step(200)
        f.hub.updateMachine(b.snap)
        assertEquals(200, f.req("POST", "/api/machine/adjust", t, mapOf("addMs" to 60_000)).status)
        assertEquals(480_000L, b.c.snapshot().totalMs)

        // Le programme change entre la requête et l'exécution : rien n'est appliqué.
        lateinit var hub: CompanionHub
        val base = FakeHost()
        val host = object : CompanionHost by base {
            override fun adjust(prepare: () -> AdjustCommand): CommandResult {
                hub.updateMachine(Snaps.idle())
                hub.updateMachine(Snaps.running().copy(jobLabel = "Autre cuisson"))
                return base.adjust(prepare)
            }
        }
        hub = CompanionHub(host, FakeCompanionClock(), MemoryStore(), Random(4))
        val token = hub.pairing.pairWithCode(hub.pairing.open().code).token!!
        hub.updateMachine(Snaps.running())
        val r = CompanionApi(hub).handle(HttpRequest("POST", "/api/machine/adjust", emptyMap(),
            mapOf("authorization" to "Bearer $token", "content-type" to "application/json"), "{\"speed\":3}".toByteArray(), "x"))
        assertEquals(409, r.status)
        assertEquals("changed", ApiError.fromMap(Json.parse(String(r.body)) as Map<String, Any?>).code)
    }

    // ------------------------------------------------------------------ 6. petits points

    @Test fun encodedSlashesNeverChangeTheRoute() {
        val r = HttpParser.read(ByteArrayInputStream("GET /api/recipes/perso%2Fveloute/image HTTP/1.1\r\n\r\n".toByteArray()), null, "x", HttpLimits())
        assertEquals(listOf("api", "recipes", "perso/veloute", "image"), r.segments)
        val f = HubFixture()
        val t = f.pair()
        fun raw(target: String) = f.api.handle(HttpParser.read(ByteArrayInputStream(
            "GET $target HTTP/1.1\r\nHost: 192.168.0.12\r\nAuthorization: Bearer $t\r\n\r\n".toByteArray()), null, "x", HttpLimits()))
        assertEquals(200, raw("/api/state").status)
        assertEquals(404, raw("/api%2Fstate").status)
        assertEquals(404, raw("/api/recipes/perso-veloute%2Fimage").status)
        assertEquals(200, raw("/api/recipes/perso-veloute").status)
    }

    @Test fun adjustNumbersAreCheckedBeforeBeingNarrowed() {
        val f = HubFixture()
        val t = f.pair()
        f.hub.updateMachine(Snaps.running())
        for (body in listOf("{\"speed\":4294967298}", "{\"speed\":2.5}", "{\"speed\":\"3\"}", "{\"tempC\":1e300}",
            "{\"totalMs\":-5}", "{\"addMs\":9223372036854775807}", "{\"reverse\":\"yes\"}", "{\"speed\":-1}")) {
            val r = f.api.handle(HttpRequest("POST", "/api/machine/adjust", emptyMap(),
                mapOf("authorization" to "Bearer $t", "content-type" to "application/json"), body.toByteArray(), "x"))
            assertEquals(body, 400, r.status)
        }
        assertTrue(f.host.calls.none { it.startsWith("adjust") })
        assertEquals(AdjustRequest(speed = 3, reverse = true), AdjustRequest.fromMap(Json.parse("{\"speed\":3.0,\"reverse\":true}") as Map<String, Any?>))
    }

    @Test fun forgetDropsTheTokenEvenWhenTheReplyIsLost() {
        val hub = CompanionHub(FakeHost(), store = MemoryStore())
        hub.start(0, lo)
        val c = CompanionClient("127.0.0.1", hub.port)
        c.pairWithCode(hub.pairing.open().code)
        c.state()
        hub.stop()
        try {
            c.forget()
            fail()
        } catch (_: IOException) {
        }
        assertNull(c.token)
    }

    @Test fun theRobotScreenCanTellWhyThereIsNoCode() {
        val clock = FakeCompanionClock()
        val m = PairingManager(clock, Random(5), MemoryStore())
        assertEquals(PairingStatus.CLOSED, m.status)
        val o = m.open()
        assertEquals(PairingStatus.OPEN, m.status)
        clock.advance(m.validityMs)
        assertEquals(PairingStatus.EXPIRED, m.status)
        m.open()
        repeat(5) { m.pairWithCode(if (o.code == "000000") "000001" else "000000") }
        assertEquals(PairingStatus.LOCKED, m.status)
        assertTrue(m.pairWithCode(m.open().code).ok)
        assertEquals(PairingStatus.USED, m.status)
        m.open(); m.close()
        assertEquals(PairingStatus.CLOSED, m.status)
    }

    // ------------------------------------------------------------------ 7. sens de rotation

    @Test fun aPhoneCanReverseAManualCookingWithinTheRules() {
        val b = Bench().ready()
        val f = HubFixture(FakeHost(b.c))
        val t = f.pair()
        assertTrue(b.c.startJob(Programs.manual(2, 0, Direction.CLOCKWISE, 300_000)).ok)
        b.step(500)
        f.hub.updateMachine(b.snap)
        assertFalse(StateWire.fromMap(f.json(f.req("GET", "/api/state", t))).job!!.reverse)
        assertEquals(200, f.req("POST", "/api/machine/adjust", t, mapOf("reverse" to true)).status)
        b.step(1_500)
        f.hub.updateMachine(b.snap)
        assertTrue(b.snap.jobReverse)
        assertEquals(Direction.COUNTER_CLOCKWISE.ordinal, if (b.snap.jobReverse) Direction.COUNTER_CLOCKWISE.ordinal else -1)
        assertTrue(StateWire.fromMap(f.json(f.req("GET", "/api/state", t))).job!!.reverse)
        // En sens inverse, le contrôleur garde sa limite de vitesse.
        val r = f.req("POST", "/api/machine/adjust", t, mapOf("speed" to 5))
        assertEquals("reverse_speed", ApiError.fromMap(f.json(r)).code)
        assertEquals(200, f.req("POST", "/api/machine/adjust", t, mapOf("reverse" to false)).status)
        b.step(1_500)
        f.hub.updateMachine(b.snap)
        assertFalse(b.snap.jobReverse)
        val adjusted = JournalPage.fromMap(f.json(f.req("GET", "/api/journal", t))).entries.filter { it.kind == "adjusted" }
        assertTrue(adjusted.any { it.detail.contains("reverse=true") && it.text.contains("sens inverse") })
        // Contenu chaud à vitesse 4 ou plus : pas de changement de sens depuis le téléphone.
        f.hub.updateMachine(Snaps.running(speed = 5, tempC = 75))
        assertEquals("hot_blend", ApiError.fromMap(f.json(f.req("POST", "/api/machine/adjust", t, mapOf("reverse" to true)))).code)
        // Les programmes à phases fixes ne changent pas de sens.
        f.hub.updateMachine(Snaps.running(kind = "TOAST", phases = 2))
        assertEquals("fixed", ApiError.fromMap(f.json(f.req("POST", "/api/machine/adjust", t, mapOf("reverse" to false)))).code)
    }
}
