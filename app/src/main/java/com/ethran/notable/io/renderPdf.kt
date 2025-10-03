package com.ethran.notable.io

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.annotation.WorkerThread
import androidx.core.graphics.createBitmap
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.ethran.notable.TAG
import io.shipbook.shipbooksdk.Log
import java.io.File
import kotlin.math.roundToInt

/* -----------------------------------------------------------------------
 *  PDF single-page rendering helpers (Android PdfRenderer + MuPDF).
 *
 *  Notes:
 *   - targetWidthPx is the logical target width; we internally oversample.
 *   - clipOut (if provided) is expressed in the OUTPUT pixel space of the fully
 *     scaled page (i.e. after applying targetWidth + oversample).
 *   - MuPDF path tries partial rendering by translating matrix; if that fails,
 *     it falls back to full render + crop.
 * ---------------------------------------------------------------------- */

/* ---------------------- Android native (alpha) ----------------------- */

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
    clipOut: Rect? = null
): Bitmap? {
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

    val safeResolution = resolutionModifier.coerceIn(0.5f, 3f)

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

            val scale = (targetWidthPx.toFloat() / pageW) * safeResolution
            val fullOutW = (pageW * scale).roundToInt().coerceAtLeast(1)
            val fullOutH = (pageH * scale).roundToInt().coerceAtLeast(1)

            var bitmap = createBitmap(fullOutW, fullOutH)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            val sanitized = sanitizeClip(clipOut, fullOutW, fullOutH)
            if (sanitized != null && (sanitized.width() != fullOutW || sanitized.height() != fullOutH)) {
                bitmap = bitmap.cropTo(sanitized) ?: return bitmap
            }
            return bitmap
        }
    } catch (oom: OutOfMemoryError) {
        Log.e(TAG, "AndroidPdf: OOM rendering page $pageIndex: ${oom.message}")
    } catch (e: Exception) {
        Log.e(TAG, "AndroidPdf: Error rendering page $pageIndex: ${e.message}", e)
    } finally {
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
    }
    return null
}

/* ---------------------------- MuPDF Path ----------------------------- */

