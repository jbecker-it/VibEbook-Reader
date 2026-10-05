package de.folio.reader.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** WorkManager discards progress when returning retry; keep the cause across retries/restarts. */
@Singleton
class SyncFailureStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("sync_failure", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(combined())
    val message = current.asStateFlow()
    private val last = MutableStateFlow(prefs.getLong("last_progress_sync", 0)); val lastProgressSync = last.asStateFlow()
    @Synchronized fun progressSucceeded() { val now = System.currentTimeMillis(); prefs.edit().putLong("last_progress_sync", now).apply(); last.value = now }

    private fun combined() = listOfNotNull(prefs.getString("message", null), prefs.getString("progress", null)).distinct().joinToString("\n").takeIf { it.isNotBlank() }
    @Synchronized fun set(message: String?, channel: String = "message") {
        prefs.edit().putString(channel, message).apply()
        current.value = combined()
    }
}
