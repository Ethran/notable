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
import androidx.core.graphics.createBitmap
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.TAG
import com.ethran.notable.db.Image
import com.ethran.notable.db.Page
import com.ethran.notable.db.PageRepository
import com.ethran.notable.db.Stroke
import com.ethran.notable.db.getBackgroundType
import com.ethran.notable.drawing.drawBg
import com.ethran.notable.drawing.drawImage
import com.ethran.notable.drawing.drawStroke
import com.ethran.notable.modals.A4_HEIGHT
import com.ethran.notable.modals.A4_WIDTH
import com.ethran.notable.modals.GlobalAppSettings
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

    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)

    // Draw background
    drawBg(context, canvas, page.getBackgroundType(), page.background)

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

    //add 50 only if we are not cutting pdf on export.
    val strokeHeight = if (strokes.isEmpty()) 0 else strokes.maxOf(Stroke::bottom)
        .toInt() + if (GlobalAppSettings.current.visualizePdfPagination) 0 else 50
    val strokeWidth = if (strokes.isEmpty()) 0 else strokes.maxOf(Stroke::right).toInt() + 50
    val scaleFactor = A4_WIDTH.toFloat() / SCREEN_WIDTH

    val contentHeight = strokeHeight.coerceAtLeast(SCREEN_HEIGHT)
    val pageHeight = (contentHeight * scaleFactor).toInt()

    if (GlobalAppSettings.current.paginatePdf) {
        var currentTop = 0
        while (currentTop < pageHeight) {
            // TODO: pageNumber are wrong
            val documentPage =
                startPage(PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, number).create())
            drawPageContent(
                context,
                documentPage.canvas,
                page,
                strokes,
                images,
                currentTop,
                scaleFactor
            )
            finishPage(documentPage)
            currentTop += A4_HEIGHT
        }
    } else {
        val documentPage =
            startPage(PdfDocument.PageInfo.Builder(A4_WIDTH, pageHeight, number).create())
        drawPageContent(context, documentPage.canvas, page, strokes, images, 0, scaleFactor)
        finishPage(documentPage)
    }
}

private fun drawPageContent(
    context: Context,
    canvas: Canvas,
    page: Page,
    strokes: List<Stroke>,
    images: List<Image>,
    scroll: Int,
    scaleFactor: Float
) {
    canvas.scale(scaleFactor, scaleFactor)
    val scaledScroll = (scroll / scaleFactor).toInt()
    drawBg(
        context,
        canvas,
        page.getBackgroundType(),
        page.background,
        scaledScroll,
        scaleFactor
    )

    for (image in images) {
        drawImage(context, canvas, image, IntOffset(0, -scaledScroll))
    }

    for (stroke in strokes) {
        drawStroke(canvas, stroke, IntOffset(0, -scaledScroll))
    }
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