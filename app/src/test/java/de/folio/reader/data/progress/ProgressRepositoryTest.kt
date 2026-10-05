package de.folio.reader.data.progress

import android.app.Application
import android.content.Context
import android.util.AtomicFile
import de.folio.reader.domain.model.ReadingProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ProgressRepositoryTest {
    private lateinit var context: Context
    private val id = "0123456789abcdef0123456789abcdef"
    private fun progress(time: Long) = ReadingProgress(id, 1, .5f, updatedAt = time, deviceId = "a")
    @Before fun setup() { context = RuntimeEnvironment.getApplication(); File(context.filesDir, "progress").deleteRecursively() }
    @Test fun pendingSurvivesRestartAndStaleAcknowledgement() = runBlocking {
        val first = ProgressRepository(context); first.write(progress(1))
        val restarted = ProgressRepository(context)
        assertEquals(listOf(id), restarted.pendingIds())
        restarted.write(progress(2)); restarted.acknowledge(progress(1))
        assertEquals(listOf(id), restarted.pendingIds())
        restarted.acknowledge(progress(2))
        assertTrue(ProgressRepository(context).pendingIds().isEmpty())
    }
    @Test fun legacyAtomicBackupRecoversBeforeBulkRead() = runBlocking {
        val dir = File(context.filesDir, "progress").apply { mkdirs() }
        File(dir, "$id.json.bak").writeText(progress(1).toJson())
        File(dir, "$id.json").writeText("truncated")
        assertEquals(progress(1), ProgressRepository(context).readAll()[id])
    }
    @Test fun damagedBookDoesNotHideOtherProgress() = runBlocking {
        val repo = ProgressRepository(context); repo.write(progress(1))
        File(context.filesDir, "progress/ffffffffffffffffffffffffffffffff.json").writeText("invalid")
        assertEquals(progress(1), ProgressRepository(context).readAll()[id])
    }
    @Test fun backupRoundTripKeepsIndependentFields() = runBlocking {
        val repo = ProgressRepository(context); repo.write(progress(1).copy(favorite = true, favoriteUpdatedAt = 3))
        val backup = repo.export(); File(context.filesDir, "progress").deleteRecursively()
        val restored = ProgressRepository(context); assertEquals(1, restored.import(backup)); assertTrue(restored.read(id)!!.favorite)
        assertEquals(listOf(id), restored.pendingIds())
    }
}
