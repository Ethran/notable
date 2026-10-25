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
    - Manages state flows for UI such as currently selected pen, eraser settings, and editor mode.
    - Tracks the current `pageId` and `notebookId` being edited.
    - Injects required global state components and caches user preferences.
    - Once the `PageView` shows a page (`onPageShown`), cancels loads of pages left behind and
      prefetches the neighbouring pages through `PageDataManager`.
    - When the editor closes, asks `PageSaver` to write the remaining edits right away.

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
)
```

- **Role**:
    - Coordinates between `PageView`, `EditorViewModel`, and the undo/redo `History`. Each page has its own history: every step is recorded, when it is made, in the history of the page it was made on. Strokes drawn less than 150 ms apart share a step. Only the three pages most recently edited or undone on keep theirs.
    - Handles tool interactions (selecting, moving strokes).
    - Orchestrates operations like scrolling, panning, and background manipulation.

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
)
```

- **Responsibilities**:
    - **The shown page.** `openPage` is an immutable `OpenPage` snapshot (page id, `Page` record, position in the notebook), replaced whole on a page change. Read it once into a local to use several fields together. `changePage(pageId)` requests a page; changes run one at a time, and only the last of several requests is shown.
    - **Holding the page.** While a page is shown, `PageView` holds its `PageLease` from `PageLeases`, so `PageDataManager` holds the page and never evicts it. `disposeOldPage()` releases it; a disposed view shows, draws and loads nothing more.
    - **Scroll and zoom.** `viewport` is a `StateFlow<PageViewport>` (`scroll`, `zoom`) of the shown page; `scroll` and `zoom` read its current value. Pages left in this editor keep their viewport and get it back when shown again; a page shown for the first time opens unzoomed at its saved vertical scroll. Pages are read off the main thread and swapped in on it, together with their viewport and screen bitmap; scroll and zoom steps run there too, each for the page its gesture was made on, and a step for a page no longer shown is dropped.
    - **Content height.** `contentHeight` is a `StateFlow<Int>` with the bottom of the page's strokes and images, for the scroll indicator.
    - **Editing.** Adds, updates and removes strokes and images of the shown page through `PageDataManager`, always naming the page id.
    - **Coordinates.** Translates between screen and page coordinates using the viewport.
    - Provides the screen bitmap (`windowedBitmap`) that the drawing surface (`DrawCanvas`) shows.

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
    - Keeps pen input with the page it was made on: the page is recorded when the pen goes down, and a stroke or erase whose page is no longer shown when its points arrive is dropped. Pen callbacks and page swaps both run on the main thread.
