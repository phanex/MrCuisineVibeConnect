package app.toque.core

import app.toque.core.machine.PortCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Vérifications avant l'ouverture du port série : lecture de la réponse du script, tri entre les
 * anciens ponts de l'appli et les autres processus, et le script lui-même sur un faux /proc.
 */
class PortCheckTest {
    private val dev = "/dev/ttyMT0"

    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().use { it.readText() }.replace("\r", "")

    /** Réponse relevée sur un SKMC 1100 A1 (Magisk 31, busybox 1.36.1), Toque en marche : son pont tient le port. */
    private val robot = fixture("port-check-skmc1100.txt")

    @Test fun readsTheRobotsAnswer() {
        val r = PortCheck.parse(robot)
        assertTrue(r.root)
        assertEquals("/data/adb/magisk/busybox", r.busybox)
        assertTrue(r.device)
        assertTrue(r.scanned)
        assertEquals(listOf("6272", "6274", "6275"), r.holders.map { it.pid })
        assertEquals("/data/adb/magisk/busybox cat /dev/ttyMT0", r.holders[1].command)
        // Le shell du pont et ses deux cat : tous à l'appli, rien d'étranger.
        assertTrue(r.holders.all { PortCheck.ownBridge(it.command, dev) })
    }

    @Test fun aFreePort() {
        val r = PortCheck.parse("0\nBB=/data/adb/magisk/busybox\nDEV=ok\nS\nEND\n")
        assertTrue(r.root && r.device && r.scanned)
        assertTrue(r.holders.isEmpty())
    }

    @Test fun refusedRootMissingBusyboxMissingPort() {
        // su refusé : le script ne tourne pas.
        assertFalse(PortCheck.parse("").root)
        assertFalse(PortCheck.parse("10047\nBB=/system/xbin/busybox\nDEV=ok\n").root)
        val noBusybox = PortCheck.parse("0\nDEV=ok\n")
        assertTrue(noBusybox.root)
        assertNull(noBusybox.busybox)
        val noPort = PortCheck.parse("0\nBB=/data/adb/magisk/busybox\n")
        assertFalse(noPort.device)
        assertFalse(noPort.scanned)
    }

    @Test fun aScanThatSawNothingOrStoppedEarlyIsNotTrusted() {
        // « ls » sans effet (busybox qui ne convient pas) : la fin est là, mais aucun processus n'a été vu.
        assertFalse(PortCheck.parse("0\nBB=/system/xbin/busybox\nDEV=ok\nEND\n").scanned)
        // Interrompu (délai dépassé) : pas de fin.
        assertFalse(PortCheck.parse("0\nBB=/data/adb/magisk/busybox\nDEV=ok\nS\nH 12 cat /dev/ttyMT0\n").scanned)
    }

    @Test fun otherProcessesAreForeign() {
        assertFalse(PortCheck.ownBridge("de.silpion.mc2", dev))
        assertFalse(PortCheck.ownBridge("/system/bin/mtk_agpsd", dev))
        assertFalse(PortCheck.ownBridge("", dev))
        assertTrue(PortCheck.ownBridge("cat", dev))
        assertTrue(PortCheck.ownBridge("/data/adb/magisk/busybox cat", dev))
    }

    /** Le numéro d'un processus finit dans une commande `kill` lancée en root : seuls des chiffres sont acceptés. */
    @Test fun aProcessNumberIsOnlyDigits() {
        val r = PortCheck.parse("0\nBB=/data/adb/magisk/busybox\nDEV=ok\nS\nH 12;reboot cat /dev/ttyMT0\nH \$(reboot) cat /dev/ttyMT0\nH 77 cat /dev/ttyMT0\nEND\n")
        assertEquals(listOf("77"), r.holders.map { it.pid })
        assertEquals(listOf("6274"), PortCheck.parseSlowScan("6274 cat /dev/ttyMT0\nsuite d'une ligne de commande coupée cat /dev/ttyMT0\n").map { it.pid })
    }

