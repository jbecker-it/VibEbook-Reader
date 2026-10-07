package de.folio.reader.domain.model

import org.junit.Assert.*
import org.junit.Test

class ReadingStatusTest {
    private fun progress() = ReadingProgress("book", 9, 1f, 42, 100, "phone", finished = true)

    @Test fun manualUnreadSurvivesNewerAutomaticCompletionAndPreservesPosition() {
        val unread = progress().copy(finished = false, finishedUpdatedAt = 200)
        val laterReading = progress().copy(updatedAt = 300, charOffset = 90)
        for (merged in listOf(ReadingProgress.merge(unread, laterReading)!!, ReadingProgress.merge(laterReading, unread)!!)) {
            assertFalse(merged.finished)
            assertEquals(90, merged.charOffset)
            assertEquals(200L, merged.finishedUpdatedAt)
            val book = Book("book", "book.epub", "Book", "", null, List(10) { "$it" }, true, 0, merged)
            assertEquals(1f, book.readFraction, 0f)
            assertFalse(book.isFinished)
        }
    }

    @Test fun statusFavoriteAndPositionMergeIndependently() {
        val read = progress().copy(finishedUpdatedAt = 400)
        val remote = progress().copy(updatedAt = 500, spineIndex = 3, favorite = true,
            favoriteUpdatedAt = 500, finished = false, finishedUpdatedAt = 200)
        val merged = ReadingProgress.merge(read, remote)!!
        assertTrue(merged.finished)
        assertTrue(merged.favorite)
        assertEquals(3, merged.spineIndex)
        assertEquals(merged, ReadingProgress.fromJson(merged.toJson()))
        assertEquals(merged, ReadingProgress.merge(remote, read))
    }

    @Test fun oldFilesKeepAutomaticCompletionAndAcceptManualOverride() {
        val old = ReadingProgress.fromJson("""{"bookId":"book","finished":true,"updatedAt":100,"schema":3}""")
        assertEquals(0L, old.finishedUpdatedAt)
        val unread = old.copy(finished = false, finishedUpdatedAt = 200)
        assertFalse(ReadingProgress.merge(old, unread)!!.finished)
        assertTrue(ReadingProgress.merge(old, old.copy(updatedAt = 50, finished = false))!!.finished)
    }
}
