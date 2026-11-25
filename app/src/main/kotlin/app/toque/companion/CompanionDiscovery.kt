package app.toque.companion

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import app.toque.ToqueApp
import app.toque.core.companion.COMPANION_PROTOCOL

/**
 * Annonce du serveur du compagnon sur le Wi-Fi (mDNS / DNS-SD, service « _toque._tcp ») : un
 * téléphone ou Home Assistant trouve le robot sans taper son adresse. Seulement tant que le serveur
 * est ouvert (un téléphone appairé ou un appairage en cours). L'annonce ne donne que ce que montre
 * déjà le code QR d'appairage : numéro du robot, version du protocole, modèle. Rien de secret.
 */
class CompanionDiscovery(private val app: ToqueApp) {
    private val nsd: NsdManager? = try {
        app.getSystemService(Context.NSD_SERVICE) as? NsdManager
    } catch (_: Throwable) {
        null
    }
    private var listener: NsdManager.RegistrationListener? = null
    private var announced = -1

    /** Annonce le serveur sur [port] (une seule annonce à la fois ; un autre port remplace la précédente). */
    @Synchronized
    fun announce(port: Int, robotId: String) {
        val m = nsd ?: return
        if (port <= 0 || port == announced) return
        withdraw()
        val info = NsdServiceInfo().apply {
            serviceName = "Toque " + app.prefs.robot.displayName
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", robotId)
            setAttribute("v", COMPANION_PROTOCOL.toString())
            setAttribute("model", app.prefs.robot.id)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) = app.log.write("compagnon", "annoncé sur le Wi-Fi : ${s.serviceName}")
            override fun onRegistrationFailed(s: NsdServiceInfo, error: Int) {
                app.log.write("compagnon", "annonce Wi-Fi impossible ($error)")
                synchronized(this@CompanionDiscovery) { if (listener === this) { listener = null; announced = -1 } }
            }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, error: Int) {}
        }
        try {
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            listener = l
            announced = port
        } catch (t: Throwable) {
            app.log.write("compagnon", "annonce Wi-Fi impossible : ${t.message}")
        }
    }

    /** Retire l'annonce (serveur fermé). */
    @Synchronized
    fun withdraw() {
        val l = listener ?: return
        listener = null
        announced = -1
        try {
            nsd?.unregisterService(l)
        } catch (_: Throwable) {
            // Déjà retirée (échec d'enregistrement) : rien à faire.
        }
    }

    companion object {
        const val SERVICE_TYPE = "_toque._tcp"
    }
}
