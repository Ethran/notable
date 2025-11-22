package com.ethran.notable.io

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.TAG
import com.ethran.notable.data.datastore.GlobalAppSettings
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
    log.v("Reloading background, path: $filePath, scale: $scale")
    val file = File(filePath)
    if (!file.exists()) {
        log.v("getOrLoadBackground: File does not exist at path: $filePath")
        return null
    }
    val timer = Timing("loadBackgroundBitmap")
    if (!filePath.endsWith(".pdf", ignoreCase = true)) {
        try {
            timer.step("decode bitmap image")
            val result = BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            if (result == null)
                log.e("loadBackgroundBitmap: result is null, couldn't decode image, file name ends with ${filePath.takeLast(4)}")
            timer.end("loaded background")
            return result?.asAndroidBitmap()
        } catch (e: Exception) {
            Log.e(TAG, "getOrLoadBackground: Error loading background - ${e.message}", e)
        }
    }
    val targetWidth = SCREEN_WIDTH * (scale.coerceAtMost(2f))
    timer.step("start android Pdf")
    log.d("Rendering using ${if (GlobalAppSettings.current.muPdfRendering) "MuPdf" else "Android Pdf"}")
    val newBitmap: Bitmap? = if (GlobalAppSettings.current.muPdfRendering)
        renderPdfPageMuPdf(filePath, pageNumber, targetWidth.toInt(), resolutionModifier = 1.5f)
    else
        renderPdfPageAndroid(file, pageNumber, targetWidth.toInt(), resolutionModifier = 1.2f)
    timer.end("loaded background")
    return newBitmap
}