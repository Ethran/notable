package com.ethran.notable.modals


import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import com.ethran.notable.TAG
import com.ethran.notable.classes.DrawCanvas
import com.ethran.notable.classes.PageView
import com.ethran.notable.components.BackgroundSelector
import com.ethran.notable.db.BackgroundType
import com.ethran.notable.utils.copyBackgroundToDatabase
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun PageSettingsModal(pageView: PageView, onClose: () -> Unit) {
    Log.d(TAG, "PageSettingsModal: $pageView")
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pageBackground by remember { mutableStateOf(pageView.pageFromDb?.background ?: "blank") }
    var maxPages: Int? by remember { mutableStateOf(getPdfPageCount(pageBackground)) }
    val currentPage: Int? by remember { mutableIntStateOf(pageView.getBackgroundPageNumber()) }


    val initialBackgroundType = pageView.pageFromDb?.backgroundType ?: "native"
    var pageBackgroundType: BackgroundType by remember {
        mutableStateOf(
            BackgroundType.fromKey(
                initialBackgroundType
            )
        )
    }

    var backgroundMode by remember {
        mutableStateOf(
            when (pageBackgroundType) {
                is BackgroundType.CoverImage -> "Cover"
                is BackgroundType.Image, is BackgroundType.ImageRepeating -> "Image"
                else -> "Native"
            }
        )
    }

    // Create an activity result launcher for picking visual media (images in this case)
    val pickMedia =
        rememberLauncherForActivityResult(contract = PickVisualMedia()) { uri ->
            uri?.let {
                // Grant read URI permission to access the selected URI
                val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flag)
                Log.e(TAG, "PageSettingsModal: $pageBackgroundType")
                val subfolder = pageBackgroundType.folderName
                //  copy image to documents/notabledb/images/filename
                val copiedFile = copyBackgroundToDatabase(context, uri, subfolder)

                Log.i(
                    "InsertImage",
                    "Image was received and copied, it is now at:${copiedFile.toUri()}"
                )
//                            DrawCanvas.addImageByUri.value = copiedFile.toUri()
                val updatedPage = pageView.pageFromDb!!.copy(
                    background = copiedFile.toString(),
                    backgroundType = pageBackgroundType.key
                )
                pageView.updatePageSettings(updatedPage)
                scope.launch { DrawCanvas.refreshUi.emit(Unit) }
                pageBackground = copiedFile.toString()
            }
        }
