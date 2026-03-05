# Copilot Instructions for Notable

**Notable** is an Android note-taking app for **Onyx BOOX e-ink tablets** (Kotlin · Jetpack Compose · Hilt · Room · OpenGL canvas · minSdk 29). Performance and responsiveness are the top priorities.

## Code Review Guidelines

- **Idiomatic Kotlin** — prefer `val`, data classes, scope functions; no dead code or unused imports.
- **Small, focused functions** — one responsibility per function; extract well-named helpers.
- **MVVM + Hilt** — all ViewModels use `@HiltViewModel`; never pass `Context` into a ViewModel.
- **Immutability** — update state via `copy()`, not direct field mutation.
- **Threading** — never block the OpenGL render thread or main thread with I/O; use coroutines.
- **Compose** — use `remember`/`derivedStateOf` to limit recomposition; keep Composables stateless.
- **Room migrations** — any `@Entity` change requires a DB version bump, a migration in `AppDatabase.kt`, and a new schema snapshot in `app/schemas/`. Run `MigrationTest`.
- **Package placement** — follow `docs/file-structure.md`. Never add code to `floatingEditor/` (unused/historical).

## Package Layout (`com.ethran.notable/`)

| Package | Contents |
|---|---|
| `data/` | Room DB (`db/`), DataStore (`datastore/`), `AppRepository`, `PageDataManager` |
| `editor/` | `canvas/`, `drawing/`, `state/` (sealed `EditorState`), `utils/`, `ui/` |
| `ui/` | `views/` (screens), `components/` (reusable), `theme/` |
| `io/` | Import/export engines |
| `navigation/` | `NotableNavHost`, `NavigationDestination` |
| `utils/` | Generic shared helpers |

## Build & Test

```bash
./gradlew assembleDebug   # debug build (no signing required)
./gradlew test            # unit tests
```

Most tasks (code review, refactoring) do not require a build. Signed release builds are CI-only.
