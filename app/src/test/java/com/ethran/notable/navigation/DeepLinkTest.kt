package com.ethran.notable.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkTest {

    private val links = listOf(
        DeepLink.Page("0b7c1f3e-2d4a-4c56-9e1f-7a8b9c0d1e2f"),
        DeepLink.Book("4f1e2d3c-5b6a-4789-8a9b-0c1d2e3f4a5b"),
        DeepLink.Pdf("/sdcard/Documents/thesis.pdf"),
        DeepLink.Pdf("/sdcard/Documents/thesis.pdf", page = 12),
        DeepLink.Pdf("/sdcard/Studia/Praca magisterska/rozdział 1 & 2 (v=3)+#.pdf", page = 1),
        DeepLink.Page("id with/odd?chars&=#%"),
    )

    @Test
    fun everyGeneratedLink_parsesBackToItself() {
        for (link in links) assertEquals(link.toUri(), link, parseDeepLink(link.toUri()))
    }

    @Test
    fun generatedLinks_areSingleTokens() {
        for (link in links) {
            val uri = link.toUri()
            assertTrue(uri, uri.none { it.isWhitespace() || it.code >= 0x80 })
            assertTrue(uri, uri.startsWith("notable://"))
        }
    }

    @Test
    fun handTypedPdfLink_isTakenLiterally() {
        assertEquals(
            DeepLink.Pdf("/sdcard/My Docs/C++ notes.pdf", page = 2),
            parseDeepLink("notable://pdf?path=/sdcard/My Docs/C++ notes.pdf&page=2")
        )
        for (path in listOf("/sdcard/100% done.pdf", "/a%2.pdf", "/a%zz.pdf", "/a%", "/sdcard/📚 ß/a.pdf"))
            assertEquals(path, DeepLink.Pdf(path), parseDeepLink("notable://pdf?path=$path"))
    }

    @Test
    fun parametersALaterVersionMayAdd_areIgnored() {
        assertEquals(
            DeepLink.Pdf("/sdcard/a.pdf", page = 2),
            parseDeepLink("notable://pdf?path=/sdcard/a.pdf&folderId=f-1&page=2&mode=read&flag&note=100%")
        )
        assertEquals(DeepLink.Book("b-1"), parseDeepLink("notable://book/b-1?page=3&x=%zz"))
        assertEquals(DeepLink.Page("p-1"), parseDeepLink("notable://Page/p-1/"))
    }

    @Test
    fun linksOfALaterKind_areToldApartFromBrokenOnes() {
        for (link in listOf("notable://folder/x", "notable://search?q=a", "Notable://Tag", "notable://x#y"))
            assertTrue(link, isLinkOfNewerKind(link))
        for (link in listOf(
            "notable://page/x", "notable://pdf?path=/a.pdf", "notable://", "notable://page-0b7c1f3e",
            "notable://2fa/x", "https://folder/x", "notable:folder/x",
        )) assertFalse(link, isLinkOfNewerKind(link))
    }

    @Test
    fun plusIsASpaceOnlyWhereHttpClientsWriteIt() {
        assertEquals("ADE changes.pdf", parseQuery("name=ADE+changes.pdf", plusIsSpace = true)["name"])
        assertEquals("C++ a.pdf", parseQuery("name=C%2B%2B+a.pdf", plusIsSpace = true)["name"])
        assertEquals("100% done", parseQuery("x=100%+done", plusIsSpace = true)["x"])
        // What `curl --url-query "name=ADE ż+x changes.pdf"` sends.
        assertEquals("ADE ż+x changes.pdf", parseQuery("name=ADE+%c5%bc%2bx+changes.pdf", plusIsSpace = true)["name"])
        assertEquals("C++ a.pdf", parseQuery("name=C++%20a.pdf")["name"])
    }

    @Test
    fun badPageNumbers_areIgnored() {
        for (page in listOf("0", "-1", "abc", "", "1.5")) {
            assertEquals(
                page,
                DeepLink.Pdf("/sdcard/a.pdf"),
                parseDeepLink("notable://pdf?path=/sdcard/a.pdf&page=$page")
            )
        }
    }

    @Test
    fun linksThisAppDoesNotKnow_areNull() {
        for (link in listOf(
            "",
            "notable://",
            "notable:page/x",
            "https://page/x",
            "notable://page",
            "notable://page/",
            "notable://page/a/b",
            "notable://book/%20",
            "notable://folder/x",
            "notable://pdf",
            "notable://pdf?path=relative/a.pdf",
            "notable://pdf/x?path=/a.pdf",
            "notable://page-0b7c1f3e",
        )) assertNull(link, parseDeepLink(link))
    }

    @Test
    fun copiedMarkup_keepsTheLinkWhole() {
        val titles = listOf("Notes, p. 3", "[draft] ideas]", "a](b) [[c]]")
        for (link in links) for (title in titles) {
            val uri = link.toUri()
            val markdown = link.toMarkup(title, LinkFormat.Markdown)
            assertTrue(markdown, markdown.endsWith("]($uri)"))
            assertEquals(markdown, 1, markdown.count { it == ']' })
            val org = link.toMarkup(title, LinkFormat.Org)
            assertTrue(org, org.startsWith("[[$uri]["))
            assertEquals(org, 3, org.count { it == ']' })
        }
    }
}
