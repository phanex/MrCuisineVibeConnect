package app.toque.core.machine

import app.toque.core.i18n.tr
import app.toque.core.protocol.BoardCodec
import app.toque.core.protocol.ConnectCodec
import app.toque.core.protocol.SmartCodec
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Exécution de commandes via `su` (Magisk). */
object RootShell {
    class Result(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
    }

    private fun drain(input: InputStream): Thread {
        val buffer = ByteArrayOutputStream()
        val t = Thread {
            try {
                val b = ByteArray(4096)
                while (true) {
                    val n = input.read(b)
                    if (n < 0) break
                    synchronized(buffer) { buffer.write(b, 0, n) }
                }
            } catch (_: IOException) {
            }
        }
        t.isDaemon = true
        t.start()
        drained[t] = buffer
        return t
    }

    private val drained = java.util.WeakHashMap<Thread, ByteArrayOutputStream>()

    fun run(command: String, timeoutMs: Long = 10_000): Result {
        val p = try {
            ProcessBuilder("su", "-c", command).start()
        } catch (e: IOException) {
            return Result(-1, "", "su introuvable : ${e.message}")
        }
        try {
            p.outputStream.close()
        } catch (_: IOException) {
        }
        val to = drain(p.inputStream)
        val te = drain(p.errorStream)
        var code = -1
        val waiter = Thread {
            try {
                code = p.waitFor()
            } catch (_: InterruptedException) {
            }
        }
        waiter.start()
        waiter.join(timeoutMs)
        if (waiter.isAlive) {
            p.destroy()
            waiter.interrupt()
            return Result(-2, text(to), "délai dépassé")
        }
        to.join(500)
        te.join(500)
        return Result(code, text(to), text(te))
    }

    private fun text(t: Thread): String {
        val b = drained[t] ?: return ""
        synchronized(b) { return b.toString("UTF-8") }
    }

    fun available(): Boolean = run("id -u", 8_000).out.trim() == "0"
}

/**
 * Vérifications faites en root avant d'ouvrir le port série : root, busybox, le port lui-même,
 * l'appli d'origine arrêtée, puis qui tient déjà le port. Tout part en un seul appel à `su`.
 *
 * Les processus qui tiennent le port sont cherchés en une passe (`ls -l` de tous les `/proc/<pid>/fd`,
 * 0,2 s sur le robot) ; la recherche d'origine lançait `readlink` pour chaque fichier ouvert du
 * système (1 400 lancements, 8 s). Elle reste la solution de repli ([slowScan]) quand la passe
 * rapide n'a rien vu du tout, par exemple avec un busybox dont le `ls` ne convient pas.
 */
internal object PortCheck {
    /** Emplacements possibles de busybox, celui de Magisk d'abord. */
    val BUSYBOX = listOf(
        "/data/adb/magisk/busybox", "/sbin/.magisk/busybox/busybox", "/data/app/com.topjohnwu.magisk*/lib/arm*/libbusybox.so",
        "/system/xbin/busybox", "/system/bin/busybox",
    )

    /** Un processus qui a le port ouvert, avec sa ligne de commande. */
    data class Holder(val pid: String, val command: String)

    /**
     * Résultat du script de [script]. [scanned] : la passe rapide a été jusqu'au bout et a bien vu
     * des processus ; sinon [holders] ne vaut rien et il faut [slowScan].
     */
    data class Result(val root: Boolean, val busybox: String?, val device: Boolean, val scanned: Boolean, val holders: List<Holder>)

