package de.folio.reader.data.nextcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.net.UnknownHostException

class TailnetDnsTest {
    private class InMemoryBackend : DnsIpBackend {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]?.trim()?.takeIf { it.isNotEmpty() }
        override fun put(key: String, value: String) { values[key] = value.trim() }
    }

    /** RFC 6761: .invalid löst garantiert nicht auf – deterministischer DNS-Fehlschlag. */
    private val unresolvable = "folio-dns-fallback.invalid"

    @Test fun fallsBackToManualIpWhenSystemDnsFails() {
        val dns = TailnetDns(DnsFallbackStore(InMemoryBackend()).also { it.setManual("100.64.0.12") })
        val addresses = dns.lookup(unresolvable)
        assertEquals(1, addresses.size)
        assertEquals("100.64.0.12", addresses[0].hostAddress)
    }

    @Test fun fallsBackToPreviouslyResolvedIp() {
        val store = DnsFallbackStore(InMemoryBackend())
        store.autoPut(unresolvable, "100.90.5.7")
        val addresses = TailnetDns(store).lookup(unresolvable)
        assertEquals("100.90.5.7", addresses[0].hostAddress)
    }

    @Test fun manualIpTakesPrecedenceOverAuto() {
        val store = DnsFallbackStore(InMemoryBackend())
        store.setManual("100.64.0.12")
        store.autoPut(unresolvable, "100.90.5.7")
        val addresses = TailnetDns(store).lookup(unresolvable)
        assertEquals("100.64.0.12", addresses[0].hostAddress)
    }

    @Test fun stillThrowsWhenNoFallbackIsKnown() {
        val dns = TailnetDns(DnsFallbackStore(InMemoryBackend()))
        try {
            dns.lookup(unresolvable)
            fail("Expected UnknownHostException")
        } catch (expected: UnknownHostException) {
        }
    }

    @Test fun rejectsInvalidManualIp() {
        val dns = TailnetDns(DnsFallbackStore(InMemoryBackend()).also { it.setManual("nicht-ip") })
        try {
            dns.lookup(unresolvable)
            fail("Expected UnknownHostException")
        } catch (expected: UnknownHostException) {
        }
    }
}