    @Test fun readsTheOriginalScan() {
        val h = PortCheck.parseSlowScan("6274 /data/adb/magisk/busybox cat /dev/ttyMT0 \n\n3747 de.silpion.mc2 \n")
        assertEquals(listOf(PortCheck.Holder("6274", "/data/adb/magisk/busybox cat /dev/ttyMT0"), PortCheck.Holder("3747", "de.silpion.mc2")), h)
    }

    /**
     * Le script envoyé à `su` sur le robot est celui du fichier fixtures/port-check.sh, à la commande
     * `am force-stop` près : ce fichier a été lancé tel quel sur un SKMC 1100 A1. Le modifier demande
     * de le réessayer sur un robot.
     */
    @Test fun theScriptSentToTheRobot() {
        val tried = fixture("port-check.sh").trim()
        assertEquals(tried, PortCheck.script(dev, null))
        val stop = "am force-stop de.silpion.mc2 > /dev/null 2>&1; "
        val full = PortCheck.script(dev, "de.silpion.mc2")
        assertEquals(tried, full.replace(stop, ""))
        // L'appli d'origine est arrêtée après les vérifications de root, busybox et port, avant la recherche.
        assertTrue(full.indexOf("exit 0; fi; ") < full.indexOf(stop))
        assertTrue(full.indexOf(stop) < full.indexOf(" ls -l "))
    }

    /** Smart : son port, et l'appli de test usine arrêtée avec celle d'origine, au même endroit du script. */
    @Test fun theSmartAlsoStopsItsFactoryApp() {
        val smart = "/dev/ttyMT1"
        val stops = "am force-stop com.tecpal.device.mc30 > /dev/null 2>&1; am force-stop com.tecpal.testapp.mc30 > /dev/null 2>&1; "
        val full = PortCheck.script(smart, "com.tecpal.device.mc30", alsoStop = listOf("com.tecpal.testapp.mc30"))
        assertEquals(PortCheck.script(smart, null), full.replace(stops, ""))
        assertTrue(full.indexOf("exit 0; fi; ") < full.indexOf(stops))
        assertTrue(full.indexOf(stops) < full.indexOf(" ls -l "))
        val root = FakeRoot("0\nBB=$bb\nDEV=ok\nS\nEND\n")
        assertEquals(bb, PortCheck.prepare(smart, "com.tecpal.device.mc30", root.run, root.pause, listOf("com.tecpal.testapp.mc30")))
        assertEquals(listOf(full), root.commands)
    }

    // ------------------------------------------------------------------ la préparation du port, commande après commande

    /** Root d'essai : note les commandes reçues et rend les réponses prévues, dans l'ordre. */
    private class FakeRoot(vararg answers: String) {
        val commands = ArrayList<String>()
        val pauses = ArrayList<Long>()
        private val left = ArrayList(answers.toList())
        val run: (String, Long) -> String = { command, _ ->
            commands += command
            if (left.isEmpty()) "" else left.removeAt(0)
        }
        val pause: (Long) -> Unit = { pauses += it }
    }

    private val vendor = "de.silpion.mc2"
    private val bb = "/data/adb/magisk/busybox"
    private val free = "0\nBB=$bb\nDEV=ok\nS\nEND\n"

    private fun held(vararg holders: String) = "0\nBB=$bb\nDEV=ok\nS\n" + holders.joinToString("") { "H $it\n" } + "END\n"

    private fun prepare(root: FakeRoot) = PortCheck.prepare(dev, vendor, root.run, root.pause)

    @Test fun aFreePortTakesOneRootCall() {
        val root = FakeRoot(free)
        assertEquals(bb, prepare(root))
        assertEquals(listOf(PortCheck.script(dev, vendor)), root.commands)
        assertTrue(root.pauses.isEmpty())
    }

