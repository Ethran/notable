package com.ethran.notable.sync

import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.model.SheetSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class DownloadedRowsTest {

    private val sheet = SheetSize(1860, 2632)

    // ---- downloadedSheet ----

    // A download never turns a local sheet into an infinite canvas, whatever the server sends.
    @Test
    fun a_download_never_clears_a_local_sheet() {
        val remotes = listOf(
            null,
            SheetSize(1404, 1872),
            SheetSize(0, 0),
            SheetSize(-1, 2632),
            SheetSize(1860, MAX_SYNCED_SHEET_SIDE + 1),
        )
        for (remote in remotes) {
            assertTrue("remote $remote", downloadedSheet(remote, local = sheet) != null)
        }
    }

    @Test
    fun a_valid_remote_sheet_is_taken() {
        assertEquals(SheetSize(1404, 1872), downloadedSheet(SheetSize(1404, 1872), local = sheet))
        assertEquals(SheetSize(1404, 1872), downloadedSheet(SheetSize(1404, 1872), local = null))
        assertEquals(
            SheetSize(MAX_SYNCED_SHEET_SIDE, 1),
            downloadedSheet(SheetSize(MAX_SYNCED_SHEET_SIDE, 1), local = null),
        )
    }

    @Test
    fun a_missing_or_invalid_remote_sheet_keeps_the_local_value() {
        val invalid = listOf(
            null,
            SheetSize(0, 0),
            SheetSize(1860, 0),
            SheetSize(-1, 2632),
            SheetSize(MAX_SYNCED_SHEET_SIDE + 1, 2632),
        )
        for (remote in invalid) {
            assertEquals("remote $remote, local sheet", sheet, downloadedSheet(remote, local = sheet))
            assertNull("remote $remote, no local sheet", downloadedSheet(remote, local = null))
        }
    }

    // ---- downloadedPageRow ----

    private fun page(positionX: Int = 0, positionY: Int = 0, sheet: SheetSize? = null) = Page(
        id = "page-1",
        notebookId = "nb-1",
        positionX = positionX,
        positionY = positionY,
        sheet = sheet,
        createdAt = Date(1_700_000_000_000),
        updatedAt = Date(1_700_000_456_000),
    )

    @Test
    fun a_download_keeps_the_local_view_position() {
        val remote = page(positionY = 900).copy(updatedAt = Date(1_700_000_999_000), background = "dotted")
        val local = page(positionX = 120, positionY = 300)

        val row = downloadedPageRow(remote, local)

        assertEquals(120, row.positionX)
        assertEquals(300, row.positionY)
        assertEquals("dotted", row.background)
        assertEquals(Date(1_700_000_999_000), row.updatedAt)
    }

    @Test
    fun a_page_new_to_this_device_opens_at_the_top() {
        val row = downloadedPageRow(page(positionY = 900, sheet = sheet), local = null)

        assertEquals(0, row.positionX)
        assertEquals(0, row.positionY)
        assertEquals(sheet, row.sheet)
    }

    @Test
    fun a_page_from_an_app_without_sheets_keeps_the_local_sheet() {
        val row = downloadedPageRow(page(sheet = null), local = page(sheet = sheet))

        assertEquals(sheet, row.sheet)
    }

    // ---- downloadedNotebookRow ----

    private fun notebook(openPageId: String? = null, defaultSheet: SheetSize? = null) = Notebook(
        id = "nb-1",
        title = "Lecture notes",
        pageIds = listOf("page-1", "page-2"),
        openPageId = openPageId,
        defaultSheet = defaultSheet,
        createdAt = Date(1_700_000_000_000),
        updatedAt = Date(1_700_000_456_000),
    )

    @Test
    fun a_notebook_download_keeps_the_open_page_and_the_local_default_sheet() {
        val row = downloadedNotebookRow(notebook(), local = notebook(openPageId = "page-2", defaultSheet = sheet))

        assertEquals("page-2", row.openPageId)
        assertEquals(sheet, row.defaultSheet)
    }

    @Test
    fun a_notebook_new_to_this_device_takes_the_remote_default_sheet() {
        val row = downloadedNotebookRow(notebook(defaultSheet = sheet), local = null)

        assertNull(row.openPageId)
        assertEquals(sheet, row.defaultSheet)
    }

    // ---- structurallyDiverges ----

    @Test
    fun a_default_sheet_difference_alone_is_not_a_structural_conflict() {
        assertFalse(structurallyDiverges(notebook(defaultSheet = sheet), notebook(defaultSheet = null)))
    }

    @Test
    fun a_page_list_difference_is_a_structural_conflict() {
        val remote = notebook().copy(pageIds = listOf("page-2", "page-1"))

        assertTrue(structurallyDiverges(notebook(), remote))
    }
}
