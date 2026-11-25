package app.toque.core.companion

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Bornes du serveur : un client hostile ou cassé ne doit jamais bloquer ni faire tomber l'interface. */
class HttpLimits(
    /** Ligne de requête et en-têtes, ensemble. */
    val maxHeaderBytes: Int = 8 * 1024,
    val maxHeaderCount: Int = 64,
    val maxBodyBytes: Int = 16 * 1024,
    /** Connexions traitées en même temps (au-delà : fermées aussitôt). C'est aussi la taille du groupe de fils. */
    val maxConnections: Int = 16,
    /** Connexions simultanées venant d'une même adresse. */
    val maxPerClient: Int = 8,
    /** Délai maximal entre deux lectures sur la socket. */
    val readTimeoutMs: Int = 10_000,
    /** Délai maximal pour recevoir toute la requête (contre les clients qui envoient un octet à la fois). */
    val requestDeadlineMs: Long = 15_000,
    /**
     * Attente maximale d'une requête longue (long-poll). 120 s : un téléphone en poche, robot au
     * repos, se réveille moins souvent (avant : 25 s, un ancien robot ramène toujours l'attente à 25 s).
     */
    val maxLongPollMs: Long = 120_000,
    /**
     * Places gardées pour les adresses qui se sont authentifiées récemment : le trafic non authentifié
     * ne peut jamais occuper plus de maxConnections - reservedForTrusted connexions.
     */
    val reservedForTrusted: Int = 4,
    /** Filet de sécurité : durée maximale du traitement d'une requête (long-poll et appel à l'appli compris). */
    val handlerDeadlineMs: Long = 150_000,
    /** Délai pour écrire une réponse : base, plus une milliseconde par [writeBytesPerMs] octets. */
    val writeDeadlineMs: Long = 10_000,
    val writeBytesPerMs: Int = 32,
) {
    init {
        require(reservedForTrusted in 0 until maxConnections) { "reservedForTrusted must leave room for pairing" }
    }
}

class HttpRequest(
    val method: String,
    /** Chemin décodé, sans la requête (« /api/state »). */
    val path: String,
    val query: Map<String, String>,
    /** Noms en minuscules. */
    val headers: Map<String, String>,
    val body: ByteArray,
    val remote: String,
    /** Adresse locale sur laquelle la connexion est arrivée (IP du robot vue par ce client). */
    val local: String = "",
    /** Segments du chemin, séparés AVANT décodage : « %2F » ne crée jamais de segment. */
    val segments: List<String> = path.split('/').filter { it.isNotEmpty() },
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
    val bodyText: String get() = String(body, Charsets.UTF_8)
}

class HttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        fun json(status: Int, value: Any?): HttpResponse =
            HttpResponse(status, "application/json; charset=utf-8", app.toque.core.json.Json.write(value).toByteArray(Charsets.UTF_8))

        fun text(status: Int, text: String): HttpResponse =
            HttpResponse(status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))
    }
}

/** Traite une requête ; peut bloquer (long-poll) dans la limite de [HttpLimits.maxLongPollMs]. */
interface HttpHandler {
    fun handle(request: HttpRequest): HttpResponse

    /**
     * Borne du corps pour cette requête, lue avant le corps (méthode, segments, en-têtes en minuscules) ;
     * null : celle du serveur ([HttpLimits.maxBodyBytes]).
     */
    fun maxBodyBytes(method: String, segments: List<String>, headers: Map<String, String>): Int? = null
}

/** Requête refusée pendant la lecture (taille, format). */
class HttpProtocolException(val status: Int, message: String) : IOException(message)

/**
 * Petit serveur HTTP/1.1 sur [ServerSocket] : GET, POST, DELETE, corps JSON de taille bornée, une
 * requête par connexion (« Connection: close »), un fil par connexion pris dans un groupe borné.
 * Tous les fils sont des démons ; [stop] ferme tout, y compris les requêtes en attente.
 */
