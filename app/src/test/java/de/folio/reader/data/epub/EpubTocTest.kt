package de.folio.reader.data.epub

import android.app.Application
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class EpubTocTest {
    @Test fun readsNestedNavAndNcxWithFragments() {
        val root = java.nio.file.Files.createTempDirectory("toc-test").toFile()
        try {
            File(root, "Chapter 1.xhtml").writeText("<html><body>text</body></html>")
            File(root, "nav.xhtml").writeText("""<nav epub:type="toc"><ol><li><a href="Chapter%201.xhtml#part">Chapter</a><ol><li><a href="Chapter%201.xhtml">Section</a></li></ol></li></ol></nav>""")
            val nav = JSONArray(EpubToc.read(root, root, "nav.xhtml", null))
            assertEquals(2, nav.length()); assertEquals("part", nav.getJSONObject(0).getString("fragment")); assertEquals(1, nav.getJSONObject(1).getInt("depth"))
            File(root, "toc.ncx").writeText("""<ncx><navMap><navPoint><navLabel><text>Chapter</text></navLabel><content src="Chapter%201.xhtml#part"/></navPoint></navMap></ncx>""")
            assertEquals("Chapter", JSONArray(EpubToc.read(root, root, null, "toc.ncx")).getJSONObject(0).getString("label"))
        } finally { root.deleteRecursively() }
    }
    @Test fun comicImagesHaveNaturalOrderAndFixedViewport() {
        val root = java.nio.file.Files.createTempDirectory("cbz-test").toFile()
        try {
            val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGP4DwQACfsD/fteaysAAAAASUVORK5CYII=")
            listOf("page10.png", "page2.png", "page1.png").forEach { File(root, it).writeBytes(png) }
            val comic = ComicArchiveParser.parse(root)
            assertEquals(3, comic.spine.size); assertTrue(File(comic.spine[0]).readText().contains("page1.png")); assertTrue(File(comic.spine[1]).readText().contains("page2.png"))
            assertTrue(File(comic.spine[0]).readText().contains("name=\"viewport\""))
        } finally { root.deleteRecursively() }
    }
}
