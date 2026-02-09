package com.ethran.notable.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ethran.notable.data.db.Image
import com.ethran.notable.editor.drawing.drawImage
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.editor.state.PlacementMode
import com.ethran.notable.editor.utils.selectImage
import com.ethran.notable.io.uriToBitmap
import com.ethran.notable.ui.showHint
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class ImageHandler(
    private val context: Context,
    private val page: PageView,
    private val state: EditorState,
    private val coroutineScope: CoroutineScope
) {
    private val logImageHandler = ShipBook.getLogger("ImageHandler")

     fun observeImageUri() {
        coroutineScope.launch {
            CanvasEventBus.addImageByUri.drop(1).collect { imageUri ->
                if (imageUri != null) {
                    logImageHandler.v("Received image: $imageUri")
                    handleImage(imageUri)
                } //else
//                    log.i(  "Image uri is empty")
            }
        }
    }

    private fun handleImage(imageUri: Uri) {
        // Convert the image to a software-backed bitmap
        val imageBitmap = uriToBitmap(context, imageUri)?.asImageBitmap()
        if (imageBitmap == null) showHint(
            "There was an error during image processing.", coroutineScope
        )
        val softwareBitmap = imageBitmap?.asAndroidBitmap()?.copy(Bitmap.Config.ARGB_8888, true)
        if (softwareBitmap != null) {
            CanvasEventBus.addImageByUri.value = null

            // Get the image dimensions
            val imageWidth = softwareBitmap.width
            val imageHeight = softwareBitmap.height

            // Calculate the center position for the image relative to the page dimensions
            val centerX = (page.viewWidth - imageWidth) / 2 + page.scroll.x.toInt()
            val centerY = (page.viewHeight - imageHeight) / 2 + page.scroll.y.toInt()
            val imageToSave = Image(
                x = centerX,
                y = centerY,
                height = imageHeight,
                width = imageWidth,
                uri = imageUri.toString(),
                pageId = page.currentPageId
            )
            drawImage(
                context, page.windowedCanvas, imageToSave, -page.scroll
            )
            selectImage(coroutineScope, page, state, imageToSave)
            // image will be added to database when released, the same as with paste element.
            state.selectionState.placementMode = PlacementMode.Paste
            // make sure, that after regaining focus, we wont go back to drawing mode
        } else {
            // Handle cases where the bitmap could not be created
            Log.e("ImageProcessing", "Failed to create software bitmap from URI.")
        }
    }
}