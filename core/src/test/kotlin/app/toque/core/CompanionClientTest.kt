package app.toque.core

import app.toque.core.companion.AdjustCommand
import app.toque.core.companion.AdjustRequest
import app.toque.core.companion.CatalogFilter
import app.toque.core.companion.CatalogPageWire
import app.toque.core.companion.CatalogQuery
import app.toque.core.companion.CatalogSearch
import app.toque.core.companion.CommandResult
import app.toque.core.companion.CompanionHost
import app.toque.core.companion.CompanionHub
import app.toque.core.companion.ImageData
import app.toque.core.companion.HttpLimits
import app.toque.core.companion.MemoryStore
import app.toque.core.companion.PairingPayload
import app.toque.core.companion.StateWire
import app.toque.core.companion.client.CompanionClient
import app.toque.core.companion.client.CompanionException
import app.toque.core.companion.client.RobotFinder
import app.toque.core.companion.client.StateFollower
import app.toque.core.machine.MachineLoop
import app.toque.core.machine.Programs
import app.toque.core.machine.SimBoard
import app.toque.core.machine.SimTransport
import app.toque.core.machine.MonotonicClock
import app.toque.core.protocol.Direction
import app.toque.core.recipes.Recipe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Bout à bout dans le processus : vrai serveur, vrai contrôleur sur la base simulée, client Kotlin. */
class CompanionClientTest {
    private val board = SimBoard(MonotonicClock)
    private lateinit var loop: MachineLoop
    private lateinit var hub: CompanionHub
    private val localhost = InetAddress.getByName("127.0.0.1")

    private val host = object : CompanionHost {
        override fun pause() = CommandResult.of(loop.call { it.pause() })
        override fun stop() = CommandResult.of(loop.call { it.stop() })
        override fun resume(stillAllowed: () -> Boolean) = loop.call { c ->
            if (stillAllowed()) CommandResult.of(c.resume(false)) else CommandResult.refuse("changed", "changé")
        }
        override fun adjust(prepare: () -> AdjustCommand): CommandResult = loop.call { c ->
            val a = prepare()
            a.refusal ?: CommandResult.of(c.adjust(a.speed, a.temperatureLevel,
                a.reverse?.let { if (it) Direction.COUNTER_CLOCKWISE else Direction.CLOCKWISE }, a.totalMs, false))
        }
        override fun tare(): CommandResult {
            loop.post { it.tare() }
            return CommandResult.OK
        }
        override fun clear(): CommandResult {
            loop.call { it.clearFinished() }
            return CommandResult.OK
        }
        override fun start(prepare: () -> app.toque.core.companion.StartCommand): CommandResult = loop.call { c ->
            val a = prepare()
            a.refusal ?: CommandResult.of(c.startJob(a.spec!!))
        }
        override fun catalog(query: CatalogQuery): CatalogPageWire = CatalogSearch.page(CompanionData.index, query)
        override fun recipe(id: String): Recipe? = servingsHost.recipe(id)
        override fun baseRecipe(id: String): Recipe? = servingsHost.baseRecipe(id)
        override fun image(recipeId: String): ImageData? =
            if (recipeId == "perso-veloute") ImageData(ByteArray(2048) { it.toByte() }, "image/png") else null
        override fun baseServings(recipeId: String) = servingsHost.baseServings(recipeId)
        override fun setServings(recipeId: String, people: Int, stillAllowed: () -> Boolean) =
            servingsHost.setServings(recipeId, people, stillAllowed)
    }
    /** Le nombre de personnes, comme l'hôte d'essai des autres tests. */
    private val servingsHost = FakeHost()

    @Before fun setUp() {
        hub = CompanionHub(host, store = MemoryStore())
        loop = MachineLoop({ SimTransport(board) }, onSnapshot = { hub.updateMachine(it) }, onEvent = { hub.onMachineEvent(it) })
        loop.start()
        hub.start(0, localhost)
        waitFor("liaison prête") { loop.call { it.snapshot().ready } }
    }

    @After fun tearDown() {
        hub.stop()
        loop.shutdown()
    }

