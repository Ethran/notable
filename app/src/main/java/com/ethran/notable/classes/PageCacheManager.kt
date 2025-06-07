package com.ethran.notable.classes

import android.graphics.Bitmap
import android.util.Log
import com.ethran.notable.TAG
import com.ethran.notable.db.Image
import com.ethran.notable.db.Stroke


// Save bitmap, to avoid loading from disk every time.
data class CachedBackground(
    val bitmap: Bitmap?,
    val path: String,
    val pageNumber: Int,
    val scale: Float
)

// Cache manager companion object
object PageCacheManager {
    private val cachedStrokes = LinkedHashMap<String, MutableList<Stroke>>()
    private val cachedImages = LinkedHashMap<String, MutableList<Image>>()
    private val cachedBackgrounds = LinkedHashMap<String, CachedBackground>()

    private val cacheLock = Any()

    fun cacheStrokes(pageId: String, strokes: List<Stroke>) {
        synchronized(cacheLock) {
            if (!cachedStrokes.containsKey(pageId)) {
                cachedStrokes[pageId] = strokes.toMutableList()
            }
        }
    }

    fun cacheImages(pageId: String, images: List<Image>) {
        synchronized(cacheLock) {
            if (!cachedImages.containsKey(pageId)) {
                cachedImages[pageId] = images.toMutableList()
            }
        }
    }

    fun setCachedImagesToEmpty(pageId: String) {
        synchronized(cacheLock) {
            cachedImages[pageId] = emptyList<Image>().toMutableList()
        }
    }

    fun setCachedStrokesToEmpty(pageId: String) {
        synchronized(cacheLock) {
            cachedStrokes[pageId] = emptyList<Stroke>().toMutableList()
        }
    }
    fun cacheBackground(pageId: String, background: CachedBackground) {
        synchronized(cacheLock) {
            if (!cachedBackgrounds.containsKey(pageId)) {
                cachedBackgrounds[pageId] = background
            }
        }
    }

    /**
     * Moves strokes to cache (transfers ownership)
     */
    fun moveStrokesToCache(pageId: String, strokes: MutableList<Stroke>) {
        synchronized(cacheLock) {
            cachedStrokes[pageId] = strokes
        }
    }

    /**
     * Moves images to cache (transfers ownership)
     */
    fun moveImagesToCache(pageId: String, images: MutableList<Image>) {
        synchronized(cacheLock) {
            cachedImages[pageId] = images
        }
    }

//    fun getPageStrokes(pageId: String): List<Stroke>? {
//        return synchronized(cacheLock) { cachedStrokes[pageId]?.map { it.copy() } }
//    }
//
//    fun getPageImages(pageId: String): List<Image>? {
//        return synchronized(cacheLock) { cachedImages[pageId]?.map { it.copy() } }
//    }

    fun getPageBackground(pageId: String): CachedBackground? {
        //TODO: return a copy of the background, and implement
        // 'taker' that will transfer original object.
        return synchronized(cacheLock) { cachedBackgrounds[pageId] }
    }

    /**
     * Retrieves and removes strokes from cache (transfers ownership back)
     */
    fun takeStrokes(pageId: String): MutableList<Stroke>? {
        return synchronized(cacheLock) {
            cachedStrokes.remove(pageId)
        }
    }


    /**
     * Retrieves and removes images from cache (transfers ownership back)
     */
    fun takeImages(pageId: String): MutableList<Image>? {
        return synchronized(cacheLock) {
            cachedImages.remove(pageId)
        }
    }


    fun isPageCached(pageId: String): Boolean {
        return synchronized(cacheLock) {
            cachedStrokes.containsKey(pageId) && cachedImages.containsKey(pageId)
        }
    }

    private fun removePage(pageId: String) {
        synchronized(cacheLock) {
            cachedStrokes.remove(pageId)
            cachedImages.remove(pageId)
            cachedBackgrounds.remove(pageId)
        }
    }

    fun clearCache() {
        Log.i(TAG + "Cache", "Clearing cache")
        synchronized(cacheLock) {
            cachedStrokes.clear()
            cachedImages.clear()
            cachedBackgrounds.clear()
        }
    }

    fun ensureEnoughMemory(requiredMb: Int, hasEnoughMemory: (Int) -> Boolean) {
        if (hasEnoughMemory(requiredMb)) return

        synchronized(cacheLock) {
            val iterator = cachedStrokes.iterator()
            while (iterator.hasNext() && !hasEnoughMemory(requiredMb)) {
                val pageId = iterator.next().key
                iterator.remove() //remove page's strokes
                cachedImages.remove(pageId)
                cachedBackgrounds.remove(pageId)
            }
        }
    }

    fun reduceCache(maxPages: Int) {
        synchronized(cacheLock) {
            while (cachedStrokes.size > maxPages) {
                val oldestPage = cachedStrokes.iterator().next().key
                removePage(oldestPage)
            }
        }
    }

    /**
     * Estimates current memory usage in MB
     * Uses approximate averages for different object types
     */
    fun estimateMemoryUsage(): Long {
        return synchronized(cacheLock) {
            var totalBytes = 0L

            // Estimate strokes memory (average 100 bytes per stroke)
            cachedStrokes.values.forEach { strokes ->
                strokes.forEach { stroke ->
                    totalBytes += stroke.points.size * 16L // 2 floats per point (x,y)
                    totalBytes += 40 // Stroke metadata overhead
                }
            }

            // Estimate images memory (average 100 bytes per image)
            cachedImages.values.forEach { images ->
                totalBytes += images.size * 100L
            }

            // Estimate background memory (average 1MB per background)
            cachedBackgrounds.values.forEach { background ->
                totalBytes += background.bitmap?.byteCount ?: 0
            }
            // Add overhead for map storage (average 50 bytes per entry)
            val totalEntries = cachedStrokes.size + cachedImages.size + cachedBackgrounds.size
            totalBytes += totalEntries * 50L
            val memoryUsedMB = totalBytes / (1024 * 1024)
            Log.d(
                "Cache",
                "Estimated memory usage: $memoryUsedMB MB, cached ${cachedStrokes.size} pages."
            )
            memoryUsedMB
        }
    }

}