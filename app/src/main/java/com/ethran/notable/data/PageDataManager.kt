package com.ethran.notable.data

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.FileObserver
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.getBackgroundType
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.data.model.BackgroundType.AutoPdf.getPage
import com.ethran.notable.data.model.BackgroundType.CoverImage
import com.ethran.notable.data.model.BackgroundType.ImageRepeating
import com.ethran.notable.editor.DrawCanvas
import com.ethran.notable.editor.utils.persistBitmapFull
import com.ethran.notable.editor.utils.persistBitmapThumbnail
import com.ethran.notable.io.IN_IGNORED
import com.ethran.notable.io.fileObserverEventNames
import com.ethran.notable.io.loadBackgroundBitmap
import com.ethran.notable.io.waitForFileAvailable
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.showHint
import com.ethran.notable.utils.chunked
import com.onyx.android.sdk.extension.isNotNull
import com.onyx.android.sdk.extension.isNull
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.lang.ref.SoftReference
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max


// Save bitmap, to avoid loading from disk every time.
data class CachedBackground(val path: String, val pageNumber: Int, val scale: Float) {
    val id: String = keyOf(path, pageNumber)

    var bitmap: Bitmap? = loadBackgroundBitmap(path, pageNumber, scale)
    fun matches(filePath: String, pageNum: Int, targetScale: Float): Boolean {
        return path == filePath && pageNumber == pageNum && scale >= targetScale // Consider valid if our scale is larger
    }

    companion object {
        fun keyOf(path: String, pageNumber: Int): String {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest("$path#$pageNumber".toByteArray(Charsets.UTF_8))
            return bytes.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}

// Cache manager companion object
object PageDataManager {
    val log = ShipBook.getLogger("PageDataManager")

    private val strokes = LinkedHashMap<String, MutableList<Stroke>>()
    private var strokesById = LinkedHashMap<String, HashMap<String, Stroke>>()

    private val images = LinkedHashMap<String, MutableList<Image>>()
    private var imagesById = LinkedHashMap<String, HashMap<String, Image>>()

    private val backgroundCache = LinkedHashMap<String, CachedBackground>()
    private val pageToBackgroundKey = HashMap<String, String>()
    private val bitmapCache = LinkedHashMap<String, SoftReference<Bitmap>>()

    // observe background file changes
    // fileObservers: filename to observer
    // fileToPages: filename to files with this file
    private val fileObservers = mutableMapOf<String, FileObserver>()
    private val fileToPages = mutableMapOf<String, MutableSet<String>>()
    val invalidateFileFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)

    // needs to be observable by UI, for scroll bars
    private var pageHigh = mutableStateMapOf<String, Int>()
    private var pageScroll = mutableStateMapOf<String, Offset>()

    // On change, we need to adjust stroke size.
    private var pageZoom = LinkedHashMap<String, Float>()

    @Volatile
    private var currentPage = ""

    private val accessLock = Any() // Lock for accessing Images, Strokes, Backgrounds & derived
    private var entrySizeMB = LinkedHashMap<String, Int>()

    private val jobLock = Mutex()
    private val dataLoadingJobs = mutableMapOf<String, Job>()
    val dataLoadingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        startFileInvalidationCollector()
    }

    /**
     * Suspends until the page is done loading (if it is being loaded).
     * Logs an error and returns if no job is present or job is cancelled.
     * Throws if no job is present or job is cancelled.
     */
    private suspend fun waitForPageLoad(pageId: String) {
        val job = jobLock.withLock { dataLoadingJobs[pageId] }
        if (job == null || job.isCancelled) {
            log.e("Illegal state: Job missing or cancelled for $pageId.")
            showHint("Illegal state: Job: ${job}.")
            return
        }
        job.join()
        if (!validatePageDataLoaded(pageId)) log.e("illegal state: after loading page, it is still not loaded correctly")
    }

