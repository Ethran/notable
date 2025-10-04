package com.ethran.notable.editor.ui.toolbar


import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.ethran.notable.R
import com.ethran.notable.TAG
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.copyImageToDatabase
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.BUTTON_SIZE
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.DrawCanvas
import com.ethran.notable.editor.EditorControlTower
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.state.UndoRedoType
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting
import com.ethran.notable.ui.dialogs.BackgroundSelector
import com.ethran.notable.ui.noRippleClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.RefreshCcw
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val toolbarLog = ShipBook.getLogger("Toolbar")
fun presentlyUsedToolIcon(mode: Mode, pen: Pen): Int {
    return when (mode) {
        Mode.Draw -> {
            when (pen) {
                Pen.BALLPEN -> R.drawable.ballpen
                Pen.REDBALLPEN -> R.drawable.ballpenred
                Pen.BLUEBALLPEN -> R.drawable.ballpenblue
                Pen.GREENBALLPEN -> R.drawable.ballpengreen
                Pen.FOUNTAIN -> R.drawable.fountain
                Pen.BRUSH -> R.drawable.brush
                Pen.MARKER -> R.drawable.marker
                Pen.PENCIL -> R.drawable.pencil
            }
        }

        Mode.Erase -> R.drawable.eraser
        Mode.Select -> R.drawable.lasso
        Mode.Line -> R.drawable.line
    }
}

fun isSelected(state: EditorState, penType: Pen): Boolean {
    return if (state.mode == Mode.Draw && state.pen == penType) {
        true
    } else if (state.mode == Mode.Line && state.pen == penType) {
        true
    } else {
        false
    }
}


private val SIZES_STROKES_DEFAULT = listOf("S" to 3f, "M" to 5f, "L" to 10f, "XL" to 20f)
private val SIZES_MARKER_DEFAULT = listOf("M" to 25f, "L" to 40f, "XL" to 60f, "XXL" to 80f)