    /**
     * Script des vérifications. Sorties, une par ligne : l'uid (0 en root), `BB=<busybox>`, `DEV=ok`,
     * puis `S` dès que la recherche voit un processus, `H <pid> <commande>` par processus qui tient
     * le port, et `END`. Sans busybox ou sans le port, il s'arrête avant de toucher à l'appli d'origine.
     * [alsoStop] : autres applis à arrêter avec celle d'origine (Smart : l'appli de test usine).
     * [proc] et [busybox] ne changent que dans les essais.
     */
    fun script(device: String, vendorPackage: String?, proc: String = "/proc", busybox: List<String> = BUSYBOX,
               alsoStop: List<String> = emptyList()): String =
        "D=$device; P=$proc; id -u; B=; " +
            "for b in ${busybox.joinToString(" ")}; do if [ -x \$b ]; then B=\$b; echo BB=\$b; break; fi; done; " +
            "[ -e \$D ] && echo DEV=ok; " +
            "if [ -z \"\$B\" ] || [ ! -e \$D ]; then exit 0; fi; " +
            (listOfNotNull(vendorPackage) + alsoStop).joinToString("") { "am force-stop $it > /dev/null 2>&1; " } +
            // Les lignes de ls : « /proc/<pid>/fd: » puis, pour chaque fichier ouvert, « … <n> -> <cible> ».
            "p=; last=; \$B ls -l \$P/[0-9]*/fd 2>/dev/null | \$B grep -e \"^\$P/[0-9]*/fd:\\\$\" -e \" -> \$D\\\$\" | " +
            "while IFS= read -r l; do case \"\$l\" in " +
            "\$P/*/fd:) [ -z \"\$p\" ] && echo S; p=\${l#\$P/}; p=\${p%/fd:} ;; " +
            // La ligne de commande sort telle quelle de tr : rien n'y est interprété par le shell.
            "*\" -> \$D\") if [ \"\$p\" != \"\$last\" ]; then last=\$p; echo -n \"H \$p \"; " +
            "\$B tr '\\000\\n' '  ' < \$P/\$p/cmdline 2>/dev/null; echo; fi ;; " +
            "esac; done; echo END"

    fun parse(out: String): Result {
        val lines = out.lines().map { it.trim() }
        return Result(
            root = lines.firstOrNull() == "0",
            busybox = lines.firstOrNull { it.startsWith("BB=") }?.substring(3)?.takeIf { it.isNotEmpty() },
            device = lines.any { it == "DEV=ok" },
            scanned = lines.any { it == "S" } && lines.lastOrNull { it.isNotEmpty() } == "END",
            holders = lines.filter { it.startsWith("H ") }.mapNotNull { holder(it.substring(2)) },
        )
    }

    /** Recherche d'origine, fichier par fichier : une ligne `<pid> <commande>` par processus qui tient le port. */
    fun slowScan(busybox: String, device: String): String =
        "B=$busybox; for p in /proc/[0-9]*; do for f in \$p/fd/*; do " +
            "if [ \"\$(\$B readlink \$f 2>/dev/null)\" = \"$device\" ]; then " +
            "echo \"\${p#/proc/} \$(\$B tr '\\000' ' ' < \$p/cmdline)\"; break; fi; done; done"

    fun parseSlowScan(out: String): List<Holder> = out.lines().mapNotNull { holder(it) }

    /** Une ligne `<pid> <commande>` ; le numéro part dans un `kill` lancé en root : des chiffres, rien d'autre. */
    private fun holder(line: String): Holder? {
        val l = line.trim()
        val pid = l.substringBefore(' ')
        if (pid.isEmpty() || !pid.all { it in '0'..'9' }) return null
        return Holder(pid, l.substringAfter(' ', "").trim())
    }

    /** Ancien pont de cette appli resté en vie (Toque mis à jour ou tué) : son shell, et ses deux `cat`. */
    fun ownBridge(command: String, device: String): Boolean =
        command.contains("cat") && command.contains(device) || command.trim() == "cat" || command.contains("busybox cat")

    /**
     * Fait les vérifications et libère le port : renvoie le busybox trouvé, ou lève une IOException
     * qui dit pourquoi le port ne peut pas être ouvert. [root] lance une commande en root et renvoie
     * ce qu'elle a écrit ; [pause] attend (rien dans les essais) ; [alsoStop] comme pour [script].
     */
    fun prepare(device: String, vendorPackage: String?, root: (command: String, timeoutMs: Long) -> String,
                pause: (Long) -> Unit = { Thread.sleep(it) }, alsoStop: List<String> = emptyList()): String {
        var check = parse(root(script(device, vendorPackage, alsoStop = alsoStop), 30_000))
        if (!check.root) throw IOException(tr("Accès root refusé (Magisk installé et autorisé ?)"))
        val bb = check.busybox ?: throw IOException(tr("busybox introuvable (fourni par Magisk)"))
        if (!check.device) throw IOException(tr("%s absent", device))

        if (check.scanned && check.holders.any { !ownBridge(it.command, device) }) {
            // Un processus de l'appli d'origine tué à l'instant peut encore tenir le port quelques
            // millisecondes ; la recherche d'origine, plus lente, ne le voyait déjà plus. Second regard.
            pause(SECOND_LOOK_MS)
            val again = parse(root(script(device, null), 20_000))
            if (again.scanned) check = again
        }
        val holders = if (check.scanned) check.holders else parseSlowScan(root(slowScan(bb, device), 20_000))
        val (own, others) = holders.partition { ownBridge(it.command, device) }
        if (own.isNotEmpty()) root("kill ${own.joinToString(" ") { it.pid }}", 5_000)
        if (others.isNotEmpty())
            throw IOException(tr("Le port série est déjà utilisé par : %s", others.joinToString { "${it.pid} ${it.command}".trim() }))
        return bb
    }

