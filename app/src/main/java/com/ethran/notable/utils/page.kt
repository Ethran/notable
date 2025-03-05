package com.ethran.notable.utils


import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.unit.IntOffset
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.TAG
import com.ethran.notable.db.PageRepository
import com.ethran.notable.db.Stroke
import io.shipbook.shipbooksdk.Log


fun drawCanvas(context: Context, pageId: String): Bitmap {
    if (Looper.getMainLooper().isCurrentThread)
        Log.e(TAG, "Exporting is done on main thread.")

    val pages = PageRepository(context)
    val (page, strokes) = pages.getWithStrokeById(pageId)
    val (_, images) = pages.getWithImageById(pageId)

    val strokeHeight = if (strokes.isEmpty()) 0 else strokes.maxOf(Stroke::bottom).toInt() + 50
    val strokeWidth = if (strokes.isEmpty()) 0 else strokes.maxOf(Stroke::right).toInt() + 50

    val height = strokeHeight.coerceAtLeast(SCREEN_HEIGHT)
    val width = strokeWidth.coerceAtLeast(SCREEN_WIDTH)

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Draw background
    drawBg(canvas, page.nativeTemplate, 0)

    // Draw strokes
    for (stroke in strokes) {
        drawStroke(canvas, stroke, IntOffset(0, 0))
    }
    for (image in images) {
        drawImage(context, canvas, image, IntOffset(0, 0))
    }
    return bitmap
}

fun PdfDocument.writePage(context: Context, number: Int, repo: PageRepository, id: String) {
    if (Looper.getMainLooper().isCurrentThread)
        Log.e(TAG, "Exporting is done on main thread.")

    val (page, strokes) = repo.getWithStrokeById(id)
    //TODO: improve that function
    val (_, images) = repo.getWithImageById(id)

    // Define the target page size (A4 in points: 595 x 842)
    val A4_WIDTH = 595
    val A4_HEIGHT = 842

    val strokeHeight = if (strokes.isEmpty()) 0 else strokes.maxOf(Stroke::bottom).toInt() + 50
    val strokeWidth = if (strokes.isEmpty()) 0 else strokes.maxOf(Stroke::right).toInt() + 50
    val scaleFactor = A4_WIDTH.toFloat() / SCREEN_WIDTH

    // todo do not rely on this anymore
    // I slightly modified it, should be better
    val contentHeight = strokeHeight.coerceAtLeast(SCREEN_HEIGHT)
    val pageHeight = (contentHeight * scaleFactor).toInt()
    val contentWidth = strokeWidth.coerceAtLeast(SCREEN_WIDTH)


    val documentPage =
        startPage(PdfDocument.PageInfo.Builder(A4_WIDTH, pageHeight, number).create())

    // Center content on the A4 page
    val offsetX = (A4_WIDTH - (contentWidth * scaleFactor)) / 2
    val offsetY = (A4_HEIGHT - (contentHeight * scaleFactor)) / 2

    documentPage.canvas.scale(scaleFactor, scaleFactor)
    drawBg(documentPage.canvas, page.nativeTemplate, 0, scaleFactor)

    for (stroke in strokes) {
        drawStroke(documentPage.canvas, stroke, IntOffset(0, 0))
    }

    for (image in images) {
        drawImage(context, documentPage.canvas, image, IntOffset(0, 0))
    }

    finishPage(documentPage)
}


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