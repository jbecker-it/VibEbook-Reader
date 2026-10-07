package de.folio.reader.data.sync

internal data class NetworkState(val connected: Boolean, val metered: Boolean)

/** ENQUEUED can mean constraints, backoff, or Android scheduling; it is not a running transfer. */
internal fun pendingSyncMessage(network: NetworkState, unmeteredOnly: Boolean, retry: Boolean): String = when {
    !network.connected -> "Offline · Synchronisierung wartet auf eine Verbindung."
    unmeteredOnly && network.metered -> "Synchronisierung pausiert · Android meldet ein getaktetes Netzwerk. Prüfe „Nur ungetaktete Netzwerke“ in den Einstellungen."
    retry -> "Synchronisierung wird nach einem Fehler erneut versucht. Zum sofortigen Wiederholen auf Synchronisieren tippen."
    else -> "Synchronisierung eingeplant · wartet auf Android. Zum erneuten Starten auf Synchronisieren tippen."
}
