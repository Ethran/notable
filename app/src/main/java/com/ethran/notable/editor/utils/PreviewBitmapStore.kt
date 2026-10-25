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
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.ethran.notable.R
import com.ethran.notable.editor.PageViewport
import com.ethran.notable.utils.ensureNotMainThread
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

fun getThumbnailFile(context: Context, pageID: String): File = thumbnailFile(context.filesDir, pageID)

private fun thumbnailFile(filesDir: File, pageID: String): File =
    File(filesDir, "pages/previews/thumbs/$pageID.webp")

private fun previewsDir(filesDir: File): File = File(filesDir, "pages/previews/full")

// [pageID]'s previews in [dir], whatever scroll they were captured at, with a write in progress.
private fun previewFiles(dir: File, pageID: String): List<File> =
    dir.listFiles { f -> f.name.startsWith("$pageID-") }?.toList().orEmpty()

/**
 * The name of [pageID]'s preview captured at [viewport]. It carries the vertical scroll, rounded
 * to whole pixels, since the page opens at the scroll it was left at.
 */
private fun previewFileName(pageID: String, viewport: PageViewport): String =
    "$pageID-sy${viewport.scroll.y.roundToInt()}.webp"

/** Deletes [pageID]'s thumbnail and previews, whatever scroll they were captured at. */
fun deletePagePreviews(filesDir: File, pageID: String) {
    thumbnailFile(filesDir, pageID).delete()
    previewFiles(previewsDir(filesDir), pageID).forEach { it.delete() }
}

private fun isEqApprox(a: Float, b: Float): Boolean = abs(a - b) <= EQUALITY_THRESHOLD

/** Whether a view at [viewport] makes the page's preview: unzoomed, at the page's left edge. */
fun canSavePreview(viewport: PageViewport): Boolean =
    isEqApprox(viewport.zoom, 1f) && isEqApprox(viewport.scroll.x, 0f)

private fun isCacheFresh(file: File, pageUpdatedAtMs: Long?): Boolean {
    return pageUpdatedAtMs == null || pageUpdatedAtMs <= 0 || file.lastModified() >= pageUpdatedAtMs
}

private val webpLossyFormat get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
    Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP

private val webpLosslessFormat get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
    Bitmap.CompressFormat.WEBP_LOSSLESS else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP

/**
 * Writes [optimized] to [file] through a temporary file of its own, so no reader sees it half
 * written. The file is dated [capturedAtMs] if given: freshness is judged by its date, and an edit
 * made while it was written is not in it. Returns whether it was written.
 */
private fun writeWebp(file: File, optimized: StorageOptimization, capturedAtMs: Long? = null): Boolean {
    var tempFile: File? = null
    try {
        tempFile = File.createTempFile("${file.name}.", ".tmp", file.parentFile)
        val compressed = tempFile.outputStream().buffered().use {
            optimized.bitmap.compress(optimized.format, optimized.quality, it)
        }
        if (compressed && tempFile.length() > 0 && tempFile.renameTo(file)) {
            capturedAtMs?.let(file::setLastModified)
            return true
        }
        log.e("Writing ${file.name} failed")
    } catch (e: Exception) {
        log.e("Writing ${file.name} failed: ${e.message}", e)
    }
    tempFile?.delete()
    return false
}

/**
 * Saves [bitmap], the view of [pageID] at [viewport] as it was at [capturedAtMs], as the page's
 * preview in place of the ones captured at other scrolls, if [canSavePreview].
 */
fun saveHQPagePreview(
    context: Context, bitmap: Bitmap, pageID: String, viewport: PageViewport, capturedAtMs: Long
) {
    ensureNotMainThread("saveHQPagePreview")
    if (!canSavePreview(viewport)) return

    val dir = previewsDir(context.filesDir).apply { mkdirs() }
    val fileName = previewFileName(pageID, viewport)
    val optimized = optimizeBitmapForStorage(bitmap, PreviewSaveMode.REGULAR, isThumbnail = false)
    try {
        if (writeWebp(File(dir, fileName), optimized, capturedAtMs)) {
            log.d("saveHQPagePreview: saved $fileName")
            previewFiles(dir, pageID).filter { it.name != fileName }.forEach { it.delete() }
        }
    } finally {
        if (optimized.bitmap != bitmap) optimized.bitmap.recycle()
    }
}

