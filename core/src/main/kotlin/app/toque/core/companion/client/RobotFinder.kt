package app.toque.core.companion.client

import app.toque.core.companion.Hello
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Trouver le robot sur le Wi-Fi : sondes parallèles à délais courts. L'appli fournit les adresses
 * candidates (par exemple [subnet] de sa propre adresse, ou la dernière adresse connue en premier).
 */
object RobotFinder {
    class Found(val host: String, val hello: Hello)

    private val daemon = ThreadFactory { r -> Thread(r, "toque-companion-probe").apply { isDaemon = true } }

    /** Les 254 adresses du /24 de [ownIpv4], sauf elle-même. Vide si l'adresse n'est pas une IPv4. */
    fun subnet(ownIpv4: String): List<String> {
        val parts = ownIpv4.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return emptyList()
        val base = parts.take(3).joinToString(".")
        return (1..254).map { "$base.$it" }.filter { it != ownIpv4 }
    }

    /**
     * Robots Toque qui répondent à GET /api/hello (pour l'appairage par code tapé : ceux dont
     * [Hello.pairing] est vrai). Rend la main en à peu près (candidats / [parallel]) × [timeoutMs] au pire.
     */
    fun scan(candidates: List<String>, port: Int, timeoutMs: Int = 800, parallel: Int = 32): List<Found> {
        if (candidates.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(parallel, candidates.size), daemon)
        try {
            val futures = candidates.distinct().map { h ->
                pool.submit<Found?> {
                    try {
                        val hello = CompanionClient(h, port, connectTimeoutMs = timeoutMs, readTimeoutMs = timeoutMs).hello(timeoutMs)
                        if (hello.service == Hello.SERVICE) Found(h, hello) else null
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            val out = ArrayList<Found>()
            for (f in futures) {
                try {
                    f.get()?.let { out += it }
                } catch (_: Exception) {
                }
            }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Adresse, parmi [candidates], du robot [robotId] qui connaît ce téléphone, ou null. Chaque candidat
     * doit passer la preuve (POST /api/prove) : le jeton n'est envoyé à aucun d'eux. La première preuve
     * juste gagne ; les autres sondes sont abandonnées.
     */
    fun locate(candidates: List<String>, port: Int, token: String, phoneId: String, robotId: String,
               timeoutMs: Int = 1_200, parallel: Int = 32, overallMs: Long = 20_000): String? {
        val list = candidates.distinct()
        if (list.isEmpty()) return null
        val pool = Executors.newFixedThreadPool(minOf(parallel, list.size), daemon)
        val cs = ExecutorCompletionService<String?>(pool)
        val futures = ArrayList<Future<String?>>()
        try {
            for (h in list) futures += cs.submit {
                try {
                    if (CompanionClient(h, port, token, phoneId, robotId, timeoutMs, timeoutMs).verify(timeoutMs)) h else null
                } catch (_: Throwable) {
                    null
                }
            }
            val deadline = System.nanoTime() / 1_000_000L + overallMs
            repeat(list.size) {
                val left = deadline - System.nanoTime() / 1_000_000L
                if (left <= 0) return null
                val f = cs.poll(left, TimeUnit.MILLISECONDS) ?: return null
                val r = try {
                    f.get()
                } catch (_: Exception) {
                    null
                }
                if (r != null) return r
            }
            return null
        } finally {
            for (f in futures) f.cancel(true)
            pool.shutdownNow()
        }
    }
}