    @Test fun oldBridgesAreKilledInOneCall() {
        val root = FakeRoot(robot)
        assertEquals(bb, prepare(root))
        assertEquals(listOf(PortCheck.script(dev, vendor), "kill 6272 6274 6275"), root.commands)
        assertTrue(root.pauses.isEmpty())
    }

    /** Un autre processus tient le port, encore au second regard : refus, et les anciens ponts sont tués quand même. */
    @Test fun aForeignProcessRefusesTheOpening() {
        val both = held("3747 de.silpion.mc2", "6274 $bb cat /dev/ttyMT0")
        val root = FakeRoot(both, both)
        val e = assertThrows(IOException::class.java) { prepare(root) }
        assertEquals("Le port série est déjà utilisé par : 3747 de.silpion.mc2", e.message)
        assertEquals(listOf(PortCheck.SECOND_LOOK_MS), root.pauses)
        // Le second regard n'arrête pas de nouveau l'appli d'origine.
        assertEquals(listOf(PortCheck.script(dev, vendor), PortCheck.script(dev, null), "kill 6274"), root.commands)
    }

    /** L'appli d'origine tuée à l'instant tient encore le port au premier regard, plus au second : on ouvre. */
    @Test fun aProcessKilledAMomentAgoIsGoneAtTheSecondLook() {
        val root = FakeRoot(held("3747 de.silpion.mc2"), free)
        assertEquals(bb, prepare(root))
        assertEquals(listOf(PortCheck.script(dev, vendor), PortCheck.script(dev, null)), root.commands)
        assertEquals(listOf(PortCheck.SECOND_LOOK_MS), root.pauses)
    }

    /** Passe rapide sans rien voir (busybox dont le « ls » ne convient pas) : la recherche d'origine décide. */
    @Test fun withoutATrustedScanTheOriginalSearchDecides() {
        val root = FakeRoot("0\nBB=/system/xbin/busybox\nDEV=ok\nEND\n", "6274 /system/xbin/busybox cat /dev/ttyMT0 \n")
        assertEquals("/system/xbin/busybox", prepare(root))
        assertEquals(listOf(PortCheck.script(dev, vendor), PortCheck.slowScan("/system/xbin/busybox", dev), "kill 6274"), root.commands)

        val taken = FakeRoot("0\nBB=/system/xbin/busybox\nDEV=ok\nEND\n", "3747 de.silpion.mc2 \n")
        val e = assertThrows(IOException::class.java) { prepare(taken) }
        assertEquals("Le port série est déjà utilisé par : 3747 de.silpion.mc2", e.message)
        assertTrue(taken.pauses.isEmpty())
    }

    @Test fun refusalsSayWhy() {
        fun refused(answer: String): String {
            val root = FakeRoot(answer)
            val e = assertThrows(IOException::class.java) { prepare(root) }
            // Rien d'autre n'est tenté : une seule commande.
            assertEquals(1, root.commands.size)
            return e.message!!
        }
        assertEquals("Accès root refusé (Magisk installé et autorisé ?)", refused(""))
        assertEquals("busybox introuvable (fourni par Magisk)", refused("0\nDEV=ok\n"))
        assertEquals("/dev/ttyMT0 absent", refused("0\nBB=$bb\n"))
    }

    // ------------------------------------------------------------------ le script, sur un faux /proc

    private class Fake(val root: File, val proc: File, val port: File, val busybox: File)

    /** Faux /proc dans un dossier temporaire ; null là où il n'y a ni `sh` ni liens symboliques (Windows). */
    private fun fake(): Fake? {
        if (!File("/bin/sh").canExecute()) return null
        val root = Files.createTempDirectory("toque-portcheck").toFile()
        val port = File(root, "ttyFAKE").apply { writeText("") }
        // « busybox » d'essai : lance simplement l'outil du système du même nom.
        val bb = File(root, "bb").apply { writeText("#!/bin/sh\nexec \"\$@\"\n"); setExecutable(true) }
        return Fake(root, File(root, "proc").apply { mkdirs() }, port, bb)
    }

