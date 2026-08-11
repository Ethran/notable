package com.ethran.notable.editor.state

import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.PageDataManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for [ViewportState]'s ownership of scroll and zoom.
 *
 * These cover a **deliberate behaviour change**, so unlike the Step 0 characterisation tests they
 * assert the new contract rather than pinning the old one. Previously `scroll` read through to
 * [PageDataManager] on every access, keyed by page id, which meant two views of the same page
 * shared a position. Each viewport now holds its own.
 *
 * [PageDataManager] remains the persistence layer — it is what reaches the database, what the
 * scroll indicator reads, and what restores position when a page is reopened — so writes must
 * still reach it.
 *
 * Instrumented because mockk is only on the androidTest classpath here.
 */
@RunWith(AndroidJUnit4::class)
class ViewportStateTest {

    private companion object {
        const val PAGE = "page-1"
        const val OTHER_PAGE = "page-2"
    }

    private lateinit var scrollStore: MutableMap<String, Offset>
    private lateinit var zoomStore: MutableMap<String, Float>
    private lateinit var dataManager: PageDataManager

    @Before
    fun setUp() {
        scrollStore = mutableMapOf()
        zoomStore = mutableMapOf()
        dataManager = mockk(relaxed = true)

        // Only the *getters* are stubbed. Offset is a Kotlin inline value class (@JvmInline over
        // a packed Long) with an internal constructor, so mockk hands secondArg() the raw Long and
        // the cast to Offset fails — and the value cannot be rebuilt from it either. Writes are
        // therefore asserted with verify(), not by capturing the written value.
        every { dataManager.getPageScroll(any()) } answers { scrollStore[firstArg()] ?: Offset.Zero }
        every { dataManager.getPageZoom(any()) } answers { zoomStore[firstArg()] ?: 1f }
    }

    // Dimensions are irrelevant to scroll ownership; zoom snapping is covered by ViewportZoomTest.
    private fun viewportOn(pageId: String) = ViewportState(dataManager, 1000, 800) { pageId }

    // ---------------------------------------------------------------- the point of the change

    /**
     * The headline behaviour. Two views of the *same* page must scroll independently — that is
     * precisely why one would open a document twice.
     */
    @Test
    fun twoViewportsOnTheSamePageScrollIndependently() {
        val left = viewportOn(PAGE)
        val right = viewportOn(PAGE)

        left.scroll = Offset(0f, 500f)

        assertEquals(Offset(0f, 500f), left.scroll)
        assertEquals("the second view must not follow the first", Offset.Zero, right.scroll)
    }

    /** Zoom is likewise per-view, not per-page. */
    @Test
    fun twoViewportsOnTheSamePageZoomIndependently() {
        val left = viewportOn(PAGE)
        val right = viewportOn(PAGE)

        left.zoomLevel.value = 2.5f

        assertEquals(2.5f, left.zoomLevel.value, 0.0001f)
        assertEquals(1f, right.zoomLevel.value, 0.0001f)
    }

    // ---------------------------------------------------------------- persistence still works

    @Test
    fun scrollWritesThroughToPersistence() {
        val viewport = viewportOn(PAGE)
        viewport.scroll = Offset(10f, 200f)

        // PageDataManager must stay current — setScrollInDb and the scroll indicator read it.
        verify(exactly = 1) { dataManager.setPageScroll(PAGE, Offset(10f, 200f)) }
    }

    @Test
    fun scrollIsSeededFromPersistenceAtConstruction() {
        scrollStore[PAGE] = Offset(0f, 320f)
        assertEquals(Offset(0f, 320f), viewportOn(PAGE).scroll)
    }

    /**
     * A page switch must adopt the stored position. Previously implicit, because every read went
     * through PageDataManager keyed by the current page; now the value is held locally, so it has
     * to be reloaded explicitly.
     */
    @Test
    fun reloadFromPersistenceAdoptsStoredScrollAndZoom() {
        scrollStore[OTHER_PAGE] = Offset(0f, 750f)
        zoomStore[OTHER_PAGE] = 1.5f

        val viewport = viewportOn(OTHER_PAGE)
        viewport.scroll = Offset(0f, 0f)
        viewport.zoomLevel.value = 3f

        viewport.reloadFromPersistence()

        assertEquals(Offset(0f, 750f), viewport.scroll)
        assertEquals(1.5f, viewport.zoomLevel.value, 0.0001f)
    }

    /**
     * `getPageScroll` deliberately avoids materialising a map entry on read, to dodge a
     * concurrent-modification crash when the scroll indicator reads during composition. Writing an
     * unchanged value would materialise it anyway, so identical writes are skipped.
     */
    @Test
    fun identicalScrollWriteIsNotPersistedAgain() {
        val viewport = viewportOn(PAGE)
        viewport.scroll = Offset(0f, 100f)

        viewport.scroll = Offset(0f, 100f)
        viewport.scroll = Offset(0f, 100f)

        verify(exactly = 1) { dataManager.setPageScroll(PAGE, Offset(0f, 100f)) }
    }

    @Test
    fun changedScrollWriteIsPersisted() {
        val viewport = viewportOn(PAGE)
        viewport.scroll = Offset(0f, 100f)
        viewport.scroll = Offset(0f, 200f)

        verify(exactly = 1) { dataManager.setPageScroll(PAGE, Offset(0f, 100f)) }
        verify(exactly = 1) { dataManager.setPageScroll(PAGE, Offset(0f, 200f)) }
    }

    /** Different pages keep separate persisted positions, as before. */
    @Test
    fun viewportsOnDifferentPagesPersistSeparately() {
        viewportOn(PAGE).scroll = Offset(0f, 100f)
        viewportOn(OTHER_PAGE).scroll = Offset(0f, 900f)

        verify(exactly = 1) { dataManager.setPageScroll(PAGE, Offset(0f, 100f)) }
        verify(exactly = 1) { dataManager.setPageScroll(OTHER_PAGE, Offset(0f, 900f)) }
    }
}
