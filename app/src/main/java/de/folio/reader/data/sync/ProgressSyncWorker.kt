package de.folio.reader.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.folio.reader.data.repository.BookRepository
import kotlinx.coroutines.CancellationException

@HiltWorker
class ProgressSyncWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted params: WorkerParameters,
    private val repository: BookRepository, private val failures: SyncFailureStore,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        repository.syncPendingProgress(inputData.getBoolean("all", false))
        failures.set(null, "progress")
        Result.success()
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
        val message = e.message ?: "Lesestand wird später erneut synchronisiert."
        failures.set(message, "progress")
        val status = (e as? de.folio.reader.data.nextcloud.DavException)?.status
        if (status == 401 || status == 403 || status == 404 || e is IllegalArgumentException) Result.failure(workDataOf("message" to message))
        else Result.retry()
    }
}
