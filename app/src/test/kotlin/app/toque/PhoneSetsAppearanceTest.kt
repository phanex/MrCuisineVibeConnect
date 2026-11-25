package app.toque

import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import app.toque.core.companion.RobotLooks
import app.toque.core.look.ColourWheel
import app.toque.core.companion.client.CompanionClient
import app.toque.core.companion.client.CompanionException
import app.toque.core.machine.JobState
import app.toque.core.machine.Programs
import app.toque.core.protocol.Direction
import app.toque.machine.MachineService
import app.toque.ui.MainActivity
import app.toque.ui.Ui
import app.toque.ui.screens.HomeScreen
import app.toque.ui.screens.SettingsScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Couleur et thème du robot changés depuis un téléphone (vrai client HTTP sur la boucle locale) : appliqués
 * comme les Réglages (interface reconstruite), le robot reste sur l'accueil ; refusés, sans rien changer,
 * quand il n'est pas libre (autre écran, fenêtre ouverte, programme en cours, qui continue).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w1024dp-h600dp-land-mdpi", application = ScreenshotApp::class)
class PhoneSetsAppearanceTest {
    private var activityController: ActivityController<MainActivity>? = null
    private var serviceController: ServiceController<MachineService>? = null
    private val activity: MainActivity get() = activityController!!.get()

    @After fun tearDown() {
        try { activityController?.pause()?.stop()?.destroy() } catch (_: Throwable) {}
        try { serviceController?.destroy() } catch (_: Throwable) {}
        activityController = null
        serviceController = null
        Ui.setTheme(false)
        Ui.setAccent(Ui.ACCENTS[0])
        idle(2)
    }

    private fun app(): ScreenshotApp = org.robolectric.RuntimeEnvironment.getApplication() as ScreenshotApp

