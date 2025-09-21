package com.ethran.notable.editor.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import com.ethran.notable.R
import com.ethran.notable.data.ensurePreviewsFullFolder
import com.ethran.notable.utils.logCallStack
import io.shipbook.shipbooksdk.ShipBook
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

private val log = ShipBook.getLogger("bitmapUtils")

// Why it is needed? I try to removed it, and sharing bimap seems to work.
class Provider : FileProvider(R.xml.file_paths)

private const val EQUALITY_THRESHOLD = 0.01f
private const val THUMBNAIL_WIDTH = 500
private const val THUMBNAIL_QUALITY = 60
private const val PREVIEW_QUALITY = 90

private fun isEqqApprox(a: Float, b: Float): Boolean = abs(a - b) <= EQUALITY_THRESHOLD

private fun checkZoomAndScroll(scroll: Offset?, zoom: Float?): Boolean {
    if (zoom == null || scroll == null) {
        log.d("persistBitmapFull: skipping persist (zoom is $zoom, scroll is $scroll)")
        return false
    }
    if (!isEqqApprox(zoom, 1f)) {
        log.d("persistBitmapFull: skipping persist (zoom=$zoom not ~1.0)")
        return false
    }
    if (!isEqqApprox(scroll.x, 0f)) {
        log.d("persistBitmapFull: skipping persist (scroll.x: ${scroll.x} != 0)")
        return false
    }
    return true
}

/**
 * Build the filename (without directories) for a persisted preview bitmap.
 * We encode the vertical scroll (rounded to Int) into the name so different vertical positions
 * can have separate cached previews.
 *
 * Format: {pageID}-sy{scrollY}.png
 */
private fun buildPreviewFileName(pageID: String, scrollY: Int): String = "${pageID}-sy$scrollY.png"


/**
 *   Remove other variants for this page (legacy + other scrollY encodings)
 */
private fun removeOldBitmaps(dir: File, latestPreview: String, pageID: String) {
    dir.listFiles()?.forEach { f ->
        if (f.name != latestPreview && (f.name == pageID || f.name == "$pageID.png" || (f.name.startsWith(
                "$pageID-sy"
            ) && f.name.endsWith(".png")))
        ) {
            if (f.delete()) log.d("persistBitmapFull: removed old preview ${f.name}")
            else log.e("persistBitmapFull: failed to delete old preview ${f.name}")
        }
    }
}

/**
 * Persist a full bitmap preview for a page.
 *
 * Rules implemented from inline spec:
 * - If zoom or scroll is null -> skip (log)
 * - If zoom is not ~1.0 (with epsilon) -> skip
 * - If scroll.x != 0f -> skip
 * - Encode scroll.y (rounded) in the file name.
 * - Remove previously persisted previews for the same page (keep only one).
 */
fun persistBitmapFull(
    context: Context, bitmap: Bitmap, pageID: String, scroll: Offset?, zoom: Float?
) {
    if (!checkZoomAndScroll(scroll, zoom)) return
    val scrollYInt = scroll!!.y.roundToInt()
    val fileName = buildPreviewFileName(pageID, scrollYInt)
    val dir = ensurePreviewsFullFolder(context)
    val file = File(dir, fileName)

    try {
        file.outputStream().buffered().use { os ->
            val success = bitmap.compress(Bitmap.CompressFormat.PNG, PREVIEW_QUALITY, os)
            if (!success) {
                log.e("persistBitmapFull: Failed to compress bitmap")
                logCallStack("persistBitmapFull")
                return
            } else {
                log.d("persistBitmapFull: cached preview saved as $fileName (scrollY=$scrollYInt)")
            }
        }
        removeOldBitmaps(dir, fileName, pageID)

    } catch (e: Exception) {
        log.e("persistBitmapFull: Exception while saving preview: ${e.message}")
        logCallStack("persistBitmapFull")
    }
}

/**
 * Load a persisted bitmap preview.
 *
 * Rules:
 * - Only load if zoom is ~1.0 (else return null)
 * - Require non-null scroll (so we can derive file name); if null -> return null
 * - File name must match encoded scroll.y used during persist
 * - Backward compatibility: if encoded file not found and scrollY != 0, attempt legacy filename (without suffix)
 */
fun loadPersistBitmap(
    context: Context, pageID: String, scroll: Offset?, zoom: Float?
): Bitmap? {
    if (!checkZoomAndScroll(scroll, zoom)) return null

    val scrollYInt = scroll!!.y.roundToInt()
    val dir = ensurePreviewsFullFolder(context)
    val encodedFile = File(dir, buildPreviewFileName(pageID, scrollYInt))

    val candidateFiles = listOf(encodedFile, File(dir, pageID), File(dir, "$pageID.png"))

    val targetFile = candidateFiles.firstOrNull { it.exists() }

    if (targetFile == null) {
        log.i("loadPersistBitmap: no cache file (expected ${encodedFile.name})")
        return null
    }

    return try {
        val imgBitmap = BitmapFactory.decodeFile(targetFile.absolutePath)
        if (imgBitmap != null) {
            if (targetFile.name != encodedFile.name) {
                log.d("loadPersistBitmap: loaded legacy cached preview (${targetFile.name})")
            } else {
                log.d("loadPersistBitmap: loaded cached preview (${targetFile.name}) for scrollY=$scrollYInt")
            }
            imgBitmap
        } else {
            log.w("loadPersistBitmap: failed to decode bitmap from ${targetFile.name}")
            null
        }
    } catch (e: Exception) {
        log.e("loadPersistBitmap: Exception while loading bitmap: ${e.message}")
        logCallStack("loadPersistBitmap")
        null
    }
}

/**
 * Persist a thumbnail for a page.
 */
fun persistBitmapThumbnail(context: Context, bitmap: Bitmap, pageID: String) {
    val file = File(context.filesDir, "pages/previews/thumbs/$pageID")
    file.parentFile?.mkdirs()
    val ratio = bitmap.height.toFloat() / bitmap.width.toFloat()
    val scaledBitmap = bitmap.scale(THUMBNAIL_WIDTH, (THUMBNAIL_WIDTH * ratio).toInt(), false)

    try {
        file.outputStream().buffered().use { os ->
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, os)
        }
    } catch (e: Exception) {
        log.e("persistBitmapThumbnail: Exception while saving thumbnail: ${e.message}")
        logCallStack("persistBitmapThumbnail")
    }

    if (scaledBitmap != bitmap) {
        scaledBitmap.recycle()
    }
}