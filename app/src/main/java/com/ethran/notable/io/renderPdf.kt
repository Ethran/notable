package com.ethran.notable.io

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.annotation.WorkerThread
import androidx.core.graphics.createBitmap
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.TAG
import io.shipbook.shipbooksdk.Log
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt


//----------------------- Android native (alpha) ---------------------------

/**
 * Render a single page using Android's PdfRenderer.
 *
 * @param file PDF file.
 * @param pageIndex zero-based page index.
 * @param targetWidthPx Desired logical width (without oversample).
 * @param resolutionModifier Extra multiplier (oversample) for sharpness (1.0–2.0 typical).
 * @param clipOut Optional output-clip in pixel space of the *scaled full page*.
 */
@WorkerThread
fun renderPdfPageAndroid(
    file: File,
    pageIndex: Int,
    targetWidthPx: Int,
    resolutionModifier: Float = 1.4f,
    clipOut: Rect? = null): Bitmap?
{
    if (!file.exists()) {
        Log.e(TAG, "AndroidPdf: file not found: ${file.absolutePath}")
        return null
    }
    if (targetWidthPx <= 0) {
        Log.e(TAG, "AndroidPdf: invalid targetWidthPx=$targetWidthPx")
        return null
    }
    if (resolutionModifier <= 1.0f) {
        Log.w(TAG, "Are you sure you want to use low resolution Modifier?: $resolutionModifier")
    }

    val safeResolutionFactor = resolutionModifier.coerceIn(0.5f, 3f)
    var pfd: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    try {
        pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(pfd)

        if (pageIndex !in 0 until renderer.pageCount) {
            Log.e(TAG, "AndroidPdf: invalid pageIndex=$pageIndex (count=${renderer.pageCount})")
            return null
        }

        renderer.openPage(pageIndex).use { page ->
            // Page intrinsic size (pixels at 72dpi baseline)
            val pageW = page.width
            val pageH = page.height
            if (pageW <= 0 || pageH <= 0) {
                Log.e(TAG, "AndroidPdf: invalid intrinsic size $pageW x $pageH")
                return null
            }

            // Scale so the *logical* width matches targetWidthPx, then apply oversample multiplier
            val baseScale = targetWidthPx.toFloat() / pageW
            val scale = baseScale * safeResolutionFactor

            val fullOutW = (pageW * scale).roundToInt().coerceAtLeast(1)
            val fullOutH = (pageH * scale).roundToInt().coerceAtLeast(1)

            var bitmap = createBitmap(fullOutW, fullOutH)
            page.render(
                bitmap,
                null,
                null,
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            )

            // Apply clip (crop) NOT TESTED!!!!
            if (clipOut != null) {
                val safeClip = Rect(
                    clipOut.left.coerceAtLeast(0),
                    clipOut.top.coerceAtLeast(0),
                    clipOut.right.coerceAtMost(bitmap.width),
                    clipOut.bottom.coerceAtMost(bitmap.height)
                )
                if (safeClip.width() > 0 && safeClip.height() > 0 &&
                    (safeClip.width() != bitmap.width || safeClip.height() != bitmap.height)
                ) {
                    val clipped = try {
                        Bitmap.createBitmap(
                            bitmap,
                            safeClip.left,
                            safeClip.top,
                            safeClip.width(),
                            safeClip.height()
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "AndroidPdf: clip failed: ${e.message}", e)
                        null
                    }
                    if (clipped != null) {
                        bitmap.recycle()
                        bitmap = clipped
                    }
                }
            }
            return bitmap
        }
    } catch (oom: OutOfMemoryError) {
        Log.e(TAG, "OOM rendering page $pageIndex: ${oom.message}")
        return null
    } catch (e: Exception) {
        Log.e(TAG, "Error rendering PDF page $pageIndex: ${e.message}", e)
        return null
    } finally {
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
    }
}
