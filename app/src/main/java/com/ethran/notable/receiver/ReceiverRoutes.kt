package com.ethran.notable.receiver

import com.ethran.notable.navigation.DeepLink
import com.ethran.notable.navigation.DeepLinkResolver.Target
import com.ethran.notable.navigation.parsePage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.discard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** What the receiver needs from the app, so that its HTTP side can be tested on the JVM. */
interface ReceiverHost {
    val version: String
    val receiveDir: File
    fun isAuthorized(authorization: String?): Boolean

    /** False until setup is finished; links are ignored until then. */
    fun isReady(): Boolean
    fun countPages(file: File): Int

    /** Redraws the pages showing [file], which the receiver has just written. */
    fun written(file: File)

    /**
     * Finds the notebook observing [link]'s file, importing the file as a notebook that observes it
     * if there is none, and shows it when [link] asks for a page or the notebook is new.
     */
    suspend fun open(link: DeepLink.Pdf): Pushed
    fun log(message: String)
}

/** What a push did: [target] is its notebook, which this push created if [imported]. */
data class Pushed(val target: Target, val imported: Boolean = false)

/**
 * The receiver's endpoints. Like links, an endpoint's meaning never changes: something new comes
 * as a new endpoint, or as an optional parameter, which older versions ignore.
 *
 * - `GET /`: `Notable <version>`, without a token, to tell "receiver off" from "wrong token".
 * - `PUT /pdf?name=<file name>[&page=N]` with `Authorization: Bearer <token>` and the PDF as the
 *   body: writes it as `<receive folder>/<name>` and redraws the notebook that observes it. The
 *   first push of a name imports it as such a notebook. A push moves the user only to show a new
 *   notebook, or to page N of the PDF when it gives `page`. Answers a status code, which scripts
 *   act on, and one line for a person.
 */
fun Application.pdfReceiver(host: ReceiverHost) {
    routing {
        get("/") { call.respondText("Notable ${host.version}\n") }

        put("/pdf") {
            val started = System.nanoTime()
            suspend fun reply(status: HttpStatusCode, text: String) {
                val ms = (System.nanoTime() - started) / 1_000_000
                host.log("PUT /pdf: ${status.value} $text ($ms ms)")
                call.respondText("$text\n", status = status)
            }

            // Read before answering, so the client sees the answer and not a reset connection.
            suspend fun refuse(status: HttpStatusCode, text: String) {
                call.receiveChannel().discard(MAX_PDF_BYTES)
                reply(status, text)
            }

            if (!host.isAuthorized(call.request.header(HttpHeaders.Authorization)))
                return@put refuse(HttpStatusCode.Unauthorized, "Wrong or missing token")
            val name = call.request.queryParameters["name"]
            if (name == null || !isAllowedName(name))
                return@put refuse(HttpStatusCode.BadRequest, "Give name=<file name>.pdf, without a path")
            val length = call.request.contentLength()
                ?: return@put reply(HttpStatusCode.LengthRequired, "Content-Length is required")
            if (length > MAX_PDF_BYTES)
                return@put reply(HttpStatusCode.PayloadTooLarge, "Larger than ${MAX_PDF_BYTES shr 20} MB")
            if (!host.isReady())
                return@put refuse(HttpStatusCode.ServiceUnavailable, "Finish setting up Notable on the tablet first")

            val target = File(host.receiveDir, name)
            val written = withContext(Dispatchers.IO) {
                call.receiveStream().use { body ->
                    writeReceivedPdf(body, length, target, host::countPages)
                        .also { if (it != WriteResult.Written) body.skipRest() }
                }
            }
            when (written) {
                WriteResult.NotPdf -> return@put reply(HttpStatusCode.UnsupportedMediaType, "Not a PDF")
                WriteResult.TooLarge ->
                    return@put reply(HttpStatusCode.PayloadTooLarge, "Larger than ${MAX_PDF_BYTES shr 20} MB")
                WriteResult.Incomplete ->
                    return@put reply(HttpStatusCode.BadRequest, "The upload ended early; the old file is kept")
                WriteResult.Unreadable ->
                    return@put reply(HttpStatusCode.UnprocessableEntity, "Not a readable PDF; the old one is kept")
                is WriteResult.Failed -> return@put reply(
                    HttpStatusCode.InternalServerError,
                    "Writing failed, the old file is kept: ${written.error.message}"
                )
                WriteResult.Written -> host.written(target)
            }

            val page = parsePage(call.request.queryParameters["page"])
            val pushed = try {
                host.open(DeepLink.Pdf(target.path, page))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The file is written by now, which a 500 would deny.
                host.log("Opening ${target.path} failed: $e")
                Pushed(Target.Failed(e.message ?: e.toString()))
            }
            val (status, text) = responseFor(pushed, page)
            reply(status, text)
        }

        route("{...}") {
            handle {
                call.respondText(
                    "Unknown request; this Notable may be older than your tool\n",
                    status = HttpStatusCode.NotFound
                )
            }
        }
    }
}

/**
 * The answer to a PDF that was written: 200 when a notebook observes it, so that it shows the new
 * version; 202 when none does.
 */
internal fun responseFor(pushed: Pushed, page: Int?): Pair<HttpStatusCode, String> =
    when (val target = pushed.target) {
        is Target.Editor, is Target.Pages -> HttpStatusCode.OK to when {
            pushed.imported -> "Imported as a new notebook, which follows the file"
            target is Target.Editor && target.note != null -> "Updated; ${target.note}"
            page != null -> "Updated, at page $page"
            else -> "Updated"
        }

        is Target.AskToImport -> HttpStatusCode.Accepted to "Written; no notebook follows it"
        is Target.Failed -> HttpStatusCode.Accepted to "Written; no notebook follows it: ${target.message}"
    }

/** Reads what is left of a body that is refused, so the client sees the answer, not a reset. */
private fun InputStream.skipRest() {
    val buffer = ByteArray(64 * 1024)
    while (read(buffer) >= 0) Unit
}
