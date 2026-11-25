package app.toque.recipes

import android.app.AlarmManager
import android.app.IntentService
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.toque.Prefs
import app.toque.TimeSync
import app.toque.ToqueApp
import app.toque.core.recipes.AutoSync
import app.toque.machine.MachineService
import app.toque.ui.MainActivity
import java.util.Calendar
import java.util.TimeZone

/**
 * Synchro automatique des recettes : monsieur-cuisine.com, puis toqueapp.org (communauté, compte). La nuit
 * (03 h 30), ou plus tard si le robot était éteint à cette heure-là, une fois inutilisé un moment (voir [AutoSync]).
 */
@Suppress("DEPRECATION")
class SyncService : IntentService("toque-synchro") {
    override fun onHandleIntent(intent: Intent?) {
        val app = ToqueApp.of(this)
        val d = decide(this, announce = true)
        if (d.run) OnlineSync(this, app.prefs, app.recipes, app.log).sync()
        // toqueapp.org : avec la synchro Lidl, ou seule quand celle-ci est déjà faite. Jamais pendant qu'elle
        // est reportée (cuisson, robot en cours d'utilisation) : le service repassera une fois le robot au calme.
        if ((d.run || d == AutoSync.Decision.NOT_DUE) && app.prefs.autoSync &&
            System.currentTimeMillis() - app.prefs.siteLastMs >= AutoSync.MIN_GAP_MS) app.site.syncNow()
    }

    companion object {
        /** État de l'interface, relevé chaque minute par l'écran principal (voir [reportUi]). */
        @Volatile private var uiIdleMs = -1L
        @Volatile private var uiCooking = false
        @Volatile private var uiSeenAt = 0L
        /** Raisons de report déjà écrites au journal (une ligne par raison, jusqu'à la prochaine synchro). */
        private val logged = HashSet<AutoSync.Decision>()

        /**
         * L'alarme de 03 h 30. Rien tant que l'horloge n'est pas réglée : posée en 2010, elle partirait dès
         * que l'heure saute à aujourd'hui. TimeSync la repose une fois l'heure réglée.
         */
        fun schedule(c: Context) {
            if (!TimeSync.plausible()) return
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(c, 0, Intent(c, SyncAlarm::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, AutoSync.NIGHT_HOUR); set(Calendar.MINUTE, AutoSync.NIGHT_MINUTE); set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
            }
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
        }

        /** Relevé de l'écran principal : temps sans activité (-1 : Toque n'est plus à l'écran) et cuisson en cours. */
        fun reportUi(idleMs: Long, cooking: Boolean) {
            uiIdleMs = idleMs
            uiCooking = cooking
            uiSeenAt = SystemClock.elapsedRealtime()
        }

        /** Vérification régulière (chaque minute, depuis l'écran principal) : lance la synchro si c'est le moment. */
        fun consider(c: Context) {
            if (OnlineSync.isRunning) return
            if (decide(c, announce = false).run) c.startService(Intent(c, SyncService::class.java))
        }

        /**
         * La décision du moment, notée au journal quand une synchro due est reportée (une fois par raison)
         * ou, avec [announce] (le service qui la fait), lancée.
         */
        @Synchronized
        private fun decide(c: Context, announce: Boolean): AutoSync.Decision {
            val app = ToqueApp.of(c)
            val now = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            // Relevé vieux de plus de 3 minutes : l'écran principal n'est plus là.
            val idle = if (elapsed - uiSeenAt > 3 * 60_000L) -1L else uiIdleMs
            val d = AutoSync.decide(now, TimeZone.getDefault(), TimeSync.plausible(), app.prefs.autoSync, app.prefs.lastSyncMs,
                elapsed, idle, uiCooking)
            when {
                d.run -> if (announce) {
                    logged.clear()
                    app.log.write("synchro", "synchro automatique lancée (${d.reason})")
                }
                d == AutoSync.Decision.JUST_STARTED || d == AutoSync.Decision.COOKING || d == AutoSync.Decision.IN_USE ->
                    if (logged.add(d)) app.log.write("synchro", "synchro automatique reportée : ${d.reason}")
                else -> {}
            }
            return d
        }
    }
}

class SyncAlarm : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        c.startService(Intent(c, SyncService::class.java))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        SyncService.schedule(c)
        c.startService(Intent(c, MachineService::class.java))
        if (Prefs(c).bootLaunch) {
            // Le robot n'a pas de bouton Accueil : Toque s'ouvre tout seul au démarrage, puis repasse
            // devant 20 s plus tard au cas où l'appli d'origine (écran d'accueil) se serait ouverte après lui.
            val open = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(open)
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 20_000,
                PendingIntent.getActivity(c, 1, open, PendingIntent.FLAG_UPDATE_CURRENT))
        }
    }
}
