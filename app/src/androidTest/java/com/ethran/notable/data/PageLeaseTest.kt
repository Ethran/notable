package com.ethran.notable.data

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.testing.PageDataTestHarness
import com.ethran.notable.testing.TestNotebookSeeder
import com.ethran.notable.testing.rows
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A page leased by the views that show it stays in [PageDataManager] until all of them let go. */
@RunWith(AndroidJUnit4::class)
class PageLeaseTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var data: PageDataTestHarness
    private val pageDataManager get() = data.pageDataManager

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
        pageDataManager.registerComponentCallbacks(app)
        val (shown, leftBehind) = seedPages(2)
        pageDataManager.requestPageLoadJoin(shown)
        pageDataManager.requestPageLoadJoin(leftBehind)

        // Two views show the page; one of them leaves it, and closes its lease twice.
        val leaving = pageDataManager.acquire(shown)
        val staying = pageDataManager.acquire(shown)
        leaving.close()
        leaving.close()

        dropEverythingPossible()
        assertTrue(pageDataManager.getStrokes(shown).isNotEmpty())
        assertTrue(pageDataManager.getStrokes(leftBehind).isEmpty())

        staying.close()
        dropEverythingPossible()
        assertTrue(pageDataManager.getStrokes(shown).isEmpty())
    }

    @Test(timeout = 30000)
    fun loadOfAShownPage_isNotCancelledWhenLoadsOfOtherPagesAre() = runBlocking {
        val (pageId) = seedPages(1)
        val lease = pageDataManager.acquire(pageId)
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
}
