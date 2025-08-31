package com.ethran.notable.editor.ui

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ethran.notable.editor.state.DOUBLE_TAP_MIN_MS
import com.ethran.notable.editor.state.DOUBLE_TAP_TIMEOUT_MS
import com.ethran.notable.editor.DrawCanvas
import com.ethran.notable.editor.EditorControlTower
import com.ethran.notable.editor.state.GestureMode
import com.ethran.notable.editor.state.GestureState
import com.ethran.notable.editor.state.HOLD_THRESHOLD_MS
import com.ethran.notable.editor.state.PINCH_ZOOM_THRESHOLD
import com.ethran.notable.editor.state.SWIPE_THRESHOLD
import com.ethran.notable.ui.showHint
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.state.UndoRedoType
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

private val log = ShipBook.getLogger("GestureReceiver")


@Composable
@ExperimentalComposeUiApi
fun EditorGestureReceiver(
    goToNextPage: () -> String?,
    goToPreviousPage: () -> String?,
    controlTower: EditorControlTower,
    state: EditorState
) {

    val coroutineScope = rememberCoroutineScope()
    val appSettings = remember { GlobalAppSettings.current }
    var crossPosition by remember { mutableStateOf<IntOffset?>(null) }
    var rectangleBounds by remember { mutableStateOf<Rect?>(null) }
    val view = LocalView.current
    Box(
        modifier = Modifier
            // TODO: Change to // .pointerInteropFilter { ev -> ……}
            // for now it consumes all gestures - even stylus one.
            .pointerInput(Unit) {
                awaitEachGesture {
                    try {
                        // Detect initial touch
                        val down = awaitFirstDown()

                        // Ignore non-touch input
                        if (down.type == PointerType.Stylus) {
                            log.i("Redirecting stylus input")

                            // TODO: It's only temporary workaround.
                            // Track all moves until the stylus is lifted
                            do {
                                val event = awaitPointerEvent()
                                val stylus =
                                    event.changes.firstOrNull { it.type == PointerType.Stylus }
                                stylus?.let {
                                    coroutineScope.launch {
                                        DrawCanvas.eraserTouchPoint.emit(it.position)
                                    }
                                    it.consume()
                                }
                            } while (stylus?.pressed == true)

                            return@awaitEachGesture
                        }

                        // testing if it will fixed exception:
                        // kotlinx.coroutines.CompletionHandlerException: Exception in resume
                        // onCancellation handler for CancellableContinuation(DispatchedContinuation[AndroidUiDispatcher@145d639,
                        // Continuation at androidx.compose.foundation.gestures.PressGestureScopeImpl.reset(TapGestureDetector.kt:357)
                        // @8b7a2c]){Completed}@4a49cf5
                        if (!coroutineScope.isActive) return@awaitEachGesture
                        // if window lost focus, ignore input
                        if (!view.hasWindowFocus()) return@awaitEachGesture

                        val gestureState = GestureState(scope = coroutineScope)
                        var overdueScroll = Offset.Zero

                        // Ignore non-touch input
                        if (down.type != PointerType.Touch) {
                            log.i("Ignoring non-touch input")
                            return@awaitEachGesture
                        }
                        gestureState.initialTimestamp = System.currentTimeMillis()
                        gestureState.insertPosition(down)

                        do {
                            // wait for second gesture
                            val event =
                                withTimeoutOrNull(HOLD_THRESHOLD_MS.toLong()) { awaitPointerEvent() }
                            if (!coroutineScope.isActive) return@awaitEachGesture
                            // if window lost focus, ignore input
                            if (!view.hasWindowFocus()) return@awaitEachGesture

                            if (event != null) {
                                val fingerChange =
                                    event.changes.filter { it.type == PointerType.Touch }

                                // is already consumed return
                                if (fingerChange.find { it.isConsumed } != null) {
                                    log.i("Canceling gesture - already consumed")
                                    if (gestureState.gestureMode == GestureMode.Selection) {
                                        crossPosition = null
                                        rectangleBounds = null
                                        gestureState.gestureMode = GestureMode.Normal
                                        if (!state.isDrawing)
                                            state.isDrawing = true
                                    }
                                    return@awaitEachGesture
                                }
                                fingerChange.forEach { change ->
                                    // Consume changes and update positions
                                    change.consume()
                                    gestureState.insertPosition(change)
                                }
                                if (fingerChange.any { !it.pressed }) {
                                    gestureState.lastTimestamp = System.currentTimeMillis()
                                    break
                                }
                            }
                            // events are only send on change, so we need to check for holding in place separately
                            gestureState.lastTimestamp = System.currentTimeMillis()
                            if (gestureState.gestureMode == GestureMode.Selection) {
                                crossPosition = gestureState.getLastPositionIO()
                                rectangleBounds = gestureState.calculateRectangleBounds()
                            } else {
                                // set selection mode
                                if (gestureState.isHoldingOneFinger()) {
                                    gestureState.gestureMode = GestureMode.Selection
                                    state.isDrawing = false // unfreeze the screen
                                    crossPosition = gestureState.getLastPositionIO()
                                    rectangleBounds = gestureState.calculateRectangleBounds()
                                    showHint("Selection mode!", coroutineScope, 1500)
                                }
                                gestureState.checkSmoothScrolling()
                                gestureState.checkContinuousZoom()
                                if(gestureState.checkHoldingTwoFingers())
                                    showHint("Drag mode!", coroutineScope, 1500)

                            }
                            if (gestureState.gestureMode == GestureMode.Scroll) {
                                val delta = gestureState.getVerticalDragDelta()
                                overdueScroll = controlTower.processScroll(
                                    delta = Offset(overdueScroll.x, overdueScroll.y + delta)
                                )
                            }
                            if (gestureState.gestureMode == GestureMode.Zoom) {
                                val delta = gestureState.getPinchDelta()
                                controlTower.onPinchToZoom(delta, gestureState.getPinchCenter())
                            }

                            if (gestureState.gestureMode == GestureMode.Drag) {
                                val delta = gestureState.getTotalDragDelta()
                                overdueScroll =
                                    controlTower.processScroll(delta = overdueScroll + delta)
                            }

                        } while (true)

                        when (gestureState.gestureMode) {
                            GestureMode.Selection -> {
                                resolveGesture(
                                    settings = appSettings,
                                    default = AppSettings.defaultHoldAction,
                                    override = AppSettings::holdAction,
                                    state = state,
                                    scope = coroutineScope,
                                    previousPage = goToPreviousPage,
                                    nextPage = goToNextPage,
                                    rectangle = rectangleBounds!!,
                                    controlTower = controlTower
                                )
                                crossPosition = null
                                rectangleBounds = null
                                gestureState.gestureMode = GestureMode.Normal
                                if (!state.isDrawing)
                                    state.isDrawing = true
                                return@awaitEachGesture
                            }

                            GestureMode.Scroll -> {
                                gestureState.gestureMode =
                                    GestureMode.Normal // return screen updates to normal.
                                return@awaitEachGesture
                            }

                            GestureMode.Zoom, GestureMode.Drag -> {
                                log.d("Zoom or drag -- final redraw")
                                coroutineScope.launch {
                                    // we need to redraw if we zoomed in only -- for now we will just always redraw after exiting gesture.
                                    DrawCanvas.forceUpdate.emit(null)
                                }
                                gestureState.gestureMode =
                                    GestureMode.Normal // return screen updates to normal.
                                return@awaitEachGesture
                            }

                            GestureMode.Normal -> {}
                        }

                        if (!coroutineScope.isActive) return@awaitEachGesture
                        // if window lost focus, ignore input
                        if (!view.hasWindowFocus()) return@awaitEachGesture


                        if (gestureState.isOneFinger()) {
                            if (gestureState.isOneFingerTap()) {
                                if (withTimeoutOrNull(DOUBLE_TAP_TIMEOUT_MS) {
                                        val secondDown = awaitFirstDown()
                                        val deltaTime =
                                            System.currentTimeMillis() - gestureState.lastTimestamp
                                        log.v("Second down detected: ${secondDown.type}, position: ${secondDown.position}, deltaTime: $deltaTime")
                                        if (deltaTime < DOUBLE_TAP_MIN_MS) {
                                            showHint(
                                                text = "Too quick for double click! time between: $deltaTime",
                                                coroutineScope
                                            )
                                            return@withTimeoutOrNull null
                                        } else {
                                            log.v("double click!")
                                        }
                                        if (secondDown.type != PointerType.Touch) {
                                            log.i("Ignoring non-touch input during double-tap detection")
                                            return@withTimeoutOrNull null
                                        }
                                        resolveGesture(
                                            settings = appSettings,
                                            default = AppSettings.defaultDoubleTapAction,
                                            override = AppSettings::doubleTapAction,
                                            state = state,
                                            scope = coroutineScope,
                                            previousPage = goToPreviousPage,
                                            nextPage = goToNextPage,
                                            controlTower = controlTower
                                        )


                                    } != null) return@awaitEachGesture
                            }
                        } else if (gestureState.isTwoFingers()) {
                            log.v("Two finger tap")
                            if (gestureState.isTwoFingersTap()) {
                                resolveGesture(
                                    settings = appSettings,
                                    default = AppSettings.defaultTwoFingerTapAction,
                                    override = AppSettings::twoFingerTapAction,
                                    state = state,
                                    scope = coroutineScope,
                                    previousPage = goToPreviousPage,
                                    nextPage = goToNextPage,
                                    controlTower = controlTower
                                )
                            }
                            // zoom gesture
                            val zoomDelta = gestureState.getPinchDrag()
                            if (!appSettings.continuousZoom && abs(zoomDelta) > PINCH_ZOOM_THRESHOLD) {
                                controlTower.onPinchToZoom(zoomDelta, Offset(0f,0f))
                                log.d("Discrete zoom: $zoomDelta")
                            }
                        }

                        val horizontalDrag = gestureState.getHorizontalDrag()
                        val verticalDrag = gestureState.getVerticalDrag()

                        log.v("horizontalDrag $horizontalDrag, verticalDrag $verticalDrag")


                        if (gestureState.gestureMode == GestureMode.Normal) {
                            if (horizontalDrag < -SWIPE_THRESHOLD)
                                resolveGesture(
                                    settings = appSettings,
                                    default = if (gestureState.getInputCount() == 1) AppSettings.defaultSwipeLeftAction else AppSettings.defaultTwoFingerSwipeLeftAction,
                                    override = if (gestureState.getInputCount() == 1) AppSettings::swipeLeftAction else AppSettings::twoFingerSwipeLeftAction,
                                    state = state,
                                    scope = coroutineScope,
                                    previousPage = goToPreviousPage,
                                    nextPage = goToNextPage,
                                    controlTower = controlTower
                                )
                            else if (horizontalDrag > SWIPE_THRESHOLD)
                                resolveGesture(
                                    settings = appSettings,
                                    default = if (gestureState.getInputCount() == 1) AppSettings.defaultSwipeRightAction else AppSettings.defaultTwoFingerSwipeRightAction,
                                    override = if (gestureState.getInputCount() == 1) AppSettings::swipeRightAction else AppSettings::twoFingerSwipeRightAction,
                                    state = state,
                                    scope = coroutineScope,
                                    previousPage = goToPreviousPage,
                                    nextPage = goToNextPage,
                                    controlTower = controlTower
                                )
                        }
                        if (!GlobalAppSettings.current.smoothScroll && gestureState.isOneFinger()
                            && abs(verticalDrag) > SWIPE_THRESHOLD
                        ) {
                            log.d("Discrete scrolling, verticalDrag: $verticalDrag")
                            controlTower.processScroll(Offset(0f, verticalDrag))
                        }
                    } catch (e: CancellationException) {
                        log.w("Gesture coroutine canceled", e)
                    } catch (e: Exception) {
                        log.e("Unexpected error in gesture handling", e)
                    }
                }
            }

            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        val density = LocalDensity.current
        // Draw cross where finger is touching
        DrawCross(crossPosition, density)
        // Draw the rectangle while dragging
        DrawRectangle(rectangleBounds, density)
    }
}

