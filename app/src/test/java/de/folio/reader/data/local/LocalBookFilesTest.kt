package de.folio.reader.data.local

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalBookFilesTest {
    @Test fun removesOnlySelectedBookRevisions() {
        val root = Files.createTempDirectory("folio-remove-test-").toFile()
        try {
            val id = "a".repeat(32)
            val books = File(root, "books").apply { mkdir() }
            val old = File(books, id).apply { mkdir() }
            val revision = File(books, "$id-revision").apply { mkdir() }
            File(revision, "chapter.xhtml").writeText("book")
            val neighbor = File(books, "b".repeat(32)).apply { mkdir() }
            val progress = File(root, "$id.json").apply { writeText("progress") }
            LocalBookFiles.remove(books, id)
            assertFalse(old.exists())
            assertFalse(revision.exists())
            assertTrue(neighbor.exists())
            assertEquals("progress", progress.readText())
        } finally { root.deleteRecursively() }
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTraversal() {
        LocalBookFiles.remove(File("unused"), "../books")
    }
}
