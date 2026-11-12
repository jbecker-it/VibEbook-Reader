package de.folio.reader.data.nextcloud

import android.content.Context

/**
 * Kleine IP-Adressen-Tabelle für den DNS-Fallback. Synchron, weil OkHttp den
 * Dns-Callback außerhalb eines suspend-Kontexts aufruft.
 *
 * [manual] ist die vom Nutzer in den Einstellungen hinterlegte Tailscale-IP.
 * [autoPut]/[autoGet] merken sich die zuletzt erfolgreich aufgelöste IP je
 * Hostname, damit der App nach einem einmaligen erfolgreichen Sync auch dann
 * wieder verbunden ist, wenn das System-DNS den Namen zeitweise nicht kennt.
 */
interface DnsIpBackend {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

class SharedPreferencesDnsBackend(context: Context) : DnsIpBackend {
    private val prefs = context.applicationContext.getSharedPreferences("dns_fallback", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)?.trim()?.takeIf { it.isNotEmpty() }
    override fun put(key: String, value: String) { prefs.edit().putString(key, value.trim()).apply() }
}

class DnsFallbackStore(private val backend: DnsIpBackend) {
    fun manual(): String = backend.get(MANUAL_KEY).orEmpty()
    fun setManual(ip: String) { backend.put(MANUAL_KEY, ip.trim()) }

    fun auto(host: String): String = backend.get("auto_" + keyFor(host)).orEmpty()
    fun autoPut(host: String, ip: String) { backend.put("auto_" + keyFor(host), ip.trim()) }

    private fun keyFor(host: String): String = host.lowercase().hashCode().toString(16)
    private companion object { const val MANUAL_KEY = "manual" }
}
