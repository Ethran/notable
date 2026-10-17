package com.ethran.notable.data

import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.db.Page
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LastOpenedPageTest {
    @Test
    fun `settings persisted before the field decode with reopening off`() {
        // Same configuration as KvProxy's Json instance.
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        val decoded = json.decodeFromString(AppSettings.serializer(), """{"version":1}""")
        assertFalse(decoded.reopenLastNoteOnLaunch)
        assertNull(decoded.lastOpenedPageId)
    }

    @Test
    fun `resolver restores a notebook page`() {
        val target = resolveLastOpenedPage(
            AppSettings(version = 1, reopenLastNoteOnLaunch = true, lastOpenedPageId = "page-1"),
            Page(id = "page-1", notebookId = "notebook-1"),
        )

        assertEquals(LastOpenedPageTarget("page-1", "notebook-1"), target)
    }

    @Test
    fun `resolver restores a quick page`() {
        val target = resolveLastOpenedPage(
            AppSettings(version = 1, reopenLastNoteOnLaunch = true, lastOpenedPageId = "quick-page"),
            Page(id = "quick-page", notebookId = null, parentFolderId = "folder-1"),
        )

        assertEquals(LastOpenedPageTarget("quick-page", null), target)
    }

    @Test
    fun `resolver skips disabled or deleted pages`() {
        assertNull(
            resolveLastOpenedPage(
                AppSettings(version = 1, lastOpenedPageId = "page-1"),
                Page(id = "page-1"),
            )
        )
        assertNull(
            resolveLastOpenedPage(
                AppSettings(version = 1, reopenLastNoteOnLaunch = true, lastOpenedPageId = "gone"),
                null,
            )
        )
    }
}
