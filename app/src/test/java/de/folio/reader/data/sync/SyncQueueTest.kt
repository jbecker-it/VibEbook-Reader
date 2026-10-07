package de.folio.reader.data.sync

import org.junit.Assert.*
import org.junit.Test

class SyncQueueTest {
    @Test fun onlineQueuedWorkIsNotReportedAsOffline() {
        val message = pendingSyncMessage(NetworkState(true, false), true, false)
        assertTrue(message.contains("wartet auf Android"))
        assertFalse(message.contains("Warte auf Netzwerk"))
    }

    @Test fun meteredRestrictionDisappearsWhenUserAllowsMeteredNetworks() {
        val network = NetworkState(true, true)
        assertTrue(pendingSyncMessage(network, true, false).contains("getaktetes Netzwerk"))
        assertFalse(pendingSyncMessage(network, false, false).contains("getaktetes Netzwerk"))
    }

    @Test fun offlineAndRetryHaveDifferentMessages() {
        assertTrue(pendingSyncMessage(NetworkState(false, false), false, false).startsWith("Offline"))
        assertTrue(pendingSyncMessage(NetworkState(true, false), false, true).contains("nach einem Fehler"))
    }
}
