# Project structure

A concise map of the codebase so you can find things fast and know where new code belongs.

app/src/main/AndroidManifest.xml
- Component declarations.

com.ethran.notable/
- data/ — Data and persistence
    - db/ — Room database (Db.kt), entities (Notebook, Page, Stroke, Image, Folder), and migrations.
    - datastore/ — App settings and editor cache (AppSettings.kt).
    - model/ — Common data types (BackgroundType, SimplePointF).
    - AppRepository.kt — Main entry point for data operations, coordinating multiple repositories.
    - PageDataManager.kt — Handles page-specific data, caching, and background loading.

- editor/ — The core note-taking engine
    - canvas/ — Hardware-specific drawing and refresh logic (DrawCanvas, OnyxInputHandler, RefreshManager).
    - drawing/ — Low-level rendering logic (OpenGLRenderer, Stroke/Line rendering, Page drawing).
    - state/ — Editor-specific state management:
        - History.kt — Undo/Redo logic.
        - Clipboard.kt — Copy/Paste support for strokes and images.
        - SelectionState.kt — Tracking selected elements.
        - EditorTypes.kt — Basic editor enums (Mode, PlacementMode).
    - ui/ — Editor-specific UI components (Topbar, PageMenu, Selector).
        - toolbar/ — Complex toolbar implementation and various tool buttons.
    - utils/ — Internal editor utilities (Pen/Eraser logic, Operations, Input handling).
    - EditorViewModel.kt — Orchestrates editor state and UI interactions.
    - EditorControlTower.kt — High-level coordination of editor components.
    - PageView.kt — The main composable representing a single page.

- io/ — File and bitmap I/O
    - ImportEngine.kt / ExportEngine.kt — Handling .xopp and PDF files.
    - PageContentRenderer.kt / ThumbnailGenerator.kt — Generating visuals from page data.
    - FileUtils.kt, share.kt — Generic file and sharing helpers.

- navigation/ — App-wide routing and navigation (NotableNavHost, NotableNavigator).

- ui/ — General application UI (Compose-based)
    - views/ — Main screen destinations (Home, Pages, Settings, Welcome).
    - viewmodels/ — Logic for the main screens.
    - components/ — Reusable UI widgets (QuickNav, NotebookCard, Settings elements).
    - dialogs/ — Modals for configuration and confirmation.
    - theme/ — App styling (Colors, Type, Theme).

- gestures/ — Custom gesture detection (QuickNav gestures, Editor receiver).

- di/ — Dependency injection (Hilt modules).

- utils/ — Generic helpers (Permissions, Debugging, Flow extensions).

app/schemas/com.ethran.notable.data.db.AppDatabase/
- Room schema snapshots (versioned JSON files).
