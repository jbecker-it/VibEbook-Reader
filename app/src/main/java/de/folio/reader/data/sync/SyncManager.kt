package de.folio.reader.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import de.folio.reader.data.progress.ProgressRepository
import de.folio.reader.data.repository.BookRepository
import de.folio.reader.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class SyncStatus(
    val running: Boolean = false,
    val message: String? = null,
    /** 0..1 während des Buch-Downloads, sonst null (unbestimmt). */
    val fraction: Float? = null,
)

@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val progressRepo: ProgressRepository,
    private val bookRepository: BookRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workManager get() = WorkManager.getInstance(context)

    init {
        // Jede lokale Fortschrittsänderung möglichst zeitnah aufs NAS bringen.
        scope.launch {
            progressRepo.changes.collect { bookId ->
                runCatching { bookRepository.syncProgress(bookId) }
            }
        }
    }

    val isSyncing: Flow<Boolean> = workManager
        .getWorkInfosForUniqueWorkFlow(WORK_ONE_TIME)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }

    /** Laufender Sync mit Status-Text und Fortschrittsanteil (null = unbestimmt). */
    val syncStatus: Flow<SyncStatus> = workManager
        .getWorkInfosForUniqueWorkFlow(WORK_ONE_TIME)
        .map { infos ->
            val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
            when {
                running != null -> {
                    val fraction = running.progress.getFloat(SyncWorker.KEY_FRACTION, -1f)
                    SyncStatus(
                        running = true,
                        message = running.progress.getString(SyncWorker.KEY_MESSAGE),
                        fraction = fraction.takeIf { it in 0f..1f },
                    )
                }
                infos.any { it.state == WorkInfo.State.ENQUEUED } ->
                    SyncStatus(running = true, message = "Warte auf Netzwerk …", fraction = null)
                else -> SyncStatus()
            }
        }

    /** Sofortige, einmalige Synchronisierung. */
    suspend fun syncNow() {
        val wifiOnly = settingsRepo.wifiOnly.first()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(WORK_ONE_TIME, ExistingWorkPolicy.KEEP, request)
    }

    /** Regelmäßiger Abgleich (alle 6 Stunden). */
    suspend fun schedulePeriodic() {
        val wifiOnly = settingsRepo.wifiOnly.first()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(Duration.ofHours(6))
            .setConstraints(constraints(wifiOnly))
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    companion object {
        private const val WORK_ONE_TIME = "folio_sync_now"
        private const val WORK_PERIODIC = "folio_sync_periodic"
    }
}
