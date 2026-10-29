package com.ethran.notable.data

import android.os.FileObserver
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
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
 * Watches the image/PDF files that pages use as backgrounds and reports which pages need their
 * cached background dropped when one changes on disk.
 *
 * Registering a file is blocking disk I/O and an inotify registration, so it is kept out of the
 * page cache's lock:
 * - **This class owns [registryLock] and nothing else takes it.** Every method may be called with no
 *   other lock held, and none of them calls back into [PageDataManager].
 * - **Callers must not hold the page-cache lock** when calling [watch] / [unwatch]: both touch the
 *   filesystem.
 * - The class reports *page ids*, never file paths, so the mapping from file to pages — the part
 *   that needs the lock — never escapes.
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

    /** Absolute file path -> the pages whose background is that file. */
    private val pagesByFile = mutableMapOf<String, MutableSet<String>>()

    /** [announce] is false for a file the app replaced itself ([replace]), which it doesn't announce. */
    private data class FileChange(val path: String, val announce: Boolean)

    private val fileChanges = MutableSharedFlow<FileChange>(extraBufferCapacity = 64)

    /** Pages whose background file changed; [announce] if the change came from outside the app. */
    data class ChangedPages(val pageIds: List<String>, val announce: Boolean)

    /**
     * Batches of pages whose background file just changed, de-duplicated within a 10 ms window
     * (a single save often produces several inotify events). Collect this to drop the cached
     * bitmaps; the watcher itself holds no cache state and does not know what invalidation means.
     */
    val invalidatedPages: Flow<ChangedPages> =
        fileChanges.chunked(10).map { batch ->
            val paths = batch.map { it.path }.distinct()
            val pageIds = synchronized(registryLock) {
                paths.flatMap { pagesByFile[it].orEmpty() }.distinct()
            }
            ChangedPages(pageIds, announce = batch.any { it.announce })
        }

    /**
     * Start reporting changes to [filePath] against [pageId]. Registering the same file for a second
     * page only adds the id — one observer per file.
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
     * Stop reporting for [pageId], shutting down any observer left with no pages. Touches the
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

    /**
     * Runs [rename], which puts a new file at [filePath], and reports the change once.
     *
     * The old file's observer is stopped first, so the rename is not reported a second time through
     * inotify, and the new file's observer is started afterwards, also when [rename] throws, and
     * before the change is reported, as for a change seen by an observer.
     *
     * Touches the filesystem: **must not** be called while holding the page-cache lock.
     */
    fun <T> replace(filePath: String, rename: () -> T): T {
        synchronized(registryLock) { observers.remove(filePath)?.stopWatching() }
        val result = try {
            rename()
        } finally {
            rearm(filePath)
        }
        if (!fileChanges.tryEmit(FileChange(filePath, announce = false)))
            log.w("Change of $filePath not reported: too many pending")
        return result
    }

    /** Stops [observer] if it still watches [filePath]; false if it was stopped already. */
    private fun retire(filePath: String, observer: FileObserver): Boolean {
        synchronized(registryLock) {
            if (observers[filePath] !== observer) return false
            observers.remove(filePath)
            observer.stopWatching()
            return true
        }
    }

    private fun newObserver(file: File, filePath: String): FileObserver =
        object : FileObserver(file, WATCH_MASK) {
            override fun onEvent(event: Int, path: String?) {
                val observer = this
                // FileObserver calls this on its own thread; do the work on our scope so nothing
                // blocks the inotify callback.
                scope.launch {
                    log.d("Background file changed: $filePath [event=${fileObserverEventNames(event)}]")
                    if (event == CLOSE_WRITE) {
                        fileChanges.emit(FileChange(filePath, announce = true))
                        return@launch
                    }

                    // The watch follows the inode, not the path, so a file replaced by a new one
                    // needs a fresh observer once the new file appears. How a replacement shows up
                    // depends on the filesystem: DELETE_SELF or MOVE_SELF on app storage, only
                    // ATTRIB, or ATTRIB then IGNORED, on /sdcard (FUSE). The first of these
                    // events retires the observer; the ones after it find it retired.
                    if (!retire(filePath, observer)) return@launch
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
                    fileChanges.emit(FileChange(filePath, announce = true))
                }
            }
        }

    private companion object {
        // Events on the watched file itself; CREATE, DELETE and MOVED_TO only come from directories.
        // IGNORED is always delivered.
        private const val WATCH_MASK = FileObserver.DELETE_SELF or
            FileObserver.CLOSE_WRITE or
            FileObserver.MOVE_SELF or
            FileObserver.ATTRIB
    }
}
