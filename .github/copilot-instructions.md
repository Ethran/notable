# Copilot Instructions for Notable

## Project Overview

**Notable** is a maintained fork of [olup/notable](https://github.com/olup/notable) — a digital note-taking Android app designed primarily for **Onyx BOOX e-ink tablets**. It provides handwriting, drawing, PDF annotation, image handling, and notebook organization on an infinite canvas. Performance and responsiveness are the top priorities: waiting for things to load is considered unacceptable.

---

## Technology Stack

| Area | Technology |
|---|---|
| **Language** | Kotlin (JVM 17, Kotlin 2.3.10) |
| **UI Framework** | Jetpack Compose (v1.10.2) |
| **Android API** | minSdk 29, targetSdk 35, compileSdk 36 |
| **Build System** | Gradle 8.5 (Groovy DSL) |
| **Dependency Injection** | Hilt 2.59.2 |
| **Database** | Room 2.8.4 (SQLite, UUID string PKs) |
| **Navigation** | Navigation Compose 2.9.7 |
| **PDF Rendering** | MuPDF/Fitz 1.26.10 |
| **Canvas Rendering** | OpenGL-based (performance-critical) |
| **E-Ink SDK** | Onyx BOOX SDK (onyxsdk-device, onyxsdk-pen, onyxsdk-base) |
| **Reactive** | RxJava2 2.2.21, RxAndroid 2.1.1 |
| **File Formats** | Apache Commons Compress (XOPP/GZIP), LZ4-Java |
| **Analytics** | Firebase Analytics, ShipBook SDK |
| **Testing** | JUnit 4, Espresso, Room Testing |

---

## Repository Layout

```
notable/
├── app/
│   ├── build.gradle               # Module build config, dependencies, signing
│   ├── gradle.properties          # IS_NEXT flag, debug keystore path
│   ├── proguard-rules.pro
│   ├── schemas/                   # Room auto-migration schema snapshots (JSON)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   └── java/com/ethran/notable/
│       │       ├── MainActivity.kt
│       │       ├── NotableApp.kt          # Hilt Application class
│       │       ├── data/                  # Data layer (Room DB, DataStore)
│       │       │   ├── db/                # Entities: Notebook, Page, Stroke, Image, Folder, Kv
│       │       │   ├── datastore/         # App settings, editor setting cache
│       │       │   ├── AppRepository.kt
│       │       │   └── PageDataManager.kt
│       │       ├── editor/                # Drawing and editing engine
│       │       │   ├── canvas/            # DrawCanvas, OnyxInputHandler, event bus
│       │       │   ├── drawing/           # Low-level rendering (OpenGL, stroke, PDF, backgrounds)
│       │       │   ├── state/             # EditorState machine (modes), SelectionState, history
│       │       │   ├── utils/             # Editor tools: Pen, Eraser, selection helpers, Provider
│       │       │   └── ui/                # Toolbar, PageMenu, SelectionUI
│       │       ├── ui/                    # Screens and navigation
│       │       │   ├── views/             # HomeView, PagesView, Settings, WelcomeView, LogView
│       │       │   ├── components/        # Reusable Compose components
│       │       │   └── theme/             # Color, Type, Shape
│       │       ├── io/                    # Import/export (PDF, PNG, JPEG, XOPP)
│       │       ├── gestures/              # Gesture handling
│       │       ├── navigation/            # NotableNavHost, NavigationDestination
│       │       └── utils/                 # Generic shared helpers
│       ├── test/                          # Unit tests
│       └── androidTest/
│           └── db/                        # MigrationTest, EncodingTest
├── docs/
│   ├── file-structure.md          # Code organization guide (authoritative)
│   ├── database-structure.md      # DB entities and stroke binary encoding spec
│   ├── export-formats.md
│   └── import-formats.md
├── .github/
│   └── workflows/
│       ├── release.yml            # Manual release build (assembleRelease)
│       └── preview.yml            # Auto-preview on push to dev branch (assembleDebug)
├── build.gradle                   # Root build file (plugins, compose version)
├── settings.gradle                # Gradle repos (google, mavenCentral, Onyx, JitPack, Ghostscript)
└── gradle.properties              # JVM args, AndroidX flags, Kotlin style
```

> See `docs/file-structure.md` for the authoritative code organization guide including where new code belongs.

---

## Building

### Local Debug Build (no signing required)
```bash
./gradlew assembleDebug
```

### Local Release Build (requires signing env vars)
```bash
export STORE_FILE=/path/to/keystore
export STORE_PASSWORD=<password>
export KEY_ALIAS=<alias>
export KEY_PASSWORD=<key_password>
./gradlew assembleRelease
```

### Print Version Name
```bash
./gradlew -q printVersionName
```

### Preview / "next" Build
```bash
./gradlew -PIS_NEXT=true assembleDebug
```
This appends a timestamp to the version name (e.g., `0.1.11-next-22.01.2025-14:30`).

### Required JDK
- Java 17+ (JDK 18 used in CI)
- Gradle 8.5

### Prerequisites for Full Builds
The following are only needed for signed builds and are injected via CI secrets:
- `STORE_FILE`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` — keystore for APK signing
- `SHIPBOOK_APP_ID`, `SHIPBOOK_APP_KEY` — logging service credentials
- `FIREBASE_CONFIG` — base64-encoded `google-services.json`

For local development, a `google-services.json` stub must be present in `app/`. You can skip Firebase-related features for local builds; the build will fall back to `"default-secret"` for ShipBook credentials.

---

## Testing

### Unit Tests
```bash
./gradlew test
```

### Instrumented Tests (requires a connected device or emulator)
```bash
./gradlew connectedAndroidTest
```

Tests are located in:
- `app/src/test/java/` — unit tests (e.g., `ExampleUnitTest.kt`)
- `app/src/androidTest/java/com/ethran/notable/db/` — DB migration tests (`MigrationTest.kt`), stroke encoding tests (`EncodingTest.kt`)

### Important: Room Schema Migrations
When modifying Room entities, you **must**:
1. Increment the database version in `AppDatabase.kt`.
2. Add an auto-migration entry or manual migration.
3. Export a new schema snapshot to `app/schemas/` (this happens automatically via `ksp` when building).
4. Run `MigrationTest` to validate the migration path.

---

## Key Architecture Patterns

### State Machine (Editor)
The editor is driven by a sealed class `EditorState` with modes such as `Idle`, `SelectionMode`, `PlacementMode`, etc., defined in `editor/state/`. Transitions are managed by `EditorControlTower`.

### Canvas & Rendering
- The main canvas is `DrawCanvas` (a Composable wrapping a native Android `View`).
- Rendering is **OpenGL-based** for performance — do not introduce synchronous or blocking operations on the render thread.
- E-ink partial screen refresh is managed via the Onyx SDK (`OnyxInputHandler`, `CanvasRefreshManager`).
- A `CanvasEventBus` decouples canvas update events from rendering.

### Data Layer
- **Room** entities use UUID strings (`jnanoid`) as primary keys.
- `Stroke.points` is stored as a custom binary SoA format (not JSON) — see `docs/database-structure.md` for the full spec.
- `PageDataManager` manages page cache, backgrounds, and invalidation.
- `AppRepository` handles cross-entity operations.

### Dependency Injection
- All ViewModels and repositories are Hilt-injected.
- The Hilt Application class is `NotableApp`.
- Annotate new ViewModels with `@HiltViewModel`.

### Navigation
- Navigation is handled by `NotableNavHost` using Navigation Compose.
- Destinations are sealed classes in `NavigationDestination.kt`.
- Deep links use the `notable://` scheme.

### Stroke Binary Format (SB1)
Stroke point lists are serialized using a custom Structure-of-Arrays binary format (magic: `SB`, version 1). Key points:
- Optional fields (pressure, tiltX, tiltY, dt) are uniform per-stroke (all present or all absent).
- Coordinates use Google Encoded Polyline (precision=2).
- LZ4 compression is applied when the body is ≥ 512 bytes and achieves ≥ 25% savings.
- See `docs/database-structure.md` for the complete spec.

---

## CI/CD

### Workflows
- **`preview.yml`**: Triggers on push to `dev` branch (when `app/**` changes). Builds a debug APK (`-PIS_NEXT=true`) and publishes it as the `next` pre-release on GitHub Releases.
- **`release.yml`**: Triggered manually via `workflow_dispatch`. Builds a signed release APK and creates a versioned GitHub Release (`v{VERSION}`).

### Secrets Required (CI)
`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `SHIPBOOK_APP_ID`, `SHIPBOOK_APP_KEY`, `FIREBASE_CONFIG`, `TOKEN`

---

## Common Development Tasks

### Adding a New Screen
1. Create a Composable in `app/src/main/java/com/ethran/notable/ui/views/`.
2. Add a destination to `NavigationDestination.kt`.
3. Register the route in `NotableNavHost`.

### Adding a New Database Entity or Field
1. Create/update the Room `@Entity` class in `data/db/`.
2. Add or update the corresponding `@Dao`.
3. Bump the database version and add a migration in `AppDatabase.kt`.
4. Run `./gradlew assembleDebug` to generate the new schema snapshot in `app/schemas/`.
5. Run `MigrationTest` to verify the migration.

### Adding a New Setting
- App-wide settings: `data/datastore/AppSettings.kt` + `GlobalAppSettings`.
- Editor settings: `data/datastore/EditorSettingCacheManager`.

### Adding a New Import/Export Format
- Add a handler to `io/ImportEngine.kt` or `io/ExportEngine.kt`.

### Onyx SDK Pen Tools
Some NeoTools (`NeoCharcoalPenV2`, `NeoMarkerPen`, `NeoBrushPen`) are disabled by default due to crashes. Take care when enabling or adding new Onyx pen tool types.

---

## Known Issues and Workarounds

- **`google-services.json` missing**: This file is git-ignored. For local builds without Firebase, place a minimal stub file at `app/google-services.json`. The build will not crash at compile time, but Firebase Analytics won't function.
- **Onyx SDK requires HiddenAPIBypass**: The dependency `org.lsposed.hiddenapibypass:hiddenapibypass:6.1` is required for Onyx SDK compatibility on newer Android versions. Do not remove it.
- **ShipBook dynamic version**: `io.shipbook:shipbooksdk:1.+` uses a dynamic version. This is intentional but can cause non-reproducible builds. Pin the version if strict reproducibility is needed.
- **Non-Onyx devices**: Handwriting (pen input) is not supported on non-Onyx devices. The app will install but the canvas will not accept stylus input.
- **LZ4 and `commons-compress` together**: Both are required — `commons-compress` for XOPP/GZIP, `lz4-java` for stroke point compression.
- **`floatingEditor/` package**: This package is an unused artifact from earlier development. Do not add new code here; it is kept only for historical reference.

---

## Documentation References

- `readme.md` — User-facing features, gestures, system requirements
- `docs/file-structure.md` — Code organization and where new code belongs
- `docs/database-structure.md` — Room entities, stroke binary encoding (SB1) spec
- `docs/export-formats.md` — Supported export formats
- `docs/import-formats.md` — Supported import formats

> Note: The docs under `docs/` were AI-generated and lightly reviewed. The code is the authoritative source of truth.