@Composable
private fun DrawRectangle(rectangleBounds: Rect?, density: Density) {
    rectangleBounds?.let { bounds ->
        // Draw the rectangle
        Box(
            Modifier
                .offset { IntOffset(bounds.left, bounds.top) }
                .size(
                    width = with(density) { (bounds.right - bounds.left).toDp() },
                    height = with(density) { (bounds.bottom - bounds.top).toDp() }
                )
                // Is there rendering speed difference between colors?
                .background(Color(0x55000000))
                .border(1.dp, Color.Black)
        )
    }

}

@Composable
private fun DrawCross(crossPosition: IntOffset?, density: Density) {

    // Draw cross where finger is touching
    crossPosition?.let { pos ->
        val crossSizePx = with(density) { 100.dp.toPx() }
        Box(
            Modifier
                .offset {
                    IntOffset(
                        pos.x - (crossSizePx / 2).toInt(),
                        pos.y
                    )
                } // Horizontal bar centered
                .size(width = 100.dp, height = 2.dp)
                .background(Color.Black)
        )
        Box(
            Modifier
                .offset {
                    IntOffset(
                        pos.x,
                        pos.y - (crossSizePx / 2).toInt()
                    )
                } // Vertical bar centered
                .size(width = 2.dp, height = 100.dp)
                .background(Color.Black)
        )
    }
}