@WorkerThread
fun renderPdfPageMuPdf(
    path: String,
    pageIndex: Int,
    targetWidthPx: Int,
    resolutionModifier: Float = 1.0f,
    clipOut: Rect? = null
): Bitmap? {
    val file = File(path)
    if (!file.exists()) {
        Log.e(TAG, "MuPdf: file not found: $path")
        return null
    }
    if (targetWidthPx <= 0) {
        Log.e(TAG, "MuPdf: invalid targetWidthPx=$targetWidthPx")
        return null
    }

    val safeResolution = resolutionModifier.coerceIn(0.5f, 3f)
    var doc: Document? = null
    try {
        doc = Document.openDocument(file.absolutePath)
        val pageCount = doc.countPages()
        if (pageIndex !in 0 until pageCount) {
            Log.e(TAG, "MuPdf: invalid pageIndex=$pageIndex (count=$pageCount)")
            return null
        }

        val page = doc.loadPage(pageIndex)
        try {
            val bounds = page.bounds

            val pageWpt = (bounds.x1 - bounds.x0)
            val pageHpt = (bounds.y1 - bounds.y0)
            if (pageWpt <= 0f || pageHpt <= 0f) {
                Log.e(TAG, "MuPdf: invalid page bounds: $bounds")
                return null
            }

            val scale = (targetWidthPx.toFloat() / pageWpt) * safeResolution
            val fullOutW = (pageWpt * scale).roundToInt().coerceAtLeast(1)
            val fullOutH = (pageHpt * scale).roundToInt().coerceAtLeast(1)

            val sanitizedClip = sanitizeClip(clipOut, fullOutW, fullOutH)

            var bitmap: Bitmap

            // Attempt partial render if clip is valid and smaller than full
            if (sanitizedClip != null && (sanitizedClip.width() != fullOutW || sanitizedClip.height() != fullOutH)) {
                try {
                    val outW = sanitizedClip.width()
                    val outH = sanitizedClip.height()
                    bitmap = createBitmap(outW, outH)

                    // Translate matrix so that the clip region is positioned at (0,0)
                    val txPdf = sanitizedClip.left / scale
                    val tyPdf = sanitizedClip.top / scale
                    val matrix = Matrix(scale, scale).apply {
                        // Negative translation to shift desired region into origin
                        translate(-txPdf, -tyPdf)
                    }

                    AndroidDrawDevice(bitmap).useSafely { dev ->
                        page.run(dev, matrix, null)
                    }
                    return bitmap
                } catch (t: Throwable) {
                    Log.w(
                        TAG,
                        "MuPdf: partial render optimization failed (${t.message}), falling back."
                    )
                    // fall through to full render path
                }
            }

            // Full render path
            bitmap = createBitmap(fullOutW, fullOutH)
            AndroidDrawDevice(bitmap).useSafely { dev ->
                val matrix = Matrix(scale, scale)
                page.run(dev, matrix, null)
            }

            if (sanitizedClip != null) {
                bitmap = bitmap.cropTo(sanitizedClip) ?: return bitmap
            }
            return bitmap
        } finally {
            page.destroy()
        }
    } catch (oom: OutOfMemoryError) {
        Log.e(TAG, "MuPdf: OOM rendering page $pageIndex: ${oom.message}")
    } catch (e: NoClassDefFoundError) {
        Log.e(TAG, "MuPdf: Fitz classes not found (missing dependency).", e)
    } catch (e: Exception) {
        Log.e(TAG, "MuPdf: Error rendering page $pageIndex: ${e.message}", e)
    } finally {
        doc?.destroy()
    }
    return null
}

/* ============================ Helpers ============================= */

/**
 * Returns a sanitized clip rectangle clamped to [0, w] x [0, h], or null if the
 * provided clip is null or empty/invalid after clamping.
 */
private fun sanitizeClip(clip: Rect?, w: Int, h: Int): Rect? {
    if (clip == null) return null
    if (w <= 0 || h <= 0) return null
    val r = Rect(
        clip.left.coerceIn(0, w),
        clip.top.coerceIn(0, h),
        clip.right.coerceIn(0, w),
        clip.bottom.coerceIn(0, h)
    )
    return if (r.width() > 0 && r.height() > 0) r else null
}

/**
 * Crops the bitmap to [rect]. Returns a new bitmap or (on failure) the original.
 * If an error occurs, logs and returns the original (unrecycled).
 */
private fun Bitmap.cropTo(rect: Rect): Bitmap? {
    if (rect.left == 0 && rect.top == 0 && rect.width() == width && rect.height() == height) return this
    return try {
        val cropped = Bitmap.createBitmap(this, rect.left, rect.top, rect.width(), rect.height())
        // Recycle original only if successful
        this.recycle()
        cropped
    } catch (e: Exception) {
        Log.e(TAG, "Crop failed: ${e.message}", e)
        this
    }
}

/**
 * Tries to call close() or destroy() if present.
 */
private fun closeQuietly(obj: Any?) {
    if (obj == null) return
    try {
        val cls = obj.javaClass
        val mClose = cls.methods.firstOrNull { it.name == "close" && it.parameterCount == 0 }
        if (mClose != null) {
            mClose.invoke(obj); return
        }
        val mDestroy = cls.methods.firstOrNull { it.name == "destroy" && it.parameterCount == 0 }
        mDestroy?.invoke(obj)
    } catch (_: Throwable) {
        // Ignore
    }
}

/**
 * Small helper to ensure device close even if the underlying version differs.
 */
private inline fun <T : Any> T.useSafely(block: (T) -> Unit) {
    try {
        block(this)
    } finally {
        closeQuietly(this)
    }
}