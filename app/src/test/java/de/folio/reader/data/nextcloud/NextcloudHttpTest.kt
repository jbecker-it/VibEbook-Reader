package de.folio.reader.data.nextcloud

import de.folio.reader.domain.model.NextcloudSettings
import de.folio.reader.domain.model.ReadingProgress
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class NextcloudHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var client: NextcloudClient
    private lateinit var settings: NextcloudSettings

    @Before fun setup() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        client = NextcloudClient(OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .followRedirects(false).followSslRedirects(false).build())
        settings = NextcloudSettings(server.url("/").toString(), "reader", "app-password")
    }
    @After fun cleanup() { server.shutdown() }

    @Test fun authenticationFailureIsNotAnEmptyLibrary() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = client.testConnection(settings)
        assertTrue(result.exceptionOrNull() is DavException)
        assertEquals(401, (result.exceptionOrNull() as DavException).status)
        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.getHeader("Depth"))
        assertNotNull(request.getHeader("Authorization"))
    }
    @Test fun only404MeansMissingProgress() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(client.readTextOrNull(settings, "progress.json"))
        server.enqueue(MockResponse().setResponseCode(403))
        try { client.readTextOrNull(settings, "progress.json"); fail("403 must not be treated as missing") }
        catch (e: DavException) {
            assertEquals(403, e.status)
            assertTrue(e.message!!.contains("Fortschritt lesen / GET (HTTP 403)"))
            assertFalse(e.message!!.contains("app-password"))
        }
    }
    @Test fun conditionalWritesDetectConcurrentChanges() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(412))
        try { client.writeText(settings, "progress.json", "{}", RemoteText("{}", "\"old\"")); fail("Conflict expected") }
        catch (e: DavException) { assertEquals(412, e.status) }
        assertEquals("\"old\"", server.takeRequest().getHeader("If-Match"))
        server.enqueue(MockResponse().setResponseCode(201))
        client.writeText(settings, "progress.json", "{}", null)
        assertEquals("*", server.takeRequest().getHeader("If-None-Match"))
    }
    @Test fun failedDownloadPreservesExistingFile() = runBlocking {
        val folder = java.nio.file.Files.createTempDirectory("folio-test-").toFile()
        val target = File(folder, "book.epub").apply { writeText("old") }
        try {
            server.enqueue(MockResponse().setResponseCode(500))
            try { client.download(settings, "book.epub", target); fail("Server failure expected") }
            catch (e: DavException) { assertEquals(500, e.status) }
            assertEquals("old", target.readText())
            assertEquals(listOf("book.epub"), folder.list()!!.toList())
        } finally { folder.deleteRecursively() }
    }

    private fun progress(time: Long) = ReadingProgress("book", 1, .5f, updatedAt = time, deviceId = "test")

    @Test fun conflictRereadsBothSidesAndMergesWithFreshToken() = runBlocking {
        val old = progress(1)
        val otherDevice = old.copy(favorite = true, favoriteUpdatedAt = 30, finished = true, finishedUpdatedAt = 40)
        server.enqueue(MockResponse().setBody(old.toJson()).addHeader("ETag", "\"v1\""))
        server.enqueue(MockResponse().setResponseCode(412))
        server.enqueue(MockResponse().setBody(otherDevice.toJson()).addHeader("ETag", "\"v2\""))
        server.enqueue(MockResponse().setResponseCode(204))
        var reads = 0
        val result = client.syncProgress(settings, "progress.json", "book") {
            reads++
            progress(if (reads == 1) 10 else 20)
        }
        assertEquals(2, reads)
        assertEquals(20L, result!!.updatedAt)
        assertTrue(result.favorite)
        assertTrue(result.finished)
        val get1 = server.takeRequest()
        assertEquals("identity", get1.getHeader("Accept-Encoding"))
        assertEquals("no-cache, no-store", get1.getHeader("Cache-Control"))
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-Match"))
        assertEquals("GET", server.takeRequest().method)
        val put2 = server.takeRequest()
        assertEquals("\"v2\"", put2.getHeader("If-Match"))
        assertEquals(result, ReadingProgress.fromJson(put2.body.readUtf8()))
    }

    @Test fun concurrentCreationReconcilesWithoutOverwritingNewerRemote() = runBlocking {
        val remote = progress(20)
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(412))
        server.enqueue(MockResponse().setBody(remote.toJson()).addHeader("ETag", "\"created\""))
        assertEquals(remote, client.syncProgress(settings, "progress.json", "book") { progress(10) })
        assertEquals("GET", server.takeRequest().method)
        assertEquals("*", server.takeRequest().getHeader("If-None-Match"))
        assertEquals("GET", server.takeRequest().method)
        assertEquals(3, server.requestCount)
    }

    @Test fun persistentConflictsAreBoundedAndNeverBecomeUnconditionalWrites() = runBlocking {
        repeat(3) { index ->
            server.enqueue(MockResponse().setBody(progress(1).toJson()).addHeader("ETag", "\"v$index\""))
            server.enqueue(MockResponse().setResponseCode(412))
        }
        try {
            client.syncProgress(settings, "progress.json", "book") { progress(10) }
            fail("Persistent conflict must remain visible")
        } catch (e: DavException) { assertEquals(412, e.status) }
        repeat(3) { index ->
            assertEquals("GET", server.takeRequest().method)
            assertEquals("\"v$index\"", server.takeRequest().getHeader("If-Match"))
        }
        assertEquals(6, server.requestCount)
    }

    @Test fun permissionFailuresAreNotRetriedAsConflicts() = runBlocking {
        server.enqueue(MockResponse().setBody(progress(1).toJson()).addHeader("ETag", "\"v1\""))
        server.enqueue(MockResponse().setResponseCode(403))
        try {
            client.syncProgress(settings, "progress.json", "book") { progress(10) }
            fail("Permission failure expected")
        } catch (e: DavException) { assertEquals(403, e.status) }
        assertEquals(2, server.requestCount)
    }
    @Test fun rejectsRedirectWithoutForwardingCredentials() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://other.example/book.epub"))
        try { client.readTextOrNull(settings, "book.epub"); fail("Redirect expected") }
        catch (e: DavException) { assertEquals(302, e.status) }
        assertEquals(1, server.requestCount)
    }
}
