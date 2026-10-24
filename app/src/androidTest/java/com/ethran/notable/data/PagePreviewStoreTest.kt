package com.ethran.notable.data

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.editor.PageLeases
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.utils.deletePagePreviews
import com.ethran.notable.editor.utils.getThumbnailFile
import com.ethran.notable.editor.utils.loadHQPagePreview
import com.ethran.notable.testing.PageDataTestHarness
import com.ethran.notable.testing.TestNotebookSeeder
import com.ethran.notable.testing.testStroke
import com.ethran.notable.testing.waitForCondition
import com.ethran.notable.ui.SnackState
import com.ethran.notable.utils.AppResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The previews and thumbnails of the pages an editor leaves: written even once the editor has
 * closed, fresh for as long as the page is not edited again, and removed with the page.
 */
@RunWith(AndroidJUnit4::class)
class PagePreviewStoreTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var data: PageDataTestHarness
    private val pageIds = mutableListOf<String>()

    @Before
    fun setUp() {
        data = PageDataTestHarness(app)
    }

    @After
    fun tearDown() {
        pageIds.forEach { deletePagePreviews(app.filesDir, it) }
        data.close()
    }

    // The preview of [pageId] at the top of the page, if it is on disk and not older than
    // [updatedAtMs].
    private fun preview(pageId: String, updatedAtMs: Long? = null) = loadHQPagePreview(
        app, pageId, scroll = Offset.Zero, zoom = 1f, pageUpdatedAtMs = updatedAtMs, requireExactMatch = true,
    )

    // Opens an editor view on [pageId], waits until the page is loaded, lets [edit] change it, and
    // closes the view and its scope, as closing the editor does.
    private fun showAndClose(pageId: String, edit: (PageView) -> Unit = {}) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val view = PageView(
            context = app,
            coroutineScope = scope,
            pageDataManager = data.pageDataManager,
            pageLeases = PageLeases(data.pageDataManager),
            appRepository = data.appRepository,
            initialPageId = pageId,
            viewWidth = 400,
            viewHeight = 600,
            snackManager = SnackState(),
        )
        assertTrue(waitForCondition(5000) {
            view.isTransformationAllowed && data.pageDataManager.validatePageDataLoaded(pageId)
        })
        edit(view)
        view.disposeOldPage()
        scope.cancel()
    }

    @Test(timeout = 60000)
    fun previewsOfPagesLeft_areWrittenAfterTheEditorCloses_stayFresh_andGoWithThePage() = runBlocking {
        PagePreviewStore(app, data.pageDataManager, data.appRepository)
        val (deletedRightAway, untouched, edited) =
            TestNotebookSeeder.seedNotebook(data.db, pageCount = 3, strokesPerPage = 5).pageIds
        pageIds += listOf(deletedRightAway, untouched, edited)

        showAndClose(deletedRightAway)
        deletePage(data.appRepository, deletedRightAway, app.filesDir)
        showAndClose(untouched)
        showAndClose(edited) { it.addStrokes(listOf(testStroke(edited, y = 300f))) }

        for (pageId in listOf(untouched, edited)) {
            assertTrue("preview and thumbnail of $pageId", waitForCondition(10000) {
                preview(pageId) != null && getThumbnailFile(app, pageId).exists()
            })
        }
        // Pages are written in the order they were left.
        assertFalse("thumbnail of page deleted right away", getThumbnailFile(app, deletedRightAway).exists())
        assertEquals("preview of page deleted right away", null, preview(deletedRightAway))

        // Nothing has saved the edit yet: the harness creates its PageSaver on first use. Saving it
        // stamps the page with the time of the edit, which the preview, captured after it, is newer
        // than.
        assertEquals(AppResult.Success(Unit), data.pageSaver.saveAll())
        val updatedAt = data.db.pageDao().getById(edited)!!.updatedAt.time
        assertNotNull("preview fresh after the save", preview(edited, updatedAt))

        deletePage(data.appRepository, edited, app.filesDir)
        assertFalse("thumbnail of deleted page", getThumbnailFile(app, edited).exists())
        assertEquals("preview of deleted page", null, preview(edited))
        assertNotNull("preview of the other page", preview(untouched))
    }
}
