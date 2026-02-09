package com.ethran.notable.editor

import android.graphics.Rect
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex

object CanvasEventBus {
    var forceUpdate = MutableSharedFlow<Rect?>() // null for full redraw
    var refreshUi = MutableSharedFlow<Unit>()
    var refreshUiImmediately = MutableSharedFlow<Unit>(
        replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    var isDrawing = MutableSharedFlow<Boolean>()
    var restartAfterConfChange = MutableSharedFlow<Unit>()

    // used for managing drawing state on regain focus
    val onFocusChange = MutableSharedFlow<Boolean>()

    // before undo we need to commit changes
    val commitHistorySignal = MutableSharedFlow<Unit>()
    val commitHistorySignalImmediately = MutableSharedFlow<Unit>()

    // used for checking if commit was completed
    var commitCompletion = CompletableDeferred<Unit>()

    // It might be bad idea, but plan is to insert graphic in this, and then take it from it
    // There is probably better way
    var addImageByUri = MutableStateFlow<Uri?>(null)
    var rectangleToSelectByGesture = MutableStateFlow<Rect?>(null)
    var drawingInProgress = Mutex()

    // For cleaning whole page, activated from toolbar menu
    var clearPageSignal = MutableSharedFlow<Unit>()


    // For QuickNav scrolling with previews
    val saveCurrent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val previewPage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val restoreCanvas = MutableSharedFlow<Unit>(extraBufferCapacity = 1)


    val changePage = MutableSharedFlow<String>(extraBufferCapacity = 1)
}