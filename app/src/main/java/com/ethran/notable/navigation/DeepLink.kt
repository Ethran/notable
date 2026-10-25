package com.ethran.notable.navigation

import java.io.ByteArrayOutputStream

const val DEEP_LINK_SCHEME = "notable"

/**
 * A `notable://` address of something in the app. Opening one only navigates; the one thing that
 * can create content, importing a PDF that no notebook links yet, asks first.
 *
 * - `notable://page/{pageId}`
 * - `notable://book/{bookId}`
 * - `notable://pdf?path={absolutePath}[&page={N}][&folderId={id}]`, page N counted from 1
 *
 * [parseDeepLink] reads what [toUri] writes. It is plain Kotlin so it can be unit-tested on the JVM.
 */
sealed interface DeepLink {
    data class Page(val pageId: String) : DeepLink
    data class Book(val bookId: String) : DeepLink

    /**
     * The notebook that links the PDF at [path]. [folderId] is where to import it when no
     * notebook links it yet.
     */
    data class Pdf(val path: String, val page: Int? = null, val folderId: String? = null) : DeepLink

    fun toUri(): String = when (this) {
        is Page -> "$DEEP_LINK_SCHEME://page/${encode(pageId)}"
        is Book -> "$DEEP_LINK_SCHEME://book/${encode(bookId)}"
        is Pdf -> buildString {
            append("$DEEP_LINK_SCHEME://pdf?path=").append(encode(path, keep = "/"))
            if (page != null) append("&page=").append(page)
            if (folderId != null) append("&folderId=").append(encode(folderId))
        }
    }
}

/** How a link is written when copied, to paste into a notes app. */
enum class LinkFormat { Markdown, Org }

/** The link as [format] markup, shown as [title]. */
fun DeepLink.toMarkup(title: String, format: LinkFormat): String {
    // Brackets would end the title early in both syntaxes.
    val text = title.replace('[', '(').replace(']', ')')
    return when (format) {
        LinkFormat.Markdown -> "[$text](${toUri()})"
        LinkFormat.Org -> "[[${toUri()}][$text]]"
    }
}

/**
 * Reads a `notable://` link, or returns null when it isn't one this app understands. Lenient
 * about what a person types by hand: unencoded spaces and `+` in a path are taken literally.
 */
fun parseDeepLink(link: String): DeepLink? {
    val prefix = "$DEEP_LINK_SCHEME://"
    if (!link.startsWith(prefix, ignoreCase = true)) return null
    val rest = link.substring(prefix.length).substringBefore('#')
    val target = rest.substringBefore('?').trimEnd('/')
    val query = parseQuery(rest.substringAfter('?', "")) ?: return null

    val segments = target.split('/')
    val kind = segments[0].lowercase()
    val id = segments.getOrNull(1)?.let(::decode)
    return when {
        kind == "page" && segments.size == 2 && !id.isNullOrBlank() -> DeepLink.Page(id)
        kind == "book" && segments.size == 2 && !id.isNullOrBlank() -> DeepLink.Book(id)
        kind == "pdf" && segments.size == 1 -> {
            val path = query["path"]?.takeIf { it.startsWith("/") } ?: return null
            DeepLink.Pdf(
                path = path,
                page = query["page"]?.toIntOrNull()?.takeIf { it >= 1 },
                folderId = query["folderId"]?.takeIf { it.isNotBlank() },
            )
        }

        else -> null
    }
}

/** Query parameters by name; null when a value isn't valid percent-encoding. */
private fun parseQuery(query: String): Map<String, String>? = buildMap {
    for (pair in query.split('&')) {
        if (pair.isEmpty()) continue
        val value = decode(pair.substringAfter('=', "")) ?: return null
        put(pair.substringBefore('='), value)
    }
}

private const val UNRESERVED = "-._~"

private fun encode(value: String, keep: String = ""): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val c = (byte.toInt() and 0xFF).toChar()
        if (c.code < 0x80 && (c.isLetterOrDigit() || c in UNRESERVED || c in keep)) append(c)
        else append('%').append("%02X".format(byte.toInt() and 0xFF))
    }
}

/** Decodes `%XX` sequences as UTF-8; anything else is kept as written. */
private fun decode(value: String): String? {
    val bytes = ByteArrayOutputStream()
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%') {
            val byte = value.substring(i + 1, minOf(i + 3, value.length)).takeIf { it.length == 2 }
                ?.toIntOrNull(16) ?: return null
            bytes.write(byte)
            i += 3
        } else {
            bytes.write(c.toString().encodeToByteArray())
            i++
        }
    }
    return bytes.toByteArray().decodeToString()
}
