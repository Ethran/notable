package com.ethran.notable.classes

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import com.ethran.notable.TAG
import com.ethran.notable.db.Image
import com.ethran.notable.db.Stroke
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.SoftReference


// Save bitmap, to avoid loading from disk every time.
data class CachedBackground(
    val bitmap: Bitmap?, val path: String, val pageNumber: Int, val scale: Float
)

// Cache manager companion object
object PageDataManager {
    private val strokes = LinkedHashMap<String, MutableList<Stroke>>()
    private var strokesById = LinkedHashMap<String, HashMap<String, Stroke>>()

    private val images = LinkedHashMap<String, MutableList<Image>>()
    private var imagesById = LinkedHashMap<String, HashMap<String, Image>>()

    private val cachedBackgrounds = LinkedHashMap<String, CachedBackground>()
    private val bitmapCache = LinkedHashMap<String, SoftReference<Bitmap>>()


    private var pageHigh = LinkedHashMap<String, Int>()

    @Volatile
    private var currentPage = ""
    private val loadingPages = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val lockLoadingPages = Mutex()

    private val accessLock = Any() // Lock for accessing Images, Strokes, Backgrounds & derived
    private var entrySizeMB = LinkedHashMap<String, Int>()
    var dataLoadingJob: Job? = null
    val dataLoadingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)


    suspend fun markPageLoading(pageId: String) {
        lockLoadingPages.withLock {
            if (!loadingPages.containsKey(pageId)) {
                Log.d(TAG + "Cache", "Marking page $pageId as loading")
                loadingPages[pageId] = CompletableDeferred()
            }
        }
    }

    suspend fun markPageLoaded(pageId: String) {
        return lockLoadingPages.withLock {
            loadingPages[pageId]?.complete(Unit)
            Log.d(TAG + "Cache", "marking done. Page $pageId, ${loadingPages[pageId].toString()}")
        }
    }

    suspend fun removeMarkPageLoaded(pageId: String) {
        lockLoadingPages.withLock {
            Log.d(TAG + "Cache", "Removing mark for page $pageId")
            loadingPages.remove(pageId)?.cancel()
        }
    }

    suspend fun awaitPageIfLoading(pageId: String) {
        if (isPageLoading(pageId)) {
            Log.d(TAG + "Cache", "Awaiting page $pageId")
            loadingPages[pageId]?.await()
            Log.d(TAG + "Cache", "waiting done. Page $pageId")
        } else {
            Log.d(TAG + "Cache", "Page $pageId is not loading, canceling unnecessary caching")
            dataLoadingJob?.cancel()
        }
    }

    suspend fun isPageLoading(pageId: String): Boolean {
        lockLoadingPages.withLock {
            return loadingPages.containsKey(pageId)
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


    fun getPageHeight(pageId: String): Int? {
        return pageHigh[pageId]
    }

    fun setPageHeight(pageId: String, height: Int) {
        pageHigh[pageId] = height
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

    fun cacheStrokes(pageId: String, strokes: List<Stroke>) {
        synchronized(accessLock) {
            if (!this.strokes.containsKey(pageId)) {
                this.strokes[pageId] = strokes.toMutableList()
            }
        }
    }

    fun cacheImages(pageId: String, images: List<Image>) {
        synchronized(accessLock) {
            if (!this.images.containsKey(pageId)) {
                this.images[pageId] = images.toMutableList()
            }
        }
    }


    fun setBackground(pageId: String, background: CachedBackground) {
        synchronized(accessLock) {
            if (!cachedBackgrounds.containsKey(pageId)) {
                cachedBackgrounds[pageId] = background
            }
        }
    }

    fun getBackground(pageId: String): CachedBackground {
        return synchronized(accessLock) {
            cachedBackgrounds[pageId] ?: CachedBackground(
                null, "", 0, 1.0f
            )
        }
    }


    fun isPageLoaded(pageId: String): Boolean {
        return synchronized(accessLock) {
            strokes.containsKey(pageId) && images.containsKey(pageId)
        }
    }

    /* cleaning and memory management */

    @Volatile
    private var currentCacheSizeMB = 0

    fun removePage(pageId: String) {
        synchronized(accessLock) {
            strokes.remove(pageId)
            images.remove(pageId)
            cachedBackgrounds.remove(pageId)
            pageHigh.remove(pageId)
            bitmapCache.remove(pageId)
            strokesById.remove(pageId)
            imagesById.remove(pageId)
            dataLoadingScope.launch {
                removeMarkPageLoaded(pageId)
            }
            currentCacheSizeMB -= entrySizeMB[pageId] ?: 0
            entrySizeMB[pageId] = 0
        }
    }

    fun clearAllPages() {
        Log.i(TAG + "Cache", "Clearing cache")
        synchronized(accessLock) {
            strokes.clear()
            images.clear()
            cachedBackgrounds.clear()
        }
    }

    fun ensureMemoryAvailable(requiredMb: Int): Boolean {
        return when {
            hasEnoughMemory(requiredMb) -> true
            else -> ensureMemoryCapacity(requiredMb)
        }
    }


    fun reduceCache(maxPages: Int) {
        synchronized(accessLock) {
            while (strokes.size > maxPages) {
                val oldestPage = strokes.iterator().next().key
                removePage(oldestPage)
            }
        }
    }

    // sign: if 1, add, if -1, remove, if 0 don't modify
    fun calculateMemoryUsage(pageId: String, sign: Int = 1): Int {
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
            cachedBackgrounds[pageId]?.let { background ->
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

    fun clearAllCache() {
        freeMemory(0)
    }

    fun hasEnoughMemory(requiredMb: Int): Boolean {
        val availableMem = Runtime.getRuntime().maxMemory() - Runtime.getRuntime().totalMemory()
        return availableMem > requiredMb * 1024 * 1024L
    }

    private fun ensureMemoryCapacity(requiredMb: Int): Boolean {
        val availableMem = ((Runtime.getRuntime().maxMemory() - Runtime.getRuntime().totalMemory())/(1024*1024)).toInt()
        if (availableMem > requiredMb)
            return true
        val toFree =  requiredMb - availableMem
        freeMemory(toFree)
        return hasEnoughMemory(requiredMb)
    }

    private fun freeMemory(cacheSizeLimit: Int): Boolean {
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
            override fun onTrimMemory(level: Int) {
                Log.d(TAG, "onTrimMemory: $level, currentCacheSizeMB: $currentCacheSizeMB")
                when (level) {
                    ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> clearAllCache()
                    ComponentCallbacks2.TRIM_MEMORY_MODERATE -> freeMemory(32)
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> freeMemory(64)
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> freeMemory(128)
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> freeMemory(256)
                    ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> freeMemory(32)
                    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> freeMemory(10)
                }
                Log.d(TAG, "after trim currentCacheSizeMB: $currentCacheSizeMB")
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