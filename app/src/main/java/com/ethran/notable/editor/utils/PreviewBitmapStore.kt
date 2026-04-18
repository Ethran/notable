package com.ethran.notable.editor.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.ethran.notable.R
import com.ethran.notable.data.ensurePreviewsFullFolder
import com.ethran.notable.utils.ensureNotMainThread
import com.ethran.notable.utils.logCallStack
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private val log = ShipBook.getLogger("bitmapUtils")

class Provider : FileProvider(R.xml.file_paths)

private const val EQUALITY_THRESHOLD = 0.01f
const val THUMBNAIL_WIDTH = 500
private const val THUMBNAIL_QUALITY = 60
private const val PREVIEW_QUALITY = 85

enum class PreviewSaveMode {
    STRICT_BW,  // Threshold to black & white, lossless max compression (WebP Lossless effort 100)
    REGULAR     // Grayscale or Color depending on device
}

private data class StorageOptimization(
    val bitmap: Bitmap,
    val format: Bitmap.CompressFormat,
    val quality: Int
)

private fun optimizeBitmapForStorage(
    bitmap: Bitmap,
    mode: PreviewSaveMode,
    isThumbnail: Boolean
): StorageOptimization {
    if (mode == PreviewSaveMode.STRICT_BW) {
        // Apply threshold for absolute B&W. WebP Lossless scale factor (effort) is passed via quality: 100 is max effort.
        return StorageOptimization(bitmap.toThresholded(), webpLosslessFormat, 100)
    }

    // REGULAR mode
    val isColor = DeviceCompat.isColorDevice()
    val isOnyx = DeviceCompat.isOnyxDevice

    if (!isColor || isOnyx) {
        val config = Bitmap.Config.RGB_565
        val optimized = createBitmap(bitmap.width, bitmap.height, config)
        val canvas = Canvas(optimized)
        val paint = Paint().apply {
            if (!isColor) {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        // WebP Lossless generates excellent small files for UI/grayscale handwriting.
        // When choosing lossless WEBP, the compression effort uses exactly `100` to yield smallest file.
        val format = if (!isThumbnail) webpLosslessFormat else webpLossyFormat
        val quality = if (!isThumbnail) 100 else THUMBNAIL_QUALITY

        return StorageOptimization(optimized, format, quality)
    }

    // Standard color saves
    val quality = if (isThumbnail) THUMBNAIL_QUALITY else PREVIEW_QUALITY
    return StorageOptimization(bitmap, webpLossyFormat, quality)
}

fun getThumbnailFile(context: Context, pageID: String): File =
    File(context.filesDir, "pages/previews/thumbs/$pageID.webp")

private fun isEqApprox(a: Float, b: Float): Boolean = abs(a - b) <= EQUALITY_THRESHOLD

private fun checkZoomAndScroll(scroll: Offset?, zoom: Float?): Boolean {
    if (zoom == null || scroll == null) {
        log.d("savePagePreview: skipping persist (zoom is $zoom, scroll is $scroll)")
        return false
    }
    if (!isEqApprox(zoom, 1f)) {
        log.d("savePagePreview: skipping persist (zoom=$zoom not ~1.0)")
        return false
    }
    if (!isEqApprox(scroll.x, 0f)) {
        log.d("savePagePreview: skipping persist (scroll.x: ${scroll.x} != 0)")
        return false
    }
    return true
}

private fun isCacheFresh(file: File, pageUpdatedAtMs: Long?): Boolean {
    return pageUpdatedAtMs == null || pageUpdatedAtMs <= 0 || file.lastModified() >= pageUpdatedAtMs
}

/**
 * Build the filename (without directories) for a persisted preview bitmap.
 * We encode the vertical scroll (rounded to Int) into the name so different vertical positions
 * can have separate cached previews.
 *
 * Format: {pageID}-sy{scrollY}.webp
 */
private fun buildPreviewFileName(pageID: String, scrollY: Int): String = "${pageID}-sy$scrollY.webp"

val webpLossyFormat get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
    Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

val webpLosslessFormat get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
    Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP

/**
 *   Remove other variants for this page (legacy + other scrollY encodings)
 */
private fun removeOldBitmaps(dir: File, latestPreview: String, pageID: String) {
    dir.listFiles()?.forEach { f ->
        if (f.name != latestPreview && f.name.startsWith(pageID)) {
            try {
                if (f.delete()) {
                    log.d("saveHQPagePreview: removed old preview ${f.name}")
                }
            } catch (_: Throwable) {
                log.e("saveHQPagePreview: failed to delete old preview ${f.name}")
            }
        }
    }
}

fun saveHQPagePreview(
    context: Context, bitmap: Bitmap, pageID: String, scroll: Offset?, zoom: Float?, mode: PreviewSaveMode = PreviewSaveMode.REGULAR
) {
    ensureNotMainThread("saveHQPagePreview")
    if (!checkZoomAndScroll(scroll, zoom)) return

    val scrollYInt = scroll!!.y.roundToInt()
    val fileName = buildPreviewFileName(pageID, scrollYInt)
    val dir = ensurePreviewsFullFolder(context)
    val file = File(dir, fileName)

    val optimized = optimizeBitmapForStorage(bitmap, mode, isThumbnail = false)

    try {
        file.outputStream().buffered().use { os ->
            val success = optimized.bitmap.compress(optimized.format, optimized.quality, os)
            if (!success) {
                log.e("saveHQPagePreview: Failed to compress bitmap")
                return@use
            }
            log.d("saveHQPagePreview: cached preview saved as $fileName (scrollY=$scrollYInt)")
        }
        removeOldBitmaps(dir, fileName, pageID)
    } catch (e: Exception) {
        log.e("saveHQPagePreview: Exception while saving preview: ${e.message}")
        logCallStack("saveHQPagePreview")
    } finally {
        if (optimized.bitmap != bitmap) {
            optimized.bitmap.recycle()
        }
    }
}

fun loadHQPagePreview(
    context: Context,
    pageID: String,
    scroll: Offset?,
    zoom: Float?,
    pageUpdatedAtMs: Long?,
    requireExactMatch: Boolean,
): Bitmap? {
    val dir = ensurePreviewsFullFolder(context)

    if (requireExactMatch) {
        if (!checkZoomAndScroll(scroll, zoom)) return null
        val scrollYInt = scroll!!.y.roundToInt()
        val expectedFileName = buildPreviewFileName(pageID, scrollYInt)
        val targetFile = File(dir, expectedFileName)

        if (!targetFile.exists()) {
            log.i("loadHQPagePreview: no exact-match cache (expected $expectedFileName)")
            return null
        }
        if (!isCacheFresh(targetFile, pageUpdatedAtMs)) {
            log.i("loadHQPagePreview: cache is stale for ${targetFile.name}")
            return null
        }
        return decodeBitmapFromFile(targetFile)
    }

    // Try finding the freshest file starting with pageID
    val candidates =
        dir.listFiles { f -> f.isFile && f.name.startsWith(pageID) && f.name.endsWith(".webp") }
            ?.toList()?.filter { isCacheFresh(it, pageUpdatedAtMs) }.orEmpty()

    if (candidates.isEmpty()) {
        log.i("loadHQPagePreview: no native cache file for pageID=$pageID")
        return null
    }

    val newest = candidates.maxByOrNull { it.lastModified() } ?: candidates.first()
    return decodeBitmapFromFile(newest)
}

suspend fun loadPagePreviewOrFallback(
    context: Context,
    pageIdToLoad: String,
    expectedWidth: Int,
    expectedHeight: Int,
    pageNumber: Int?,
    pageUpdatedAtMs: Long?
): Bitmap = withContext(Dispatchers.IO) {
    // Load from disk (full quality folder) ignoring requireExactMatch initially to find any full image
    var bitmapFromDisk: Bitmap? = try {
        loadHQPagePreview(
            context,
            pageIdToLoad,
            null,
            null,
            pageUpdatedAtMs = pageUpdatedAtMs,
            requireExactMatch = false
        )
    } catch (t: Throwable) {
        log.e("Failed to load persisted bitmap: ${t.message}")
        null
    }

    if (bitmapFromDisk == null) {
        val thumbFile = getThumbnailFile(context, pageIdToLoad)
        if (thumbFile.exists()) {
            bitmapFromDisk = decodeBitmapFromFile(thumbFile)
        }
    }

    when {
        bitmapFromDisk == null -> {
            log.d("No persisted preview for $pageIdToLoad. Creating placeholder.")
            createPlaceholderPreview(expectedWidth, expectedHeight, pageNumber)
        }

        bitmapFromDisk.width == expectedWidth && bitmapFromDisk.height == expectedHeight -> {
            log.d("Loaded preview for page $pageIdToLoad (fits view).")
            bitmapFromDisk
        }

        else -> {
            log.i("Preview size mismatch -> scaling to ${expectedWidth}x${expectedHeight}")
            val scaled = createBitmap(
                expectedWidth, expectedHeight, bitmapFromDisk.config ?: Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(scaled)
            val paint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
                isDither = true
            }
            val srcRect = Rect(0, 0, bitmapFromDisk.width, bitmapFromDisk.height)
            val destRect = Rect(0, 0, expectedWidth, expectedHeight)
            canvas.drawBitmap(bitmapFromDisk, srcRect, destRect, paint)

            if (scaled != bitmapFromDisk) bitmapFromDisk.recycle()
            scaled
        }
    }
}


private fun createPlaceholderPreview(
    width: Int, height: Int, pageNumber: Int?
): Bitmap {
    val bmp = createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1))
    val canvas = Canvas(bmp)
    canvas.drawColor(Color.WHITE)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textAlign = Paint.Align.CENTER
        textSize = (min(width, height) * 0.05f).coerceAtLeast(16f)
    }
    val msg = pageNumber?.let { "Page $it — No Preview" } ?: "No Preview"

    val fm = paint.fontMetrics
    val x = width / 2f
    val y = height / 2f - (fm.ascent + fm.descent) / 2f
    canvas.drawText(msg, x, y, paint)

    return bmp
}

