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
          <d:response><d:href>$path/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
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
    @Test(expected = IllegalArgumentException::class) fun emptyMultistatusIsNotAnEmptyLibrary() {
        NextcloudClient.parseListing("<d:multistatus xmlns:d=\"DAV:\"/>", settings.davUrl(), settings.davUrl())
    }
    @Test(expected = IllegalArgumentException::class) fun deniedRootIsNotSkipped() {
        val path = settings.davUrl().encodedPath
        NextcloudClient.parseListing("""<d:multistatus xmlns:d="DAV:"><d:response><d:href>$path</d:href><d:status>HTTP/1.1 403 Forbidden</d:status></d:response></d:multistatus>""", settings.davUrl(), settings.davUrl())
    }
    @Test fun equalTimestampsConvergeInBothOrders() {
        val a = ReadingProgress("book", 1, .2f, updatedAt = 10, deviceId = "a", favorite = true, favoriteUpdatedAt = 10, favoriteDeviceId = "a")
        val b = a.copy(spineIndex = 2, deviceId = "b", favorite = false, favoriteDeviceId = "b")
        assertEquals(ReadingProgress.merge(a, b), ReadingProgress.merge(b, a))
        assertEquals(ReadingProgress.merge(a, b), ReadingProgress.merge(ReadingProgress.merge(a, b), b))
    }
    @Test fun oldAutomaticCompletionTiesAlsoConverge() {
        val a = ReadingProgress("book", 1, .2f, updatedAt = 10, deviceId = "a")
        val b = a.copy(finished = true)
        assertEquals(ReadingProgress.merge(a, b), ReadingProgress.merge(b, a))
    }
    @Test fun splitPropertyBlocksAreMerged() {
        val path = settings.davUrl().encodedPath
        val xml = """<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:response><d:href>$path/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            <d:response><d:href>$path/a.epub</d:href><d:propstat><d:prop><d:resourcetype/><d:getetag>etag</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat><d:propstat><d:prop><oc:id>42</oc:id><d:getcontentlength>7</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        val entry = NextcloudClient.parseListing(xml, settings.davUrl(), settings.davUrl()).single()
        assertEquals("42", entry.remoteId); assertEquals(7L, entry.size); assertEquals("etag", entry.etag)
    }
}
