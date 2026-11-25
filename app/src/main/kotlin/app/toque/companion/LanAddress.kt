package app.toque.companion

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Adresse IPv4 du robot sur le réseau de la maison, lue sur les interfaces réseau (aucune
 * permission Android en plus). Le Wi-Fi (« wlan… ») d'abord, sinon toute adresse privée.
 */
object LanAddress {
    fun ipv4(): String? = try {
        val found = ArrayList<Pair<String, String>>()
        val list = NetworkInterface.getNetworkInterfaces()
        while (list != null && list.hasMoreElements()) {
            val nif = list.nextElement()
            if (!nif.isUp || nif.isLoopback || nif.isVirtual || nif.isPointToPoint) continue
            for (a in nif.inetAddresses) {
                if (a is Inet4Address && a.isSiteLocalAddress) found += nif.name to (a.hostAddress ?: continue)
            }
        }
        (found.firstOrNull { it.first.startsWith("wlan") } ?: found.firstOrNull())?.second
    } catch (_: Exception) {
        null
    }
}
