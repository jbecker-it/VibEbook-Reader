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
    private val current = MutableStateFlow(prefs.getString("message", null))
    val message = current.asStateFlow()

    @Synchronized fun set(message: String?) {
        prefs.edit().putString("message", message).apply()
        current.value = message
    }
}