    /**
     * Returns the existing loading Job for the page, or starts and returns a new one.
     * Locking is handled internally.
     */
    private suspend fun getOrStartLoadingJob(
        appRepository: AppRepository, pageId: String, bookId: String?
    ): Job {
        log.d("getOrStartLoadingJob($pageId)")
        //             PageDataManager.ensureMemoryAvailable(15)
        return jobLock.withLock {
            val existing = dataLoadingJobs[pageId]
            when {
                existing?.isActive == true -> {
                    log.d("Page($pageId) is already loading")
                    existing
                }

                existing?.isCompleted == true -> {
                    log.d("Page($pageId) already in memory, stroke number ${strokes[pageId]?.size}")
                    existing
                }

                existing == null || existing.isCancelled -> {
                    // Cancel any previous job, without current, next and previous page
                    if (bookId.isNotNull())
                        cancelUnnecessaryLoading(appRepository, pageId, bookId)
                    log.d("starting loading of the Page($pageId)")
                    if (existing.isNull() && areListInitialized(pageId)) log.e("Illegal state: Page($pageId) already in memory, but job is null.")
                    val newJob = dataLoadingScope.launch {
                        loadPageFromDb(appRepository, this, pageId)
                    }
                    dataLoadingJobs[pageId] = newJob
                    newJob
                }

                else -> error("Unexpected job state, for Page($pageId)")
            }
        }
    }

    /**
     * Ensures that the page is loaded; suspends until load is finished.
     */
    suspend fun requestPageLoadJoin(
        appRepository: AppRepository, pageId: String, bookId: String?
    ) {
        log.d("requestPageLoadJoin($pageId)")
        getOrStartLoadingJob(appRepository, pageId, bookId).join()
    }

    private fun cancelUnnecessaryLoading(
        appRepository: AppRepository,
        pageId: String,
        bookId: String
    ) {
        log.d("Canceling unnecessary loading of the Page($pageId)")
        val nextPageId =
            appRepository.getNextPageIdFromBookAndPage(pageId = pageId, notebookId = bookId)
        val prevPageId =
            appRepository.getPreviousPageIdFromBookAndPage(pageId = pageId, notebookId = bookId)

        cancelLoadingPages(
            ignoredPageIds =
                listOfNotNull(nextPageId, prevPageId, pageId).distinct()
        )
    }

    fun cacheNeighbors(appRepository: AppRepository, pageId: String, bookId: String) {

        // Only attempt to cache neighbors if we have memory to spare.
        if (!hasEnoughMemory(15)) return
        try {
            // Cache next page if not already cached
            val nextPageId =
                appRepository.getNextPageIdFromBookAndPage(pageId = pageId, notebookId = bookId)
            log.d("Caching next page $nextPageId")

            nextPageId?.let { nextPage ->
                requestPageLoad(appRepository, nextPage)
            }
            if (hasEnoughMemory(15)) {
                // Cache previous page if not already cached
                val prevPageId =
                    appRepository.getPreviousPageIdFromBookAndPage(
                        pageId = pageId,
                        notebookId = bookId
                    )
                log.d("Caching prev page $prevPageId")

                prevPageId?.let { prevPage ->
                    requestPageLoad(appRepository, prevPage)
                }
            }
        } catch (e: CancellationException) {
            log.i("Caching was cancelled: ${e.message}")
        } catch (e: Exception) {
            // All other unexpected exceptions
            log.e("Error caching neighbor pages", e)
            showHint("Error encountered while caching neighbors", duration = 5000)

        }

    }

    /**
     * Requests that the given page is loaded, but doesn't wait.
     * If already loading, is a no-op.
     */
    fun requestPageLoad(
        appRepository: AppRepository, pageId: String
    ) {
        dataLoadingScope.launch {
            getOrStartLoadingJob(appRepository, pageId, null)
        }
    }

    private fun preLoadBackground(appRepository: AppRepository, pageId: String) {
        val pageFromDb = appRepository.pageRepository.getById(pageId) ?: return
        val backgroundType = pageFromDb.getBackgroundType()
        val background = pageFromDb.background
        val pageNumber = when (backgroundType) {
            is BackgroundType.Pdf -> backgroundType.page
            is BackgroundType.AutoPdf -> backgroundType.getPage(
                appRepository, pageFromDb.notebookId, pageId
            ) ?: return

            BackgroundType.Native -> return
            BackgroundType.Image, ImageRepeating, CoverImage -> -1
        }
        val value = CachedBackground(background, pageNumber, 1f)
        log.i("Preloaded background: $value")
        val observeBg = appRepository.isObservable(pageFromDb.notebookId)
        setBackground(pageId, value, observeBg)
    }

