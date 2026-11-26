package com.ethran.notable.data

import android.content.Intent
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.LatestValueWriter
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.kvJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastOpenedPageTest {
    @Test
    fun `settings persisted before the field decode with reopening off`() {
        val decoded = kvJson.decodeFromString(AppSettings.serializer(), """{"version":1}""")
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

    @Test
    fun `a deleted page is cleared on restore`() = runBlocking {
        var cleared = false
        val settings = AppSettings(version = 1, reopenLastNoteOnLaunch = true, lastOpenedPageId = "gone")

        val target = restoreLastOpenedPage(settings, getPage = { null }, clearSavedId = { cleared = true })

        assertNull(target)
        assertTrue(cleared)
    }

    @Test
    fun `an existing page is restored without clearing`() = runBlocking {
        var cleared = false
        val settings = AppSettings(version = 1, reopenLastNoteOnLaunch = true, lastOpenedPageId = "p")

        val target = restoreLastOpenedPage(
            settings, getPage = { Page(id = it, notebookId = "b") }, clearSavedId = { cleared = true },
        )

        assertEquals(LastOpenedPageTarget("p", "b"), target)
        assertFalse(cleared)
    }

    @Test
    fun `recording only happens while reopening is on`() {
        val off = AppSettings(version = 1)
        assertEquals(off, off.withLastOpenedPage("p"))
        assertEquals("p", off.copy(reopenLastNoteOnLaunch = true).withLastOpenedPage("p").lastOpenedPageId)
    }

    @Test
    fun `turning reopening off forgets the saved page`() {
        val on = AppSettings(version = 1, reopenLastNoteOnLaunch = true, lastOpenedPageId = "p")

        assertNull(on.withReopenLastNote(false).lastOpenedPageId)
        assertFalse(on.withReopenLastNote(false).reopenLastNoteOnLaunch)
        assertEquals("p", on.withReopenLastNote(true).lastOpenedPageId)
    }

    @Test
    fun `disabled with a stale id does no lookup and no clear`() = runBlocking {
        var lookedUp = false
        var cleared = false
        val settings = AppSettings(version = 1, reopenLastNoteOnLaunch = false, lastOpenedPageId = "stale")

        val target = restoreLastOpenedPage(
            settings, getPage = { lookedUp = true; null }, clearSavedId = { cleared = true },
        )

        assertNull(target)
        assertFalse(lookedUp)
        assertFalse(cleared)
    }

    @Test
    fun `only a fresh launch without a link reopens`() {
        assertTrue(shouldReopenOnLaunch(hasSavedState = false, carriesLink = false))
        assertFalse(shouldReopenOnLaunch(hasSavedState = true, carriesLink = false))
        assertFalse(shouldReopenOnLaunch(hasSavedState = false, carriesLink = true))
    }

    @Test
    fun `a link replayed from recents is not a link to open`() {
        val history = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
        assertTrue(carriesLinkToOpen(Intent.ACTION_VIEW, "notable://page/1", 0))
        assertFalse(carriesLinkToOpen(Intent.ACTION_VIEW, "notable://page/1", history))
        assertFalse(carriesLinkToOpen(Intent.ACTION_VIEW, null, 0))
        assertFalse(carriesLinkToOpen(Intent.ACTION_MAIN, "notable://page/1", 0))
    }

    @Test
    fun `recording the page already saved writes nothing`() = runBlocking {
        val before = GlobalAppSettings.current
        try {
            val saved = AppSettings(version = 1, reopenLastNoteOnLaunch = true, lastOpenedPageId = "p")
            GlobalAppSettings.update { saved }
            val writes = mutableListOf<AppSettings>()
            val writer = LatestValueWriter({ GlobalAppSettings.current }) { writes += it }
            writer.load(load = { saved }, publish = {})

            GlobalAppSettings.update { it.withLastOpenedPage("p") }
            writer.persist()
            assertTrue(writes.isEmpty())

            GlobalAppSettings.update { it.withLastOpenedPage("q") }
            writer.persist()
            assertEquals("q", writes.single().lastOpenedPageId)
        } finally {
            GlobalAppSettings.update { before }
        }
    }

    @Test
    fun `recording updates memory at once and the save survives the caller being cancelled`() = runBlocking {
        val before = GlobalAppSettings.current
        try {
            val saved = AppSettings(version = 1, reopenLastNoteOnLaunch = true)
            GlobalAppSettings.update { saved }
            val release = CompletableDeferred<Unit>()
            val writes = mutableListOf<AppSettings>()
            val writer = LatestValueWriter({ GlobalAppSettings.current }) { release.await(); writes += it }
            writer.load(load = { saved }, publish = {})
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            val caller = launch {
                GlobalAppSettings.update { it.withLastOpenedPage("p") }
                writer.persistIn(appScope)
                awaitCancellation()
            }
            while (GlobalAppSettings.current.lastOpenedPageId != "p") yield()
            caller.cancelAndJoin()
            release.complete(Unit)
            appScope.coroutineContext[Job]!!.children.forEach { it.join() }

            assertEquals("p", writes.single().lastOpenedPageId)
        } finally {
            GlobalAppSettings.update { before }
        }
    }
}
