package de.folio.reader.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncManagerDecisionTest {
    private val now = 1_700_000_000_000L

    @Test fun staleSyncTriggersAutoSync() {
        assertTrue(shouldAutoSyncLibrary(now, now - SyncManager.AUTO_SYNC_AFTER_MS - 1, true, false))
    }

    @Test fun recentSyncSkipsAutoSync() {
        assertFalse(shouldAutoSyncLibrary(now, now - 1000L, true, false))
        assertFalse(shouldAutoSyncLibrary(now, now, true, false))
    }

    @Test fun neverSyncedTriggersAutoSync() {
        assertTrue(shouldAutoSyncLibrary(now, 0L, true, false))
    }

    @Test fun unconfiguredAccountSkipsAutoSync() {
        assertFalse(shouldAutoSyncLibrary(now, 0L, false, false))
    }

    @Test fun activeWorkSkipsAutoSync() {
        assertFalse(shouldAutoSyncLibrary(now, 0L, true, true))
    }
}