    private fun idle(rounds: Int = 1) {
        repeat(rounds) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            assertTrue("attente trop longue : $what", System.currentTimeMillis() < end)
            idle()
            Thread.sleep(30)
        }
    }

    private fun <T> offMain(block: () -> T): T {
        var out: Any? = null
        var error: Throwable? = null
        val t = Thread { try { out = block() } catch (e: Throwable) { error = e } }
        t.start()
        val end = System.currentTimeMillis() + 15_000
        while (t.isAlive && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        t.join(1_000)
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun start(): CompanionClient {
        val app = app()
        val service = Robolectric.buildService(MachineService::class.java).create().also { serviceController = it }.get()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, MachineService::class.java),
            service.onBind(Intent(app, MachineService::class.java)))
        activityController = Robolectric.buildActivity(MainActivity::class.java).setup()
        waitFor("base simulée prête") { app.machine.snapshot.ready }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        val hub = app.companion!!.hub
        val o = hub.pairing.open()
        idle()
        waitFor("serveur ouvert") { hub.running && hub.port > 0 }
        val phone = CompanionClient("127.0.0.1", hub.port)
        offMain { phone.pairWithCode(o.code) }
        hub.pairing.close()
        return phone
    }

    private fun refused(phone: CompanionClient, accent: String?, theme: String?): String {
        try {
            offMain { phone.setAppearance(accent, theme) }
            fail("accepté")
        } catch (e: CompanionException) {
            assertEquals(409, e.status)
            return e.error.code + " " + e.error.message
        }
        return ""
    }

    @Test fun theRobotColoursAreTheContractOnes() {
        assertEquals(RobotLooks.ACCENTS, Ui.ACCENTS.map { String.format("#%06X", it and 0xFFFFFF) })
    }

    @Test fun aLookFromAPhoneRebuildsTheInterfaceAndStaysOnHome() {
        val phone = start()
        val app = app()
        assertTrue(offMain { phone.state() }.can.setAppearance.allowed)
        assertEquals("#FF8A3D", offMain { phone.state() }.robot.accent)
        val before = activity

        assertTrue(offMain { phone.setAppearance("#4FC3F7", "light") }.ok)
        idle(3)
        assertEquals(0xFF4FC3F7.toInt(), app.prefs.accentColor)
        assertTrue(app.prefs.lightTheme)
        assertEquals(0xFF4FC3F7.toInt(), Ui.ACCENT)
        assertTrue(Ui.light)
        // Interface reconstruite, sur l'accueil (pas dans les Réglages).
        assertNotSame(before, activity)
        assertTrue(activity.current is HomeScreen)
        assertEquals(1, activity.depth)
        waitFor("apparence vue du téléphone") { offMain { phone.state() }.robot.let { it.accent == "#4FC3F7" && it.theme == "light" } }
        assertTrue(offMain { phone.journal(0, 50) }.entries.any { it.kind == "appearance" && it.origin.self })

        // La même apparence : rien n'est reconstruit.
        val now = activity
        assertTrue(offMain { phone.setAppearance("#4FC3F7", "light") }.ok)
        idle(3)
        assertSame(now, activity)
    }

    /**
     * Une couleur de la roue envoyée par un téléphone : le robot le dit (robot.anyAccent) et la prend comme
     * les dix ; une couleur sombre est refusée (400) sans rien changer.
     */
    @Test fun aWheelColourFromAPhoneIsTakenADarkOneRefused() {
        val phone = start()
        val app = app()
        assertTrue(offMain { phone.state() }.robot.anyAccent)
        try {
            offMain { phone.setAppearance("#800020", null) }
            fail("accepté")
        } catch (e: CompanionException) {
            assertEquals(400, e.status)
            assertEquals("bad_request", e.error.code)
        }
        idle(2)
        assertEquals(0, app.prefs.accentColor)
        assertEquals(Ui.ACCENTS[0], Ui.ACCENT)
        val wheel = ColourWheel.colour(145.0, 1.0)
        val hex = String.format("#%06X", wheel and 0xFFFFFF)
        assertTrue(offMain { phone.setAppearance(hex, null) }.ok)
        idle(3)
        assertEquals(wheel, app.prefs.accentColor)
        assertEquals(wheel, Ui.ACCENT)
        assertTrue(activity.current is HomeScreen)
        waitFor("couleur vue du téléphone") { offMain { phone.state() }.robot.accent == hex }
    }

    @Test fun aBusyRobotRefusesAndKeepsWhatItShows() {
        val phone = start()
        val app = app()
        // Un autre écran par-dessus l'accueil.
        activity.push(SettingsScreen(activity))
        idle()
        assertTrue(refused(phone, "#F2639A", null).startsWith("busy Le robot n'est pas sur son écran d'accueil"))
        assertTrue(activity.current is SettingsScreen)
        assertEquals(0, app.prefs.accentColor)
        activity.home()
        idle()

        // Une fenêtre ouverte sur l'accueil.
        activity.confirm("Essai", "Une fenêtre", "OK") {}
        idle()
        assertTrue(activity.overlayShown)
        assertTrue(refused(phone, null, "light").startsWith("busy"))
        assertTrue(activity.overlayShown)
        assertFalse(app.prefs.lightTheme)
        activity.closeOverlay()
        idle()

        // Un programme en cours : refusé, et le programme continue.
        var started: Boolean? = null
        app.machine.start(Programs.manual(2, 0, Direction.CLOCKWISE, 600_000)) { v -> started = v.ok }
        waitFor("cuisson lancée") { started == true && app.machine.snapshot.state == JobState.RUNNING }
        val running = activity
        assertTrue(refused(phone, "#8BD85F", "light").startsWith("busy Un programme est en cours"))
        idle(3)
        assertSame(running, activity)
        assertEquals(JobState.RUNNING, app.machine.snapshot.state)
        assertEquals(0, app.prefs.accentColor)
        assertFalse(app.prefs.lightTheme)
        assertFalse(offMain { phone.journal(0, 50) }.entries.any { it.kind == "appearance" })
    }
}
