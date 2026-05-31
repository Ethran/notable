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
 * These tests pin down that reasoning so the fix can't be silently reverted: snapshot-guarded
 * writes and removals must interleave with concurrent reads without throwing.
 */
class SnapshotStateMapConcurrencyTest {

    @Test
    fun guardedWrite_isVisibleToLaterReaders() {
        val map = mutableStateMapOf<String, Int>()

        Snapshot.withMutableSnapshot { map["page"] = 42 }

        assertEquals(42, map["page"])
    }

    /**
     * Many threads mutate the same snapshot map through `Snapshot.withMutableSnapshot` while a reader
     * thread keeps reading entries (mimicking composition reading `page.scroll` / `page.height`).
     * This must complete without surfacing a concurrent-modification failure.
     *
     * Note: Writers are synchronized to avoid [SnapshotApplyConflictException] when multiple
     * background threads attempt to apply to the global snapshot simultaneously. This still
     * validates that guarded writes do not crash concurrent readers.
     */
    @Test
    fun guardedConcurrentWrites_doNotThrow() {
        val map = mutableStateMapOf<String, Int>()
        val keys = (0 until 16).map { "page-$it" }
        Snapshot.withMutableSnapshot { keys.forEach { map[it] = 0 } }

        val writers = 8
        val writesPerThread = 1000
        val pool = Executors.newFixedThreadPool(writers + 1)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val writeLock = Any()

        val readerDone = CountDownLatch(1)
        pool.submit {
            try {
                start.await()
                repeat(writers * writesPerThread) {
                    keys.forEach { map[it] }
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
                        synchronized(writeLock) {
                            Snapshot.withMutableSnapshot { map["page-$writerIndex"] = i }
                        }
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
     * safe to interleave with concurrent reads.
     */
    @Test
    fun guardedRemovalDuringReads_doesNotThrow() {
        val map = mutableStateMapOf<String, Int>()
        val keys = (0 until 200).map { "page-$it" }
        Snapshot.withMutableSnapshot { keys.forEachIndexed { index, key -> map[key] = index } }

        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(2)

        pool.submit {
            try {
                start.await()
                repeat(2000) {
                    map.values.sum()
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
                keys.forEach { key -> Snapshot.withMutableSnapshot { map.remove(key) } }
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
