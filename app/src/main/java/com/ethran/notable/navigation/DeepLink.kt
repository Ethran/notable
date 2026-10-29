package com.ethran.notable.navigation

import java.io.ByteArrayOutputStream

const val DEEP_LINK_SCHEME = "notable"

/**
 * A `notable://` address of something in the app. Opening one only navigates; the one thing that
 * can create content, importing a PDF that no notebook links yet, asks first.
 *
 * - `notable://page/{pageId}`
 * - `notable://book/{bookId}`
 * - `notable://pdf?path={absolutePath}[&page={N}]`, page N of the PDF, counted from 1
 *
 * Links are stored in other apps' notes, so these rules hold for every later version:
 * - A kind is a word of letters a–z, read case-insensitively; parameter names are case-sensitive.
 * - What a kind's path and parameters mean never changes. Something new comes as a new kind,
 *   which older versions report as needing a newer Notable, or as a new optional parameter, which
 *   they ignore.
 *
 * [parseDeepLink] reads what [toUri] writes. It is plain Kotlin so it can be unit-tested on the JVM.
 */
sealed interface DeepLink {
    data class Page(val pageId: String) : DeepLink
    data class Book(val bookId: String) : DeepLink

    /** The notebook that links the PDF at [path], at the notebook page showing PDF page [page]. */
    data class Pdf(val path: String, val page: Int? = null) : DeepLink

    fun toUri(): String = when (this) {
        is Page -> "$DEEP_LINK_SCHEME://page/${encode(pageId)}"
        is Book -> "$DEEP_LINK_SCHEME://book/${encode(bookId)}"
        is Pdf -> buildString {
            append("$DEEP_LINK_SCHEME://pdf?path=").append(encode(path, keep = "/"))
            if (page != null) append("&page=").append(page)
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
 * about what a person types by hand: unencoded spaces, `+` and `%` in a path are taken literally.
 */
fun parseDeepLink(link: String): DeepLink? {
    val prefix = "$DEEP_LINK_SCHEME://"
    if (!link.startsWith(prefix, ignoreCase = true)) return null
    val rest = link.substring(prefix.length).substringBefore('#')
    val target = rest.substringBefore('?').trimEnd('/')
    val query = parseQuery(rest.substringAfter('?', ""))

    val segments = target.split('/')
    val kind = segments[0].lowercase()
    val id = segments.getOrNull(1)?.let(::decode)
    return when {
        kind == "page" && segments.size == 2 && !id.isNullOrBlank() -> DeepLink.Page(id)
        kind == "book" && segments.size == 2 && !id.isNullOrBlank() -> DeepLink.Book(id)
        kind == "pdf" && segments.size == 1 -> {
            val path = query["path"]?.takeIf { it.startsWith("/") } ?: return null
            DeepLink.Pdf(path = path, page = parsePage(query["page"]))
        }

        else -> null
    }
}

private val KNOWN_KINDS = setOf("page", "book", "pdf")

/**
 * Whether [link] is a `notable://` link of a kind this version doesn't know, presumably one made
 * by a newer Notable. Kinds are words of letters, so a malformed link doesn't count.
 */
fun isLinkOfNewerKind(link: String): Boolean {
    val prefix = "$DEEP_LINK_SCHEME://"
    if (!link.startsWith(prefix, ignoreCase = true)) return false
    val kind = link.substring(prefix.length).takeWhile { it != '/' && it != '?' && it != '#' }
        .lowercase()
    return kind.isNotEmpty() && kind.all { it in 'a'..'z' } && kind !in KNOWN_KINDS
}

/**
 * Query parameters by name; of a repeated one, the last. A `+` is a plus sign unless
 * [plusIsSpace]: links are typed by hand, while HTTP clients write a space as `+` in a query.
 */
internal fun parseQuery(query: String, plusIsSpace: Boolean = false): Map<String, String> = buildMap {
    for (pair in query.split('&')) {
        if (pair.isEmpty()) continue
        put(pair.substringBefore('='), decode(pair.substringAfter('=', ""), plusIsSpace))
    }
}

/** A page number counted from 1; anything else is no page. */
internal fun parsePage(value: String?): Int? = value?.toIntOrNull()?.takeIf { it >= 1 }

private const val UNRESERVED = "-._~"

private fun encode(value: String, keep: String = ""): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val c = (byte.toInt() and 0xFF).toChar()
        if (c.code < 0x80 && (c.isLetterOrDigit() || c in UNRESERVED || c in keep)) append(c)
        else append('%').append("%02X".format(byte.toInt() and 0xFF))
    }
}

/** Decodes `%XX` sequences as UTF-8; anything else, a `%` without two hex digits too, is kept. */
private fun decode(value: String, plusIsSpace: Boolean = false): String {
    val bytes = ByteArrayOutputStream()
    var i = 0
    while (i < value.length) {
        val hex = value.substring(i + 1, minOf(i + 3, value.length))
        if (value[i] == '%' && hex.length == 2 && hex.all { it in HEX_DIGITS }) {
            bytes.write(hex.toInt(16))
            i += 3
        } else {
            // Up to the next %, as a whole, so that a character outside the BMP keeps both halves.
            val end = value.indexOf('%', i + 1).takeIf { it >= 0 } ?: value.length
            val text = value.substring(i, end)
            bytes.write((if (plusIsSpace) text.replace('+', ' ') else text).encodeToByteArray())
            i = end
        }
    }
    return bytes.toByteArray().decodeToString()
}

private const val HEX_DIGITS = "0123456789abcdefABCDEF"
