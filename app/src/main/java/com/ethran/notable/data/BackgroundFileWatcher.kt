package com.ethran.notable.data

import android.os.FileObserver
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.io.IN_IGNORED
import com.ethran.notable.io.fileObserverEventNames
import com.ethran.notable.io.waitForFileAvailable
import com.ethran.notable.utils.chunked
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches the image/PDF files that pages use as backgrounds and reports which of them changed on
 * disk. Which pages show a file, and what a change means for them, is [PageDataManager]'s business;
 * the pages here only decide how long a file is watched.
 *
 * Registering a file is blocking disk I/O and an inotify registration, so it is kept out of the
 * page cache's lock:
 * - **This class owns [registryLock] and nothing else takes it.** Every method may be called with no
 *   other lock held, and none of them calls back into [PageDataManager].
 * - **Callers must not hold the page-cache lock** when calling [watch] / [unwatch]: both touch the
 *   filesystem.
 */
@Singleton
class BackgroundFileWatcher @Inject constructor(
    private val appEventBus: AppEventBus,
) {
    private val log = ShipBook.getLogger("BackgroundFileWatcher")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guards [observers] and [pagesByFile]. Never held across a call out of this class. */
    private val registryLock = Any()

    /** Absolute file path -> the running observer for it. */
    private val observers = mutableMapOf<String, FileObserver>()

    /** Absolute file path -> the pages it is watched for; the file is watched while there are any. */
    private val pagesByFile = mutableMapOf<String, MutableSet<String>>()

    private val fileChanges = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /**
     * Absolute paths of files that just changed. One save can report a file more than once; reports
     * that queue up while the collector is busy come as one batch.
     */
    val changedFiles: Flow<Set<String>> = fileChanges.chunked(10).map { it.toSet() }

    /**
     * Watch [filePath] for [pageId]. Registering the same file for a second page only adds the id —
     * one observer per file.
     *
     * Touches the filesystem: **must not** be called while holding the page-cache lock.
     */
    fun watch(pageId: String, filePath: String) {
        synchronized(registryLock) {
            pagesByFile.getOrPut(filePath) { mutableSetOf() }.add(pageId)
            if (observers.containsKey(filePath)) return // already observing this file

            val file = File(filePath)
            if (!file.exists() || !file.canRead()) {
                log.w("Cannot observe background file: $filePath does not exist or is not readable")
                return
            }
            val observer = newObserver(file, filePath)
            observer.startWatching()
            observers[filePath] = observer
        }
    }

    /**
     * After the file was replaced, installs an observer for the new file at [filePath] — but **only
     * if pages still use it**, and without touching the page set.
     *
     * Goes by [pagesByFile], not by the page that first registered the file: that page may have
     * been evicted meanwhile while another page still shows the file, and every page may have been
     * unwatched during [waitForFileAvailable].
     */
    private fun rearm(filePath: String) {
        synchronized(registryLock) {
            if (pagesByFile[filePath].isNullOrEmpty()) return // all real users gone -> stay unwatched
            if (observers.containsKey(filePath)) return       // someone already re-armed it
            val file = File(filePath)
            if (!file.exists() || !file.canRead()) {
                log.w("Cannot re-observe background file: $filePath does not exist or is not readable")
                return
            }
            val observer = newObserver(file, filePath)
            observer.startWatching()
            observers[filePath] = observer
        }
    }

    /**
     * Queue [unwatch] to run off the calling thread.
     *
     * Unlike [watch] / [unwatch] this **is** safe to call while holding another lock: it only
     * dispatches. Page eviction happens under the cache lock and must not stop an inotify watch
     * inline; dropping the watch is pure cleanup, ordered against nothing, so deferring it changes
     * nothing observable.
     */
    fun unwatchAsync(pageId: String) {
        scope.launch { unwatch(pageId) }
    }

    /**
     * Stop watching files for [pageId], shutting down any observer left with no pages. Touches the
     * filesystem — see [unwatchAsync] if a lock is held.
     */
    fun unwatch(pageId: String) {
        synchronized(registryLock) {
            val iterator = pagesByFile.entries.iterator()
            while (iterator.hasNext()) {
                val (filePath, pageIds) = iterator.next()
                if (pageIds.remove(pageId) && pageIds.isEmpty()) {
                    observers.remove(filePath)?.stopWatching()
                    iterator.remove()
                }
            }
        }
    }

    private fun newObserver(file: File, filePath: String): FileObserver =
        object : FileObserver(file, WATCH_MASK) {
            override fun onEvent(event: Int, path: String?) {
                // FileObserver calls this on its own thread; do the work on our scope so nothing
                // blocks the inotify callback.
                scope.launch {
                    if (event == IN_IGNORED) return@launch
                    log.d("Background file changed: $filePath [event=${fileObserverEventNames(event)}]")

                    if (event == DELETE_SELF || event == MOVE_SELF) {
                        // The watch follows the inode, not the path. Editors that save by replacing
                        // the file (deleting or moving it away, then writing a new one) therefore
                        // need a fresh observer once the new file appears.
                        log.d("Background file deleted or moved away.")
                        synchronized(registryLock) { observers.remove(filePath)?.stopWatching() }
                        if (!waitForFileAvailable(filePath)) {
                            log.w("File changed, but does not exist: $filePath")
                            appEventBus.tryEmit(
                                AppEvent.ActionHint("Background does not exist", 3000)
                            )
                            return@launch
                        }
                        // Re-arm by file path, re-checking who still uses it -- never by a captured
                        // page id, which may have been evicted while this observer waited.
                        rearm(filePath)
                    }

                    fileChanges.emit(filePath)
                }
            }
        }

    private companion object {
        // Events on the watched file itself; CREATE, DELETE and MOVED_TO only come from directories.
        private const val WATCH_MASK = FileObserver.DELETE_SELF or
            FileObserver.CLOSE_WRITE or
            FileObserver.MOVE_SELF
    }
}
