package com.ethran.notable.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression tests for the "Unsupported concurrent change during composition" crash.
 *
 * [GlobalAppSettings.current] is a Compose snapshot state that is read all over the UI during
 * composition, while [GlobalAppSettings.update] is invoked from background coroutines. Updating the
 * snapshot state directly from a non-composition thread used to race the recomposer and crash.
 * [GlobalAppSettings.update] now commits inside a global mutable snapshot, which must make
 * concurrent background writes safe against concurrent reads.
 */
class GlobalAppSettingsConcurrencyTest {

    @Test
    fun update_isVisibleAfterCommit() {
        GlobalAppSettings.update(AppSettings(version = 1, neoTools = false))
        GlobalAppSettings.update(AppSettings(version = 1, neoTools = true))

        assertEquals(true, GlobalAppSettings.current.neoTools)
    }

    /**
     * Hammers [GlobalAppSettings.update] from many background threads while a separate thread keeps
     * reading [GlobalAppSettings.current], mimicking composition observing the value. Before the fix
     * this pattern could surface a concurrent-modification failure; with the snapshot-guarded write
     * it must complete without throwing.
     */
    @Test
    fun concurrentBackgroundUpdates_doNotThrow() {
        val writers = 8
        val updatesPerWriter = 500
        val pool = Executors.newFixedThreadPool(writers + 1)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)

        val readerDone = CountDownLatch(1)
        pool.submit {
            try {
                start.await()
                repeat(writers * updatesPerWriter) {
                    // Plain read, as composition would observe it.
                    GlobalAppSettings.current.version
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
                    repeat(updatesPerWriter) { i ->
                        GlobalAppSettings.update(
                            AppSettings(version = writerIndex * updatesPerWriter + i)
                        )
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

        assertNull("Concurrent update must not throw", failure.get())
        assertNotNull(GlobalAppSettings.current)
    }
}
