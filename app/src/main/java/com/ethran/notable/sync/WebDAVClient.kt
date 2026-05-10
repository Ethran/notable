package com.ethran.notable.sync

import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.logCallStack
import com.ethran.notable.utils.map
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onSuccess
import io.shipbook.shipbooksdk.Log
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * A remote WebDAV collection entry with its name and last-modified timestamp.
 */
data class RemoteEntry(val name: String, val lastModified: Date?)

data class DownloadedFile(
    val content: ByteArray, val etag: String?
) {
    override fun equals(other: Any?): Boolean {
        logCallStack("equals called")
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DownloadedFile

        if (!content.contentEquals(other.content)) return false
        if (etag != other.etag) return false

        return true
    }

    override fun hashCode(): Int {
        logCallStack("hashCode called")
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
    private val serverUrl: String, username: String, password: String
) {
    private val client =
        OkHttpClient.Builder().connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    private val credentials = Credentials.basic(username, password)

    /**
     * Test connection to WebDAV server.
     * Checks server connectivity and detects clock skew.
     * @return AppResult.Success with ConnectionTestResult (includes clock skew info if available),
     *         or AppResult.Error with details.
     */
    fun testConnection(): AppResult<ConnectionTestResult, DomainError> {
        return try {
            val request =
                Request.Builder().url(serverUrl).head().header("Authorization", credentials).build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val clockSkewMs = getServerTime()?.let { System.currentTimeMillis() - it }
                        AppResult.Success(ConnectionTestResult(clockSkewMs = clockSkewMs))
                    }

                    response.code == 401 -> AppResult.Error(DomainError.SyncAuthError)
                    else -> AppResult.Error(DomainError.SyncError("Server rejected connection: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            AppResult.Error(DomainError.NetworkError(e.message ?: "Connection test failed"))
        }
    }

    /**
     * Get the server's current time from the Date response header.
     * Makes a HEAD request and parses the RFC 1123 Date header.
     * @return Server time as epoch millis, or null if unavailable/unparseable
     */
    fun getServerTime(): Long? {
        return try {
            val request =
                Request.Builder().url(serverUrl).head().header("Authorization", credentials).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val dateHeader = response.header("Date") ?: return@use null
                parseHttpDate(dateHeader)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get server time: ${e.message}")
            null
        }
    }

    /**
     * Check if a resource exists on the server.
     * @param path Resource path relative to server URL
     * @return true if resource exists
     */
    fun exists(path: String): Boolean {
        return try {
            val url = buildUrl(path)
            val request =
                Request.Builder().url(url).head().header("Authorization", credentials).build()

            client.newCall(request).execute().use { response ->
                response.code == HttpURLConnection.HTTP_OK
            }
        } catch (e: Exception) {
            Log.w(TAG, "exists($path) check failed: ${e.message}")
            false
        }
    }

    /**
     * Create a WebDAV collection (directory).
     * @param path Collection path relative to server URL
     * @throws IOException if creation fails
     */
    fun createCollection(path: String): AppResult<Unit, DomainError> {
        return try {
            val url = buildUrl(path)
            val request = Request.Builder().url(url).method("MKCOL", null)
                .header("Authorization", credentials).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 405) {
                    AppResult.Success(Unit)
                } else {
                    AppResult.Error(DomainError.SyncError("MKCOL failed: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            AppResult.Error(DomainError.NetworkError(e.message ?: "MKCOL failed"))
        }
    }

    /**
     * Upload a file to the WebDAV server.
     * @param path Remote path relative to server URL
     * @param content File content as ByteArray
     * @param contentType MIME type of the content
     * @throws IOException if upload fails
     */
    fun putFile(
        path: String,
        content: ByteArray,
        contentType: String = "application/octet-stream",
        ifMatch: String? = null
    ): AppResult<Unit, DomainError> {
        return try {
            val url = buildUrl(path)
            val requestBody = content.toRequestBody(contentType.toMediaType())
            val requestBuilder =
                Request.Builder().url(url).put(requestBody).header("Authorization", credentials)

            ifMatch?.let { requestBuilder.header("If-Match", it) }

            client.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.code == HttpURLConnection.HTTP_PRECON_FAILED -> AppResult.Error(
                        DomainError.SyncConflict
                    )

                    response.isSuccessful -> AppResult.Success(Unit)
                    else -> AppResult.Error(DomainError.SyncError("PUT failed: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            AppResult.Error(DomainError.NetworkError(e.message ?: "PUT failed"))
        }
    }

    /**
     * Upload a file from local filesystem.
     * @param path Remote path relative to server URL
     * @param localFile Local file to upload
     * @param contentType MIME type of the content
     * @throws IOException if upload fails
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

    fun getFile(path: String): AppResult<ByteArray, DomainError> {
        return getFileWithMetadata(path).map { it.content }
    }

    fun getFileWithMetadata(path: String): AppResult<DownloadedFile, DomainError> {
        return try {
            val url = buildUrl(path)
            val request =
                Request.Builder().url(url).get().header("Authorization", credentials).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    AppResult.Success(
                        DownloadedFile(
                            response.body.bytes(), response.header("ETag")
                        )
                    )
                } else {
                    AppResult.Error(DomainError.SyncError("GET failed: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            AppResult.Error(DomainError.NetworkError(e.message ?: "GET failed"))
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
     * @param path Resource path relative to server URL
     * @throws IOException if deletion fails
     */
    fun delete(path: String): AppResult<Unit, DomainError> {
        return try {
            val url = buildUrl(path)
            val request =
                Request.Builder().url(url).delete().header("Authorization", credentials).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == HttpURLConnection.HTTP_NOT_FOUND) {
                    AppResult.Success(Unit)
                } else {
                    AppResult.Error(DomainError.SyncError("DELETE failed: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            AppResult.Error(DomainError.NetworkError(e.message ?: "DELETE failed"))
        }
    }


    /**
     * List resources in a collection using PROPFIND.
     * @param path Collection path relative to server URL
     * @return List of resource names in the collection
     * @throws IOException if PROPFIND fails
     */
    fun listCollection(path: String): AppResult<List<String>, DomainError> {
        return try {
            val url = buildUrl(path)
            // WebDAV PROPFIND request body for directory listing
            val propfindXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:">
                <D:allprop/>
            </D:propfind>
        """.trimIndent()
            val requestBody = propfindXml.toRequestBody("application/xml".toMediaType())
            val request = Request.Builder().url(url).method("PROPFIND", requestBody)
                .header("Authorization", credentials).header("Depth", "1").build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val hrefs = parseHrefsFromXml(response.body.string())
                    AppResult.Success(hrefs.filter { it != path && !it.endsWith("/$path") }
                        .map { it.trimEnd('/').substringAfterLast('/') }.filter { isValidUuid(it) })
                } else {
                    AppResult.Error(DomainError.SyncError("PROPFIND failed: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            AppResult.Error(DomainError.NetworkError(e.message ?: "PROPFIND failed"))
        }
    }

    /**
     * List resources in a collection with their last-modified timestamps.
     * Used for tombstone-based deletion tracking where we need the server's
     * own timestamp for conflict resolution.
     * @param path Collection path relative to server URL
     * @return List of RemoteEntry objects; empty if collection doesn't exist
     * @throws IOException if PROPFIND fails for a reason other than 404
     */
    fun listCollectionWithMetadata(path: String): AppResult<List<RemoteEntry>, DomainError> {
        return try {
            val url = buildUrl(path)

            val propfindXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:">
                <D:prop>
                    <D:getlastmodified/>
                </D:prop>
            </D:propfind>
        """.trimIndent()
            val requestBody = propfindXml.toRequestBody("application/xml".toMediaType())
            val request = Request.Builder().url(url).method("PROPFIND", requestBody)
                .header("Authorization", credentials).header("Depth", "1").build()

            client.newCall(request).execute().use { response ->
                if (response.code == HttpURLConnection.HTTP_NOT_FOUND) return@use AppResult.Success(
                    emptyList()
                )
                if (response.isSuccessful) {
                    val entries = parseEntriesFromXml(response.body.string())
                    AppResult.Success(entries.filter { (href, _) -> href != path && !href.endsWith("/$path") }
                        .mapNotNull { (href, lastModified) ->
                            val name = href.trimEnd('/').substringAfterLast('/')
                            if (isValidUuid(name)) RemoteEntry(name, lastModified) else null
                        })
                } else {
                    AppResult.Error(DomainError.SyncError("PROPFIND failed: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            AppResult.Error(DomainError.NetworkError(e.message ?: "PROPFIND failed"))
        }
    }

    /**
     * Ensure parent directories exist, creating them if necessary.
     * @param path File path (will create parent directories)
     * @throws IOException if directory creation fails
     */
    fun ensureParentDirectories(path: String): AppResult<Unit, DomainError> {
        val segments = path.trimStart('/').split('/')
        if (segments.size <= 1) return AppResult.Success(Unit)

        var currentPath = ""
        for (i in 0 until segments.size - 1) {
            currentPath += "/" + segments[i]
            if (!exists(currentPath)) {
                createCollection(currentPath).onError { return AppResult.Error(it) }
            }
        }
        return AppResult.Success(Unit)
    }

    /**
     * Build full URL from server URL and path.
     * @param path Relative path
     * @return Full URL
     */
    private fun buildUrl(path: String): String {
        val normalizedServer = serverUrl.trimEnd('/')
        val normalizedPath = if (path.startsWith('/')) path else "/$path"
        return normalizedServer + normalizedPath
    }

    /**
     * Parse href values from WebDAV XML response.
     * Properly handles namespaces, CDATA, and whitespace.
     * @param xml XML response from PROPFIND
     * @return List of href values
     */
    private fun parseHrefsFromXml(xml: String): List<String> {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            val hrefs = mutableListOf<String>()
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name.lowercase() == "href") {
                    if (parser.next() == XmlPullParser.TEXT) hrefs.add(parser.text.trim())
                }
                eventType = parser.next()
            }
            hrefs
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse XML for hrefs: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parse <response> blocks from a PROPFIND XML response, returning each
     * resource's href paired with its last-modified date (null if absent).
     */
    private fun parseEntriesFromXml(xml: String): List<Pair<String, Date?>> {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            val entries = mutableListOf<Pair<String, Date?>>()
            var currentHref: String? = null
            var currentLastModified: Date? = null
            var inResponse = false

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                        "response" -> {
                            inResponse = true; currentHref = null; currentLastModified = null
                        }

                        "href" -> if (inResponse && parser.next() == XmlPullParser.TEXT) currentHref =
                            parser.text.trim()

                        "getlastmodified" -> if (inResponse && parser.next() == XmlPullParser.TEXT) {
                            currentLastModified =
                                parseHttpDate(parser.text.trim())?.let { Date(it) }
                        }
                    }

                    XmlPullParser.END_TAG -> if (parser.name.lowercase() == "response" && inResponse) {
                        currentHref?.let { entries.add(it to currentLastModified) }
                        inResponse = false
                    }
                }
                eventType = parser.next()
            }
            entries
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse XML for entries", e)
            emptyList()
        }
    }

    private fun isValidUuid(name: String): Boolean =
        name.length == 36 && name[8] == '-' && name[13] == '-' && name[18] == '-' && name[23] == '-'

    companion object {
        private const val TAG = "WebDAVClient"

        // Timeout constants
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 60L
        private const val WRITE_TIMEOUT_SECONDS = 60L

        // RFC 1123 date format used in HTTP Date headers
        private const val HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"

        /**
         * Parse an HTTP Date header (RFC 1123 format) to epoch millis.
         * @return Epoch millis or null if unparseable
         */
        fun parseHttpDate(dateHeader: String): Long? {
            return try {
                val sdf = SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("GMT")
                sdf.parse(dateHeader)?.time
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse HTTP date: ${e.message}", e)
                null
            }
        }
    }
}
