package app.toque.core

import app.toque.core.companion.HttpHandler
import app.toque.core.companion.HttpLimits
import app.toque.core.companion.HttpParser
import app.toque.core.companion.HttpProtocolException
import app.toque.core.companion.HttpRequest
import app.toque.core.companion.HttpResponse
import app.toque.core.companion.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CompanionHttpTest {
    private val limits = HttpLimits(maxHeaderBytes = 1024, maxHeaderCount = 10, maxBodyBytes = 64)

    private fun parse(raw: String): HttpRequest =
        HttpParser.read(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)), null, "test", limits)

    private fun status(raw: String): Int = try {
        parse(raw)
        200
    } catch (e: HttpProtocolException) {
        e.status
    }

    @Test fun parsesARequestWithQueryAndBody() {
        val r = parse("POST /api/x%C3%A9?q=v%C3%A9lout%C3%A9+chaud&a=1&a=2&flag HTTP/1.1\r\nHost: 192.168.0.12\r\n" +
            "Content-Type: application/json\r\nContent-Length: 7\r\n\r\n{\"a\":1}")
        assertEquals("POST", r.method)
        assertEquals("/api/xé", r.path)
        assertEquals("vélouté chaud", r.query["q"])
        assertEquals("1", r.query["a"])
        assertEquals("", r.query["flag"])
        assertEquals("application/json", r.header("Content-Type"))
        assertEquals("{\"a\":1}", r.bodyText)
    }

    @Test fun refusesMalformedOrOversizedRequests() {
        val cases = listOf(
            "GET / HTTP/1.1\r\nHost: x\r\n" + "X: " + "a".repeat(1100) + "\r\n\r\n" to 431,
            "GET / HTTP/1.1\r\n" + (1..11).joinToString("") { "H$it: v\r\n" } + "\r\n" to 431,
            "POST / HTTP/1.1\r\nContent-Length: 65\r\n\r\n" + "x".repeat(65) to 413,
            "POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n" to 400,
            "POST / HTTP/1.1\r\nContent-Length: 1x\r\n\r\n" to 400,
            "POST / HTTP/1.1\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\nab" to 400,
            "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n" to 501,
            "POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc" to 400,
            "GET / HTTP/1.1\nHost: x\n\n" to 400,
            "GET / HTTP/2.0\r\n\r\n" to 505,
            "PUT / HTTP/1.1\r\n\r\n" to 405,
            "OPTIONS / HTTP/1.1\r\n\r\n" to 405,
            "GET /a%zz HTTP/1.1\r\n\r\n" to 400,
            "GET /a%2 HTTP/1.1\r\n\r\n" to 400,
            "GET /a%00 HTTP/1.1\r\n\r\n" to 400,
            "GET http://x/ HTTP/1.1\r\n\r\n" to 400,
            "GET / HTTP/1.1\r\n folded\r\n\r\n" to 400,
            "GET / HTTP/1.1\r\nBad Name: v\r\n\r\n" to 400,
            "GET /  HTTP/1.1\r\n\r\n" to 400,
            "GET / HTTP/1.1\r\nHost: x\r\n" to 400,
            "" to 400,
        )
        for ((raw, expected) in cases) assertEquals(raw.take(60), expected, status(raw))
    }

    @Test fun writesClosingResponsesWithoutCors() {
        val out = ByteArrayOutputStream()
        HttpParser.write(out, HttpResponse.json(200, mapOf("a" to 1)))
        val text = out.toString("UTF-8")
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Connection: close\r\n"))
        assertTrue(text.contains("Content-Length: 7\r\n"))
        assertTrue(!text.contains("Access-Control"))
        assertTrue(!text.contains("Set-Cookie"))
    }

    private fun exchange(port: Int, raw: String): String {
        Socket(InetAddress.getByName("127.0.0.1"), port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write(raw.toByteArray())
            return s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    @Test fun survivesHostileClientsAndStopsCleanly() {
        val inside = CountDownLatch(1)
        val handler = object : HttpHandler {
            override fun handle(request: HttpRequest): HttpResponse {
                if (request.path == "/boom") throw IllegalStateException("boom")
                if (request.path == "/slow") {
                    inside.countDown()
                    Thread.sleep(30_000)
                }
                return HttpResponse.text(200, "ok")
            }
        }
        val server = HttpServer(handler, 0, InetAddress.getByName("127.0.0.1"),
            HttpLimits(readTimeoutMs = 300, requestDeadlineMs = 1_000, maxConnections = 5, maxPerClient = 3, reservedForTrusted = 1))
        server.start()
        val port = server.boundPort
        try {
            // Une exception du gestionnaire donne une 500, pas un plantage.
            assertTrue(exchange(port, "GET /boom HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 500"))
            // Des clients muets occupent des connexions puis sont coupés au bout du délai de lecture.
            val idle = (1..3).map { Socket(InetAddress.getByName("127.0.0.1"), port) }
            Thread.sleep(100)
            // Au-delà de maxPerClient, la connexion est fermée tout de suite.
            Socket(InetAddress.getByName("127.0.0.1"), port).use { s ->
                s.soTimeout = 2_000
                assertEquals(-1, s.getInputStream().read())
            }
            Thread.sleep(800)
            for (s in idle) s.close()
            Thread.sleep(100)
            assertTrue(exchange(port, "GET / HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 200"))
            // Un client qui envoie un octet à la fois est coupé (délai global).
            Socket(InetAddress.getByName("127.0.0.1"), port).use { s ->
                s.soTimeout = 3_000
                val o = s.getOutputStream()
                val started = System.nanoTime()
                try {
                    for (c in "GET / HTTP/1.1\r\nX-Slow: " + "a".repeat(200)) {
                        o.write(c.code)
                        o.flush()
                        Thread.sleep(10)
                        if (System.nanoTime() - started > 2_500_000_000L) fail("pas coupé")
                    }
                } catch (_: java.io.IOException) {
                }
            }
            // stop() coupe aussi une requête en cours.
            val t = Thread { try { exchange(port, "GET /slow HTTP/1.1\r\n\r\n") } catch (_: Exception) {} }
            t.start()
            assertTrue(inside.await(2, TimeUnit.SECONDS))
        } finally {
            server.stop()
        }
        Thread.sleep(200)
        try {
            Socket(InetAddress.getByName("127.0.0.1"), port).close()
            fail("le port devrait être fermé")
        } catch (_: java.io.IOException) {
        }
    }
}
