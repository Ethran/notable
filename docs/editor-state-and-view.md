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

The `EditorViewModel` is an Android `ViewModel` that holds the editor UI state.

- **Role**:
    - Holds the editor UI state in one flat `StateFlow<ToolbarUiState>` (`toolbarState`): selected pen preset and its settings, eraser, editor mode, open menus, the current `pageId` and `notebookId`, and more.
    - Takes user actions as `ToolbarAction`s (`onToolbarAction`), sends imperative drawing work to the canvas as `CanvasCommand`s (`canvasCommands`) and navigation as `EditorUiEvent`s.
    - `ToolbarUiState.isDrawing` (whether the pen draws, i.e. raw drawing is on) is derived, never stored: it is true while the activity is resumed and focused, no menu, stroke menu, background selector or QuickNav is open, and nothing is selected or being selected. To change it, change one of its inputs.
    - Owns the `SelectionState` (`selectionState`), which is the only writer of `ToolbarUiState.isSelectionActive`.
    - Injects required global state components and caches user preferences.
    - Once the `PageView` shows a page (`onPageShown`), cancels loads of pages left behind and
      prefetches the neighbouring pages through `PageDataManager`. When the view leaves a page (`onPageLeft`), asks `PagePreviewStore` for its preview.
    - When the editor closes (`onDispose`), releases the selection, disposes the `PageView`, asks `PageSaver` to write the remaining edits right away and starts the sync-on-close.

See [editor-data-flow.md](editor-data-flow.md) for how page content is loaded, cached and saved.

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
) : GestureActions
```

- **Role**:
    - Coordinates between `PageView`, `EditorViewModel`, and the undo/redo `History`. Each page has its own history: every step is recorded, when it is made, in the history of the page it was made on. Strokes drawn less than 150 ms apart share a step. Only the three pages most recently edited or undone on keep theirs.
    - Implements `GestureActions`, the actions the gesture code (`EditorGestureReceiver`) triggers: scroll, pinch zoom, page turns, undo/redo, rectangle selection, redraw.
    - Handles selection operations (move, resize, delete, duplicate, cut/copy/paste through `ClipboardStore`).
    - Orchestrates scrolling and zooming; each scroll or zoom step is for the page its gesture was made on.

---

## `PageView`

`PageView` is the editor's view of one page at a time: which page is shown, how it is scrolled and zoomed, and the screen bitmap it is drawn into.

```kotlin
class PageView(
    val context: Context,
    val coroutineScope: CoroutineScope,
    val pageDataManager: PageDataManager,
    private val pageLeases: PageLeases,
    private val appRepository: AppRepository,
    val initialPageId: String,
    var viewWidth: Int,
    var viewHeight: Int,
    val snackManager: SnackState,
    private val onPageShown: (pageId: String) -> Unit = {},
    private val onPageLeft: (pageId: String) -> Unit = {},
)
```

- **Responsibilities**:
    - **The shown page.** `openPage` is an immutable `OpenPage` snapshot (page id, `Page` record, position in the notebook), replaced whole on a page change. Read it once into a local to use several fields together. `changePage(pageId)` requests a page; changes run one at a time, and only the last of several requests is shown.
    - **Holding the page.** While a page is shown, `PageView` holds its `PageLease` from `PageLeases`, so `PageDataManager` holds the page and never evicts it. `disposeOldPage()` releases it; a disposed view shows, draws and loads nothing more.
    - **Callbacks.** `onPageShown` runs once a page holds its lease; `onPageLeft` runs once the screen bitmap of a page left (or of the page shown at disposal) is on the shelf. Both run on the main thread under the view's page lock, so they only hand the id on.
    - **Scroll and zoom.** `viewport` is a `StateFlow<PageViewport>` (`scroll`, `zoom`) of the shown page; `scroll` and `zoom` read its current value. Pages left in this editor keep their viewport and get it back when shown again; a page shown for the first time opens unzoomed at its saved vertical scroll. Pages are read off the main thread and swapped in on it, together with their viewport and screen bitmap; scroll and zoom steps run there too, each for the page its gesture was made on, and a step for a page no longer shown is dropped.
    - **Content height.** `contentHeight` is a `StateFlow<Int>` with the bottom of the page's strokes and images, for the scroll indicator.
    - **Editing.** Adds, updates and removes strokes and images of the shown page through `PageDataManager`, always naming the page id.
    - **Coordinates.** Translates between screen and page coordinates using the viewport.
    - Provides the screen bitmap (`windowedBitmap`) that the drawing surface (`DrawCanvas`) shows.

---

## `DrawCanvas`

The `DrawCanvas` (`editor/canvas/DrawCanvas.kt`) is the drawing surface, created by the `EditorSurface` composable.

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
    - Implements a `SurfaceView` that shows the `PageView`'s screen bitmap. The page content is drawn into that bitmap (`editor/drawing/pageDrawing.kt`); `CanvasRefreshManager` copies it to the surface and drives the e-ink refresh.
    - `OnyxInputHandler` runs the Onyx raw drawing (`TouchHelper`) and turns pen input into strokes, erasing and lasso selection. Raw drawing is on exactly while `ToolbarUiState.isDrawing` is true; `CanvasObserverRegistry` collects that and the other `EditorViewModel` / `CanvasEventBus` flows the canvas reacts to.
    - Consumes stylus and eraser touch events (`MotionEvent.TOOL_TYPE_STYLUS` / `TOOL_TYPE_ERASER`) so Compose underneath never sees them. On non-Onyx devices they go to `OpenGLRenderer`, a front-buffered OpenGL renderer.
    - Keeps pen input with the page it was made on: the page is recorded when the pen goes down, and a stroke or erase whose page is no longer shown when its points arrive is dropped. Pen callbacks and page swaps both run on the main thread.
