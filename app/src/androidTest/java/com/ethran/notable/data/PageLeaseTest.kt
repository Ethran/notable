package com.ethran.notable.data

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.editor.PageLeases
import com.ethran.notable.testing.PageDataTestHarness
import com.ethran.notable.testing.TestNotebookSeeder
import com.ethran.notable.testing.rows
import com.ethran.notable.testing.waitForCondition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** A page leased by the views that show it stays in [PageDataManager] until all of them let go. */
@RunWith(AndroidJUnit4::class)
class PageLeaseTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var data: PageDataTestHarness
    private val pageDataManager get() = data.pageDataManager
    private val pageLeases by lazy { PageLeases(pageDataManager) }

    @Before
    fun setUp() {
        data = PageDataTestHarness(app)
    }

    @After
    fun tearDown() {
        data.close()
    }

    private suspend fun seedPages(count: Int): List<String> =
        TestNotebookSeeder.seedNotebook(data.db, pageCount = count, strokesPerPage = 5).pageIds

    /** The app is sent to the background: the cache drops every page it may. */
    @Suppress("DEPRECATION")
    private fun dropEverythingPossible() = app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)

    @Test(timeout = 30000)
    fun memoryPressure_dropsAPageOnlyOnceNoViewShowsIt() = runBlocking {
        val callbacks = pageDataManager.registerComponentCallbacks(app)
        try {
            val (shown, leftBehind) = seedPages(2)
            pageDataManager.requestPageLoadJoin(shown)
            pageDataManager.requestPageLoadJoin(leftBehind)

            // Two views show the page; one of them leaves it, and closes its lease twice.
            val leaving = pageLeases.acquire(shown)
            val staying = pageLeases.acquire(shown)
            leaving.close()
            leaving.close()

            dropEverythingPossible()
            assertTrue(pageDataManager.getStrokes(shown).isNotEmpty())
            assertTrue(pageDataManager.getStrokes(leftBehind).isEmpty())

            staying.close()
            dropEverythingPossible()
            assertTrue(pageDataManager.getStrokes(shown).isEmpty())
        } finally {
            app.unregisterComponentCallbacks(callbacks)
        }
    }

    @Test(timeout = 30000)
    fun loadOfAShownPage_isNotCancelledWhenLoadsOfOtherPagesAre() = runBlocking {
        val (pageId) = seedPages(1)
        val lease = pageLeases.acquire(pageId)
        val reading = CompletableDeferred<Unit>()
        val readAllowed = CompletableDeferred<Unit>()
        data.beforePageRead = {
            reading.complete(Unit)
            readAllowed.await()
        }

        val load = launch(Dispatchers.IO) { pageDataManager.requestPageLoadJoin(pageId) }
        reading.await()
        // Another view, or the editor turning the page, stops loads of pages it doesn't need.
        pageDataManager.cancelLoadsExcept(keep = setOf("another-page"))
        readAllowed.complete(Unit)
        load.join()

        assertEquals(data.storedStrokes(pageId).rows(), pageDataManager.getStrokes(pageId).rows())
        lease.close()
    }

    @Test(timeout = 30000)
    fun cancelledLoadFinishingLate_leavesTheNewerLoadOfItsPageAlone() = runBlocking {
        val (pageId) = seedPages(1)
        val firstReadStarted = CompletableDeferred<Unit>()
        val firstReadMayEnd = CompletableDeferred<Unit>()
        val secondReadStarted = CompletableDeferred<Unit>()
        val secondReadMayEnd = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        data.beforePageRead = {
            if (reads.incrementAndGet() == 1) {
                firstReadStarted.complete(Unit)
                // A database read runs to its end even once its load is cancelled, then the load
                // finds out it was cancelled.
                withContext(NonCancellable) { firstReadMayEnd.await() }
                currentCoroutineContext().ensureActive()
            } else {
                secondReadStarted.complete(Unit)
                secondReadMayEnd.await()
            }
        }

        // A neighbour is prefetched, the user jumps elsewhere, which cancels it, and comes back
        // before the cancelled read has finished, so the neighbour is prefetched again.
        pageDataManager.prefetch(listOf(pageId))
        firstReadStarted.await()
        pageDataManager.cancelLoadsExcept(keep = emptySet())
        pageDataManager.prefetch(listOf(pageId))
        secondReadStarted.await()

        firstReadMayEnd.complete(Unit)
        // The cancelled load is done once it has handled its cancellation; wait for it, or for the
        // newer load to be gone.
        waitForCondition(timeoutMs = 1000) { pageRow(pageId) == null }
        assertTrue("newer load is gone: ${pageRow(pageId)}", pageRow(pageId)?.loading == true)

        secondReadMayEnd.complete(Unit)
        assertTrue(waitForCondition(timeoutMs = 5000) { pageRow(pageId)?.loaded == true })
        assertEquals(data.storedStrokes(pageId).rows(), pageDataManager.getStrokes(pageId).rows())
    }

    private fun pageRow(pageId: String) =
        pageDataManager.memorySnapshot().pages.firstOrNull { it.pageId == pageId }
}
