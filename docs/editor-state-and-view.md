# Editor State and View Management

This document explains how the editor identifies which page to display and how the view components are managed.

**It was created by AI, and should be checked for correctness. Refer to code for actual implementation.**

Contents:
- `EditorViewModel`
- `EditorControlTower`
- `PageView`
- `DrawCanvas`

---

## `EditorViewModel`

The `EditorViewModel` is an Android `ViewModel` that holds the editor UI state and integrates with the `PageDataManager` to manage data loading and saving.

- **Role**:
    - Manages state flows for UI such as currently selected pen, eraser settings, and editor mode.
    - Tracks the current `pageId` and `notebookId` being edited.
    - Injects required global state components and caches user preferences.

---

## `EditorControlTower`

The `EditorControlTower` is the central coordinator for the editor logic, bridging UI interactions, gestures, and canvas drawing.

```kotlin
class EditorControlTower(
    private val scope: CoroutineScope,
    val page: PageView,
    private var history: History,
    private val viewModel: EditorViewModel,
    private val clipboardStore: ClipboardStore,
)
```

- **Role**:
    - Coordinates between `PageView`, `EditorViewModel`, and the undo/redo `History`.
    - Handles tool interactions (selecting, moving strokes).
    - Orchestrates operations like scrolling, panning, and background manipulation.

---

## `PageView`

`PageView` manages the data representing the current visual frame of the document being edited. It is responsible for setting up the spatial properties and handling interactions in coordinate space.

```kotlin
class PageView(
    val context: Context,
    val coroutineScope: CoroutineScope,
    val pageDataManager: PageDataManager,
    val initialPageId: String,
    var viewWidth: Int,
    var viewHeight: Int,
    val snackManager: SnackState,
)
```

- **Responsibilities**:
    - Translates screen inputs to logical coordinates on the page.
    - Manages the size and offset (pan/zoom level) of the page view.
    - Serves as the middle layer that provides the current geometry structure to the actual drawing surface (`DrawCanvas`).

---

## `DrawCanvas`

The `DrawCanvas` component, now residing in `canvas/DrawCanvas.kt`, handles the low-level rendering.

```kotlin
class DrawCanvas(
    context: Context,
    val coroutineScope: CoroutineScope,
    val viewModel: EditorViewModel,
    val page: PageView,
    val history: History
) : SurfaceView(context)
```

- **Responsibilities**:
    - Implements a `SurfaceView` optimized for high-performance drawing.
    - Renders the strokes, images, and background.
    - Tracks touch events, including distinguishing between stylus and eraser (`MotionEvent.TOOL_TYPE_STYLUS` / `TOOL_TYPE_ERASER`), and dispatching drawing operations correctly.
