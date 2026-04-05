package com.ethran.notable.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import coil.compose.rememberAsyncImagePainter
import com.ethran.notable.editor.utils.getThumbnailFile

@Composable
fun PagePreview(modifier: Modifier = Modifier, pageId: String) {
    val isPreview = LocalInspectionMode.current
    val context = LocalContext.current

    // If we are in the Android Studio Preview, just draw a gray placeholder
    if (isPreview) {
        androidx.compose.foundation.layout.Box(
            modifier = modifier.background(Color.LightGray)
        )
        return
    }

    // Pass the File directly to Coil.
    // Coil automatically handles decoding the Bitmap on an IO thread!
    val imgFile = remember(pageId) {
        getThumbnailFile(context, pageId)
    }

    Image(
        painter = rememberAsyncImagePainter(model = imgFile),
        contentDescription = "Page Preview",
        contentScale = ContentScale.FillWidth,
        modifier = modifier.background(Color.LightGray)
    )
}