private fun resolveGesture(
    settings: AppSettings?,
    default: AppSettings.GestureAction,
    override: AppSettings.() -> AppSettings.GestureAction?,
    state: EditorState,
    scope: CoroutineScope,
    previousPage: () -> String?,
    nextPage: () -> String?,
    rectangle: Rect = Rect(),
    controlTower: EditorControlTower
) {
    when (if (settings != null) override(settings) else default) {
        null -> log.i("No Action")
        AppSettings.GestureAction.PreviousPage -> {
            val previous = previousPage()
            if (previous != null) {
                controlTower.switchPage(previous)
            }
        }
        AppSettings.GestureAction.NextPage ->
        {
            val next = nextPage()
            if (next!= null) {
                controlTower.switchPage(next)
            }
        }

        AppSettings.GestureAction.ChangeTool ->
            state.mode = if (state.mode == Mode.Draw) Mode.Erase else Mode.Draw

        AppSettings.GestureAction.ToggleZen ->
            state.isToolbarOpen = !state.isToolbarOpen

        AppSettings.GestureAction.Undo -> {
            log.i("Undo")
            scope.launch {
                History.moveHistory(UndoRedoType.Undo)
//                moved to history operation - avoids unnecessary refresh, and ensures that it will be done after drawing.
//                DrawCanvas.refreshUi.emit(Unit)
            }
        }

        AppSettings.GestureAction.Redo -> {
            log.i("Redo")
            scope.launch {
                History.moveHistory(UndoRedoType.Redo)
//                DrawCanvas.refreshUi.emit(Unit)
            }
        }

        AppSettings.GestureAction.Select -> {
            log.i("select")
            scope.launch {
//                log.w( "rect in screen coord: $rectangle")
                DrawCanvas.rectangleToSelect.emit(rectangle)
            }
        }
    }
}

