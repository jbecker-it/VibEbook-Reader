package de.folio.reader.data.nextcloud

import de.folio.reader.domain.model.NextcloudSettings
import de.folio.reader.domain.model.ReadingProgress
import de.folio.reader.data.progress.BookId
import org.junit.Assert.*
import org.junit.Test

class NextcloudTest {
    private val settings = NextcloudSettings("https://cloud.example.com/nextcloud", "reader", "secret")
    @Test fun encodesUnicodeAndPreservesSubdirectory() {
        assertEquals("https://cloud.example.com/nextcloud/remote.php/dav/files/reader/B%C3%BCcher/A%20%23%20B.epub", settings.davUrl("Bücher/A # B.epub").toString())
        assertFalse(settings.toString().contains("secret"))
    }
    @Test fun rejectsHttpAndTraversal() {
        assertFalse(settings.copy(serverUrl = "http://cloud.example.com").isConfigured)
        assertFalse(settings.copy(rootPath = "books/../private").isConfigured)
        assertFalse(settings.copy(password = "").isConfigured)
    }
    @Test fun parsesListingAndSkipsParent() {
        val path = settings.davUrl("Books").encodedPath
        val xml = """<d:multistatus xmlns:d="DAV:">
          <d:response><d:href>$path/</d:href></d:response>
          <d:response><d:href>$path/A%20%2B%20B.epub</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>42</d:getcontentlength><d:getetag>"abc"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
        </d:multistatus>"""
        val entries = NextcloudClient.parseListing(xml, settings.davUrl(), settings.davUrl("Books"))
        assertEquals(listOf(RemoteEntry("Books/A + B.epub", false, 42, "\"abc\"")), entries)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsEntityExpansion() {
        NextcloudClient.parseListing("<!DOCTYPE x><x/>", settings.davUrl(), settings.davUrl())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsForeignOrigin() {
        NextcloudClient.parseListing("""<d:multistatus xmlns:d="DAV:"><d:response><d:href>https://evil.example/book.epub</d:href></d:response></d:multistatus>""", settings.davUrl(), settings.davUrl())
    }
    @Test fun mergesFieldsIndependentlyAndRoundTrips() {
        val first = ReadingProgress("book", 1, 0.25f, 100, 1000, "a", favorite = true, favoriteUpdatedAt = 3000)
        val second = first.copy(spineIndex = 2, updatedAt = 2000, favorite = false, favoriteUpdatedAt = 1000)
        val merged = ReadingProgress.merge(first, second)!!
        assertEquals(2, merged.spineIndex)
        assertTrue(merged.favorite)
        assertEquals(merged, ReadingProgress.fromJson(merged.toJson()))
    }
    @Test fun pathIdentityIsStable() {
        assertEquals(BookId.fromPath("Books/Novel.epub"), BookId.fromPath("Books/Novel.epub"))
        assertNotEquals(BookId.fromPath("Books/Novel.epub"), BookId.fromPath("Books/Other.epub"))
    }
}
