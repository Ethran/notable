package com.ethran.notable.receiver

import com.ethran.notable.navigation.DeepLink
import com.ethran.notable.navigation.DeepLinkResolver.Target
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReceiverRoutesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val pdf = "%PDF-1.5 a page".encodeToByteArray()

    private inner class FakeHost : ReceiverHost {
        override val version = "1.2.3"
        override val receiveDir: File get() = folder.root
        var ready = true
        var pushed = Pushed(Target.Editor("p-1", "b-1"))
        var openFails = false
        val opened = mutableListOf<DeepLink.Pdf>()
        val written = mutableListOf<File>()

        override fun isAuthorized(authorization: String?) = authorization == "Bearer t0ken"
        override fun isReady() = ready
        override fun countPages(file: File) = 1
        override fun written(file: File) {
            written += file
        }

        override suspend fun open(link: DeepLink.Pdf): Pushed {
            opened += link
            if (openFails) throw IllegalStateException("database is locked")
            return pushed
        }
        override fun log(message: String) {}
    }

    private fun receiver(host: FakeHost = FakeHost(), test: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { pdfReceiver(host) }
            test()
        }

    private suspend fun ApplicationTestBuilder.push(
        query: String, body: ByteArray = pdf, token: String = "t0ken"
    ): HttpResponse = client.put("/pdf?$query") {
        header(HttpHeaders.Authorization, "Bearer $token")
        setBody(body)
    }

    private fun received() = folder.root.list()!!.toSet()

    @Test
    fun healthCheck_needsNoToken() = receiver {
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Notable 1.2.3\n", response.bodyAsText())
    }

    @Test
    fun aPush_writesTheFile_andOpensItLikeALink() {
        val host = FakeHost()
        receiver(host) {
            // What `curl --url-query "name=ADE ż+x changes.pdf"` sends.
            val response = push("name=ADE+%c5%bc%2bx+changes.pdf&page=3")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("Updated, at page 3\n", response.bodyAsText())
        }
        val file = File(folder.root, "ADE ż+x changes.pdf")
        assertTrue(pdf.contentEquals(file.readBytes()))
        assertEquals(listOf(file), host.written)
        assertEquals(listOf(DeepLink.Pdf(file.path, page = 3)), host.opened)
    }

    @Test
    fun refusedPushes_writeNothing() {
        val host = FakeHost()
        receiver(host) {
            assertEquals(HttpStatusCode.Unauthorized, push("name=a.pdf", token = "wrong").status)
            assertEquals(HttpStatusCode.BadRequest, push("page=2").status)
            for (name in listOf("..%2Fa.pdf", ".a.pdf", "a.txt", "%2Fsdcard%2Fa.pdf"))
                assertEquals(name, HttpStatusCode.BadRequest, push("name=$name").status)
            assertEquals(HttpStatusCode.UnsupportedMediaType, push("name=a.pdf", body = "<html>".encodeToByteArray()).status)
            host.ready = false
            assertEquals(HttpStatusCode.ServiceUnavailable, push("name=a.pdf").status)
        }
        assertEquals(emptySet<String>(), received())
        assertEquals(emptyList<File>(), host.written)
        assertEquals(emptyList<DeepLink.Pdf>(), host.opened)
    }

    @Test
    fun aBodyWithoutLength_isRefused() = receiver {
        val response = client.put("/pdf?name=a.pdf") {
            header(HttpHeaders.Authorization, "Bearer t0ken")
            setBody(object : OutgoingContent.WriteChannelContent() {
                override suspend fun writeTo(channel: ByteWriteChannel) = channel.writeFully(pdf)
            })
        }
        assertEquals(HttpStatusCode.LengthRequired, response.status)
        assertFalse(File(folder.root, "a.pdf").exists())
    }

    @Test
    fun theAnswer_saysWhetherANotebookFollowsTheFile() {
        val cases = listOf(
            Pushed(Target.Editor("p-1", "b-1")) to HttpStatusCode.OK,
            Pushed(Target.Editor("p-1", "b-1"), imported = true) to HttpStatusCode.OK,
            Pushed(Target.Editor("p-1", "b-1", note = "X has 2 pages")) to HttpStatusCode.OK,
            Pushed(Target.Pages("b-1")) to HttpStatusCode.OK,
            Pushed(Target.Failed("Import failed: no pages"), imported = true) to HttpStatusCode.Accepted,
            Pushed(Target.AskToImport(DeepLink.Pdf("/x.pdf"))) to HttpStatusCode.Accepted,
        )
        for ((pushed, status) in cases) {
            val host = FakeHost().apply { this.pushed = pushed }
            receiver(host) { assertEquals(pushed.toString(), status, push("name=a.pdf").status) }
        }
    }

    @Test
    fun aFailureAfterTheWrite_saysTheFileIsWritten() {
        val host = FakeHost().apply { openFails = true }
        receiver(host) {
            val response = push("name=a.pdf")
            assertEquals(HttpStatusCode.Accepted, response.status)
            assertTrue(response.bodyAsText(), response.bodyAsText().contains("database is locked"))
        }
        assertTrue(pdf.contentEquals(File(folder.root, "a.pdf").readBytes()))
    }

    @Test
    fun unknownRequests_sayTheToolMayBeNewer() = receiver {
        for (response in listOf(client.get("/events"), client.put("/") { setBody(pdf) })) {
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("older than your tool"))
        }
    }
}