/** [pageID]'s preview captured at [viewport], if there is one and it is newer than [pageUpdatedAtMs]. */
fun loadHQPagePreview(
    context: Context,
    pageID: String,
    viewport: PageViewport,
    pageUpdatedAtMs: Long?,
): Bitmap? {
    if (!canSavePreview(viewport)) return null
    val file = File(previewsDir(context.filesDir), previewFileName(pageID, viewport))
    if (!file.exists()) {
        log.i("loadHQPagePreview: no preview ${file.name}")
        return null
    }
    if (!isCacheFresh(file, pageUpdatedAtMs)) {
        log.i("loadHQPagePreview: preview ${file.name} is stale")
        return null
    }
    return decodeBitmapFromFile(file)
}

/**
 * A picture of [pageIdToLoad] of the given size: its preview at whatever scroll, if fresh, or else
 * its thumbnail, scaled; or a placeholder if it has neither.
 */
suspend fun loadPagePreviewOrFallback(
    context: Context,
    pageIdToLoad: String,
    expectedWidth: Int,
    expectedHeight: Int,
    pageNumber: Int?,
    pageUpdatedAtMs: Long?
): Bitmap = withContext(Dispatchers.IO) {
    val preview = previewFiles(previewsDir(context.filesDir), pageIdToLoad)
        .filter { it.name.endsWith(".webp") && isCacheFresh(it, pageUpdatedAtMs) }
        .maxByOrNull { it.lastModified() }
    val bitmapFromDisk = preview?.let(::decodeBitmapFromFile)
        ?: getThumbnailFile(context, pageIdToLoad).takeIf { it.exists() }?.let(::decodeBitmapFromFile)

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
            bitmapFromDisk.recycle()
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

// Decodes [file], deleting it if it is empty or can't be decoded.
private fun decodeBitmapFromFile(file: File): Bitmap? {
    if (file.length() == 0L) {
        log.w("decodeBitmapFromFile: file is zero bytes, deleting: ${file.name}")
        file.delete()
        return null
    }
    return try {
        val imgBitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (imgBitmap != null) {
            log.d("decodeBitmapFromFile: loaded cached preview '${file.name}'")
            imgBitmap
        } else {
            log.w("decodeBitmapFromFile: failed to decode bitmap from ${file.name}, deleting")
            file.delete()
            null
        }
    } catch (e: Exception) {
        log.e("decodeBitmapFromFile: Exception while loading bitmap: ${e.message}")
        file.delete()
        null
    }
}

/**
 * Persist a thumbnail for a page.
 */
fun savePageThumbnail(
    context: Context,
    bitmap: Bitmap,
    pageID: String,
    mode: PreviewSaveMode = PreviewSaveMode.REGULAR,
    capturedAtMs: Long? = null,
) {
    ensureNotMainThread("savePageThumbnail")
    val finalFile = getThumbnailFile(context, pageID)
    finalFile.parentFile?.mkdirs()

    val ratio = bitmap.height.toFloat() / bitmap.width.toFloat()
    val scaledBitmap = bitmap.scale(THUMBNAIL_WIDTH, (THUMBNAIL_WIDTH * ratio).toInt(), false)
    val optimized = optimizeBitmapForStorage(scaledBitmap, mode, isThumbnail = true)
    try {
        if (writeWebp(finalFile, optimized, capturedAtMs)) log.d("savePageThumbnail: thumbnail saved for $pageID")
    } finally {
        if (optimized.bitmap != scaledBitmap) optimized.bitmap.recycle()
        if (scaledBitmap != bitmap) scaledBitmap.recycle()
    }
}


private fun Bitmap.toThresholded(threshold: Int = 180): Bitmap {
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
