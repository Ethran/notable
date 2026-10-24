package com.ethran.notable.editor

import com.ethran.notable.data.PageDataManager
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pages the editor's views show. A view takes a [PageLease] on each page it shows and closes it
 * when it stops showing the page. [PageDataManager] holds a page while any lease on it is open, so
 * views showing the same page never release it for each other.
 */
@Singleton
class PageLeases @Inject constructor(private val pageDataManager: PageDataManager) {
    // Open leases per page. Holding and releasing happen under this lock too, so they follow the
    // counts in order.
    private val counts = HashMap<String, Int>()

    /** Leases [pageId]; doesn't load the page. */
    fun acquire(pageId: String): PageLease {
        synchronized(counts) {
            val count = counts[pageId] ?: 0
            counts[pageId] = count + 1
            if (count == 0) pageDataManager.hold(pageId)
        }
        return PageLease(pageId, ::release)
    }

    private fun release(pageId: String) {
        synchronized(counts) {
            val count = counts[pageId] ?: return
            if (count > 1) counts[pageId] = count - 1
            else {
                counts.remove(pageId)
                pageDataManager.release(pageId)
            }
        }
    }
}

/** A lease on [pageId] taken with [PageLeases.acquire], released by [close]. Closing it again does nothing. */
class PageLease internal constructor(
    val pageId: String,
    private val release: (pageId: String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release(pageId)
    }
}
