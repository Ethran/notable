package com.ethran.notable.sync

import io.shipbook.shipbooksdk.Log
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
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

/**
 * Wrapper for streaming file downloads that properly manages the underlying HTTP response.
 * This class ensures that both the InputStream and the HTTP Response are properly closed.
 *
 * Usage:
 * ```
 * webdavClient.getFileStream(path).use { streamResponse ->
 *     streamResponse.inputStream.copyTo(outputStream)
 * }
 * ```
 */
class StreamResponse(
    private val response: Response,
    val inputStream: InputStream
) : Closeable {
    override fun close() {
        try {
            inputStream.close()
        } catch (e: Exception) {
            // Ignore input stream close errors
        }
        try {
            response.close()
        } catch (e: Exception) {
            // Ignore response close errors
        }
    }
}

/**
 * WebDAV client built on OkHttp for Notable sync operations.
 * Supports basic authentication and common WebDAV methods.
 */
class WebDAVClient(
    private val serverUrl: String,
    private val username: String,
    private val password: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val credentials = Credentials.basic(username, password)

    /**
     * Test connection to WebDAV server.
     * @return true if connection successful, false otherwise
     */
    fun testConnection(): Boolean {
        return try {
            Log.i(TAG, "Testing connection to: $serverUrl")
            val request = Request.Builder()
                .url(serverUrl)
                .head()
                .header("Authorization", credentials)
                .build()

            client.newCall(request).execute().use { response ->
                Log.i(TAG, "Response code: ${response.code}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed: ${e.message}", e)
            false
        }
    }

    /**
     * Get the server's current time from the Date response header.
     * Makes a HEAD request and parses the RFC 1123 Date header.
     * @return Server time as epoch millis, or null if unavailable/unparseable
     */
    fun getServerTime(): Long? {
        return try {
            val request = Request.Builder()
                .url(serverUrl)
                .head()
                .header("Authorization", credentials)
                .build()

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
            val request = Request.Builder()
                .url(url)
                .head()
                .header("Authorization", credentials)
                .build()

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
    fun createCollection(path: String) {
        val url = buildUrl(path)
        val request = Request.Builder()
            .url(url)
            .method("MKCOL", null)
            .header("Authorization", credentials)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 405) {
                // 405 Method Not Allowed means collection already exists, which is fine
                throw IOException("Failed to create collection: ${response.code} ${response.message}")
            }
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
        contentType: String = "application/octet-stream"
    ) {
        val url = buildUrl(path)
        val mediaType = contentType.toMediaType()
        val requestBody = content.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .put(requestBody)
            .header("Authorization", credentials)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to upload file: ${response.code} ${response.message}")
            }
        }
    }

    /**
     * Upload a file from local filesystem.
     * @param path Remote path relative to server URL
     * @param localFile Local file to upload
     * @param contentType MIME type of the content
     * @throws IOException if upload fails
     */
    fun putFile(path: String, localFile: File, contentType: String = "application/octet-stream") {
        if (!localFile.exists()) {
            throw IOException("Local file does not exist: ${localFile.absolutePath}")
        }
        putFile(path, localFile.readBytes(), contentType)
    }

    /**
     * Download a file from the WebDAV server.
     * @param path Remote path relative to server URL
     * @return File content as ByteArray
     * @throws IOException if download fails
     */
    fun getFile(path: String): ByteArray {
        val url = buildUrl(path)
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", credentials)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to download file: ${response.code} ${response.message}")
            }
            return response.body?.bytes() ?: throw IOException("Empty response body")
        }
    }

    /**
     * Download a file and save it to local filesystem.
     * @param path Remote path relative to server URL
     * @param localFile Local file to save to
     * @throws IOException if download or save fails
     */
    fun getFile(path: String, localFile: File) {
        val content = getFile(path)
        localFile.parentFile?.mkdirs()
        localFile.writeBytes(content)
    }

    /**
     * Get file as InputStream for streaming large files.
     * Returns a StreamResponse that wraps both the InputStream and underlying HTTP Response.
     * IMPORTANT: Caller MUST close the StreamResponse (use .use {} block) to prevent resource leaks.
     *
     * Example usage:
     * ```
     * webdavClient.getFileStream(path).use { streamResponse ->
     *     streamResponse.inputStream.copyTo(outputStream)
     * }
     * ```
     *
     * @param path Remote path relative to server URL
     * @return StreamResponse containing InputStream and managing underlying HTTP connection
     * @throws IOException if download fails
     */
    fun getFileStream(path: String): StreamResponse {
        val url = buildUrl(path)
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", credentials)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Failed to download file: ${response.code} ${response.message}")
        }

        val inputStream = response.body?.byteStream()
            ?: run {
                response.close()
                throw IOException("Empty response body")
            }

        return StreamResponse(response, inputStream)
    }

    /**
     * Delete a resource from the WebDAV server.
     * @param path Resource path relative to server URL
     * @throws IOException if deletion fails
     */
    fun delete(path: String) {
        val url = buildUrl(path)
        val request = Request.Builder()
            .url(url)
            .delete()
            .header("Authorization", credentials)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != HttpURLConnection.HTTP_NOT_FOUND) {
                // 404 means already deleted, which is fine
                throw IOException("Failed to delete resource: ${response.code} ${response.message}")
            }
        }
    }

    /**
     * Get last modified timestamp of a resource using PROPFIND.
     * @param path Resource path relative to server URL
     * @return Last modified timestamp in ISO 8601 format, or null if not available
     * @throws IOException if PROPFIND fails
     */
    fun getLastModified(path: String): String? {
        val url = buildUrl(path)

        // WebDAV PROPFIND request body for last-modified
        val propfindXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:">
                <D:prop>
                    <D:getlastmodified/>
                </D:prop>
            </D:propfind>
        """.trimIndent()

        val requestBody = propfindXml.toRequestBody("application/xml".toMediaType())

        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", requestBody)
            .header("Authorization", credentials)
            .header("Depth", "0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return null
            }

            val responseBody = response.body?.string() ?: return null

            // Parse XML response using XmlPullParser to properly handle namespaces and CDATA
            return parseLastModifiedFromXml(responseBody)
        }
    }

    /**
     * List resources in a collection using PROPFIND.
     * @param path Collection path relative to server URL
     * @return List of resource names in the collection
     * @throws IOException if PROPFIND fails
     */
    fun listCollection(path: String): List<String> {
        val url = buildUrl(path)

        // WebDAV PROPFIND request body for directory listing
        val propfindXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:">
                <D:allprop/>
            </D:propfind>
        """.trimIndent()

        val requestBody = propfindXml.toRequestBody("application/xml".toMediaType())

        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", requestBody)
            .header("Authorization", credentials)
            .header("Depth", "1")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to list collection: ${response.code} ${response.message}")
            }

            val responseBody = response.body?.string() ?: return emptyList()
            val allHrefs = parseHrefsFromXml(responseBody)

            return allHrefs
                .filter { it != path && !it.endsWith("/$path") }
                .map { href -> href.trimEnd('/').substringAfterLast('/') }
                .filter { isValidUuid(it) }
                .toList()
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
    fun listCollectionWithMetadata(path: String): List<RemoteEntry> {
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

        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", requestBody)
            .header("Authorization", credentials)
            .header("Depth", "1")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == HttpURLConnection.HTTP_NOT_FOUND) return emptyList()
            if (!response.isSuccessful) {
                throw IOException("Failed to list collection: ${response.code} ${response.message}")
            }

            val responseBody = response.body?.string() ?: return emptyList()
            return parseEntriesFromXml(responseBody)
                .filter { (href, _) -> href != path && !href.endsWith("/$path") }
                .mapNotNull { (href, lastModified) ->
                    val name = href.trimEnd('/').substringAfterLast('/')
                    if (isValidUuid(name)) RemoteEntry(name, lastModified) else null
                }
        }
    }

    /**
     * Ensure parent directories exist, creating them if necessary.
     * @param path File path (will create parent directories)
     * @throws IOException if directory creation fails
     */
    fun ensureParentDirectories(path: String) {
        val segments = path.trimStart('/').split('/')
        if (segments.size <= 1) return // No parent directories

        var currentPath = ""
        for (i in 0 until segments.size - 1) {
            currentPath += "/" + segments[i]
            if (!exists(currentPath)) {
                createCollection(currentPath)
            }
        }
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
     * Parse last modified timestamp from WebDAV XML response.
     * Properly handles namespaces, CDATA, and whitespace.
     * @param xml XML response from PROPFIND
     * @return Last modified timestamp, or null if not found
     */
    private fun parseLastModifiedFromXml(xml: String): String? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    // Check for getlastmodified tag (case-insensitive, namespace-aware)
                    val localName = parser.name.lowercase()
                    if (localName == "getlastmodified") {
                        // Get text content, handling CDATA properly
                        if (parser.next() == XmlPullParser.TEXT) {
                            return parser.text.trim()
                        }
                    }
                }
                eventType = parser.next()
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse XML for last modified: ${e.message}")
            null
        }
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
                if (eventType == XmlPullParser.START_TAG) {
                    // Check for href tag (case-insensitive, namespace-aware)
                    val localName = parser.name.lowercase()
                    if (localName == "href") {
                        // Get text content, handling CDATA properly
                        if (parser.next() == XmlPullParser.TEXT) {
                            hrefs.add(parser.text.trim())
                        }
                    }
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
                            inResponse = true
                            currentHref = null
                            currentLastModified = null
                        }

                        "href" -> if (inResponse && parser.next() == XmlPullParser.TEXT) {
                            currentHref = parser.text.trim()
                        }

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
            Log.e(TAG, "Failed to parse XML entries: ${e.message}")
            emptyList()
        }
    }

    private fun isValidUuid(name: String): Boolean =
        name.length == UUID_LENGTH &&
                name[UUID_DASH_POS_1] == '-' &&
                name[UUID_DASH_POS_2] == '-' &&
                name[UUID_DASH_POS_3] == '-' &&
                name[UUID_DASH_POS_4] == '-'

    companion object {
        private const val TAG = "WebDAVClient"

        // Timeout constants
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 60L
        private const val WRITE_TIMEOUT_SECONDS = 60L

        // UUID validation constants
        private const val UUID_LENGTH = 36
        private const val UUID_DASH_POS_1 = 8
        private const val UUID_DASH_POS_2 = 13
        private const val UUID_DASH_POS_3 = 18
        private const val UUID_DASH_POS_4 = 23

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
                null
            }
        }

        /**
         * Factory method to test connection and detect clock skew.
         * @return Pair of (connectionSuccessful, clockSkewMs) where clockSkewMs is
         *         the difference (deviceTime - serverTime) in milliseconds, or null
         *         if the server did not return a Date header.
         */
        fun testConnection(
            serverUrl: String,
            username: String,
            password: String
        ): Pair<Boolean, Long?> {
            return try {
                val client = WebDAVClient(serverUrl, username, password)
                val connected = client.testConnection()
                val clockSkewMs = if (connected) {
                    client.getServerTime()?.let { serverTime ->
                        System.currentTimeMillis() - serverTime
                    }
                } else {
                    null
                }
                Pair(connected, clockSkewMs)
            } catch (e: Exception) {
                Log.e(TAG, "Connection test failed: ${e.message}", e)
                Pair(false, null)
            }
        }

        /**
         * Factory method to get server time without full initialization.
         * @return Server time as epoch millis, or null if unavailable
         */
        fun getServerTime(serverUrl: String, username: String, password: String): Long? {
            return try {
                WebDAVClient(serverUrl, username, password).getServerTime()
            } catch (e: Exception) {
                null
            }
        }
    }
}
