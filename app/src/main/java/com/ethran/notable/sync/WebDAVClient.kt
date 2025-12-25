package com.ethran.notable.sync

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

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
            io.shipbook.shipbooksdk.Log.i("WebDAVClient", "Testing connection to: $serverUrl")
            val request = Request.Builder()
                .url(serverUrl)
                .head()
                .header("Authorization", credentials)
                .build()

            client.newCall(request).execute().use { response ->
                io.shipbook.shipbooksdk.Log.i("WebDAVClient", "Response code: ${response.code}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            io.shipbook.shipbooksdk.Log.e("WebDAVClient", "Connection test failed: ${e.message}", e)
            false
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
    fun putFile(path: String, content: ByteArray, contentType: String = "application/octet-stream") {
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
     * Caller is responsible for closing the InputStream.
     * @param path Remote path relative to server URL
     * @return InputStream of file content
     * @throws IOException if download fails
     */
    fun getFileStream(path: String): InputStream {
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
        return response.body?.byteStream() ?: throw IOException("Empty response body")
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

            // DEBUG: Log the raw response
            io.shipbook.shipbooksdk.Log.i("WebDAVClient", "PROPFIND response for $path (first $DEBUG_LOG_MAX_CHARS chars):")
            io.shipbook.shipbooksdk.Log.i("WebDAVClient", responseBody.take(DEBUG_LOG_MAX_CHARS))

            // Parse XML response using XmlPullParser to properly handle namespaces and CDATA
            val allHrefs = parseHrefsFromXml(responseBody)
            io.shipbook.shipbooksdk.Log.i("WebDAVClient", "Found ${allHrefs.size} hrefs: $allHrefs")

            val filtered = allHrefs.filter { it != path && !it.endsWith("/$path") }
            io.shipbook.shipbooksdk.Log.i("WebDAVClient", "After filtering (exclude $path): $filtered")

            return filtered.map { href ->
                    // Extract just the filename/dirname from the full path
                    href.trimEnd('/').substringAfterLast('/')
                }
                .filter { filename ->
                    // Only include valid UUIDs
                    filename.length == UUID_LENGTH &&
                    filename[UUID_DASH_POS_1] == '-' &&
                    filename[UUID_DASH_POS_2] == '-' &&
                    filename[UUID_DASH_POS_3] == '-' &&
                    filename[UUID_DASH_POS_4] == '-'
                }
                .toList()
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
            io.shipbook.shipbooksdk.Log.e("WebDAVClient", "Failed to parse XML for last modified: ${e.message}")
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
            io.shipbook.shipbooksdk.Log.e("WebDAVClient", "Failed to parse XML for hrefs: ${e.message}")
            emptyList()
        }
    }

    companion object {
        // Timeout constants
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 60L
        private const val WRITE_TIMEOUT_SECONDS = 60L

        // Debug logging
        private const val DEBUG_LOG_MAX_CHARS = 1500

        // UUID validation constants
        private const val UUID_LENGTH = 36
        private const val UUID_DASH_POS_1 = 8
        private const val UUID_DASH_POS_2 = 13
        private const val UUID_DASH_POS_3 = 18
        private const val UUID_DASH_POS_4 = 23

        /**
         * Factory method to test connection without full initialization.
         * @return true if connection successful
         */
        fun testConnection(serverUrl: String, username: String, password: String): Boolean {
            return try {
                WebDAVClient(serverUrl, username, password).testConnection()
            } catch (e: Exception) {
                false
            }
        }
    }
}
