package com.ethran.notable.editor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.MemorySnapshot
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.PageMemoryRow
import com.ethran.notable.testing.PageDataTestHarness
import com.ethran.notable.testing.TestNotebookSeeder
import com.ethran.notable.testing.createEditorViewModelForTest
import com.ethran.notable.testing.rows
import com.ethran.notable.testing.waitForCondition
import com.ethran.notable.ui.SnackState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Turning pages in the editor: the [PageView] shows the pages asked for one at a time and leases
 * only the page it shows; the [EditorViewModel] loads around the page shown last.
 */
@RunWith(AndroidJUnit4::class)
class PageChangeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var data: PageDataTestHarness
    private val pageDataManager get() = data.pageDataManager
    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        data = PageDataTestHarness(app)
    }

    @After
    fun tearDown() {
        viewScope.cancel()
        data.close()
    }

    private suspend fun seedPages(count: Int): List<String> =
        TestNotebookSeeder.seedNotebook(data.db, pageCount = count, strokesPerPage = 5).pageIds

    private fun createPageView(pageId: String, onPageShown: (String) -> Unit) = PageView(
        context = app,
        coroutineScope = viewScope,
        pageDataManager = pageDataManager,
        appRepository = data.appRepository,
        initialPageId = pageId,
        viewWidth = 400,
        viewHeight = 600,
        snackManager = SnackState(),
        onPageShown = onPageShown,
    )

    private fun isLoaded(pageId: String) =
        pageDataManager.memorySnapshot().pages.any { it.pageId == pageId && it.loaded }

    @Test(timeout = 30000)
    fun pagesAskedForWhileAPageChangeRuns_onlyTheLastIsShown_andOnlyItIsLeased() = runBlocking {
        val (first, slow, skipped, last, after) = seedPages(5)
        val shown = ConcurrentLinkedQueue<String>()
        val slowShown = CountDownLatch(1)
        val slowMayFinish = CountDownLatch(1)
        val view = createPageView(first) { pageId ->
            shown += pageId
            if (pageId == slow) {
                slowShown.countDown()
                slowMayFinish.await(10, TimeUnit.SECONDS)
            }
        }
        assertTrue(waitForCondition(5000) { first in shown })

        view.changePage(slow)
        assertTrue(slowShown.await(5, TimeUnit.SECONDS))
        view.changePage(skipped)
        view.changePage(last)
        slowMayFinish.countDown()

        assertTrue(waitForCondition(5000) { view.currentPageId == last && isLoaded(last) })
        // Page changes run in order, so one still pending would show its page before this one.
        view.changePage(after)
        assertTrue(waitForCondition(5000) { view.currentPageId == after && isLoaded(after) })
        assertEquals(listOf(first, slow, last, after), shown.toList())
        assertEquals(listOf(after), pageDataManager.memorySnapshot().leasedPageIds)
        assertEquals(data.storedStrokes(after).rows(), view.strokes.rows())
    }

    private enum class CloseStep { ReadingFirstPage, ReadingNextPage, LeasingNextPage, LoadingNextPage }

    @Test(timeout = 60000)
    fun viewClosedAtAnyStepOfShowingAPage_leavesNothingBehind() = runBlocking {
        for (step in CloseStep.entries) {
            val (first, next) = seedPages(2)
            val view = CompletableDeferred<PageView>()
            val atClose = CompletableDeferred<MemorySnapshot>()
            suspend fun closeAt(at: CloseStep, pageId: String) {
                val pageOfStep = if (at == CloseStep.ReadingFirstPage) first else next
                if (at != step || pageId != pageOfStep || atClose.isCompleted) return
                view.await().disposeOldPage()
                atClose.complete(pageDataManager.memorySnapshot())
            }
            data.beforePageRecordRead = { pageId ->
                closeAt(CloseStep.ReadingFirstPage, pageId)
                closeAt(CloseStep.ReadingNextPage, pageId)
            }
            data.beforePageRead = { pageId -> closeAt(CloseStep.LoadingNextPage, pageId) }
            view.complete(createPageView(first) { pageId ->
                runBlocking { closeAt(CloseStep.LeasingNextPage, pageId) }
            })
            if (step != CloseStep.ReadingFirstPage) {
                assertTrue(waitForCondition(5000) { isLoaded(first) })
                view.await().changePage(next)
            }

            val closed = withTimeout(5000) { atClose.await() }
            // Let the interrupted step run to its end.
            Thread.sleep(500)
            assertTrue(waitForCondition(5000) { pageDataManager.memorySnapshot().pages.none { it.loading } })

            val now = pageDataManager.memorySnapshot()
            fun MemorySnapshot.pagesWhere(has: (PageMemoryRow) -> Boolean) =
                pages.filter(has).map { it.pageId }.toSet()
            assertEquals("$step: leases", emptyList<String>(), now.leasedPageIds)
            assertEquals("$step: loads", emptySet<String>(), now.pagesWhere { it.loading })
            assertTrue(
                "$step: pages loaded after the view closed",
                closed.pagesWhere { it.loaded }.containsAll(now.pagesWhere { it.loaded }),
            )
            assertTrue(
                "$step: bitmaps cached after the view closed",
                closed.pagesWhere { it.bitmapBytes > 0 }.containsAll(now.pagesWhere { it.bitmapBytes > 0 }),
            )
            data.beforePageRecordRead = {}
            data.beforePageRead = {}
        }
    }

    @Test(timeout = 30000)
    fun pageShownBeforeTheLastHasLoaded_onlyTheNewestGetsItsNeighboursPrefetched() = runBlocking {
        val pages = seedPages(5)
        val pdm = mockk<PageDataManager>(relaxed = true)
        val firstJoinStarted = CompletableDeferred<Unit>()
        coEvery { pdm.awaitPageLoad(pages[1]) } coAnswers {
            firstJoinStarted.complete(Unit)
            awaitCancellation()
        }
        val prefetched = CompletableDeferred<List<String>>()
        coEvery { pdm.prefetch(any()) } coAnswers { prefetched.complete(firstArg()) }
        val viewModel = createEditorViewModelForTest(app, data.db, pageDataManager = pdm)

        viewModel.onPageShown(pages[1])
        withTimeout(5000) { firstJoinStarted.await() }
        viewModel.onPageShown(pages[3])

        assertEquals(listOf(pages[4], pages[2]), withTimeout(5000) { prefetched.await() })
        coVerifyOrder {
            pdm.cancelLoadsExcept(setOf(pages[2], pages[0], pages[1]))
            pdm.awaitPageLoad(pages[1])
            pdm.cancelLoadsExcept(setOf(pages[4], pages[2], pages[3]))
            pdm.awaitPageLoad(pages[3])
            pdm.trimToBudget()
            pdm.prefetch(listOf(pages[4], pages[2]))
        }
        coVerify(exactly = 1) { pdm.prefetch(any()) }
    }
}