// PDF picker for backgrounds
    val pickPdf = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flag)
            val subfolder = pageBackgroundType.folderName
            val copiedFile = copyBackgroundToDatabase(context, uri, subfolder)

            val updatedPage = pageView.pageFromDb!!.copy(
                background = copiedFile.toString(),
                backgroundType = "pdf0" // Start at page 0
            )
            pageView.updatePageSettings(updatedPage)
            scope.launch { DrawCanvas.refreshUi.emit(Unit) }
            pageBackground = copiedFile.toString()
            pageBackgroundType = BackgroundType.Pdf(1)
            Log.i(
                TAG,
                "PDF was received and copied, it is now at:${copiedFile.toUri()}"
            )
            Log.i(TAG, "PageSettingsModal: $pageBackgroundType")
        }
    }


    Dialog(onDismissRequest = { onClose() }) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .fillMaxWidth()
                .border(2.dp, Color.Black, RectangleShape)
        ) {
            Column(Modifier.padding(20.dp, 10.dp)) {
                Text("Page setting")
            }
            Box(
                Modifier
                    .height(0.5.dp)
                    .fillMaxWidth()
                    .background(Color.Black)
            )
            Column(Modifier.padding(20.dp, 10.dp)) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Background Mode:")
                    Spacer(Modifier.width(10.dp))

                    // Native Option
                    Button(
                        onClick = {
                            backgroundMode = "Native"
                            pageBackgroundType = BackgroundType.Native
                        },
                        modifier = Modifier.padding(5.dp),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (backgroundMode == "Native") Color.Gray else Color.LightGray
                        )
                    ) {
                        Text("Native")
                    }

                    // Image Option
                    Button(
                        onClick = {
                            backgroundMode = "Image"
                            pageBackgroundType = BackgroundType.Image
                        },
                        modifier = Modifier.padding(5.dp),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (backgroundMode == "Image") Color.Gray else Color.LightGray
                        )
                    ) {
                        Text("Image")
                    }

                    // Cover Option
                    Button(
                        onClick = {
                            backgroundMode = "Cover"
                            pageBackgroundType = BackgroundType.CoverImage
                        },
                        modifier = Modifier.padding(5.dp),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (backgroundMode == "Cover") Color.Gray else Color.LightGray
                        )
                    ) {
                        Text("Cover")
                    }
                    // PDF Option
                    Button(
                        onClick = {
                            backgroundMode = "PDF"
                            pageBackgroundType = BackgroundType.Pdf(1)
                        },
                        modifier = Modifier.padding(5.dp),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (backgroundMode == "PDF") Color.Gray else Color.LightGray
                        )
                    ) {
                        Text("PDF")
                    }

                }


                Spacer(Modifier.height(10.dp))


                when (backgroundMode) {
                    "Image", "imagerepeating" -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Repeat background")
                            Spacer(Modifier.width(10.dp))
                            Switch(
                                checked = pageBackgroundType == BackgroundType.ImageRepeating,
                                onCheckedChange = { isChecked ->
                                    pageBackgroundType =
                                        if (isChecked) BackgroundType.ImageRepeating else BackgroundType.Image
                                    val updatedPage =
                                        pageView.pageFromDb!!.copy(backgroundType = pageBackgroundType.key)
                                    pageView.updatePageSettings(updatedPage)
                                    scope.launch { DrawCanvas.refreshUi.emit(Unit) }
                                }
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        // Here you can add an Image Grid if you have predefined images
                        BackgroundSelector(
                            currentBackground = pageBackground,
                            currentBackgroundType = pageBackgroundType,
                            onBackgroundChange = { background, type ->
                                val updatedPage = pageView.pageFromDb!!.copy(
                                    background = background,
                                    backgroundType = type.key
                                )
                                pageView.updatePageSettings(updatedPage)
                                scope.launch { DrawCanvas.refreshUi.emit(Unit) }
                                pageBackground = background
                                pageBackgroundType = type
                            },
                            onRequestFilePicker = {
                                pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                            }
                        )
                    }

                    "Cover" -> {
                        BackgroundSelector(
                            currentBackground = pageBackground,
                            currentBackgroundType = pageBackgroundType,
                            onBackgroundChange = { background, type ->
                                val updatedPage = pageView.pageFromDb!!.copy(
                                    background = background,
                                    backgroundType = type.key
                                )
                                pageView.updatePageSettings(updatedPage)
                                scope.launch { DrawCanvas.refreshUi.emit(Unit) }
                                pageBackground = background
                                Log.e(TAG, "onBackgroundChange: $type")
                                pageBackgroundType = type
                            },
                            onRequestFilePicker = {
                                Log.e(TAG, "onRequestFilePicker: $pageBackgroundType")
                                pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                            }
                        )
                    }

                    "Native" -> {
                        Column {
                            listOf(
                                "blank" to "Blank page",
                                "dotted" to "Dot grid",
                                "lined" to "Lines",
                                "squared" to "Small squares grid",
                                "hexed" to "Hexagon grid"
                            ).forEach { (value, label) ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp)
                                        .background(if (pageBackground == value) Color.LightGray else Color.Transparent)
                                        .clickable {
                                            val updatedPage = pageView.pageFromDb!!.copy(
                                                background = value,
                                                backgroundType = "native"
                                            )
                                            pageView.updatePageSettings(updatedPage)
                                            scope.launch { DrawCanvas.refreshUi.emit(Unit) }
                                            pageBackground = value
                                            pageBackgroundType = BackgroundType.Native
                                        }
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    }

                    "PDF" -> {
                        BackgroundSelector(
                            currentBackground = pageBackground,
                            currentBackgroundType = pageBackgroundType,
                            onBackgroundChange = { background, type ->
                                val updatedPage = pageView.pageFromDb!!.copy(
                                    background = background,
                                    backgroundType = type.key
                                )
                                pageView.updatePageSettings(updatedPage)
                                scope.launch { DrawCanvas.refreshUi.emit(Unit) }
                                pageBackground = background
                                pageBackgroundType = type
                                maxPages = getPdfPageCount(background)
                            },
                            onRequestFilePicker = {
                                Log.e(TAG, "onRequestFilePicker: $pageBackgroundType")
                                pickPdf.launch(arrayOf("application/pdf"))
                            },
                            maxPages = maxPages,
                            currentPage = currentPage
                        )
                    }

                }
            }
        }
    }
}


fun getPdfPageCount(uri: String): Int {
    if (uri.isEmpty())
        return 0

    return try {
        val fileDescriptor =
            ParcelFileDescriptor.open(File(uri), ParcelFileDescriptor.MODE_READ_ONLY)

        if (fileDescriptor != null) {
            PdfRenderer(fileDescriptor).use { renderer ->
                renderer.pageCount
            }
        } else {
            Log.e(TAG, "File descriptor is null for URI: $uri")
            0
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to open PDF: ${e.message}")
        0
    }
}