class HttpServer(
    private val handler: HttpHandler,
    private val port: Int,
    private val bindAddress: InetAddress? = null,
    val limits: HttpLimits = HttpLimits(),
    private val log: (String) -> Unit = {},
    /** Adresse authentifiée récemment (places réservées, voir [HttpLimits.reservedForTrusted]). */
    private val trusted: (String) -> Boolean = { false },
) {
    /** Une connexion en cours et l'heure (monotone) au-delà de laquelle elle est coupée. */
    private class Conn(val socket: Socket, val remote: String, val trusted: Boolean) {
        @Volatile var deadline = Long.MAX_VALUE
    }

    @Volatile private var server: ServerSocket? = null
    private var pool: ThreadPoolExecutor? = null
    private val active: MutableSet<Conn> = Collections.synchronizedSet(HashSet())
    private val perClient = HashMap<String, Int>()
    private var total = 0
    private var untrusted = 0
    @Volatile var running = false
        private set

    /** Port réellement ouvert (utile avec le port 0). */
    val boundPort: Int get() = server?.localPort ?: -1

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        if (running) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(bindAddress, port), 32)
        server = s
        val n = AtomicInteger()
        val factory = ThreadFactory { r -> Thread(r, "toque-http-" + n.incrementAndGet()).apply { isDaemon = true } }
        pool = ThreadPoolExecutor(0, limits.maxConnections + 2, 30, TimeUnit.SECONDS, SynchronousQueue(), factory)
        running = true
        Thread({ acceptLoop(s) }, "toque-http-accept").apply { isDaemon = true }.start()
        Thread({ reapLoop() }, "toque-http-reaper").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
        val conns = synchronized(active) { ArrayList(active) }
        for (c in conns) closeQuietly(c.socket)
        pool?.shutdownNow()
        pool = null
    }

    private fun now(): Long = System.nanoTime() / 1_000_000L

    /** Coupe les connexions qui dépassent leur délai, quoi que fasse le client (lecture ou écriture bloquée). */
    private fun reapLoop() {
        while (running) {
            try {
                Thread.sleep(REAP_MS)
            } catch (_: InterruptedException) {
                return
            }
            val t = now()
            val late = synchronized(active) { active.filter { t > it.deadline } }
            for (c in late) closeQuietly(c.socket)
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val client = try {
                s.accept()
            } catch (e: IOException) {
                if (running) log("accept : ${e.message}")
                if (s.isClosed) return
                continue
            }
            val remote = client.inetAddress?.hostAddress ?: "?"
            val isTrusted = try {
                trusted(remote)
            } catch (_: Throwable) {
                false
            }
            if (!admit(remote, isTrusted)) {
                closeQuietly(client)
                continue
            }
            val conn = Conn(client, remote, isTrusted)
            conn.deadline = now() + limits.requestDeadlineMs
            val p = pool
            if (p == null) {
                release(conn)
                closeQuietly(client)
                return
            }
            active += conn
            try {
                p.execute { serve(conn) }
            } catch (_: RejectedExecutionException) {
                active -= conn
                release(conn)
                closeQuietly(client)
            }
        }
    }

    private fun admit(remote: String, isTrusted: Boolean): Boolean = synchronized(perClient) {
        val n = perClient[remote] ?: 0
        if (n >= limits.maxPerClient) return false
        if (total >= limits.maxConnections) return false
        if (!isTrusted && untrusted >= limits.maxConnections - limits.reservedForTrusted) return false
        perClient[remote] = n + 1
        total++
        if (!isTrusted) untrusted++
        true
    }

    private fun release(c: Conn) = synchronized(perClient) {
        val n = (perClient[c.remote] ?: 1) - 1
        if (n <= 0) perClient.remove(c.remote) else perClient[c.remote] = n
        total--
        if (!c.trusted) untrusted--
    }

    private fun serve(conn: Conn) {
        val client = conn.socket
        try {
            client.soTimeout = limits.readTimeoutMs
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()
            val local = client.localAddress?.hostAddress ?: ""
            val response = try {
                val request = HttpParser.read(input, output, conn.remote, limits, deadlineMs = conn.deadline, local = local,
                    bodyLimit = { m, s, h -> handler.maxBodyBytes(m, s, h) })
                conn.deadline = now() + limits.handlerDeadlineMs
                try {
                    handler.handle(request).let { if (request.method == "HEAD") HttpResponse(it.status, it.contentType, ByteArray(0), it.headers) else it }
                } catch (e: InterruptedException) {
                    return
                } catch (e: Throwable) {
                    log("requête en échec : $e")
                    ApiError.response(500, "internal", "Internal error")
                }
            } catch (e: HttpProtocolException) {
                ApiError.response(e.status, protocolCode(e.status), e.message ?: "")
            }
            // Un client qui ne lit jamais sa réponse est coupé lui aussi.
            conn.deadline = now() + limits.writeDeadlineMs + response.body.size / limits.writeBytesPerMs.coerceAtLeast(1)
            HttpParser.write(output, response)
        } catch (_: SocketException) {
        } catch (_: IOException) {
        } catch (e: Throwable) {
            log("connexion en échec : $e")
        } finally {
            active -= conn
            release(conn)
            closeQuietly(client)
        }
    }

    private fun protocolCode(status: Int): String = when (status) {
        405 -> "method_not_allowed"
        408 -> "request_timeout"
        413 -> "body_too_large"
        431 -> "headers_too_large"
        501 -> "not_implemented"
        505 -> "http_version"
        else -> "bad_request"
    }

    private fun closeQuietly(s: Socket) {
        try {
            s.close()
        } catch (_: IOException) {
        }
    }

    private companion object {
        const val REAP_MS = 100L
    }
}

