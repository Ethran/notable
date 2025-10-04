package com.ethran.notable.ui.dialogs


import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.ethran.notable.R
import com.ethran.notable.TAG
import com.ethran.notable.data.copyBackgroundToDatabase
import com.ethran.notable.data.ensureBackgroundsFolder
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.editor.DrawCanvas
import com.ethran.notable.editor.drawing.drawDottedBg
import com.ethran.notable.editor.drawing.drawHexedBg
import com.ethran.notable.editor.drawing.drawLinedBg
import com.ethran.notable.editor.drawing.drawSquaredBg
import com.ethran.notable.io.getPdfPageCount
import compose.icons.FeatherIcons
import compose.icons.feathericons.Loader
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream


@Composable
fun BackgroundSelector(
    initialPageBackgroundType: String,
    initialPageBackground: String,
    initialPageNumberInPdf: Int = 0,
    isNotebookBgSelector: Boolean = false, // for notebook default background
    notebookId: String? = null,
    pageNumberInBook: Int = -1,
    onChange: (backgroundType: String, background: String?) -> Unit,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pageBackground by remember { mutableStateOf(initialPageBackground) }
    var maxPages: Int? by remember { mutableStateOf(getPdfPageCount(pageBackground)) }
    val currentPage: Int? by remember { mutableIntStateOf(initialPageNumberInPdf) }

    var pageBackgroundType: BackgroundType by remember {
        mutableStateOf(
            BackgroundType.fromKey(
                initialPageBackgroundType
            )
        )
    }

    var selectedBackgroundMode by remember {
        mutableStateOf(
            when (pageBackgroundType) {
                is BackgroundType.CoverImage -> "Cover"
                is BackgroundType.Image, is BackgroundType.ImageRepeating -> "Image"
                is BackgroundType.Pdf, is BackgroundType.AutoPdf -> "PDF"
                else -> "Native"
            }
        )
    }

    fun selectedToType(): BackgroundType {
        return when (selectedBackgroundMode) {
            "Cover" -> BackgroundType.CoverImage
            "Image" -> BackgroundType.Image
            "PDF" -> BackgroundType.Pdf(1)
            else -> {
                throw Exception("Unknown BackgroundType for selection $selectedBackgroundMode")
            }

        }
    }

    // Create an activity result launcher for picking visual media (images in this case)
    val pickMedia =
        rememberLauncherForActivityResult(contract = PickVisualMedia()) { uri ->
            if (uri == null) {
                Log.w(
                    TAG,
                    "PickVisualMedia: uri is null (user cancelled or provider returned null)"
                )
                return@rememberLauncherForActivityResult
            }

            val currentType = selectedToType()
            Log.d(TAG, "PickVisualMedia: will copy to subfolder=\"$currentType\"")

            scope.launch(Dispatchers.IO) {
                try {
                    val copiedFile = copyBackgroundToDatabase(context, uri, currentType.folderName)

                    Log.i(TAG, "PickVisualMedia: copied -> ${copiedFile.absolutePath}")
                    onChange(currentType.key, copiedFile.toString())
                    scope.launch { DrawCanvas.refreshUi.emit(Unit) }
                    pageBackground = copiedFile.toString()
                    Log.d(
                        TAG,
                        "PickVisualMedia: UI updated, pageBackground=$pageBackground, type=${currentType.key}"
                    )

                } catch (e: Exception) {
                    Log.e(TAG, "PickVisualMedia: copy failed: ${e.message}", e)
                }
            }
        }
    // PDF picker for backgrounds
    val pickPdf = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            Log.w(
                TAG,
                "PickPdf: uri is null (user cancelled or provider returned null)"
            )
            return@rememberLauncherForActivityResult
        }

        val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flag)

        val currentType = selectedToType()
        Log.d(TAG, "PickPdf: will copy to subfolder=\"$currentType\"")
        scope.launch(Dispatchers.IO) {
            try {
                val copiedFile = copyBackgroundToDatabase(context, uri, currentType.folderName)
                onChange(currentType.key, copiedFile.toString())
                scope.launch { DrawCanvas.refreshUi.emit(Unit) }
                pageBackground = copiedFile.toString()
                pageBackgroundType = currentType
                Log.i(
                    TAG,
                    "PDF was received and copied, it is now at:${copiedFile.toUri()}"
                )
                Log.i(TAG, "PageSettingsModal: $pageBackgroundType")
            } catch (e: Exception) {
                Log.e(TAG, "PdfPicker: copy failed: ${e.message}", e)
            }
        }
    }

    val modalHeight = 550.dp
    Dialog(onDismissRequest = { onClose() }) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .fillMaxWidth()
                .height(modalHeight)
                .border(2.dp, Color.Black, RectangleShape)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Background Mode Buttons
                    listOf("Native", "Image", "Cover", "PDF").forEach { modeName ->
                        Button(
                            onClick = {
                                selectedBackgroundMode = modeName
                            },
                            modifier = Modifier.padding(horizontal = 5.dp),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (selectedBackgroundMode == modeName) Color.Gray else Color.LightGray
                            )
                        ) {
                            Text(modeName)
                        }
                    }
                }
            }
            Box(
                Modifier
                    .height(0.5.dp)
                    .fillMaxWidth()
                    .background(Color.Black)
            )
            Column(Modifier.padding(20.dp, 10.dp)) {
                when (selectedBackgroundMode) {
                    "Image" -> {
                        val currentBackgroundType =
                            if (pageBackgroundType == BackgroundType.ImageRepeating || pageBackgroundType == BackgroundType.Image)
                                pageBackgroundType else BackgroundType.Image
                        ShowImageOption(
                            currentBackground = pageBackground,
                            currentBackgroundType = currentBackgroundType,
                            onBackgroundChange = { background, type ->
                                onChange(type.key, background)
                                pageBackground = background
                                pageBackgroundType = type
                            },
                            onRequestFilePicker = {
                                pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                            }
                        )
                        if (pageBackgroundType == BackgroundType.ImageRepeating || pageBackgroundType == BackgroundType.Image) {
                            Spacer(Modifier.height(10.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Repeat background")
                                Spacer(Modifier.width(10.dp))
                                Switch(
                                    checked = pageBackgroundType == BackgroundType.ImageRepeating,
                                    onCheckedChange = { isChecked ->
                                        pageBackgroundType =
                                            if (isChecked) BackgroundType.ImageRepeating else BackgroundType.Image

                                        onChange(pageBackgroundType.key, null)
                                    }
                                )
                            }
                        }
                    }

                    "Cover" -> {
                        ShowImageOption(
                            currentBackground = pageBackground,
                            currentBackgroundType = BackgroundType.CoverImage,
                            onBackgroundChange = { background, type ->
                                onChange(type.key, background)
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
                        ShowNativeOption(
                            currentBackground = pageBackground,
                            currentBackgroundType = BackgroundType.Native,
                            onBackgroundChange = { background, type ->
                                onChange(type.key, background)
                                pageBackground = background
                                pageBackgroundType = type
                            },
                        )
                    }

                    "PDF" -> {
                        val currentBackgroundType =
                            if (pageBackgroundType == BackgroundType.AutoPdf || pageBackgroundType is BackgroundType.Pdf)
                                pageBackgroundType else BackgroundType.Pdf(1)

                        fun onBackgroundChange(type: BackgroundType, background: String) {
                            onChange(type.key, background)
                            pageBackground = background
                            pageBackgroundType = type
                            maxPages = getPdfPageCount(background)
                        }
                        ShowPdfOption(
                            currentBackground = pageBackground,
                            currentBackgroundType = currentBackgroundType,
                            onBackgroundChange = ::onBackgroundChange,
                            onRequestFilePicker = {
                                Log.e(TAG, "onRequestFilePicker: $pageBackgroundType")
                                pickPdf.launch(arrayOf("application/pdf"))
                            }
                        )
                        PageNumberSelector(
                            currentBackground = pageBackground,
                            currentBackgroundType = pageBackgroundType,
                            maxPages = maxPages,
                            currentPage = currentPage,
                            onBackgroundChange = ::onBackgroundChange,
                            showAutoPdfOption = (notebookId != null) && ((maxPages
                                ?: 0) > pageNumberInBook),
                            isNotebookBgSelector = isNotebookBgSelector
                        )

                    }

                }
            }
        }
    }
}


