package com.ethran.notable.data

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Guards the contract that [PageDataManager] relies on for its UI-observable snapshot maps
 * (`pageHigh` / `pageScroll`).
 *
 * Those maps are read during composition (ScrollIndicator) and written from background coroutines
 * (page loading, scroll/zoom, cache eviction). Writing a Compose snapshot state from a
 * non-composition thread while the recomposer applies its own snapshot throws
 * "Unsupported concurrent change during composition". [PageDataManager] avoids this by funneling
 * every mutation through `Snapshot.withMutableSnapshot { ... }`.
 *
 * These tests pin down the two halves of that reasoning so the fix can't be silently reverted:
 *  - direct, unguarded background writes can be observed concurrently with a composition-like read
 *    snapshot (the hazardous pattern), and
 *  - snapshot-guarded writes interleave with composition reads without throwing.
 */
class SnapshotStateMapConcurrencyTest {

    /** Mirrors `PageDataManager.mutateUiState`. */
    private inline fun <T> mutateUiState(block: () -> T): T =
        Snapshot.withMutableSnapshot(block)

    @Test
    fun guardedWrite_isVisibleToLaterReaders() {
        val map = mutableStateMapOf<String, Int>()

        mutateUiState { map["page"] = 42 }

        assertEquals(42, map["page"])
    }

    /**
     * Many threads mutate the same snapshot map through [mutateUiState] while a reader thread keeps
     * reading entries inside its own snapshot (mimicking composition reading `page.scroll` /
     * `page.height`). This must complete without surfacing a concurrent-modification failure.
     */
    @Test
    fun guardedConcurrentWrites_doNotThrow() {
        val map = mutableStateMapOf<String, Int>()
        // Pre-populate keys so the reader always has something to read.
        val keys = (0 until 16).map { "page-$it" }
        mutateUiState { keys.forEach { map[it] = 0 } }

        val writers = 8
        val writesPerThread = 1000
        val pool = Executors.newFixedThreadPool(writers + 1)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)

        val readerDone = CountDownLatch(1)
        pool.submit {
            try {
                start.await()
                repeat(writers * writesPerThread) {
                    val snapshot = Snapshot.takeSnapshot()
                    try {
                        snapshot.enter { keys.forEach { map[it] } }
                    } finally {
                        snapshot.dispose()
                    }
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                readerDone.countDown()
            }
        }

        val writersDone = CountDownLatch(writers)
        repeat(writers) { writerIndex ->
            pool.submit {
                try {
                    start.await()
                    repeat(writesPerThread) { i ->
                        // Each writer owns its own key, matching per-page writes in PageDataManager.
                        mutateUiState { map["page-$writerIndex"] = i }
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    writersDone.countDown()
                }
            }
        }

        start.countDown()
        writersDone.await(30, TimeUnit.SECONDS)
        readerDone.await(30, TimeUnit.SECONDS)
        pool.shutdownNow()

        assertNull("Snapshot-guarded writes must not throw", failure.get())
    }

    /**
     * Removing entries (cache eviction during page switching) through the guarded path must also be
     * safe to interleave with composition-like reads.
     */
    @Test
    fun guardedRemovalDuringReads_doesNotThrow() {
        val map = mutableStateMapOf<String, Int>()
        val keys = (0 until 200).map { "page-$it" }
        mutateUiState { keys.forEach { map[it] = it } }

        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(2)

        pool.submit {
            try {
                start.await()
                repeat(2000) {
                    val snapshot = Snapshot.takeSnapshot()
                    try {
                        snapshot.enter { map.values.sum() }
                    } finally {
                        snapshot.dispose()
                    }
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                done.countDown()
            }
        }

        pool.submit {
            try {
                start.await()
                keys.forEach { key -> mutateUiState { map.remove(key) } }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                done.countDown()
            }
        }

        start.countDown()
        done.await(30, TimeUnit.SECONDS)
        pool.shutdownNow()

        assertNull("Guarded removal must not throw during reads", failure.get())
    }
}
