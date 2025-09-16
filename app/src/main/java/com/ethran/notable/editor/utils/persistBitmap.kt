package com.ethran.notable.editor.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import com.ethran.notable.R
import com.ethran.notable.utils.logCallStack
import io.shipbook.shipbooksdk.ShipBook
import java.io.File
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.math.abs
import kotlin.math.roundToInt

private val log = ShipBook.getLogger("bitmapUtils")

// Why it is needed? I try to removed it, and sharing bimap seems to work.
class Provider : FileProvider(R.xml.file_paths)

private const val EQUALITY_THRESHOLD = 0.01f

private fun isEqqApprox(a: Float, b: Float): Boolean = abs(a - b) <= EQUALITY_THRESHOLD

private fun checkZoomAndScroll(scroll: Offset?, zoom: Float?): Boolean {
    if (zoom == null || scroll == null) {
        log.i("persistBitmapFull: skipping persist (zoom is $zoom, scroll is $scroll)")
        return false
    }
    if (!isEqqApprox(zoom, 1f)) {
        log.i("persistBitmapFull: skipping persist (zoom=$zoom not ~1.0)")
        return false
    }
    if (!isEqqApprox(scroll.x, 0f)) {
        log.i("persistBitmapFull: skipping persist (scroll.x: ${scroll.x} == 0)")
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
 * Persist a full bitmap preview for a page.
 *
 * Rules implemented from inline spec:
 * - If zoom or scroll is null -> skip (log)
 * - If zoom is not ~1.0 (with epsilon) -> skip
 * - If scroll.x == 0f -> skip
 * - Encode scroll.y (rounded) in the file name.
 * - Remove previously persisted previews for the same page (keep only one).
 */
fun persistBitmapFull(
    context: Context, bitmap: Bitmap, pageID: String, scroll: Offset?, zoom: Float?
) {
    if (!checkZoomAndScroll(scroll, zoom)) return
    val scrollYInt = scroll!!.y.roundToInt()
    val fileName = buildPreviewFileName(pageID, scrollYInt)
    val dir = File(context.filesDir, "pages/previews/full")
    val file = File(dir, fileName)
    // also remove previous bitmap if it exists, we want to keep only one per page.
    try {
        Files.createDirectories(Path(dir.absolutePath))
    } catch (t: Throwable) {
        log.e("persistBitmapFull: failed to create directories: ${t.message}")
        logCallStack("persistBitmapFull")
        return
    }

    file.outputStream().buffered().use { os ->
        val success = bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
        if (!success) {
            log.e("persistBitmapFull: Failed to compress bitmap")
            logCallStack("persistBitmapFull")
            return
        } else {
            log.i("persistBitmapFull: cached preview saved as $fileName (scrollY=$scrollYInt)")
        }
    }

    // Remove other variants for this page (legacy + other scrollY encodings)
    dir.listFiles()?.forEach { f ->
        if (f.name != fileName && (f.name == pageID || f.name == "$pageID.png" || (f.name.startsWith(
                "$pageID-sy"
            ) && f.name.endsWith(".png")))
        ) {
            if (f.delete()) {
                log.i("persistBitmapFull: removed old preview ${f.name}")
            }
        }
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
    val dir = File(context.filesDir, "pages/previews/full")
    val encodedFile = File(dir, buildPreviewFileName(pageID, scrollYInt))

    val candidateFiles = buildList {
        add(encodedFile)
        // Legacy fallback (older cache without scroll encoding)
        add(File(dir, pageID)) // (no extension as previously used)
        add(File(dir, "$pageID.png")) // possible variant with extension
    }.distinct()

    val targetFile = candidateFiles.firstOrNull { it.exists() }

    if (targetFile == null) {
        log.i("loadPersistBitmap: no cache file (expected ${encodedFile.name})")
        return null
    }

    val imgBitmap = BitmapFactory.decodeFile(targetFile.absolutePath)
    return if (imgBitmap != null) {
        if (targetFile.name != encodedFile.name) {
            log.i("loadPersistBitmap: loaded legacy cached preview (${targetFile.name})")
        } else {
            log.i("loadPersistBitmap: loaded cached preview (${targetFile.name}) for scrollY=$scrollYInt")
        }
        imgBitmap
    } else {
        log.i("loadPersistBitmap: failed to decode bitmap from ${targetFile.name}")
        null
    }
}

fun persistBitmapThumbnail(context: Context, bitmap: Bitmap, pageID: String) {
    val file = File(context.filesDir, "pages/previews/thumbs/$pageID")
    Files.createDirectories(Path(file.absolutePath).parent)
    val ratio = bitmap.height.toFloat() / bitmap.width.toFloat()
    val scaledBitmap = bitmap.scale(500, (500 * ratio).toInt(), false)

    file.outputStream().buffered().use { os ->
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, os)
    }

    if (scaledBitmap != bitmap) {
        scaledBitmap.recycle()
    }
}