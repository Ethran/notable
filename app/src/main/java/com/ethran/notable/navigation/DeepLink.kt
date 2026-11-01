package com.ethran.notable.navigation

import java.net.URLEncoder

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
 *   which older versions don't recognize, or as a new optional parameter, which they ignore.
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
            append("$DEEP_LINK_SCHEME://pdf?path=").append(encode(path, keepSlashes = true))
            if (page != null) append("&page=").append(page)
        }
    }
}

/** How a link is written when copied, to paste into a notes app. */
enum class LinkFormat { Plain, Markdown, Org }

/** The link as [format] markup, shown as [title]; a [LinkFormat.Plain] link is the bare address. */
fun DeepLink.toMarkup(title: String, format: LinkFormat): String {
    // Brackets would end the title early in both syntaxes.
    val text = title.replace('[', '(').replace(']', ')')
    return when (format) {
        LinkFormat.Plain -> toUri()
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

/** Query parameters by name; of a repeated one, the last. */
private fun parseQuery(query: String): Map<String, String> = buildMap {
    for (pair in query.split('&')) {
        if (pair.isEmpty()) continue
        put(pair.substringBefore('='), decode(pair.substringAfter('=', "")))
    }
}

/** A page number counted from 1; anything else is no page. */
internal fun parsePage(value: String?): Int? = value?.toIntOrNull()?.takeIf { it >= 1 }

private fun encode(value: String, keepSlashes: Boolean = false): String {
    val encoded = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    return if (keepSlashes) encoded.replace("%2F", "/") else encoded
}

/** Decodes runs of `%XX` as UTF-8; anything else, a `%` without two hex digits too, is kept. */
private fun decode(value: String): String =
    Regex("(?:%[0-9A-Fa-f]{2})+").replace(value) { run ->
        run.value.chunked(3).map { it.drop(1).toInt(16).toByte() }.toByteArray().decodeToString()
    }
