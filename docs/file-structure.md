# Project structure

A concise map of the codebase so you can find things fast and know where new code belongs.

app/src/main/AndroidManifest.xml
- Component declarations. Provider lives under editor/utils.

com.ethran.notable/
- data/ — Data and persistence
    - db/ — Room entities, DAOs, migrations, repositories
        - Add: new tables (Entity), DAOs, Repositories, migration specs
    - datastore/ — App/editor settings and cache
        - Add: AppSettings fields, GlobalAppSettings, EditorSettingCacheManager
    - model/ — Small data types used by data/db
    - AppRepository.kt — Cross-repository operations
    - PageDataManager.kt — Page cache, background handling, invalidation

- editor/ — Handles everything related to drawing
    - drawing/ — Low-level rendering (drawImage, drawStroke, etc.)
    - state/ — Editor state machine and modes (EditorState, Mode, PlacementMode)
    - utils/ — Editor tools/utilities (Pen, Eraser, NamedSettings, Provider, selection helpers)
    - DrawCanvas.kt, PageView.kt — Editor surfaces and helpers
    - Add: new tools/modes, rendering helpers, editor-only utilities

- floatingEditor/ — Not used; artifact from old development (kept for now)

- ui/ — Screens, navigation, common UI (Router, Snackbar system, permission helpers)
    - Add: new screens/components, routes in Router

- io/ — File/bitmap/background I/O helpers
    - Add: import/export, background loading, file ops

- utils/ — Small, generic helpers shared across features
    - Prefer feature-local utils (e.g., editor/utils) before adding here

app/schemas/com.ethran.notable.data.db.AppDatabase/
- Room schema snapshots for auto-migrations (versioned JSON files)