package app.toque.core.machine

import app.toque.core.i18n.tr
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Boucle de pilotage : un fil unique émet une trame toutes les [periodMs] (cadence du robot :
 * 100 ms pour le Connect, 200 ms pour le Smart), reçoit les états, rouvre la liaison si elle
 * tombe. Toute interaction avec le contrôleur passe par [call].
 *
 * **Chien de garde.** La détection de liaison perdue du contrôleur (`staleMs`) tourne sur le fil machine : si ce fil
 * se bloque (écriture qui ne rend pas la main, verrou, boucle infinie), plus aucune trame ne part et plus rien ne
 * le détecte. Un fil indépendant surveille donc l'heure du dernier tour ; au-delà de [stallMs] de silence il
 * (1) signale un défaut à l'interface, (2) envoie lui-même des trames STOP, (3) ferme la liaison, ce qui débloque
 * une écriture coincée : le fil machine reprend alors par le chemin habituel (écriture en échec, défaut, réouverture).
 * Si le silence atteint [fatalStallMs], il appelle [onUnrecoverable] (le service redémarre alors son processus : la
 * nouvelle boucle émet des trames STOP). L'ouverture de la liaison est exemptée : elle peut légitimement durer des
 * dizaines de secondes (demande d'autorisation root) et rien n'est piloté pendant ce temps.
 */
class MachineLoop(
    private val transportFactory: () -> Transport,
    private val clock: Clock = MonotonicClock,
    config: ControllerConfig = ControllerConfig(),
    private val periodMs: Long = 100,
    private val onSnapshot: (Snapshot) -> Unit = {},
    private val onEvent: (MachineEvent) -> Unit = {},
    private val log: (String) -> Unit = {},
    /**
     * Silence de la boucle (hors ouverture de la liaison) à partir duquel le chien de garde intervient. Au-dessus de
     * `ControllerConfig.staleMs` (1,5 s) : une boucle vivante mais lente (ramasse-miettes…) reste l'affaire du
     * contrôleur, le chien de garde ne traite que le fil qui ne tourne plus.
     */
    private val stallMs: Long = 2_000,
    /** Silence à partir duquel plus rien ne se rattrape : [onUnrecoverable] est appelé (une fois par blocage). */
    private val fatalStallMs: Long = 6_000,
    private val watchdogCheckMs: Long = 250,
    /** Temps maximal accordé à une action du chien de garde (STOP, fermeture) qui peut elle-même se bloquer. */
    private val guardMs: Long = 300,
    private val onUnrecoverable: () -> Unit = {},
) {
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "toque-machine").apply { priority = Thread.MAX_PRIORITY }
    }
    private val controller = MachineController(clock, config)
    @Volatile private var transport: Transport? = null
    private var nextOpenAt = 0L
    private var openFailures = 0
    @Volatile private var running = false
    @Volatile var suspended = false
        private set

    /** Début du dernier tour de boucle : ce que surveille le chien de garde. */
    @Volatile private var lastTickStartMs = 0L
    /** Vrai pendant l'ouverture de la liaison (longue par nature) : le chien de garde ne s'en mêle pas. */
    @Volatile private var opening = false
    private var watchdog: Thread? = null
    private val stopBusy = AtomicBoolean(false)
    private val closeBusy = AtomicBoolean(false)

    init {
        controller.listener = { e -> onEvent(e) }
    }

    fun start() {
        if (running) return
        running = true
        lastTickStartMs = clock.nowMs()
        exec.scheduleWithFixedDelay({ safeTick() }, 0, periodMs, TimeUnit.MILLISECONDS)
        watchdog = Thread({ watchdogLoop() }, "toque-watchdog").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun watchdogLoop() {
        var stalledSince = -1L
        var fatalReported = false
        while (running) {
            try {
                Thread.sleep(watchdogCheckMs)
            } catch (_: InterruptedException) {
                return
            }
            if (!running) return
            val now = clock.nowMs()
            val silentMs = now - lastTickStartMs
            if (opening || silentMs <= stallMs) {
                if (stalledSince >= 0) {
                    log("chien de garde : la boucle machine est repartie (silence de ${now - stalledSince + stallMs} ms)")
                    stalledSince = -1
                    fatalReported = false
                }
                continue
            }
            if (stalledSince < 0) {
                stalledSince = now
                log("chien de garde : la boucle machine ne répond plus depuis $silentMs ms")
                onEvent(MachineEvent(EventType.FAULT, tr("La boucle de la base moteur ne répond plus : arrêt d'urgence")))
            }
            // La boucle n'émet plus rien : le chien de garde envoie des STOP à sa place, puis ferme la liaison, ce
            // qui débloque une écriture coincée (le fil machine repart alors sur une écriture en échec).
            guarded(stopBusy, "toque-watchdog-stop") { stopFromWatchdog() }
            guarded(closeBusy, "toque-watchdog-close") { transport?.close() }
            if (silentMs >= fatalStallMs && !fatalReported) {
                fatalReported = true
                log("chien de garde : boucle bloquée depuis $silentMs ms, abandon du processus")
                try {
                    onUnrecoverable()
                } catch (t: Throwable) {
                    log("chien de garde : abandon impossible : $t")
                }
            }
        }
    }

    /**
     * Exécute [block] sur un fil jetable et attend au plus [guardMs] : une action de secours (écrire un STOP, fermer
     * la liaison) peut se bloquer comme ce qu'elle remplace. Une seule à la fois par [busy] (pas de fils en pile).
     */
    private fun guarded(busy: AtomicBoolean, name: String, block: () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        val t = Thread({
            try {
                block()
            } catch (_: Throwable) {
            } finally {
                busy.set(false)
            }
        }, name).apply { isDaemon = true }
        t.start()
        try {
            t.join(guardMs)
        } catch (_: InterruptedException) {
        }
        if (t.isAlive) log("chien de garde : $name est lui aussi bloqué")
    }

    /** Exécute [block] sur le fil machine et renvoie son résultat. */
    fun <T> call(block: (MachineController) -> T): T =
        exec.submit(Callable { block(controller) }).get(3, TimeUnit.SECONDS)

    fun post(block: (MachineController) -> Unit) {
        exec.execute {
            try {
                block(controller)
            } catch (t: Throwable) {
                log("commande en échec : $t")
            }
        }
    }

    private fun safeTick() {
        try {
            tick()
        } catch (t: Throwable) {
            // Édition Ratatouille (R2) : pendant l'arrêt de la boucle (changement de mode,
            // [shutdown]), le fil est interrompu exprès. Ce n'est pas une panne : pas d'alerte,
            // mais la trame STOP part quand même ci-dessous.
            val stopping = !running && (t is InterruptedException || t.cause is InterruptedException)
            if (stopping) {
                log("boucle interrompue pendant son arrêt")
            } else {
                log("erreur de boucle : $t")
                try {
                    controller.onWriteFailed(tr("erreur interne : %s", t.toString()))
                } catch (_: Throwable) {
                }
            }
            try {
                transport?.write(controller.stopFrame())
            } catch (_: Throwable) {
            }
            // Le drapeau d'interruption est rétabli après la trame STOP, pour qu'elle parte.
            if (stopping) Thread.currentThread().interrupt()
        }
    }

    /** Vrai après [emergencyStop] : la boucle n'émet plus que des trames STOP. */
    @Volatile private var halted = false

    private fun tick() {
        val now = clock.nowMs()
        lastTickStartMs = now
        if (transport == null && !suspended && now >= nextOpenAt) openTransport(now)
        val frame = if (halted) controller.stopFrame() else controller.tick()
        val t = transport
        if (t != null) {
            try {
                t.write(frame)
            } catch (e: IOException) {
                controller.onWriteFailed(e.message ?: tr("erreur d'écriture"))
                closeTransport(t, tr("écriture impossible"))
            }
        }
        onSnapshot(controller.snapshot())
    }

    private fun openTransport(now: Long) {
        opening = true
        try {
            openTransportUnguarded(now)
        } finally {
            // Le tour recommence à zéro : sans cela, la durée de l'ouverture compterait comme un silence.
            lastTickStartMs = clock.nowMs()
            opening = false
        }
    }

    private fun openTransportUnguarded(now: Long) {
        val t = try {
            transportFactory()
        } catch (e: Throwable) {
            nextOpenAt = now + retryDelayMs(++openFailures)
            log("transport indisponible : $e")
            return
        }
        try {
            t.open()
        } catch (e: Throwable) {
            nextOpenAt = now + retryDelayMs(++openFailures)
            controller.transportName = t.name
            log("ouverture impossible (${t.name}) : ${e.message}")
            lastOpenError = e.message ?: e.toString()
            return
        }
        lastOpenError = ""
        openFailures = 0
        transport = t
        controller.onTransportUp(t.name, t.codec)
        log("liaison ouverte : ${t.name}")
        val reader = Thread({ readLoop(t) }, "toque-rx")
        reader.isDaemon = true
        reader.priority = Thread.MAX_PRIORITY
        reader.start()
    }

    @Volatile var lastOpenError: String = ""
        private set

    private fun readLoop(t: Transport) {
        val buffer = ByteArray(256)
        var reason = tr("fin de flux")
        try {
            while (true) {
                val n = t.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                val copy = buffer.copyOf(n)
                exec.execute {
                    if (transport === t) controller.onBytes(copy, n)
                }
            }
        } catch (e: IOException) {
            reason = e.message ?: tr("erreur de lecture")
        } catch (e: Throwable) {
            reason = e.toString()
        }
        if (running) exec.execute { if (transport === t) closeTransport(t, reason) }
    }

    private fun closeTransport(t: Transport, reason: String) {
        if (transport !== t) return
        transport = null
        try {
            t.write(controller.stopFrame()) // dernier essai, la liaison est peut-être encore utilisable
        } catch (_: Throwable) {
        }
        try {
            t.close()
        } catch (_: Throwable) {
        }
        controller.onTransportDown(reason)
        nextOpenAt = clock.nowMs() + 2_000
        log("liaison fermée : $reason")
    }

    /**
     * Libère le port série (pour laisser l'appli d'origine se synchroniser). Refusé si un
     * programme est en cours. Renvoie vrai si le port est libéré.
     */
    fun suspendPort(): Boolean = call { c ->
        if (c.busy) return@call false
        suspended = true
        val t = transport
        if (t != null) {
            repeat(3) {
                try {
                    t.write(c.stopFrame())
                    Thread.sleep(60)
                } catch (_: Throwable) {
                }
            }
            transport = null
            try {
                t.close()
            } catch (_: Throwable) {
            }
            c.onTransportDown(tr("port libéré"))
        }
        true
    }

    fun resumePort() {
        post {
            suspended = false
            nextOpenAt = 0
            openFailures = 0
        }
    }

    /** Arrête proprement : envoie plusieurs STOP puis ferme la liaison. */
    fun shutdown() {
        if (!running) return
        running = false
        watchdog?.interrupt()
        try {
            exec.submit {
                val t = transport
                if (t != null) {
                    repeat(3) {
                        try {
                            t.write(controller.stopFrame())
                            Thread.sleep(60)
                        } catch (_: Throwable) {
                        }
                    }
                    transport = null
                    try {
                        t.close()
                    } catch (_: Throwable) {
                    }
                }
            }.get(2, TimeUnit.SECONDS)
        } catch (_: Throwable) {
        } finally {
            exec.shutdownNow()
        }
    }

    /**
     * STOP écrit par le chien de garde. Sans le verrou de [emergencyStop] (fait pour un processus qui meurt) : une
     * boucle qui repart doit pouvoir piloter de nouveau. Le programme en cours, lui, ne repart pas : la liaison
     * fermée fait échouer l'écriture suivante, ce qui lève un défaut à acquitter.
     */
    private fun stopFromWatchdog() {
        val t = transport ?: return
        try {
            t.write(controller.stopFrame())
        } catch (_: Throwable) {
        }
    }

    /** Dernier recours (plantage du processus) : écrit un STOP depuis n'importe quel fil. */
    fun emergencyStop() {
        halted = true
        val t = transport ?: return
        try {
            t.write(controller.stopFrame())
        } catch (_: Throwable) {
        }
    }

    companion object {
        /**
         * Délai avant de retenter d'ouvrir la liaison : 3 s, puis de plus en plus espacé jusqu'à
         * 1 min (évite, par exemple, une demande root toutes les 3 s quand Magisk refuse).
         */
        fun retryDelayMs(failures: Int): Long = minOf(3_000L shl (failures - 1).coerceIn(0, 5), 60_000L)
    }
}