@Composable
fun ShowNativeOption(
    currentBackground: String,
    currentBackgroundType: BackgroundType,
    onBackgroundChange: (String, BackgroundType) -> Unit
) {
    val nativeOptions = listOf(
        "blank" to "Blank page",
        "dotted" to "Dot grid",
        "lined" to "Lines",
        "squared" to "Small squares grid",
        "hexed" to "Hexagon grid"
    )

    val context = LocalContext.current
    val backgroundFolder = File(context.filesDir, "native_backgrounds")

    val bitmaps = remember {
        mutableStateMapOf<String, Bitmap?>()
    }

    LaunchedEffect(Unit) {
        if (!backgroundFolder.exists()) {
            backgroundFolder.mkdirs()
        }

        nativeOptions.forEach { (key, _) ->
            val file = File(backgroundFolder, "$key.png")
            if (!file.exists()) {
                val bitmap = createBitmap(300, 550)
                val canvas = Canvas(bitmap)

                when (key) {
                    "blank" -> canvas.drawColor(Color.White.toArgb())
                    "dotted" -> drawDottedBg(canvas, Offset.Zero, 1f)
                    "lined" -> drawLinedBg(canvas, Offset.Zero, 1f)
                    "squared" -> drawSquaredBg(canvas, Offset.Zero, 1f)
                    "hexed" -> drawHexedBg(canvas, Offset.Zero, 0.4f)
                }

                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                bitmaps[key] = bitmap
            } else {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                bitmaps[key] = bitmap
            }
        }
    }

    Text("Choose Native Template", fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))

    LazyVerticalGrid(
        columns = GridCells.Adaptive(120.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp)
            .padding(8.dp)
    ) {
        items(nativeOptions) { (value, label) ->
            val bitmap = bitmaps[value]

            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .border(
                        width = if (value == currentBackground) 3.dp else 1.dp,
                        color = if (value == currentBackground) Color.Black else Color.LightGray,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onBackgroundChange(value, currentBackgroundType) },
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Preview for $label",
                        contentScale = ContentScale.None,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.75f) // maintain image proportion
                            .background(Color.White)
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.75f)
                            .background(Color.LightGray),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Loader,
                            contentDescription = "Preview of background not loaded.",
                            tint = Color.Gray,
                        )
                    }
                }

                Text(
                    text = label,
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.7f))
                        .padding(4.dp)
                )
            }
        }
    }
}


