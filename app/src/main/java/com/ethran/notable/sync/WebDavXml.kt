package com.ethran.notable.sync

import io.shipbook.shipbooksdk.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.Date

/**
 * Parsing helpers for WebDAV PROPFIND XML responses.
 *
 * Split out of [WebDAVClient], which is otherwise concerned only with issuing HTTP requests and
 * mapping status codes. Date-header parsing stays in [WebDAVClient.parseHttpDate] (it is HTTP,
 * not XML, and has a unit test pinned to that location).
 */
internal object WebDavXml {
    private const val TAG = "WebDavXml"

    /**
     * Parse href values from a WebDAV XML response.
     * Properly handles namespaces, CDATA, and whitespace.
     */
    fun parseHrefs(xml: String): List<String> {
        return try {
            val parser = newParser(xml)
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
     * Parse `<response>` blocks from a PROPFIND XML response, returning each resource's href
     * paired with its last-modified date (null if absent).
     */
    fun parseEntries(xml: String): List<Pair<String, Date?>> {
        return try {
            val parser = newParser(xml)
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
                                WebDAVClient.parseHttpDate(parser.text.trim())?.let { Date(it) }
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

    fun isValidUuid(name: String): Boolean =
        name.length == 36 && name[8] == '-' && name[13] == '-' && name[18] == '-' && name[23] == '-'

    private fun newParser(xml: String): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newPullParser().apply { setInput(StringReader(xml)) }
    }
}