    const val SECOND_LOOK_MS = 300L
}

/**
 * Liaison série réelle avec la base moteur, via un petit pont shell exécuté en root. Exige un
 * accès exclusif : l'appli d'origine (et, sur le Smart, l'appli de test usine) est arrêtée.
 *
 * Connect : `/dev/ttyMT0`, 4800 bauds, `de.silpion.mc2`, [ConnectCodec].
 * Smart : `/dev/ttyMT1`, 9600 bauds, `com.tecpal.device.mc30` + `com.tecpal.testapp.mc30`, [SmartCodec]
 * (voir [forSmart]).
 */
class RootSerialTransport(
    private val device: String = "/dev/ttyMT0",
    private val baud: Int = 4800,
    private val vendorPackage: String? = "de.silpion.mc2",
    private val extraStopPackages: List<String> = emptyList(),
    override val codec: BoardCodec = ConnectCodec,
) : Transport {
    companion object {
        const val SMART_DEVICE = "/dev/ttyMT1"
        const val SMART_BAUD = 9600
        const val SMART_VENDOR = "com.tecpal.device.mc30"
        const val SMART_FACTORY_APP = "com.tecpal.testapp.mc30"

        /** Liaison du Monsieur Cuisine Smart. */
        fun forSmart(device: String = SMART_DEVICE, vendorPackage: String? = SMART_VENDOR) = RootSerialTransport(
            device, SMART_BAUD, vendorPackage, listOf(SMART_FACTORY_APP), SmartCodec(),
        )
    }

    override val name: String get() = tr("Base moteur (%s)", device)

    @Volatile private var process: Process? = null

    override fun open() {
        val bb = PortCheck.prepare(device, vendorPackage, { command, timeoutMs -> RootShell.run(command, timeoutMs).out },
            alsoStop = extraStopPackages)

        val script = "D=$device; B=$bb; " +
            "\$B stty -F \$D $baud raw -echo cs8 -cstopb -parenb -crtscts clocal cread || exit 3; " +
            "\$B cat \$D & R=\$!; trap 'kill \$R 2>/dev/null' EXIT INT TERM HUP; " +
            "\$B cat > \$D; " +
            // Toque s'est arrêté (fermeture, plantage, processus tué) : la carte du Smart ne
            // décompte pas et garderait la dernière commande. On envoie STOP depuis le pont.
            "for i in 1 2 3; do \$B printf '${shellOctal(codec.stopFrame())}' > \$D; \$B usleep 200000; done; " +
            "kill \$R 2>/dev/null"
        val p = ProcessBuilder("su", "-c", script).start()
        process = p
        try {
            Thread.sleep(400)
        } catch (_: InterruptedException) {
        }
        val exited = try {
            p.exitValue()
        } catch (_: IllegalThreadStateException) {
            null
        }
        if (exited != null) {
            val err = try {
                p.errorStream.bufferedReader().readText().trim()
            } catch (_: IOException) {
                ""
            }
            process = null
            throw IOException(tr("Pont série arrêté (code %d) %s", exited, err))
        }
    }

    /** Octets sous forme d'échappements octaux pour `printf` (\252\000…). */
    internal fun shellOctal(bytes: ByteArray): String =
        bytes.joinToString("") { "\\" + Integer.toOctalString(it.toInt() and 0xFF).padStart(3, '0') }

    override fun write(frame: ByteArray) {
        val p = process ?: throw IOException(tr("port fermé"))
        p.outputStream.write(frame)
        p.outputStream.flush()
    }

    override fun read(buffer: ByteArray): Int {
        val p = process ?: return -1
        return p.inputStream.read(buffer)
    }

    override fun close() {
        val p = process ?: return
        process = null
        try {
            p.outputStream.close()
        } catch (_: IOException) {
        }
        try {
            Thread.sleep(150)
        } catch (_: InterruptedException) {
        }
        p.destroy()
    }
}
