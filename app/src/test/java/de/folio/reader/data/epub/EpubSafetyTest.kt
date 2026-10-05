package de.folio.reader.data.epub

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class EpubSafetyTest {
    @Test fun removesActiveContentAndPreservesComicLayout() {
        val output = EpubSafety.sanitize("""<html><head><style>body{transform:scale(.2)}</style></head><body onload="AndroidReader.onNextChapter()"><script>bad()</script><iframe src="file:///private"/><img src="a.jpg" onerror="bad()"><a href="javascript:bad()">text</a></body></html>""")
        assertFalse(output.contains("onload")); assertFalse(output.contains("onerror")); assertFalse(output.contains("<script")); assertFalse(output.contains("<iframe")); assertFalse(output.contains("javascript:"))
        assertTrue(output.contains("transform:scale(.2)")); assertTrue(output.contains("Content-Security-Policy"))
    }
    @Test fun rejectsMetadataEscapesAndDecodesOnce() {
        val root = File(System.getProperty("java.io.tmpdir"), "epub-test").apply { mkdirs() }
        assertEquals(File(root, "100%20.xhtml").canonicalFile, EpubSafety.resolve(root, root, "100%2520.xhtml"))
        try { EpubSafety.resolve(root, root, "../private"); fail("Escape expected") } catch (_: IllegalArgumentException) { }
        try { EpubSafety.resolve(root, root, "file:///private"); fail("Scheme expected") } catch (_: IllegalArgumentException) { }
    }
    @Test fun capsExpandedZipSize() {
        val root = java.nio.file.Files.createTempDirectory("epub-limit").toFile()
        try {
            val zip = File(root, "book.zip")
            ZipOutputStream(zip.outputStream()).use { it.putNextEntry(ZipEntry("a.txt")); it.write(ByteArray(1024)); it.closeEntry() }
            try { EpubParser().extract(zip, File(root, "out"), 100); fail("Size limit expected") } catch (_: IllegalArgumentException) { }
        } finally { root.deleteRecursively() }
    }
}