private fun decodeBitmapFromFile(file: File): Bitmap? {
    return try {
        val imgBitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (imgBitmap != null) {
            log.d("decodeBitmapFromFile: loaded cached preview '${file.name}'")
            imgBitmap
        } else {
            log.w("decodeBitmapFromFile: failed to decode bitmap from ${file.name}")
            log.d(
                $$"""
                exists=$${file.exists()}
                size=$${file.length()}
                name=${file.name}
                """.trimIndent()
            )
            null
        }
    } catch (e: Exception) {
        log.e("decodeBitmapFromFile: Exception while loading bitmap: ${e.message}")
        null
    }
}

/**
 * Persist a thumbnail for a page.
 */
fun savePageThumbnail(
    context: Context, bitmap: Bitmap, pageID: String, mode: PreviewSaveMode = PreviewSaveMode.REGULAR
) {
    ensureNotMainThread("savePageThumbnail")
    val file = getThumbnailFile(context, pageID)
    file.parentFile?.mkdirs()

    val ratio = bitmap.height.toFloat() / bitmap.width.toFloat()
    val scaledBitmap = bitmap.scale(THUMBNAIL_WIDTH, (THUMBNAIL_WIDTH * ratio).toInt(), false)
    val optimized = optimizeBitmapForStorage(scaledBitmap, mode, isThumbnail = true)

    try {
        file.outputStream().buffered().use { os ->
            optimized.bitmap.compress(optimized.format, optimized.quality, os)
        }
    } catch (e: Exception) {
        log.e("savePageThumbnail: Exception while saving thumbnail: ${e.message}")
        logCallStack("savePageThumbnail")
    }

    if (optimized.bitmap != scaledBitmap) {
        optimized.bitmap.recycle()
    }
    if (scaledBitmap != bitmap) {
        scaledBitmap.recycle()
    }
}


fun Bitmap.toThresholded(threshold: Int = 180): Bitmap {
    val result = createBitmap(width, height, Bitmap.Config.RGB_565)
    val canvas = Canvas(result)
    val paint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            // R output = 0.299R + 0.587G + 0.114B (luminance)
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0f,     0f,     0f,     1f, 0f
        )))
    }
    canvas.drawBitmap(this, 0f, 0f, paint)
    // now threshold: push every pixel to pure black or white
    val pixels = IntArray(width * height)
    result.getPixels(pixels, 0, width, 0, 0, width, height)
    for (i in pixels.indices) {
        val lum = Color.red(pixels[i]) // R=G=B after desaturate
        pixels[i] = if (lum < threshold) Color.BLACK else Color.WHITE
    }
    result.setPixels(pixels, 0, width, 0, 0, width, height)
    return result
}