    private fun waitFor(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            if (cond()) return
            Thread.sleep(50)
        }
        fail("attente trop longue : $what")
    }

    private fun pairedClient(): CompanionClient {
        val c = CompanionClient("127.0.0.1", hub.port)
        val o = hub.pairing.open()
        c.pairWithCode(o.code)
        return c
    }

    @Test fun pairsByCodeAndByQrThenDrivesTheMachine() {
        val c = CompanionClient("127.0.0.1", hub.port)
        assertFalse(c.hello().pairing)
        val o = hub.pairing.open()
        assertTrue(c.hello().pairing)
        try {
            c.pairWithCode("000000".takeIf { it != o.code } ?: "000001")
            fail()
        } catch (e: CompanionException) {
            assertEquals("wrong_code", e.error.code)
        }
        val reply = c.pairWithCode(o.code)
        assertEquals(hub.pairing.robotId, reply.robot.id)
        assertEquals(reply.token, c.token)

        // Deuxième téléphone par QR code ; le texte ne sert qu'une fois.
        hub.pairing.open()
        val qr = hub.pairingPayload("127.0.0.1")!!
        assertTrue(qr.length <= 200)
        val c2 = CompanionClient("0.0.0.0", 1)
        assertEquals("Téléphone 2", c2.pairWithQr(qr).phone.name)
        assertEquals(hub.port, c2.port)
        try {
            CompanionClient("0.0.0.0", 1).pairWithQr(qr)
            fail()
        } catch (e: CompanionException) {
            assertEquals(410, e.status)
        }
        // Un QR code d'un autre robot (même adresse) est refusé.
        hub.pairing.open()
        val forged = PairingPayload.parse(hub.pairingPayload("127.0.0.1")!!)!!.copy(robotId = "000000000000")
        try {
            CompanionClient("0.0.0.0", 1).pairWithPayload(forged)
            fail()
        } catch (e: java.io.IOException) {
            assertFalse(e is CompanionException)
        }

        // Cuisson manuelle lancée « sur le robot », puis pause, reprise, réglage, arrêt depuis le téléphone.
        assertTrue(loop.call { it.startJob(Programs.manual(2, 0, Direction.CLOCKWISE, 120_000)) }.ok)
        waitFor("en cours") { c.state().job?.state == "RUNNING" }
        assertTrue(c.pause().ok)
        waitFor("en pause") { c.state().can.resume.allowed }
        assertTrue(c.state().job!!.pause!!.origin.self)
        assertTrue(c.resume().ok)
        waitFor("reprise") { c.state().job?.state == "RUNNING" }
        assertTrue(c.adjust(AdjustRequest(addMs = 60_000)).ok)
        waitFor("durée") { c.state().job?.totalMs == 180_000L }
        // Couvercle ouvert pendant une pause : la reprise est refusée, même couvercle refermé.
        assertTrue(c.pause().ok)
        waitFor("en pause") { c.state().job?.state == "PAUSED" }
        board.lidOpen = true
        Thread.sleep(300)
        board.lidOpen = false
        waitFor("refus") { c.state().can.resume.code == "lid_opened_during_pause" }
        try {
            c.resume()
            fail()
        } catch (e: CompanionException) {
            assertEquals(409, e.status)
            assertEquals("lid_opened_during_pause", e.error.code)
        }
        assertTrue(c.stop().ok)
        waitFor("arrêt") { c.state().job?.state == "ABORTED" }
        assertTrue(c.tare().ok)

        // Recette envoyée puis retirée ; catalogue, fiche, image, journal.
        assertTrue(c.sendRecipe("perso-veloute").ok)
        assertEquals("perso-veloute", c.state().waiting!!.recipeId)
        assertTrue(c.removeWaiting().ok)
        assertNull(c.state().waiting)
        assertEquals(25, c.recipes(limit = 50).total)
        assertEquals(listOf("perso-veloute"), c.recipes("potimarron velouté").items.map { it.id }.take(1))
        assertTrue(c.recipes(filter = CatalogFilter.FAVORITES).items.all { it.favorite })
        // Pastilles du robot : la liste avec la première page, une catégorie (accents encodés), « 30 min max ».
        assertEquals("Soupes", c.recipes().filters!!.last().category)
        val desserts = c.recipes(filter = CatalogFilter.CATEGORY, category = "Desserts", limit = 50)
        assertEquals(12, desserts.total)
        assertTrue(desserts.items.all { it.category == "Desserts" })
        assertEquals(0, c.recipes(filter = CatalogFilter.CATEGORY, category = "Entrées & plats").total)
        assertTrue(c.recipes(filter = CatalogFilter.QUICK, limit = 50).items.all { it.totalMinutes in 1..30 })
        assertNull(c.recipes(offset = 20).filters)
        assertEquals(4, c.recipe("perso-veloute").steps.size)
        // Nombre de personnes : la fiche revient à ce nombre ; refus lisibles.
        assertEquals(6, c.setServings("perso-veloute", 6).servings)
        assertEquals(6, c.recipe("perso-veloute").servings)
        // ?base=1 : à son propre nombre, le choix reste enregistré.
        assertEquals(4, c.recipe("perso-veloute", base = true).servings)
        assertEquals(6, c.recipe("perso-veloute").servings)
        try {
            c.setServings("perso-veloute", 20)
            fail()
        } catch (e: CompanionException) {
            assertEquals(400, e.status)
            assertEquals("servings_range", e.error.code)
        }
        assertEquals(4, c.setServings("perso-veloute", 4).servings)
        assertEquals(2048, c.image("perso-veloute")!!.size)
        assertNull(c.image("r1"))
        val journal = c.journal()
        assertTrue(journal.entries.any { it.kind == "job_stopped" && it.origin.self })
        assertTrue(journal.entries.any { it.kind == "recipe_sent" })
        assertEquals(journal.lastSeq, c.state().journalSeq)
        assertTrue(c.journal(after = journal.lastSeq).entries.isEmpty())

        // « Oublier » : le jeton ne vaut plus rien.
        val token = c.token!!
        c.forget()
        assertNull(c.token)
        c.token = token
        // Retiré : le robot ne peut plus prouver qu'il connaît ce téléphone, le jeton ne part même pas.
        try {
            c.state()
            fail()
        } catch (e: app.toque.core.companion.client.RobotProofException) {
        }
    }

    /** Cuisson manuelle lancée depuis le téléphone : vrai serveur, vrai contrôleur, base simulée. */
    @Test fun startsAManualCookingFromThePhone() {
        val c = pairedClient()
        // Interrupteur du robot coupé par défaut tant que l'appli ne l'a pas donné.
        assertEquals("disabled", c.state().can.start.code)
        try {
            c.start(app.toque.core.companion.StartRequest(seconds = 120, tempC = 0, speed = 2))
            fail()
        } catch (e: CompanionException) {
            assertEquals(409, e.status)
            assertEquals("disabled", e.error.code)
        }
        hub.setRemoteStart(true)
        waitFor("lancement permis : ${c.state().can.start} ${c.state().machine}") { c.state().can.start.allowed }
        val reply = c.start(app.toque.core.companion.StartRequest(seconds = 120, tempC = 0, speed = 2, reverse = true, accessory = "BLADE"))
        assertTrue(reply.ok)
        // La réponse annonce une révision dont l'état montre déjà la cuisson.
        val st = c.state()
        assertTrue(st.revision >= reply.revision)
        assertTrue(st.job!!.state in setOf("STARTING", "RUNNING"))
        assertEquals(120_000L, st.job!!.totalMs)
        assertTrue(st.job!!.reverse)
        assertEquals("busy", st.can.start.code)
        val started = c.journal().entries.first { it.kind == "job_started" }
        assertTrue(started.origin.self)
        assertEquals("Démarrage depuis Téléphone 1 : Cuisson manuelle", started.text)
        // Rien d'autre ne démarre : un second lancement est refusé.
        try {
            c.start(app.toque.core.companion.StartRequest(seconds = 60, tempC = 0, speed = 2))
            fail()
        } catch (e: CompanionException) {
            assertEquals("busy", e.error.code)
        }
        assertTrue(c.stop().ok)
        waitFor("arrêt") { c.state().job?.state == "ABORTED" }
        // Le programme arrêté reste affiché jusqu'au « OK », du robot ou d'un téléphone.
        assertTrue(c.state().can.clear.allowed)
        assertEquals("Arrêté", c.state().job!!.endReason)
        // Un programme fini encore affiché n'empêche pas de relancer.
        waitFor("permis") { c.state().can.start.allowed }
        assertTrue(c.start(app.toque.core.companion.StartRequest(seconds = 30, tempC = 0, speed = 3)).ok)
        assertTrue(c.stop().ok)
    }

    /** « OK » depuis le téléphone : le vrai contrôleur retire le programme arrêté, la réponse le montre déjà. */
    @Test fun clearsAnEndedJobLikeTheRobotsOkButton() {
        val c = pairedClient()
        try {
            c.clear()
            fail()
        } catch (e: CompanionException) {
            assertEquals(409, e.status)
            assertEquals("nothing_ended", e.error.code)
        }
        assertTrue(loop.call { it.startJob(Programs.manual(2, 0, Direction.CLOCKWISE, 120_000)) }.ok)
        waitFor("en cours") { c.state().job?.state == "RUNNING" }
        assertEquals("nothing_ended", c.state().can.clear.code)
        assertTrue(c.stop().ok)
        waitFor("arrêt") { c.state().job?.state == "ABORTED" }
        val seq = c.state().journalSeq
        val reply = c.clear()
        assertTrue(reply.ok)
        val st = c.state()
        assertTrue(st.revision >= reply.revision)
        assertNull(st.job)
        assertEquals("nothing_ended", st.can.clear.code)
        // Aucune ligne de journal pour un « OK ».
        assertEquals(seq, st.journalSeq)
    }

    @Test fun followerSeesChangesOutagesAndRemoval() {
        val c = pairedClient()
        val states = CopyOnWriteArrayList<StateWire>()
        val unreachable = CountDownLatch(1)
        val reachable = CountDownLatch(1)
        val unauthorized = CountDownLatch(1)
        val f = StateFollower(c, object : StateFollower.Listener {
            override fun onState(state: StateWire) { states += state }
            override fun onUnreachable(error: Exception) = unreachable.countDown()
            override fun onReachable() = reachable.countDown()
            override fun onUnauthorized() = unauthorized.countDown()
        }, waitSeconds = 5)
        f.start()
        waitFor("premier état") { states.isNotEmpty() }
        val t0 = System.nanoTime()
        hub.pairing.open()
        assertTrue(c.sendRecipe("r1").ok)
        waitFor("changement vu", 2_000) { states.any { it.waiting?.recipeId == "r1" } }
        assertTrue((System.nanoTime() - t0) < 1_500_000_000L)
        // Robot injoignable puis de retour sur le même port.
        val port = hub.port
        hub.stop()
        assertTrue(unreachable.await(5, TimeUnit.SECONDS))
        hub.start(port, localhost)
        assertTrue(reachable.await(10, TimeUnit.SECONDS))
        // Retiré sur le robot : le suiveur le dit et s'arrête.
        for (p in hub.pairing.list) hub.pairing.remove(p.id)
        assertTrue(unauthorized.await(30, TimeUnit.SECONDS))
        waitFor("arrêt du suiveur") { !f.isRunning }
        f.stop()
    }

    @Test fun findsTheRobotAmongCandidates() {
        val c = pairedClient()
        hub.pairing.open()
        val found = RobotFinder.scan(listOf("127.0.0.3", "127.0.0.1", "127.0.0.2"), hub.port, timeoutMs = 500)
        assertEquals(listOf("127.0.0.1"), found.map { it.host })
        assertTrue(found[0].hello.pairing)
        val who = c.robot()
        assertEquals(hub.pairing.robotId, who.robot.id)
        val pid = c.phoneId!!
        assertEquals("127.0.0.1", RobotFinder.locate(listOf("127.0.0.2", "127.0.0.1"), hub.port, c.token!!, pid, who.robot.id, timeoutMs = 500))
        assertNull(RobotFinder.locate(listOf("127.0.0.1"), hub.port, c.token!!, pid, "ffffffffffff", timeoutMs = 500))
        assertNull(RobotFinder.locate(listOf("127.0.0.1"), hub.port, "0".repeat(64), pid, who.robot.id, timeoutMs = 500))
        assertNull(RobotFinder.locate(listOf("127.0.0.1"), hub.port, c.token!!, "00000000", who.robot.id, timeoutMs = 500))
        assertEquals(253, RobotFinder.subnet("192.168.0.12").size)
        assertTrue(RobotFinder.subnet("pas une ip").isEmpty())
        assertNotNull(c.state())
    }

    @Test fun longWaitAndAnOlderRobotsCap() {
        // Le téléphone en poche demande 120 s ; le serveur l'accepte (filet de traitement au-delà).
        val limits = HttpLimits()
        assertTrue(limits.maxLongPollMs >= 120_000 && limits.handlerDeadlineMs > limits.maxLongPollMs + 10_000)
        // Un robot au plafond plus bas (25 s sur les anciens, 1 s ici) ramène l'attente à son plafond, sans erreur.
        val old = CompanionHub(host, store = MemoryStore(), limits = HttpLimits(maxLongPollMs = 1_000))
        old.start(0, localhost)
        try {
            val c = CompanionClient("127.0.0.1", old.port)
            c.pairWithCode(old.pairing.open().code)
            val rev = c.state().revision
            val t0 = System.nanoTime()
            val s = c.state(rev, 120)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals(rev, s.revision)
            assertTrue("$ms ms", ms in 800..5_000)
            // Délai de lecture du client : l'attente demandée plus sa marge.
            assertEquals(130_000, c.readTimeoutMs + 120 * 1000)
        } finally {
            old.stop()
        }
    }

    @Test fun followerPacingNudgeAndStaleWait() {
        val c = pairedClient()
        val elapsed = java.util.concurrent.atomic.AtomicLong(0)
        val retries = CopyOnWriteArrayList<Long>()
        val answers = java.util.concurrent.atomic.AtomicInteger()
        val unreachable = java.util.concurrent.atomic.AtomicInteger()
        val states = CopyOnWriteArrayList<StateWire>()
        val pacing = object : StateFollower.Pacing {
            override fun waitSeconds() = 60
            override fun backoffMs(failures: Int) = 60_000L
            override fun locate(failures: Int) = false
        }
        val f = StateFollower(c, object : StateFollower.Listener {
            override fun onState(state: StateWire) { states += state }
            override fun onAnswer() { answers.incrementAndGet() }
            override fun onUnreachable(error: Exception) { unreachable.incrementAndGet() }
            override fun onRetry(failures: Int, delayMs: Long) { retries += delayMs }
        }, pacing = pacing, elapsedMs = { elapsed.get() })
        f.start()
        waitFor("premier état") { states.isNotEmpty() && answers.get() >= 1 }
        Thread.sleep(300)
        // Attente longue en cours : la couper pour un nouveau rythme ne compte pas comme une panne.
        f.nudge(restart = true)
        Thread.sleep(300)
        assertEquals(0, unreachable.get())
        assertTrue(f.isRunning)
        hub.pairing.open()
        assertTrue(c.sendRecipe("r1").ok)
        waitFor("changement vu après la relance", 3_000) { states.any { it.waiting?.recipeId == "r1" } }
        // Attente qui dure bien au-delà de son terme (le téléphone a dormi) : coupée, comptée comme une panne.
        assertFalse(f.cutIfStale())
        // (laisser partir l'attente suivante : sa connexion doit exister pour être coupée)
        Thread.sleep(500)
        elapsed.addAndGet(200_000)
        assertTrue(f.cutIfStale())
        waitFor("panne vue") { unreachable.get() == 1 }
        // Pause du rythme (60 s) : un réveil la coupe, l'essai suivant part aussitôt.
        waitFor("pause") { retries.size == 1 }
        assertEquals(60_000L, retries[0])
        f.nudge()
        waitFor("réessai sans attendre", 3_000) { answers.get() >= 3 }
        f.stop()
    }
}