/** Lecture et écriture du protocole, séparées du serveur pour être testées sans socket. */
object HttpParser {
    private val METHODS = setOf("GET", "POST", "DELETE", "HEAD")
    private val TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    @Throws(IOException::class)
    fun read(input: InputStream, output: OutputStream?, remote: String, limits: HttpLimits,
             deadlineMs: Long = System.nanoTime() / 1_000_000L + limits.requestDeadlineMs, local: String = "",
             bodyLimit: (String, List<String>, Map<String, String>) -> Int? = { _, _, _ -> null }): HttpRequest {
        val head = readHead(input, limits, deadlineMs)
        val lines = head.split("\r\n")
        val requestLine = lines[0]
        val parts = requestLine.split(' ')
        if (parts.size != 3) throw HttpProtocolException(400, "Invalid request line")
        val (method, target, version) = parts
        if (!TOKEN.matches(method)) throw HttpProtocolException(400, "Invalid method")
        if (version != "HTTP/1.1" && version != "HTTP/1.0") throw HttpProtocolException(505, "HTTP version not supported")
        if (method !in METHODS) throw HttpProtocolException(405, "Method not allowed")
        if (!target.startsWith("/")) throw HttpProtocolException(400, "Invalid request target")

        val headers = LinkedHashMap<String, String>()
        var count = 0
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            if (line[0] == ' ' || line[0] == '\t') throw HttpProtocolException(400, "Folded header refused")
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpProtocolException(400, "Invalid header")
            val name = line.substring(0, colon)
            if (!TOKEN.matches(name)) throw HttpProtocolException(400, "Invalid header name")
            val key = name.lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            if (++count > limits.maxHeaderCount) throw HttpProtocolException(431, "Too many headers")
            if (key == "content-length" && headers.containsKey(key)) throw HttpProtocolException(400, "Duplicate Content-Length")
            headers[key] = if (headers.containsKey(key)) headers[key] + ", " + value else value
        }
        if (headers.containsKey("transfer-encoding")) throw HttpProtocolException(501, "Transfer-Encoding not supported")
        val lengthText = headers["content-length"]
        val length = when {
            lengthText == null -> 0
            lengthText.isEmpty() || lengthText.length > 9 || !lengthText.all { it in '0'..'9' } -> throw HttpProtocolException(400, "Invalid Content-Length")
            else -> lengthText.toInt()
        }
        val q = target.indexOf('?')
        val rawPath = if (q < 0) target else target.substring(0, q)
        val rawQuery = if (q < 0) "" else target.substring(q + 1)
        // Segments séparés avant décodage : un « %2F » reste dans son segment.
        val segments = rawPath.split('/').filter { it.isNotEmpty() }.map { decode(it, false) }
        if (length > (bodyLimit(method, segments, headers) ?: limits.maxBodyBytes)) throw HttpProtocolException(413, "Body too large")
        if (length > 0 && output != null && headers["expect"]?.lowercase(Locale.ROOT) == "100-continue") {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        }
        val body = ByteArray(length)
        var got = 0
        while (got < length) {
            checkDeadline(deadlineMs)
            val n = input.read(body, got, length - got)
            if (n < 0) throw HttpProtocolException(400, "Incomplete body")
            got += n
        }

