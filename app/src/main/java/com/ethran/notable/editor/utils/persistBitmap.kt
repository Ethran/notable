package com.ethran.notable.editor.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import com.ethran.notable.R
import com.ethran.notable.data.ensurePreviewsFullFolder
import com.ethran.notable.utils.logCallStack
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.min
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
 * TODO: If scroll differs by a small factor, update scroll to match the saved value.
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
    context: Context, pageID: String, scroll: Offset?, zoom: Float?, requireExactMatch: Boolean
): Bitmap? {
    val dir = ensurePreviewsFullFolder(context)

    // Exact match path: enforce zoom/scroll checks and precise encoded filename
    if (requireExactMatch) {
        if (!checkZoomAndScroll(scroll, zoom)) return null
        val scrollYInt = scroll!!.y.roundToInt()
        val encodedFile = File(dir, buildPreviewFileName(pageID, scrollYInt))
        val candidateFiles = listOf(
            encodedFile, File(dir, pageID),          // legacy (no suffix)
            File(dir, "$pageID.png")    // legacy .png
        )

        val targetFile = candidateFiles.firstOrNull { it.exists() }
        if (targetFile == null) {
            log.i("loadPersistBitmap: no exact-match cache (expected ${encodedFile.name})")
            return null
        }
        return decodePreview(targetFile, encodedFile.name)
    }

    // Non-exact path: accept any zoom/scroll and pick the best matching cached file for this page
    // Prefer the newest file among all files starting with pageID (including legacy and encoded variants)
    val allMatches: List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith(pageID) }?.toList().orEmpty()

    // Also include legacy fallbacks explicitly (in case listFiles filtering changes)
    val legacyExtras = listOf(
        File(dir, pageID), File(dir, "$pageID.png")
    )

    // If we do have a scroll, include its encoded name as a candidate too (may help if it's present)
    val encodedFromProvidedScroll = scroll?.let {
        File(dir, buildPreviewFileName(pageID, it.y.roundToInt()))
    }

    // Merge and deduplicate by name
    val candidates =
        (listOfNotNull(encodedFromProvidedScroll) + legacyExtras + allMatches).distinctBy { it.name }
            .filter { it.exists() }

    if (candidates.isEmpty()) {
        log.i("loadPersistBitmap: no cache file for pageID=$pageID (non-exact)")
        return null
    }

    // Pick newest by lastModified
    val newest = candidates.maxByOrNull { it.lastModified() } ?: candidates.first()

    // For logging, try to compute the "exact" encoded filename if we had a scroll; otherwise pass the actual name
    val expectedName = encodedFromProvidedScroll?.name ?: newest.name
    return decodePreview(newest, expectedName)
}


// Load preview fast, without touching any windowed canvas.
// Returns a bitmap that fits expectedWidth x expectedHeight (letterboxed if needed), or a placeholder.
suspend fun loadPreview(
    context: Context,
    pageIdToLoad: String,
    expectedWidth: Int,
    expectedHeight: Int,
    pageNumber: Int?
): Bitmap = withContext(Dispatchers.IO) {
    // Load from disk
    val bitmapFromDisk: Bitmap? = try {
        loadPersistBitmap(context, pageIdToLoad, null, null, false)
    } catch (t: Throwable) {
        log.e("Failed to load persisted bitmap: ${t.message}")
        null
    }

    val prepared = when {
        bitmapFromDisk == null -> {
            log.d("No persisted preview for $pageIdToLoad. Creating placeholder.")
            createPlaceholderPreview(expectedWidth, expectedHeight, pageNumber)
        }

        bitmapFromDisk.width == expectedWidth && bitmapFromDisk.height == expectedHeight -> {
            log.d("Loaded preview for page $pageIdToLoad (fits view).")
            bitmapFromDisk
        }

        else -> {
            log.i(
                "Preview size mismatch (${bitmapFromDisk.width}x${bitmapFromDisk.height}) -> " + "scaling to ${expectedWidth}x${expectedHeight}"
            )
            bitmapFromDisk.scaleToFitLetterboxed(expectedWidth, expectedHeight)
        }
    }

    prepared
}

// Maintain aspect ratio; letterbox into an ARGB_8888 bitmap of target size.
private fun Bitmap.scaleToFitLetterboxed(targetWidth: Int, targetHeight: Int): Bitmap {
    if (targetWidth <= 0 || targetHeight <= 0) {
        return this
    }

    val scale = min(
        targetWidth / this.width.toFloat(), targetHeight / this.height.toFloat()
    )
    val scaledW = (this.width * scale).roundToInt().coerceAtLeast(1)
    val scaledH = (this.height * scale).roundToInt().coerceAtLeast(1)

    val scaled = if (scaledW == this.width && scaledH == this.height) this
    else Bitmap.createScaledBitmap(this, scaledW, scaledH, true)

    val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    canvas.drawColor(Color.WHITE)

    val left = ((targetWidth - scaledW) / 2f)
    val top = ((targetHeight - scaledH) / 2f)
    canvas.drawBitmap(scaled, left, top, null)

    return output
}


private fun createPlaceholderPreview(
    width: Int,
    height: Int,
    pageNumber: Int?
): Bitmap {
    val bmp = Bitmap.createBitmap(
        width.coerceAtLeast(1),
        height.coerceAtLeast(1),
        Bitmap.Config.ARGB_8888
    )
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

private fun decodePreview(file: File, expectedNameForLog: String): Bitmap? {
    return try {
        val imgBitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (imgBitmap != null) {
            if (file.name != expectedNameForLog) {
                log.d("loadPersistBitmap: loaded cached preview (non-exact or legacy) '${file.name}'")
            } else {
                log.d("loadPersistBitmap: loaded cached preview '${file.name}'")
            }
            imgBitmap
        } else {
            log.w("loadPersistBitmap: failed to decode bitmap from ${file.name}")
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