    private suspend fun loadPageFromDb(
        appRepository: AppRepository, coroutineScope: CoroutineScope, pageId: String
    ) {
        try {
            log.d("Loading page $pageId")
//            sleep(5000)
            coroutineScope.launch {
                log.d("Preloading background for page $pageId")
                preLoadBackground(appRepository, pageId)
            }

            val pageWithStrokes = appRepository.pageRepository.getWithStrokeByIdSuspend(pageId)
            // What will happened if page isn't in repository?
            cacheStrokes(pageId, pageWithStrokes.strokes)
            val pageWithImages = appRepository.pageRepository.getWithImageById(pageId)
            cacheImages(pageId, pageWithImages.images)
            recomputeHeight(pageId)
            indexImages(coroutineScope, pageId)
            indexStrokes(coroutineScope, pageId)
            calculateMemoryUsage(pageId, 1)
        } catch (e: CancellationException) {
            log.w("Loading of page $pageId was cancelled.")
            if (!validatePageDataLoaded(pageId)) removePage(pageId)
            throw e  // rethrow cancellation
        } finally {
            log.d("Loaded page $pageId")
        }

    }


    /**
     * - Verifies loaded data presence.
     * - Tries to peek job state without suspending (tryLock).
     * - If inconsistent, logs a warning, clears the page, and returns false.
     *   (Call the overload below to also trigger reload.)
     */
    fun validatePageDataLoaded(pageId: String): Boolean {
        // 1) Snapshot job state non-suspending
        val jobSnapshot: Job? = if (jobLock.tryLock()) {
            try {
                dataLoadingJobs[pageId]
            } finally {
                jobLock.unlock()
            }
        } else {
            // Could not acquire lock without suspending; treat as unknown
            log.d("isPAgeLoaded: Couldn't obtain job status.")
            null
        }
        if (jobSnapshot?.isActive == true) {
            log.d("isPageLoaded: Still loading page($pageId).")
            return false
        }
        // if its canceled or null, we consider that data are not loaded
        val jobDone = jobSnapshot?.isCompleted ?: false

        // 2) Snapshot data state
        val dataLoaded = areListInitialized(pageId)

        // 3) Reconcile: if they disagree, warn and clear
        if (jobSnapshot.isNotNull() && dataLoaded != jobDone) {
            log.e("Inconsistent state for page($pageId): dataLoaded=$dataLoaded, jobDone=$jobDone, job=$jobSnapshot")
            showHint("Fixing inconsistent page state: $pageId")
            dataLoadingScope.launch {
                // Cancel/remove any job for this page
                jobLock.withLock {
                    dataLoadingJobs.remove(pageId)?.cancel()
                }
                // Drop partial data
                removePage(pageId)
            }
            return false
        }
        return dataLoaded
    }

