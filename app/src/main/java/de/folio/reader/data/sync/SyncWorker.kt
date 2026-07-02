package de.folio.reader.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.folio.reader.data.repository.BookRepository

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val bookRepository: BookRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        bookRepository.syncLibrary { message, fraction ->
            setProgress(
                workDataOf(
                    KEY_MESSAGE to message,
                    KEY_FRACTION to (fraction ?: -1f),
                )
            )
        }
        Result.success()
    } catch (e: Exception) {
        // Verbindungsfehler im mobilen Netz: später erneut versuchen.
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    companion object {
        const val KEY_MESSAGE = "message"
        const val KEY_FRACTION = "fraction"
    }
}