    private fun Fake.process(pid: Int, cmdline: String, vararg fds: Pair<Int, String>): Boolean {
        val dir = File(proc, "$pid/fd").apply { mkdirs() }
        File(proc, "$pid/cmdline").writeBytes(cmdline.toByteArray())
        return try {
            for ((n, target) in fds) Files.createSymbolicLink(File(dir, "$n").toPath(), File(target).toPath())
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun Fake.run(vararg busyboxes: String): PortCheck.Result {
        val p = ProcessBuilder("/bin/sh", "-c", PortCheck.script(port.path, null, proc.path, busyboxes.toList()))
            .redirectErrorStream(false).start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("script trop long", p.waitFor(20, TimeUnit.SECONDS))
        return PortCheck.parse(out)
    }

    @Test fun theScriptFindsWhoHoldsThePort() {
        val f = fake()
        assumeTrue("ni sh ni liens symboliques ici", f != null)
        f!!
        try {
            val dev = f.port.path
            var ok = f.process(101, "/data/adb/magisk/busybox\u0000cat\u0000$dev\u0000", 0 to "/dev/null", 3 to dev)
            // Deux fois le port : une seule ligne ; l'appli d'origine, avec les zéros qui terminent son nom.
            ok = ok && f.process(202, "de.silpion.mc2\u0000\u0000\u0000", 1 to dev, 5 to dev, 7 to "/dev/null")
            ok = ok && f.process(303, "/system/bin/logd\u0000", 0 to "/dev/null", 4 to "$dev.autre")
            // Ligne de commande piégeuse : antislash, retour à la ligne, guillemets.
            ok = ok && f.process(404, "we\\cird \"x\"\nname\u0000-a\u0000", 9 to dev)
            File(f.proc, "self/fd").mkdirs()
            assumeTrue("liens symboliques refusés ici", ok)

            val r = f.run("/nulle/part/busybox", f.busybox.path)
            assertEquals(f.busybox.path, r.busybox)
            assertTrue(r.device)
            assertTrue(r.scanned)
            assertEquals(listOf(
                PortCheck.Holder("101", "/data/adb/magisk/busybox cat $dev"),
                PortCheck.Holder("202", "de.silpion.mc2"),
                PortCheck.Holder("404", "we\\cird \"x\" name -a"),
            ), r.holders)
            assertTrue(PortCheck.ownBridge(r.holders[0].command, dev))
            assertFalse(PortCheck.ownBridge(r.holders[1].command, dev))
        } finally {
            f.root.deleteRecursively()
        }
    }

    @Test fun theScriptStopsWithoutBusyboxOrPort() {
        val f = fake()
        assumeTrue("pas de sh ici", f != null)
        f!!
        try {
            assumeTrue("liens symboliques refusés ici", f.process(101, "cat\u0000", 3 to f.port.path))
            // Aucun busybox : pas de recherche, donc rien à croire.
            val none = f.run("/nulle/part/busybox")
            assertNull(none.busybox)
            assertTrue(none.device)
            assertFalse(none.scanned)
            assertTrue(none.holders.isEmpty())
            // Port absent.
            f.port.delete()
            val gone = f.run(f.busybox.path)
            assertEquals(f.busybox.path, gone.busybox)
            assertFalse(gone.device)
            assertFalse(gone.scanned)
        } finally {
            f.root.deleteRecursively()
        }
    }

    @Test fun aScanThatSeesNoProcessAsksForTheOriginalOne() {
        val f = fake()
        assumeTrue("pas de sh ici", f != null)
        f!!
        try {
            // /proc sans aucun processus lisible (comme un « ls » qui ne convient pas) : fin atteinte, rien vu.
            val r = f.run(f.busybox.path)
            assertTrue(r.device)
            assertFalse(r.scanned)
        } finally {
            f.root.deleteRecursively()
        }
    }
}