    private fun areListInitialized(pageId: String): Boolean {
        return synchronized(accessLock) {
            log.d(
                "page($pageId)areListInitialized, ${strokes.containsKey(pageId)}, ${
                    images.containsKey(
                        pageId
                    )
                }, ${
                    entrySizeMB[pageId]
                }"
            )
            strokes.containsKey(pageId) && images.containsKey(pageId) && entrySizeMB.containsKey(
                pageId
            )
        }
    }

    val saveTopic = MutableSharedFlow<String>()
    fun collectAndPersistBitmapsBatch(
        context: Context, scope: CoroutineScope
    ) {
        scope.launch(Dispatchers.IO) {
            saveTopic.buffer(10).chunked(1000).collect { pageIdBatch ->
                    // 3. Take only the unique page IDs from the batch.
                    val uniquePageIds = pageIdBatch.distinct()

                    if (uniquePageIds.isEmpty()) return@collect

                    log.i("Persisting batch of bitmaps for pages: $uniquePageIds")

                    // 4. Process each unique ID.
                    for (pageId in uniquePageIds) {
                        val ref = bitmapCache[pageId]
                        val currentZoomLevel = pageZoom[pageId]
                        val currentScroll = pageScroll[pageId]
                        val bitmap = ref?.get()


                        if (bitmap == null || bitmap.isRecycled) {
                            log.e("Page $pageId: Bitmap is recycled/null — cannot persist it")
                            continue // Skip to the next ID in the batch
                        }

                        scope.launch(Dispatchers.IO) {
                            persistBitmapFull(
                                context,
                                bitmap,
                                pageId,
                                currentScroll,
                                currentZoomLevel
                            )
                            persistBitmapThumbnail(context, bitmap, pageId)
                        }
                    }
                }
        }
    }


    fun setPage(pageId: String) {
        currentPage = pageId
    }

    fun getCachedBitmap(pageId: String): Bitmap? {
        return bitmapCache[pageId]?.get()?.takeIf {
            !it.isRecycled && it.isMutable
        } // Returns null if GC reclaimed it
    }

    fun cacheBitmap(pageId: String, bitmap: Bitmap) {
        bitmapCache[pageId] = SoftReference(bitmap)
    }

    fun getPageHeight(pageId: String): Int? = pageHigh[pageId]
    fun setPageHeight(pageId: String, height: Int) {
        pageHigh[pageId] = height
    }

    fun recomputeHeight(pageId: String): Int {
        synchronized(accessLock) {
            if (strokes[pageId].isNullOrEmpty()) {
                return SCREEN_HEIGHT
            }
            val maxStrokeBottom = strokes[pageId]!!.maxOf { it.bottom }.plus(50)
            pageHigh[pageId] = max(maxStrokeBottom.toInt(), SCREEN_HEIGHT)
            return pageHigh[pageId]!!
        }
    }

    fun computeWidth(pageId: String): Int {
        synchronized(accessLock) {
            if (strokes[pageId].isNullOrEmpty()) {
                return SCREEN_WIDTH
            }
            val maxStrokeRight = strokes[pageId]!!.maxOf { it.right }.plus(50)
            return max(maxStrokeRight.toInt(), SCREEN_WIDTH)
        }
    }

    fun getPageScroll(pageId: String): Offset? = pageScroll[pageId]
    fun setPageScroll(pageId: String, scroll: Offset) {
        pageScroll[pageId] = scroll
    }

    fun getPageZoom(pageId: String): Float = pageZoom.getOrPut(pageId) { 1f }
    fun setPageZoom(pageId: String, zoom: Float) {
        pageZoom[pageId] = zoom
    }


    fun getStrokes(pageId: String): List<Stroke> = strokes[pageId] ?: emptyList()


    fun setStrokes(pageId: String, strokes: List<Stroke>) {
        this.strokes[pageId] = strokes.toMutableList()
    }

    fun getImages(pageId: String): List<Image> = images[pageId] ?: emptyList()

    fun setImages(pageId: String, images: List<Image>) {
        this.images[pageId] = images.toMutableList()
    }

    fun indexStrokes(scope: CoroutineScope, pageId: String) {
        scope.launch {
            strokesById[pageId] =
                hashMapOf(*strokes[pageId]!!.map { s -> s.id to s }.toTypedArray())
        }
    }

    fun indexImages(scope: CoroutineScope, pageId: String) {
        scope.launch {
            imagesById[pageId] =
                hashMapOf(*images[pageId]!!.map { img -> img.id to img }.toTypedArray())
        }
    }

    fun getStrokes(strokeIds: List<String>, pageId: String): List<Stroke?> {
        return strokeIds.map { s -> strokesById[pageId]?.get(s) }
    }

    fun getImage(imageId: String, pageId: String): Image? {
        return imagesById[pageId]?.get(imageId)
    }

    fun getImages(imageIds: List<String>, pageId: String): List<Image?> {
        return imageIds.map { i -> imagesById[pageId]?.get(i) }
    }


    // Assuming Rect uses 'left', 'top', 'right', 'bottom'
    fun getImagesInRectangle(inPageCoordinates: Rect, id: String): List<Image>? {
        synchronized(accessLock) {
            if (!validatePageDataLoaded(id)) return null
            val imageList = images[id] ?: return emptyList()
            return imageList.filter { image ->
                image.x < inPageCoordinates.right && (image.x + image.width) > inPageCoordinates.left && image.y < inPageCoordinates.bottom && (image.y + image.height) > inPageCoordinates.top
            }
        }
    }

    fun getStrokesInRectangle(inPageCoordinates: Rect, id: String): List<Stroke>? {
        synchronized(accessLock) {
            if (!validatePageDataLoaded(id)) return null
            val strokeList = strokes[id] ?: return emptyList()
            return strokeList.filter { stroke ->
                stroke.right > inPageCoordinates.left && stroke.left < inPageCoordinates.right && stroke.bottom > inPageCoordinates.top && stroke.top < inPageCoordinates.bottom
            }
        }
    }


    private fun cacheStrokes(pageId: String, strokes: List<Stroke>) {
        synchronized(accessLock) {
            if (!this.strokes.containsKey(pageId)) {
                this.strokes[pageId] = strokes.toMutableList()
            } else {
                log.d("Joining strokes drawn during page loading and existing strokes")
                this.strokes[pageId]?.addAll(strokes)
            }
        }
    }

    private fun cacheImages(pageId: String, images: List<Image>) {
        synchronized(accessLock) {
            if (!this.images.containsKey(pageId)) {
                this.images[pageId] = images.toMutableList()
            } else {
                log.d("Joining images drawn during page loading and existing images")
                this.images[pageId]?.addAll(images)
            }
        }
    }

    fun setBackground(pageId: String, background: CachedBackground, observe: Boolean) {
        synchronized(accessLock) {

            // Merge/upgrade cache: if we already have an entry for this background,
            // keep the one with higher scale (higher quality).
            val existing = backgroundCache[background.id]
            if (existing == null || background.scale > existing.scale) {
                backgroundCache[background.id] = background
                log.d("Cached background set: id=${background.id} scale=${background.scale}")
            } else {
                log.d("Cached background exists with equal/higher scale; reusing id=${existing.id} scale=${existing.scale}")
            }

            // Link this page to the background key
            pageToBackgroundKey[pageId] = background.id

            if (observe) observeBackgroundFile(pageId, background.path)
        }
    }

    /**
     * Retrieves the cached background for a specific page.
     *
     * If a background is associated with the page and is present in the cache, it returns the
     * [CachedBackground] object.
     *
     * If no background is found for the given `pageId`, it returns a default, empty
     * [CachedBackground] object to prevent null pointer exceptions downstream.
     *
     * @param pageId The unique identifier of the page for which to retrieve the background.
     * @return The [CachedBackground] associated with the page, or a default empty instance if not found.
     */
    fun getBackground(pageId: String): CachedBackground {
        return synchronized(accessLock) {
            val key = pageToBackgroundKey[pageId]
            val bg = if (key != null) backgroundCache[key] else null
            log.d("Background for page $pageId: $bg")
            bg ?: CachedBackground("", 0, 1.0f)
        }
    }

    /**
     * Start observing a background file for changes.
     * Registers the pageId to the file, and launches a FileObserver if not already present.
     */
    private fun observeBackgroundFile(pageId: String, filePath: String) {
        synchronized(fileObservers) {
            fileToPages.getOrPut(filePath) { mutableSetOf() }.add(pageId)
            if (fileObservers.containsKey(filePath)) return // Already observing this file

            val file = File(filePath)
            if (!file.exists() || !file.canRead()) {
                log.w("Cannot observe background file: $filePath does not exist or is not readable")
                return
            }
            val mask = (FileObserver.CREATE or
                    FileObserver.DELETE or
                    FileObserver.DELETE_SELF or
                    FileObserver.CLOSE_WRITE or
                    FileObserver.MOVED_TO or
                    FileObserver.MOVE_SELF)

            // Launch a FileObserver for this file
            val observer = object : FileObserver(file, mask) {
                override fun onEvent(event: Int, path: String?) {
                    dataLoadingScope.launch {
                        if(event == IN_IGNORED)
                            return@launch
                        val eventString = fileObserverEventNames(event)

                        log.d("Background file changed: $filePath [event=$eventString]")
                        if (event == DELETE || event == DELETE_SELF) {
                            log.d("Background file deleted.")
                            synchronized(fileObservers) {
                                fileObservers.remove(filePath)?.stopWatching()
                            }
                            if (!waitForFileAvailable(filePath)) {
                                log.w("File changed, but does not exist: $filePath")
                                SnackState.globalSnackFlow.emit(
                                    SnackConf(text = "Background does not exist", duration = 3000)
                                )
                                return@launch
                            } else
                                observeBackgroundFile(pageId, filePath)
                        }


                        invalidateFileFlow.emit(filePath)
                    }
                }
            }
            observer.startWatching()
            fileObservers[filePath] = observer
        }
    }


    /**
     * Starts the collector to process file invalidation events.
     * Uses chunked batching to process all events received in a 10ms window.
     */
    fun startFileInvalidationCollector() {
        dataLoadingScope.launch {
            invalidateFileFlow.chunked(10) // Batch events every 20ms
                .collect { filePathBatch ->
                    val uniqueFilePaths = filePathBatch.distinct()
                    if (uniqueFilePaths.isEmpty()) return@collect
                    log.i("Persisting batch of fileChanges: $uniqueFilePaths")
                    for (filePath in uniqueFilePaths) {
                        // Invalidate all pages that use this file
                        fileToPages[filePath]?.forEach { pid ->
                            invalidateBackground(pid)
                            if (pid == currentPage) {
                                DrawCanvas.Companion.forceUpdate.emit(null)
                                SnackState.globalSnackFlow.emit(
                                    SnackConf(text = "Background file changed", duration = 4000)
                                )
                            }
                        }
                    }
                }
        }
    }

    /**
     * Stop observing the background file for the given page.
     * Cleans up observers if no more pages are using the file.
     */
    private fun stopObservingBackground(pageId: String) {
        synchronized(fileObservers) {
            val iterator = fileToPages.entries.iterator()
            while (iterator.hasNext()) {
                val (filePath, pageIds) = iterator.next()
                if (pageIds.remove(pageId) && pageIds.isEmpty()) {
                    fileObservers.remove(filePath)?.stopWatching()
                    iterator.remove()
                }
            }
        }
    }

    private fun invalidateBackground(pageId: String) {
        synchronized(accessLock) {
            // Remove page->bg mapping and drop bg if no other page references it
            val key = pageToBackgroundKey.remove(pageId)
            if (key != null) {
                val stillUsed = pageToBackgroundKey.values.any { it == key }
                if (!stillUsed) {
                    backgroundCache.remove(key)
                    log.d("Invalidated background cache key=$key (no remaining pages)")
                } else {
                    log.d("Unlinked page $pageId from background key=$key (still used elsewhere)")
                }
            }
            bitmapCache.remove(pageId) // existing windowed bitmap cache per page stays per-page
            log.d("Invalidated background cache for page: $pageId")
        }
    }

    fun updateOnExit(targetPageId: String) {
        log.i("Page exit, is page loaded: ${validatePageDataLoaded(targetPageId)}")
        if (validatePageDataLoaded(targetPageId)) {
            recomputeHeight(targetPageId)
            calculateMemoryUsage(targetPageId, 0)
            // TODO: if we exited the book, we should clear the cache.
        }
    }

    /** --- cleaning and memory management ---- **/

    @Volatile
    private var currentCacheSizeMB = 0

    fun removePage(pageId: String) {
        log.d("Removing page $pageId")
        if (pageId == currentPage) log.w("Removing current page!")
        synchronized(accessLock) {
            strokes.remove(pageId)
            images.remove(pageId)
            pageHigh.remove(pageId)
            pageZoom.remove(pageId)
            pageScroll.remove(pageId)
            bitmapCache.remove(pageId)
            strokesById.remove(pageId)
            imagesById.remove(pageId)
            dataLoadingJobs.remove(pageId)
            currentCacheSizeMB -= entrySizeMB[pageId] ?: 0
            entrySizeMB.remove(pageId)

            // Unlink and possibly remove background
            val key = pageToBackgroundKey.remove(pageId)
            if (key != null && !pageToBackgroundKey.values.any { it == key }) {
                backgroundCache.remove(key)
            }
            stopObservingBackground(pageId)
        }
    }


    /**
     * Cancels and removes currently loading page, given by [pageId].
     */
    fun cancelLoadingPage(pageId: String) {
        dataLoadingScope.launch {
            log.d("Cancelling loading page: pageId=$pageId")
            jobLock.withLock {
                removePage(pageId)
            }
        }
    }


    /**
     * Cancels and removes all currently loading pages, optionally ignoring a specified list of pages -- [ignoredPageIds].
     */
    fun cancelLoadingPages(ignoredPageIds: List<String> = listOf()) {
        dataLoadingScope.launch {
            log.d("Cancelling loading pages, ignoring: $ignoredPageIds")
            val toCancel: List<String>
            jobLock.withLock {
                // Collect all pageIds with jobs that are not finished
                toCancel = dataLoadingJobs.filter { (_, job) ->
                    job.isActive
                }.map { (pageId, _) -> pageId }
                // Cancel and remove pages outside the lock
                for (pageId in toCancel) {
                    if (ignoredPageIds.contains(pageId)) continue
                    log.d("Cancelling page $pageId")
                    removePage(pageId)
                }
            }
        }
    }

    fun clearAllPages() {
        dataLoadingScope.launch {
            log.d("Clearing loaded pages")
            jobLock.withLock {
                // Collect all pageIds with jobs that are not finished
                dataLoadingJobs.forEach { id, _ ->
                    removePage(id)
                }
            }
        }
    }

    fun ensureMemoryAvailable(requiredMb: Int): Boolean {
        return when {
            hasEnoughMemory(requiredMb) -> true
            else -> ensureMemoryCapacity(requiredMb)
        }
    }

    fun getUsedMemory(): Int {
        return currentCacheSizeMB
    }

    fun reduceCache(maxPages: Int) {
        log.d("reduceCache($maxPages)")
        synchronized(accessLock) {
            while (strokes.size > maxPages) {
                val oldestPage = strokes.iterator().next().key
                removePage(oldestPage)
            }
        }
    }

    // sign: if 1, add, if -1, remove, if 0 don't modify
    private fun calculateMemoryUsage(pageId: String, sign: Int = 1): Int {
        return synchronized(accessLock) {
            var totalBytes = 0L

            // 1. Calculate strokes memory
            strokes[pageId]?.let { strokeList ->
                totalBytes += strokeList.sumOf { stroke ->
                    // Stroke object base size (~120 bytes)
                    var strokeMemory = 120L
                    // Points memory (32 bytes per StrokePoint)
                    strokeMemory += stroke.points.size * 32L
                    // Bounding box (4 floats = 16 bytes)
                    strokeMemory += 16L
                    strokeMemory
                }
            }

            // 2. Calculate images memory (average 100 bytes per image)
            totalBytes += images.size.times(100L)


            // 3. Calculate background memory
            backgroundCache[pageToBackgroundKey[pageId]]?.let { background ->
                background.bitmap?.let { bitmap ->
                    totalBytes += bitmap.allocationByteCount.toLong()
                }
                // Background metadata (approx 50 bytes)
                totalBytes += 50L
            }

            // 4. Calculate cached bitmap memory
            bitmapCache[pageId]?.get()?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    totalBytes += bitmap.allocationByteCount.toLong()
                }
            }

            // 5. Add map entry overhead (approx 40 bytes per entry)
            totalBytes += 40L * 4 // 4 maps (strokes, images, backgrounds, bitmaps)

            // Convert to MB and update cache
            val memoryUsedMB = (totalBytes / (1024 * 1024)).toInt()
            entrySizeMB[pageId] = memoryUsedMB
            currentCacheSizeMB += memoryUsedMB * sign
            memoryUsedMB
        }
    }

    private fun clearAllCache() {
        freeMemory(0)
    }

    fun hasEnoughMemory(requiredMb: Int): Boolean {
        val availableMem = Runtime.getRuntime().maxMemory() - Runtime.getRuntime().totalMemory()
        return availableMem > requiredMb * 1024 * 1024L
    }

    private fun ensureMemoryCapacity(requiredMb: Int): Boolean {
        val availableMem = ((Runtime.getRuntime().maxMemory() - Runtime.getRuntime()
            .totalMemory()) / (1024 * 1024)).toInt()
        if (availableMem > requiredMb) return true
        val toFree = requiredMb - availableMem
        freeMemory(toFree)
        return hasEnoughMemory(requiredMb)
    }

    private fun freeMemory(cacheSizeLimit: Int): Boolean {
        log.d("freeMemory($cacheSizeLimit)")
        synchronized(accessLock) {
            val pagesToRemove = strokes.keys.filter { it != currentPage }
            for (pageId in pagesToRemove) {
                if (currentCacheSizeMB <= cacheSizeLimit) break
                removePage(pageId)
            }
            currentCacheSizeMB = maxOf(0, currentCacheSizeMB)
            return currentCacheSizeMB <= cacheSizeLimit
        }
    }

    // Add to your PageDataManager:
    // In PageDataManager:
    fun registerComponentCallbacks(context: Context) {
        context.registerComponentCallbacks(object : ComponentCallbacks2 {
            @Suppress("DEPRECATION")
            override fun onTrimMemory(level: Int) {
                log.d("onTrimMemory: $level, currentCacheSizeMB: $currentCacheSizeMB")
                when (level) {
                    // for API <34
                    ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> clearAllCache()
                    ComponentCallbacks2.TRIM_MEMORY_MODERATE -> freeMemory(32)
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> freeMemory(64)
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> freeMemory(128)
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> freeMemory(256)
                    ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> freeMemory(32)
                    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> freeMemory(10)
                }
                log.d("after trim currentCacheSizeMB: $currentCacheSizeMB")
            }

            override fun onConfigurationChanged(newConfig: Configuration) {
                // No action needed for config changes
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                // Handle legacy low-memory callback (API < 14)
                clearAllCache()
            }
        })
    }
}