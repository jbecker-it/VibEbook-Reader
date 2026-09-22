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
    private val failureStore: SyncFailureStore,
) : CoroutineWorker(appContext, params) {

    private var phase = "Synchronisierung"

    override suspend fun doWork(): Result = try {
        bookRepository.syncLibrary { message, fraction ->
            phase = message
            setProgress(
                workDataOf(
                    KEY_MESSAGE to message,
                    KEY_FRACTION to (fraction ?: -1f),
                )
            )
        }
        failureStore.set(null)
        Result.success()
    } catch (e: kotlinx.coroutines.CancellationException) { throw e
    } catch (e: Exception) {
        val message = "$phase\n${e.message?.take(500) ?: e.javaClass.simpleName}"
        failureStore.set(message)
        // Verbindungsfehler im mobilen Netz: später erneut versuchen.
        val retryable = e is java.io.IOException && (e !is de.folio.reader.data.nextcloud.DavException || e.status in listOf(408, 412, 423, 429) || e.status >= 500)
        if (retryable && runAttemptCount < 3) Result.retry()
        else Result.failure(workDataOf(KEY_MESSAGE to message))
    }

    companion object {
        const val KEY_MESSAGE = "message"
        const val KEY_FRACTION = "fraction"
    }
}
