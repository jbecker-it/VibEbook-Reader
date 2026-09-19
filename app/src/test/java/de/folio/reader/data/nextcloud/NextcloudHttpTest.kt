package de.folio.reader.data.nextcloud

import de.folio.reader.domain.model.NextcloudSettings
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
        catch (e: DavException) { assertEquals(403, e.status) }
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
    @Test fun rejectsRedirectWithoutForwardingCredentials() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://other.example/book.epub"))
        try { client.readTextOrNull(settings, "book.epub"); fail("Redirect expected") }
        catch (e: DavException) { assertEquals(302, e.status) }
        assertEquals(1, server.requestCount)
    }
}
