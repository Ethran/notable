# Project structure

A concise map of the codebase so you can find things fast and know where new code belongs.

app/src/main/AndroidManifest.xml
- Component declarations.

com.ethran.notable/
- MainActivity.kt — The single activity: storage check, deep links received, snack and sync bridges, Compose root.
- NotableApp.kt — The `Application` (Hilt entry point, crash handler, Onyx SDK setup).

- data/ — Data and persistence
    - db/ — Room database (Db.kt), entities (Notebook, Page, Stroke, Image, Folder, Kv, sync state), DAOs/repositories, migrations (Migrations.kt), stroke point encoding (EncodePolyline.kt, StrokePointConverter.kt, StrokeReencoding.kt).
    - datastore/ — App settings (AppSettings.kt, `GlobalAppSettings`) and the editor settings cache (EditorSettingCacheManager.kt).
    - events/ — `AppEvent` and `AppEventBus`: domain events turned into snacks by the UI (see `docs/snacks.md`).
    - model/ — Common data types (BackgroundType, SimplePointF).
    - AppRepository.kt — Main entry point for data operations, coordinating multiple repositories.
    - PageDataManager.kt — In-memory page content (strokes, images, backgrounds) by page id: loading, held pages, unsaved edits.
    - PageCache.kt — Cache sizing and eviction policy for PageDataManager.
    - PageMemoryReport.kt — Memory snapshot types for PageDataManager, shown in the debug memory view.
    - PageSaver.kt — Writes unsaved page edits to the database.
    - PagePreviewStore.kt — Writes the previews and thumbnails of the pages the editor leaves.
    - BackgroundFileWatcher.kt — Watches the image/PDF files pages use as backgrounds and reports changes.
    - dbUtils.kt — Location of the database directory.

- editor/ — The core note-taking engine
    - canvas/ — The drawing surface and its hardware-specific logic:
        - DrawCanvas.kt — The `SurfaceView` the editor draws on.
        - OnyxInputHandler.kt — Onyx raw drawing (pen input → strokes, erasing, lasso).
        - RawDrawingController.kt — The only owner of the Onyx pen's raw-drawing on/off state.
        - PenDevice.kt — The only caller of the Onyx `TouchHelper`'s raw-drawing switches and live pen style.
        - CanvasRefreshManager.kt — Copies the page bitmap to the surface; e-ink refresh.
        - CanvasObserverRegistry.kt — The flows the canvas reacts to (ViewModel state, CanvasEventBus).
        - CanvasEventBus.kt — App-wide canvas signals (redraw, refresh, resumed/focus state).
    - drawing/ — Rendering: page drawing (pageDrawing.kt), backgrounds (backgrounds.kt), dry-stroke renderers (StrokeRenderer.kt, AppStrokeRenderer.kt, onyx/ with the Onyx pen wrappers), pen styles (StrokeStyleRegistry.kt, penStrokes.kt), OpenGLRenderer/LineRenderer (front-buffered drawing on non-Onyx devices).
    - state/ — Editor-specific state management:
        - History.kt — Undo/Redo logic, per page.
        - Clipboard.kt — Copy/Paste support for strokes and images (`ClipboardStore`).
        - SelectionState.kt — Tracking selected elements.
        - EditorTypes.kt — Basic editor enums (Mode, PlacementMode, Shape).
    - ui/ — Editor-specific UI components (EditorSurface, Topbar, PageMenu, ScrollIndicator, selection bitmap and cues, Select dropdown).
        - toolbar/ — The data-driven toolbar (see `docs/toolbar-architecture.md`); model/ holds its elements, layout and pen presets.
    - utils/ — Internal editor utilities: drawing and erasing (draw.kt, eraser.kt), lasso selection (Select.kt), input conversion (handleInput.kt), pens (pen.kt), images (ImageHandler.kt), page previews and thumbnails (PreviewBitmapStore.kt), e-ink refresh modes (einkHelper.kt, EpdRefreshArbiter.kt), geometry and operations.
    - EditorView.kt — The editor screen (navigation destination); creates the PageView, History and EditorControlTower.
    - EditorViewModel.kt — Editor UI state (`ToolbarUiState`) and the actions on it.
    - EditorControlTower.kt — High-level coordination of editor components; the gesture actions.
    - PageView.kt — The editor's view of the shown page: page changes, scroll and zoom, the screen bitmap.
    - OpenPage.kt — Snapshot of the page a PageView shows.
    - PageLeases.kt — Leases on the pages the views show; keeps them held in PageDataManager.
    - PageViewport.kt — Scroll and zoom of a page.

- io/ — File and bitmap I/O
    - ImportEngine.kt / ExportEngine.kt — Import (.xopp, PDF) and export (PDF, PNG, JPEG, .xopp); see `docs/import-formats.md`, `docs/export-formats.md`.
    - XoppFile.kt, importPdf.kt, renderPdf.kt, renderFromFile.kt — Format-specific reading, writing and rendering.
    - PageContentRenderer.kt / ThumbnailGenerator.kt / ThumbnailBackfillQueue.kt — Generating visuals from page data.
    - FileUtils.kt, share.kt — Generic file and sharing helpers.

- navigation/ — App-wide routing: NotableNavHost, NotableNavigator, NavigationDestination; `notable://` links (DeepLink.kt format, DeepLinkResolver finds or imports the target, DeepLinkHandler opens it).

- ui/ — General application UI (Compose-based)
    - views/ — Main screen destinations (Home/library, Pages, Settings with SyncSettingsTab, Welcome, NewNotebook, bug report (LogView), SystemInformation).
    - viewmodels/ — Logic for the main screens.
    - components/ — Reusable UI widgets (NotableApp root, QuickNav, NotebookCard, PageCard, settings tabs, EInkButton, scaled dialogs/popups).
    - dialogs/ — Modals for configuration and confirmation.
    - theme/ — App styling (Colors, Type, Shape, Theme).
    - SnackBar.kt, SnackDispatcher.kt, AppEventUiBridge.kt, SyncWorkUiBridge.kt — Snackbars (see `docs/snacks.md`).

- gestures/ — Touch gesture recognition: EditorGestureReceiver (editor finger gestures), GestureClassifier, PointerTracker, GestureThresholds, GestureActions (what a gesture triggers), QuickNavGesture (app-wide three-finger swipe).

- sync/ — WebDAV synchronization (SyncOrchestrator, SyncWorker/SyncScheduler, WebDAVClient, serializers/; see `docs/webdav-sync-technical.md`).

- di/ — Dependency injection (Hilt modules: coroutine scopes and dispatchers, AppEventBus, SnackDispatcher).

- utils/ — Generic helpers (AppResult/ErrorAccumulator, permissions, debugging, timing, flow extensions, version check).

app/schemas/com.ethran.notable.data.db.AppDatabase/
- Room schema snapshots (versioned JSON files).