@Composable
fun ShowImageOption(
    currentBackground: String,
    currentBackgroundType: BackgroundType,
    onBackgroundChange: (String, BackgroundType) -> Unit,
    onRequestFilePicker: () -> Unit
) {
    val imageType = when (currentBackgroundType) {
        BackgroundType.CoverImage -> "Cover "
        BackgroundType.ImageRepeating -> "Repeating "
        else -> ""
    }
    Text("Choose ${imageType}Image", fontWeight = FontWeight.Bold)

    val baseOptions = listOf(
        Triple("iris", "Iris", painterResource(id = R.drawable.iris)),
    )

    val folderName = currentBackgroundType.folderName
    val folder = File(ensureBackgroundsFolder(), folderName)

    val uriOptions = folder.listFiles()?.filter { it.isFile }?.map { file ->
        Triple(file.absolutePath, file.nameWithoutExtension, null as Painter?)
    } ?: emptyList()

    val chooseFileOption = listOf(Triple("file", "Choose From File...", null))

    val options = baseOptions + uriOptions + chooseFileOption

    LazyVerticalGrid(
        columns = GridCells.Adaptive(100.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp)
            .padding(8.dp)
    ) {
        items(options) { (value, label, painter) ->
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .border(
                        width = if (value == currentBackground) 3.dp else 1.dp,
                        color = if (value == currentBackground) Color.Black else Color.LightGray,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        if (value == "file") {
                            onRequestFilePicker()
                        } else {
                            onBackgroundChange(value, currentBackgroundType)
                        }
                    }) {
                if (painter != null) {
                    Image(
                        painter = painter,
                        contentDescription = label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                    )
                } else if (value != "file") {
                    val bitmap = remember(value) {
                        BitmapFactory.decodeFile(value)?.asImageBitmap()
                    }

                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = label,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .background(Color.Gray),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label, color = Color.White, fontWeight = FontWeight.Light
                            )
                        }
                    }
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(Color.Gray),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label, color = Color.White, fontWeight = FontWeight.Light
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun ShowPdfOption(
    currentBackground: String,
    currentBackgroundType: BackgroundType,
    onBackgroundChange: (BackgroundType, String) -> Unit,
    onRequestFilePicker: () -> Unit,
) {
    Text("Choose PDF background", fontWeight = FontWeight.Bold)

    val folderName = currentBackgroundType.folderName
    val folder = File(ensureBackgroundsFolder(), folderName)

    val uriOptions = folder.listFiles()?.filter { it.isFile }?.map { file ->
        Pair(file.absolutePath, file.nameWithoutExtension)
    } ?: emptyList()
    val pdfOptions = listOf(
        "file" to "Import PDF"
    ) + uriOptions

    LazyVerticalGrid(
        columns = GridCells.Adaptive(100.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .padding(8.dp)
    ) {
        items(pdfOptions) { (value, label) ->
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .border(
                        width = if (value == currentBackground) 3.dp else 1.dp,
                        color = if (value == currentBackground) Color.Black else Color.LightGray,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        if (value == "file") {
                            onRequestFilePicker()
                        } else {
                            onBackgroundChange(currentBackgroundType, value)
                        }
                    }, contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(Color.Gray)
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = "PDF",
                        tint = Color.White,
                        modifier = Modifier.height(36.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(label, color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun PageNumberSelector(
    currentBackground: String,
    currentBackgroundType: BackgroundType,
    maxPages: Int?,
    currentPage: Int?,
    onBackgroundChange: (BackgroundType, String) -> Unit,
    showAutoPdfOption: Boolean,
    isNotebookBgSelector: Boolean
) {
    val pdfSelectorEnabled =
        currentBackground.endsWith(".pdf") && maxPages != null && currentPage != null

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer(alpha = if (pdfSelectorEnabled) 1f else 0.5f)
    ) {
        Column {
            Spacer(Modifier.height(10.dp))
            Text("Select PDF Page", fontWeight = FontWeight.SemiBold)

            var pageText by remember {
                mutableStateOf(
                    ((currentPage ?: (0 + 1))).toString()
                )
            }
            val pageNumber = pageText.toIntOrNull() ?: 1

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 100.dp)
            ) {
                IconButton(
                    onClick = {
                        if (pageNumber > 1) {
                            val newPage = (pageNumber - 1).coerceAtMost(maxPages ?: 1)
                            pageText = newPage.toString()
                            onBackgroundChange(
                                BackgroundType.Pdf(newPage - 1), currentBackground,

                                )
                        }
                    }) {
                    Icon(Icons.Default.Remove, contentDescription = "Previous Page")
                }

                OutlinedTextField(
                    value = pageText,
                    onValueChange = {
                        val input = it.toIntOrNull()
                        if (input != null && input in 1..(maxPages ?: 1)) {
                            pageText = input.toString()
                            onBackgroundChange(
                                BackgroundType.Pdf(input - 1),
                                currentBackground,

                                )
                        } else {
                            pageText = it
                        }
                    },
                    label = { Text("Page number") },
                    keyboardOptions = KeyboardOptions.Default.copy(
                        keyboardType = KeyboardType.Number, imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = {
                        if (pageNumber < (maxPages ?: 1)) {
                            val newPage = (pageNumber + 1).coerceAtLeast(1)
                            pageText = newPage.toString()
                            onBackgroundChange(
                                BackgroundType.Pdf(newPage - 1),
                                currentBackground,

                                )
                        }
                    }) {
                    Icon(Icons.Default.Add, contentDescription = "Next Page")
                }
                if (showAutoPdfOption) {
                    Spacer(Modifier.width(10.dp))

                    var isSelected = currentBackgroundType == BackgroundType.AutoPdf

                    Button(
                        onClick = {
                            if (!isSelected) {
                                onBackgroundChange(
                                    BackgroundType.AutoPdf,
                                    currentBackground,
                                )
                                isSelected = true
                            } else {
                                onBackgroundChange(
                                    BackgroundType.Pdf(pageNumber),
                                    currentBackground,
                                )
                                isSelected = false
                            }
                        }, modifier = Modifier.padding(4.dp), colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (isSelected) Color.Gray else Color.White,
                            contentColor = Color.Black
                        )
                    ) {
                        val name = if (isNotebookBgSelector) "Observe PDF" else "Current Page"
                        Icon(
                            imageVector = Icons.Default.Autorenew,
                            contentDescription = name,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(name)
                        Log.d(TAG, "PageNumberSelector: $isSelected, $currentBackgroundType")
                    }
                }
            }

            Text(
                "PDF has $maxPages pages",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .align(Alignment.CenterHorizontally)
            )

        }
    }
}