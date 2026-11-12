package de.folio.reader.data.nextcloud

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Nutzt das System-DNS, fällt aber auf eine bekannte IP-Adresse zurück, wenn
 * der Hostname gerade nicht auflösbar ist – typisch für Android nach einem
 * Tailscale-Aussetzer, wenn der Resolver negative Ergebnisse vorhält.
 *
 * Die TLS-Zertifikatsprüfung läuft weiterhin gegen den ursprünglichen
 * Hostnamen, deshalb bleibt das von `tailscale serve` ausgestellte
 * Zertifikat auch bei Verbindung über die 100.x.y.z-Adresse gültig.
 */
class TailnetDns(private val store: DnsFallbackStore) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.lowercase()
        return try {
            val addresses = Dns.SYSTEM.lookup(hostname)
            val ip = addresses.firstOrNull { it is Inet4Address }?.hostAddress ?: addresses.firstOrNull()?.hostAddress
            if (ip != null) store.autoPut(host, ip)
            addresses
        } catch (e: UnknownHostException) {
            fallback(host) ?: throw e
        } catch (e: SocketTimeoutException) {
            fallback(host) ?: throw e
        }
    }

    private fun fallback(host: String): List<InetAddress>? {
        val ip = store.manual().ifBlank { store.auto(host) }
        if (ip.isBlank()) return null
        // Nur IP-Literale akzeptieren: Inet4Address.getByName wirft sonst,
        // weil es andernfalls selbst ein (weiteres) Name-Lookup wäre.
        val address = runCatching { Inet4Address.getByName(ip) }.getOrNull() ?: return null
        return listOf(address)
    }
}
