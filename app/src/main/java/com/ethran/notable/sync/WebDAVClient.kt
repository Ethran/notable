package com.ethran.notable.sync

import android.net.Uri
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.getOrNull
import com.ethran.notable.utils.map
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onFailure
import com.ethran.notable.utils.onSuccess
import io.shipbook.shipbooksdk.Log
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.net.HttpURLConnection
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * A remote WebDAV collection entry with its name and last-modified timestamp.
 */
data class RemoteEntry(val name: String, val lastModified: Date?)

data class DownloadedFile(
    val content: ByteArray, val etag: String?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DownloadedFile

        if (!content.contentEquals(other.content)) return false
        if (etag != other.etag) return false

        return true
    }

    override fun hashCode(): Int {
        var result = content.contentHashCode()
        result = 31 * result + (etag?.hashCode() ?: 0)
        return result
    }
}

/**
 * Result of a connection test, including optional clock skew information.
 */
data class ConnectionTestResult(val clockSkewMs: Long? = null)

/**
 * WebDAV client built on OkHttp for Notable sync operations.
 */
class WebDAVClient(
    private val serverUrl: String,
    username: String,
    password: String,
    private val client: OkHttpClient
) {
    private val credentials = Credentials.basic(username, password)

    /**
     * Test connection to WebDAV server.
     * Checks server connectivity and detects clock skew.
     * @return AppResult.Success with ConnectionTestResult (includes clock skew info if available),
     *         or AppResult.Error with details.
     */
    fun testConnection(): AppResult<ConnectionTestResult, DomainError> =
        execute("Connection test", {
            Request.Builder().url(serverUrl).head().header("Authorization", credentials).build()
        }) { response ->
            when {
                response.isSuccessful -> {
                    val clockSkewMs =
                        getServerTime().getOrNull()?.let { System.currentTimeMillis() - it }
                    AppResult.Success(ConnectionTestResult(clockSkewMs = clockSkewMs))
                }

                response.code == 401 -> AppResult.Error(DomainError.SyncAuthError)
                else -> AppResult.Error(DomainError.SyncError("Server rejected connection: ${response.code}"))
            }
        }

    /**
     * Get the server's current time from the Date response header (RFC 1123).
     * @return Server time as epoch millis on success, or an Error when the request failed or the
     *         Date header was missing/unparseable.
     */
    fun getServerTime(): AppResult<Long, DomainError> =
        execute("Server time", {
            Request.Builder().url(serverUrl).head().header("Authorization", credentials).build()
        }) { response ->
            if (!response.isSuccessful) {
                AppResult.Error(DomainError.SyncError("Server time HEAD failed: ${response.code}"))
            } else {
                response.header("Date")?.let { parseHttpDate(it) }
                    ?.let { AppResult.Success(it) }
                    ?: AppResult.Error(DomainError.NetworkError("Missing or unparseable Date header"))
            }
        }

    /**
     * Check whether a resource exists on the server.
     *
     * Tri-state: `Success(true)` when present, `Success(false)` on a 404, and `Error` when the
     * check could not be completed (network failure or an unexpected status). Callers must NOT
     * treat "could not determine" as "absent" -- doing so previously let a transient network error
     * trigger an unguarded upload over a possibly-newer remote (P2).
     */
    fun exists(path: String): AppResult<Boolean, DomainError> =
        execute("HEAD", {
            Request.Builder().url(buildUrl(path)).head().header("Authorization", credentials).build()
        }) { response ->
            when {
                response.isSuccessful -> AppResult.Success(true)
                response.code == HttpURLConnection.HTTP_NOT_FOUND -> AppResult.Success(false)
                else -> AppResult.Error(DomainError.SyncError("HEAD failed: ${response.code}"))
            }
        }

    /**
     * Create a WebDAV collection (directory).
     * A 405 is treated as success (collection already exists, per RFC 4918).
     */
    fun createCollection(path: String): AppResult<Unit, DomainError> =
        execute("MKCOL", {
            Request.Builder().url(buildUrl(path)).method("MKCOL", null)
                .header("Authorization", credentials).build()
        }) { response ->
            if (response.isSuccessful || response.code == 405) {
                AppResult.Success(Unit)
            } else {
                AppResult.Error(DomainError.SyncError("MKCOL failed: ${response.code}"))
            }
        }

    /**
     * Upload a file to the WebDAV server.
     * @param path Remote path relative to server URL
     * @param content File content as ByteArray
     * @param contentType MIME type of the content
     * @param ifMatch optional ETag for optimistic concurrency (returns SyncConflict on 412)
     */
    fun putFile(
        path: String,
        content: ByteArray,
        contentType: String = "application/octet-stream",
        ifMatch: String? = null
    ): AppResult<Unit, DomainError> =
        execute("PUT", {
            val requestBody = content.toRequestBody(contentType.toMediaType())
            Request.Builder().url(buildUrl(path)).put(requestBody)
                .header("Authorization", credentials)
                .apply { ifMatch?.let { header("If-Match", it) } }
                .build()
        }) { response ->
            when {
                response.code == HttpURLConnection.HTTP_PRECON_FAILED ->
                    AppResult.Error(DomainError.SyncConflict)

                response.isSuccessful -> AppResult.Success(Unit)
                else -> AppResult.Error(DomainError.SyncError("PUT failed: ${response.code}"))
            }
        }

    /**
     * Upload a file from local filesystem.
     */
    fun putFile(
        path: String,
        localFile: File,
        contentType: String = "application/octet-stream",
        ifMatch: String? = null
    ): AppResult<Unit, DomainError> {
        if (!localFile.exists()) return AppResult.Error(DomainError.SyncError("Local file missing"))
        return putFile(path, localFile.readBytes(), contentType, ifMatch)
    }

    /**
     * Upload a file and return the server's new ETag for it (from the PUT response `ETag` header),
     * or `null` if the server did not send one. Used for the manifest so the notebook's stored ETag
     * matches the just-published version, enabling cheap `If-None-Match` change detection next sync
     * (P26). Returns [DomainError.SyncConflict] on a 412 like [putFile].
     */
    fun putFileReturningEtag(
        path: String,
        content: ByteArray,
        contentType: String = "application/octet-stream",
        ifMatch: String? = null
    ): AppResult<String?, DomainError> =
        execute("PUT", {
            val requestBody = content.toRequestBody(contentType.toMediaType())
            Request.Builder().url(buildUrl(path)).put(requestBody)
                .header("Authorization", credentials)
                .apply { ifMatch?.let { header("If-Match", it) } }
                .build()
        }) { response ->
            when {
                response.code == HttpURLConnection.HTTP_PRECON_FAILED ->
                    AppResult.Error(DomainError.SyncConflict)

                response.isSuccessful -> AppResult.Success(response.header("ETag"))
                else -> AppResult.Error(DomainError.SyncError("PUT failed: ${response.code}"))
            }
        }

    fun getFile(path: String): AppResult<ByteArray, DomainError> {
        return getFileWithMetadata(path).map { it.content }
    }

    fun getFileWithMetadata(path: String): AppResult<DownloadedFile, DomainError> =
        execute("GET", {
            Request.Builder().url(buildUrl(path)).get().header("Authorization", credentials).build()
        }) { response ->
            if (response.isSuccessful) {
                AppResult.Success(DownloadedFile(response.body.bytes(), response.header("ETag")))
            } else {
                AppResult.Error(DomainError.SyncError("GET failed: ${response.code}"))
            }
        }

    /**
     * Conditional GET: fetch a file only if its ETag differs from [etag].
     * Returns `Success(null)` when the server replies `304 Not Modified` (the resource is unchanged
     * since we stored [etag]) — a cheap, bodyless "no change" answer that avoids clock math (5a).
     * Otherwise returns the fetched file with its current ETag.
     */
    fun getFileIfNoneMatch(path: String, etag: String): AppResult<DownloadedFile?, DomainError> =
        execute("GET", {
            Request.Builder().url(buildUrl(path)).get()
                .header("Authorization", credentials)
                .header("If-None-Match", etag)
                .build()
        }) { response ->
            when {
                response.code == HttpURLConnection.HTTP_NOT_MODIFIED -> AppResult.Success(null)
                response.isSuccessful ->
                    AppResult.Success(DownloadedFile(response.body.bytes(), response.header("ETag")))

                else -> AppResult.Error(DomainError.SyncError("GET failed: ${response.code}"))
            }
        }

    fun getFile(path: String, localFile: File): AppResult<Unit, DomainError> {
        return getFile(path).onSuccess { content ->
            localFile.parentFile?.mkdirs()
            localFile.writeBytes(content)
        }.map { }
    }

    /**
     * Delete a resource from the WebDAV server.
     * A 404 is treated as success (the resource is already gone).
     */
    fun delete(path: String): AppResult<Unit, DomainError> =
        execute("DELETE", {
            Request.Builder().url(buildUrl(path)).delete().header("Authorization", credentials)
                .build()
        }) { response ->
            if (response.isSuccessful || response.code == HttpURLConnection.HTTP_NOT_FOUND) {
                AppResult.Success(Unit)
            } else {
                AppResult.Error(DomainError.SyncError("DELETE failed: ${response.code}"))
            }
        }

    /**
     * List resources in a collection using PROPFIND.
     * @return List of UUID resource names in the collection
     */
    fun listCollection(path: String): AppResult<List<String>, DomainError> =
        execute("PROPFIND", { propfindRequest(path, PROPFIND_ALLPROP) }) { response ->
            if (response.isSuccessful) {
                val hrefs = WebDavXml.parseHrefs(response.body.string())
                AppResult.Success(hrefs.filter { it != path && !it.endsWith("/$path") }
                    .map { Uri.decode(it.trimEnd('/').substringAfterLast('/')) }
                    .filter { WebDavXml.isValidUuid(it) })
            } else {
                AppResult.Error(DomainError.SyncError("PROPFIND failed: ${response.code}"))
            }
        }

    /**
     * List the raw child names of a collection (file names *with* extensions, decoded), excluding
     * the collection's own entry. Unlike [listCollection] this does NOT filter to bare UUIDs, so it
     * can see `{pageId}.json`, image, and background files — used for garbage collection.
     * Returns an empty list when the collection does not exist (404).
     */
    fun listNames(path: String): AppResult<List<String>, DomainError> =
        execute("PROPFIND", { propfindRequest(path, PROPFIND_ALLPROP) }) { response ->
            when {
                response.code == HttpURLConnection.HTTP_NOT_FOUND -> AppResult.Success(emptyList())
                response.isSuccessful -> {
                    val selfName = path.trimEnd('/').substringAfterLast('/')
                    val names = WebDavXml.parseHrefs(response.body.string())
                        .map { Uri.decode(it.trimEnd('/').substringAfterLast('/')) }
                        .filter { it.isNotEmpty() && it != selfName }
                    AppResult.Success(names)
                }

                else -> AppResult.Error(DomainError.SyncError("PROPFIND failed: ${response.code}"))
            }
        }

    /**
     * List resources in a collection with their last-modified timestamps.
     * Used for tombstone-based deletion tracking where we need the server's
     * own timestamp for conflict resolution.
     * @return List of RemoteEntry objects; empty if collection doesn't exist
     */
    fun listCollectionWithMetadata(path: String): AppResult<List<RemoteEntry>, DomainError> =
        execute("PROPFIND", { propfindRequest(path, PROPFIND_LASTMODIFIED) }) { response ->
            when {
                response.code == HttpURLConnection.HTTP_NOT_FOUND -> AppResult.Success(emptyList())
                response.isSuccessful -> {
                    val entries = WebDavXml.parseEntries(response.body.string())
                    AppResult.Success(entries.filter { (href, _) -> href != path && !href.endsWith("/$path") }
                        .mapNotNull { (href, lastModified) ->
                            val name = Uri.decode(href.trimEnd('/').substringAfterLast('/'))
                            if (WebDavXml.isValidUuid(name)) RemoteEntry(name, lastModified) else null
                        })
                }

                else -> AppResult.Error(DomainError.SyncError("PROPFIND failed: ${response.code}"))
            }
        }

    /**
     * Ensure parent directories exist, creating them if necessary.
     * @param path File path (will create parent directories)
     */
    fun ensureParentDirectories(path: String): AppResult<Unit, DomainError> {
        val segments = path.trimStart('/').split('/')
        if (segments.size <= 1) return AppResult.Success(Unit)

        var currentPath = ""
        for (i in 0 until segments.size - 1) {
            currentPath += "/" + segments[i]
            val present = exists(currentPath).onFailure { return AppResult.Error(it) }
            if (!present) {
                createCollection(currentPath).onError { return AppResult.Error(it) }
            }
        }
        return AppResult.Success(Unit)
    }

    /**
     * Issue an authenticated request and map the response, translating any thrown exception into a
     * [DomainError.NetworkError]. The request is built inside the try so that malformed-URL failures
     * are reported as network errors rather than propagating.
     */
    private inline fun <T> execute(
        errorLabel: String,
        buildRequest: () -> Request,
        map: (Response) -> AppResult<T, DomainError>
    ): AppResult<T, DomainError> {
        return try {
            client.newCall(buildRequest()).execute().use { response -> map(response) }
        } catch (e: Exception) {
            AppResult.Error(DomainError.NetworkError(e.message ?: "$errorLabel failed"))
        }
    }

    private fun propfindRequest(path: String, body: String): Request {
        val requestBody = body.toRequestBody("application/xml".toMediaType())
        return Request.Builder().url(buildUrl(path)).method("PROPFIND", requestBody)
            .header("Authorization", credentials).header("Depth", "1").build()
    }

    /**
     * Build full URL from server URL and path.
     */
    private fun buildUrl(path: String): String {
        val normalizedServer = serverUrl.trimEnd('/')
        // Percent-encode each path segment individually (keeping the '/' separators) so that
        // image/background filenames with spaces or reserved characters produce valid URLs.
        val encodedPath = path.trim('/').split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { Uri.encode(it) }
        return "$normalizedServer/$encodedPath"
    }

    companion object {
        private const val TAG = "WebDAVClient"

        private val PROPFIND_ALLPROP = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:">
                <D:allprop/>
            </D:propfind>
        """.trimIndent()

        private val PROPFIND_LASTMODIFIED = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:">
                <D:prop>
                    <D:getlastmodified/>
                </D:prop>
            </D:propfind>
        """.trimIndent()

        /**
         * Parse an HTTP Date header (RFC 1123 format) to epoch millis.
         * Uses the thread-safe [DateTimeFormatter.RFC_1123_DATE_TIME] singleton (safe to reuse
         * across concurrent parses, unlike [java.text.SimpleDateFormat]).
         * @return Epoch millis or null if unparseable
         */
        fun parseHttpDate(dateHeader: String): Long? {
            return try {
                ZonedDateTime.parse(dateHeader, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse HTTP date: ${e.message}", e)
                null
            }
        }
    }
}