@Composable
@ExperimentalComposeUiApi
fun Toolbar(
    navController: NavController, state: EditorState, controlTower: EditorControlTower
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Observe zoom level to decide button visibility
    val zoomLevel by state.pageView.zoomLevel.collectAsState()

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
            scope.launch(Dispatchers.IO) {
                try {
                    //  copy image to documents/notabledb/images/filename
                    val copiedFile = copyImageToDatabase(context, uri)

                    // Set isImageLoaded to true
                    toolbarLog.i("Image was received and copied, it is now at:${copiedFile.toUri()}")
                    DrawCanvas.addImageByUri.value = copiedFile.toUri()

                } catch (e: Exception) {
                    toolbarLog.e("ImagePicker: copy failed: ${e.message}", e)
                }
            }

        }

    // on exit of toolbar, update drawing state
    LaunchedEffect(state.menuStates.isBackgroundSelectorModalOpen, state.menuStates.isMenuOpen) {
        // TODO: move it to menuState.
        toolbarLog.i("Updating drawing state")
        state.checkForSelectionsAndMenus()
    }
    fun handleChangePen(pen: Pen) {
        if (state.mode == Mode.Draw && state.pen == pen) {
            state.menuStates.isStrokeSelectionOpen = true
        } else {
            state.mode = Mode.Draw
            state.pen = pen
        }
    }

    fun handleEraser() {
        state.mode = Mode.Erase
    }

    fun handleSelection() {
        state.mode = Mode.Select
    }

    fun handleLine() {
        state.mode = Mode.Line
    }

    fun onChangeStrokeSetting(penName: String, setting: PenSetting) {
        val settings = state.penSettings.toMutableMap()
        settings[penName] = setting.copy()
        state.penSettings = settings
    }

    if (state.menuStates.isBackgroundSelectorModalOpen) {
        toolbarLog.i("Opening page settings modal")
        BackgroundSelector(
            initialPageBackgroundType = state.pageView.pageFromDb?.backgroundType ?: "native",
            initialPageBackground = state.pageView.pageFromDb?.background ?: "blank",
            initialPageNumberInPdf = state.pageView.getBackgroundPageNumber(),
            notebookId = state.pageView.pageFromDb?.notebookId,
            pageNumberInBook = state.pageView.currentPageNumber,
            onChange = { backgroundType, background ->
                val updatedPage =
                    if (background == null)
                        state.pageView.pageFromDb!!.copy(
                            backgroundType = backgroundType
                        )
                    else state.pageView.pageFromDb!!.copy(
                        background = background,
                        backgroundType = backgroundType
                    )
                state.pageView.updatePageSettings(updatedPage)
                scope.launch { DrawCanvas.refreshUi.emit(Unit) }
            }
        ) {
            state.menuStates.isBackgroundSelectorModalOpen = false
        }
    }
    if (state.isToolbarOpen) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height((BUTTON_SIZE + 51).dp)
                .padding(bottom = 50.dp) // TODO: fix this
        ) {
            if (GlobalAppSettings.current.toolbarPosition == AppSettings.Position.Bottom) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.Black)
                )
            }
            Row(
                Modifier
                    .background(Color.White)
                    .height(BUTTON_SIZE.dp)
                    .fillMaxWidth()
            ) {
                ToolbarButton(
                    onSelect = {
                        state.isToolbarOpen = !state.isToolbarOpen
                    }, vectorIcon = FeatherIcons.EyeOff, contentDescription = "close toolbar"
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                PenToolbarButton(
                    onStrokeMenuOpenChange = { state.isDrawing = !it },
                    pen = Pen.BALLPEN,
                    icon = R.drawable.ballpen,
                    isSelected = isSelected(state, Pen.BALLPEN),
                    onSelect = { handleChangePen(Pen.BALLPEN) },
                    sizes = SIZES_STROKES_DEFAULT,
                    penSetting = state.penSettings[Pen.BALLPEN.penName] ?: return,
                    onChangeSetting = { onChangeStrokeSetting(Pen.BALLPEN.penName, it) })

                if (!GlobalAppSettings.current.monochromeMode) {
                    PenToolbarButton(
                        onStrokeMenuOpenChange = { state.isDrawing = !it },
                        pen = Pen.REDBALLPEN,
                        icon = R.drawable.ballpenred,
                        isSelected = isSelected(state, Pen.REDBALLPEN),
                        onSelect = { handleChangePen(Pen.REDBALLPEN) },
                        sizes = SIZES_STROKES_DEFAULT,
                        penSetting = state.penSettings[Pen.REDBALLPEN.penName] ?: return,
                        onChangeSetting = { onChangeStrokeSetting(Pen.REDBALLPEN.penName, it) },
                    )

                    PenToolbarButton(
                        onStrokeMenuOpenChange = { state.isDrawing = !it },
                        pen = Pen.BLUEBALLPEN,
                        icon = R.drawable.ballpenblue,
                        isSelected = isSelected(state, Pen.BLUEBALLPEN),
                        onSelect = { handleChangePen(Pen.BLUEBALLPEN) },
                        sizes = SIZES_STROKES_DEFAULT,
                        penSetting = state.penSettings[Pen.BLUEBALLPEN.penName] ?: return,
                        onChangeSetting = { onChangeStrokeSetting(Pen.BLUEBALLPEN.penName, it) },
                    )
//              Removed to make space for insert tool
                    PenToolbarButton(
                        onStrokeMenuOpenChange = { state.isDrawing = !it },
                        pen = Pen.GREENBALLPEN,
                        icon = R.drawable.ballpengreen,
                        isSelected = isSelected(state, Pen.GREENBALLPEN),
                        onSelect = { handleChangePen(Pen.GREENBALLPEN) },
                        sizes = SIZES_STROKES_DEFAULT,
                        penSetting = state.penSettings[Pen.GREENBALLPEN.penName] ?: return,
                        onChangeSetting = { onChangeStrokeSetting(Pen.GREENBALLPEN.penName, it) },
                    )
                }
                if (GlobalAppSettings.current.neoTools) {
                    PenToolbarButton(
                        onStrokeMenuOpenChange = { state.isDrawing = !it },
                        pen = Pen.PENCIL,
                        icon = R.drawable.pencil,
                        isSelected = isSelected(state, Pen.PENCIL),
                        onSelect = { handleChangePen(Pen.PENCIL) }, // Neo-tool! Usage not recommended
                        sizes = SIZES_STROKES_DEFAULT,
                        penSetting = state.penSettings[Pen.PENCIL.penName] ?: return,
                        onChangeSetting = { onChangeStrokeSetting(Pen.PENCIL.penName, it) },
                    )

                    PenToolbarButton(
                        onStrokeMenuOpenChange = { state.isDrawing = !it },
                        pen = Pen.BRUSH,
                        icon = R.drawable.brush,
                        isSelected = isSelected(state, Pen.BRUSH),
                        onSelect = { handleChangePen(Pen.BRUSH) }, // Neo-tool! Usage not recommended
                        sizes = SIZES_STROKES_DEFAULT,
                        penSetting = state.penSettings[Pen.BRUSH.penName] ?: return,
                        onChangeSetting = { onChangeStrokeSetting(Pen.BRUSH.penName, it) },
                    )
                }
                PenToolbarButton(
                    onStrokeMenuOpenChange = { state.isDrawing = !it },
                    pen = Pen.FOUNTAIN,
                    icon = R.drawable.fountain,
                    isSelected = isSelected(state, Pen.FOUNTAIN),
                    onSelect = { handleChangePen(Pen.FOUNTAIN) },// Neo-tool! Usage not recommended
                    sizes = SIZES_STROKES_DEFAULT,
                    penSetting = state.penSettings[Pen.FOUNTAIN.penName] ?: return,
                    onChangeSetting = { onChangeStrokeSetting(Pen.FOUNTAIN.penName, it) },
                )

                LineToolbarButton(
                    unSelect = { state.mode = Mode.Draw },
                    icon = R.drawable.line,
                    isSelected = state.mode == Mode.Line,
                    onSelect = { handleLine() },
                )

                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                PenToolbarButton(
                    onStrokeMenuOpenChange = { state.isDrawing = !it },
                    pen = Pen.MARKER,
                    icon = R.drawable.marker,
                    isSelected = isSelected(state, Pen.MARKER),
                    onSelect = { handleChangePen(Pen.MARKER) },
                    sizes = SIZES_MARKER_DEFAULT,
                    penSetting = state.penSettings[Pen.MARKER.penName] ?: return,
                    onChangeSetting = { onChangeStrokeSetting(Pen.MARKER.penName, it) })
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )
                EraserToolbarButton(
                    isSelected = state.mode == Mode.Erase,
                    onSelect = {
                        handleEraser()
                    },
                    onMenuOpenChange = { state.menuStates.isStrokeSelectionOpen = it },
                    value = state.eraser,
                    onChange = { state.eraser = it })
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )
                ToolbarButton(
                    isSelected = state.mode == Mode.Select,
                    onSelect = { handleSelection() },
                    iconId = R.drawable.lasso,
                    contentDescription = "lasso"
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                ToolbarButton(
                    iconId = R.drawable.image,
                    contentDescription = "library",
                    onSelect = {
                        // Call insertImage when the button is tapped
                        toolbarLog.i("Launching image picker...")
                        pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                    }
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                if (state.clipboard != null) {
                    ToolbarButton(
                        vectorIcon = FeatherIcons.Clipboard,
                        contentDescription = "paste",
                        onSelect = {
                            controlTower.pasteFromClipboard()
                        }
                    )
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(0.5.dp)
                            .background(Color.Black)
                    )
                }

                // Show "Reset view" only when zoom != 1f or scroll != 0
                val showResetView = state.pageView.scroll.x != 0f || zoomLevel != 1.0f
                if (showResetView) {
                    ToolbarButton(
                        vectorIcon = FeatherIcons.RefreshCcw,
                        contentDescription = "reset zoom and scroll",
                        onSelect = { controlTower.resetZoomAndScroll() }
                    )
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(0.5.dp)
                            .background(Color.Black)
                    )
                }

                Spacer(Modifier.weight(1f))

                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                ToolbarButton(
                    onSelect = {
                        scope.launch {
                            History.moveHistory(UndoRedoType.Undo)
                            DrawCanvas.refreshUi.emit(Unit)
                        }
                    },
                    iconId = R.drawable.undo,
                    contentDescription = "undo"
                )

                ToolbarButton(
                    onSelect = {
                        scope.launch {
                            History.moveHistory(UndoRedoType.Redo)
                            DrawCanvas.refreshUi.emit(Unit)
                        }
                    },
                    iconId = R.drawable.redo,
                    contentDescription = "redo"
                )

                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                if (state.bookId != null) {
                    val book = AppRepository(context).bookRepository.getById(state.bookId)

                    // TODO maybe have generic utils for this ?
                    val pageNumber =
                        remember(state.pageId) { book!!.pageIds.indexOf(state.pageId) + 1 }
                    val totalPageNumber = book!!.pageIds.size

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(35.dp)
                            .padding(10.dp, 0.dp)
                    ) {
                        Text(
                            text = "${pageNumber}/${totalPageNumber}",
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.noRippleClickable {
                                navController.navigate("books/${state.bookId}/pages")
                            },
                            textAlign = TextAlign.Center
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(0.5.dp)
                            .background(Color.Black)
                    )
                }
                // Add Library Button
                ToolbarButton(
                    iconId = R.drawable.home, // Replace with your library icon resource
                    contentDescription = "library",
                    onSelect = {
                        navController.navigate("library") // Navigate to main library
                    }
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )
                Column {
                    ToolbarButton(
                        onSelect = {
                            state.menuStates.isMenuOpen = !state.menuStates.isMenuOpen
                        }, iconId = R.drawable.menu, contentDescription = "menu"
                    )
                    if (state.menuStates.isMenuOpen) ToolbarMenu(
                        navController = navController,
                        state = state,
                        onClose = { state.menuStates.isMenuOpen = false },
                        onBackgroundSelectorModalOpen = {
                            toolbarLog.i("Opening page settings modal")
                            state.menuStates.isBackgroundSelectorModalOpen = true
                        })
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.Black)
            )
        }
    } else {
        ToolbarButton(
            onSelect = { state.isToolbarOpen = true },
            iconId = presentlyUsedToolIcon(state.mode, state.pen),
            penColor = if (state.mode != Mode.Erase) state.penSettings[state.pen.penName]?.color?.let {
                Color(
                    it
                )
            } else null,
            contentDescription = "open toolbar",
            modifier = Modifier
                .height((BUTTON_SIZE + 51).dp)
                .padding(bottom = 50.dp)
        )
    }
}