        val path = decode(rawPath, false)
        if (path.contains('\u0000')) throw HttpProtocolException(400, "Invalid path")
        return HttpRequest(method, path, parseQuery(rawQuery), headers, body, remote, local, segments)
    }

    private fun checkDeadline(deadlineMs: Long) {
        if (System.nanoTime() / 1_000_000L > deadlineMs) throw HttpProtocolException(408, "Request too slow")
    }

    private fun readHead(input: InputStream, limits: HttpLimits, deadlineMs: Long): String {
        val buf = ByteArrayOutputStream(512)
        var state = 0 // nombre d'octets de « \r\n\r\n » déjà vus
        // Tolère des lignes vides avant la requête (RFC 9112, 2.2), mais elles comptent dans la taille et le délai.
        var leading = true
        var seen = 0
        while (true) {
            checkDeadline(deadlineMs)
            val b = input.read()
            if (b < 0) throw HttpProtocolException(400, "Incomplete request")
            if (++seen > limits.maxHeaderBytes) throw HttpProtocolException(431, "Headers too large")
            if (leading && (b == '\r'.code || b == '\n'.code)) continue
            leading = false
            if (b == '\n'.code && state != 1 && state != 3) throw HttpProtocolException(400, "Line feed without carriage return")
            buf.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
            if (state == 4) break
        }
        val bytes = buf.toByteArray()
        for (x in bytes) if (x.toInt() == 0) throw HttpProtocolException(400, "Null byte in headers")
        // ISO-8859-1 : un octet par caractère, jamais d'erreur de décodage.
        return String(bytes, 0, bytes.size - 4, Charsets.ISO_8859_1)
    }

    fun parseQuery(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (raw.isEmpty()) return out
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = decode(if (eq < 0) pair else pair.substring(0, eq), true)
            val v = if (eq < 0) "" else decode(pair.substring(eq + 1), true)
            if (!out.containsKey(k)) out[k] = v
        }
        return out
    }

    /** Décodage « %XX » en UTF-8 ; un échappement invalide est refusé. */
    fun decode(s: String, plusIsSpace: Boolean): String {
        if (s.indexOf('%') < 0 && !(plusIsSpace && s.indexOf('+') >= 0)) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' -> {
                    if (i + 2 >= s.length) throw HttpProtocolException(400, "Invalid percent escape")
                    val h = Character.digit(s[i + 1], 16)
                    val l = Character.digit(s[i + 2], 16)
                    if (h < 0 || l < 0) throw HttpProtocolException(400, "Invalid percent escape")
                    out.write(h * 16 + l)
                    i += 3
                }
                c == '+' && plusIsSpace -> { out.write(' '.code); i++ }
                else -> {
                    val bytes = c.toString().toByteArray(Charsets.UTF_8)
                    out.write(bytes, 0, bytes.size)
                    i++
                }
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    fun reason(status: Int): String = when (status) {
        200 -> "OK"; 204 -> "No Content"; 304 -> "Not Modified"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 408 -> "Request Timeout"; 409 -> "Conflict"; 410 -> "Gone"
        413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"; 421 -> "Misdirected Request"
        429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"; 501 -> "Not Implemented"; 503 -> "Service Unavailable"
        504 -> "Gateway Timeout"; 505 -> "HTTP Version Not Supported"
        else -> "Status"
    }

    @Throws(IOException::class)
    fun write(output: OutputStream, r: HttpResponse) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n")
        sb.append("Content-Type: ").append(r.contentType).append("\r\n")
        sb.append("Content-Length: ").append(r.body.size).append("\r\n")
        sb.append("Connection: close\r\n")
        if (!r.headers.containsKey("Cache-Control")) sb.append("Cache-Control: no-store\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("X-Frame-Options: DENY\r\n")
        sb.append("Referrer-Policy: no-referrer\r\n")
        for ((k, v) in r.headers) {
            if (k.contains('\r') || k.contains('\n') || v.contains('\r') || v.contains('\n')) continue
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        output.write(r.body)
        output.flush()
    }
}
