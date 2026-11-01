package com.ethran.notable.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReceivedPdfTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val pdf = "%PDF-1.5 a page".encodeToByteArray()

    @Test
    fun onlyPlainPdfNames_areAllowed() {
        for (name in listOf("ADE changes.pdf", "a.PDF", "zażółć (v2) + notes.pdf", "x".repeat(196) + ".pdf"))
            assertTrue(name, isAllowedName(name))
        for (name in listOf(
            "", ".pdf", ".x.pdf", "../x.pdf", "a/b.pdf", "/sdcard/a.pdf", "x.txt", "x.pdf.part",
            "a\u0000.pdf", "x".repeat(197) + ".pdf",
        )) assertFalse(name, isAllowedName(name))
    }

    @Test
    fun aGoodPdf_replacesTheTarget_andLeavesNoTemporaryFile() {
        val target = folder.newFile("a.pdf").apply { writeText("old") }
        val result = writeReceivedPdf(pdf.inputStream(), pdf.size.toLong(), target, { 1 })
        assertEquals(WriteResult.Written, result)
        assertTrue(pdf.contentEquals(target.readBytes()))
        assertEquals(listOf("a.pdf"), folder.root.list()!!.toList())
    }

    @Test
    fun aNewName_isCreated() {
        val target = File(folder.root, "sub/new.pdf")
        assertEquals(
            WriteResult.Written,
            writeReceivedPdf(pdf.inputStream(), pdf.size.toLong(), target, { 1 })
        )
        assertTrue(pdf.contentEquals(target.readBytes()))
    }

    @Test
    fun anyFailure_keepsTheOldFile_andLeavesNoTemporaryFile() {
        class Case(val expected: WriteResult, val body: ByteArray, val pages: Int = 1, val missingBytes: Int = 0)
        val cases = listOf(
            Case(WriteResult.NotPdf, "hello world".encodeToByteArray()),
            Case(WriteResult.NotPdf, "%PD".encodeToByteArray()),
            Case(WriteResult.TooLarge, pdf + ByteArray(100)),
            Case(WriteResult.Incomplete, pdf, missingBytes = 10),
            Case(WriteResult.Unreadable, pdf, pages = 0),
        )
        for (case in cases) {
            val target = File(folder.root, "a.pdf").apply { writeText("old") }
            val result = writeReceivedPdf(
                case.body.inputStream(), (case.body.size + case.missingBytes).toLong(), target,
                { case.pages },
                maxBytes = 50,
            )
            assertEquals(case.expected, result)
            assertEquals("old", target.readText())
            assertEquals(listOf("a.pdf"), folder.root.list()!!.toList())
        }
    }

    @Test
    fun aFailedRename_keepsWhatIsThere_andLeavesNoTemporaryFile() {
        // A non-empty directory can't be replaced by a file.
        val target = folder.newFolder("a.pdf")
        File(target, "kept").writeText("old")
        val result = writeReceivedPdf(pdf.inputStream(), pdf.size.toLong(), target, { 1 })
        assertTrue(result.toString(), result is WriteResult.Failed)
        assertEquals("old", File(target, "kept").readText())
        assertEquals(listOf("a.pdf"), folder.root.list()!!.toList())
    }

    @Test
    fun leftoverTemporaryFiles_areDeleted_andNothingElse() {
        folder.newFile(".a.pdf.1a2b3c4d.part")
        folder.newFile("a.pdf")
        folder.newFile("notes.part")
        deleteLeftoverParts(folder.root)
        assertEquals(setOf("a.pdf", "notes.part"), folder.root.list()!!.toSet())
    }
}
