package com.ethran.notable.io

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.TAG
import com.ethran.notable.utils.Timing
import com.ethran.notable.utils.ensureNotMainThread
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import java.io.File


private val log = ShipBook.getLogger("renderFromFile")

/**
 * Converts a URI to a Bitmap using the provided [context] and [uri].
 *
 * @param context The context used to access the content resolver.
 * @param uri The URI of the image to be converted to a Bitmap.
 * @return The Bitmap representation of the image, or null if conversion fails.
 * https://medium.com/@munbonecci/how-to-display-an-image-loaded-from-the-gallery-using-pick-visual-media-in-jetpack-compose-df83c78a66bf
 */
fun uriToBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        // Obtain the content resolver from the context
        val contentResolver: ContentResolver = context.contentResolver

        // Since the minimum SDK is 29, we can directly use ImageDecoder to decode the Bitmap
        val source = ImageDecoder.createSource(contentResolver, uri)
        ImageDecoder.decodeBitmap(source)
    } catch (e: SecurityException) {
        Log.e(TAG, "SecurityException: ${e.message}", e)
        null
    } catch (e: ImageDecoder.DecodeException) {
        Log.e(TAG, "DecodeException: ${e.message}", e)
        null
    } catch (e: Exception) {
        Log.e(TAG, "Unexpected error: ${e.message}", e)
        null
    }
}


fun loadBackgroundBitmap(filePath: String, pageNumber: Int, scale: Float): Bitmap? {
    // TODO: it's very slow, needs to be changed for better tool
    if (filePath.isEmpty()) return null
    ensureNotMainThread("loadBackgroundBitmap")
    Log.v(TAG, "Reloading background, path: $filePath, scale: $scale")
    val file = File(filePath)
    if (!file.exists()) {
        Log.e(TAG, "getOrLoadBackground: File does not exist at path: $filePath")
        return null
    }
    val timer = Timing("loadBackgroundBitmap")
    val newBitmap: ImageBitmap? = try {
        if (filePath.endsWith(".pdf", ignoreCase = true)) {
            timer.step("preparing for rendering pdf")
            // PDF rendering
            val fileDescriptor =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(fileDescriptor).use { renderer ->
                if (pageNumber < 0 || pageNumber >= renderer.pageCount) {
                    Log.e(
                        TAG,
                        "getOrLoadBackground: Invalid page number $pageNumber (total: ${renderer.pageCount})"
                    )
                    return null
                }

                renderer.openPage(pageNumber).use { pdfPage ->
                    // Render in bigger resolution, as there are nasty artifacts
                    // when rendering in exact resolution (with colors, highlights and math formulas )
                    val targetWidth = SCREEN_WIDTH * (scale.coerceAtMost(2f))
                    val scaleFactor = (targetWidth / pdfPage.width) * 3f

                    val width = (pdfPage.width * scaleFactor).toInt()
                    val height = (pdfPage.height * scaleFactor).toInt()

                    val bitmap = createBitmap(width, height)
                    timer.step("prepared for recreation for render")
                    pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.asImageBitmap()
                }
            }
        } else {
            timer.step("loading image")
            // Image file
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }
    } catch (e: Exception) {
        Log.e(TAG, "getOrLoadBackground: Error loading background - ${e.message}", e)
        null
    }
    timer.end("loaded background")
    return newBitmap?.asAndroidBitmap()
}