# Copilot Instructions for Notable

**Notable** is an Android note-taking app for **Onyx BOOX e-ink tablets** (Kotlin · Jetpack Compose · Hilt · Room · Onyx SDK raw drawing · MuPDF · WorkManager · minSdk 29). Performance and responsiveness are the top priorities. Architecture docs are in `docs/` (start with `docs/file-structure.md`, `docs/editor-data-flow.md` and `docs/editor-state-and-view.md`); contribution rules are in `CONTRIBUTING.md`.

## Code Review Guidelines

- **Idiomatic Kotlin** — prefer `val`, data classes, scope functions; no dead code or unused imports.
- **Small, focused functions** — one responsibility per function; extract well-named helpers.
- **MVVM + Hilt** — all ViewModels use `@HiltViewModel`; never pass `Context` into a ViewModel.
- **Immutability** — update state via `copy()`, not direct field mutation.
- **Threading** — never block the main thread or the canvas/render thread with I/O or database work; use coroutines. No allocations in per-point or per-frame loops (touch handling, stroke drawing).
- **E-ink refresh** — flag Onyx SDK or refresh calls that could add full-screen refreshes or flicker; changes to their order or timing need a test on a BOOX device.
- **Comments** — describe the code as it is; no "previously this did X" and no references to plans or review findings.
- **Compose** — use `remember`/`derivedStateOf` to limit recomposition; keep Composables stateless.
- **Room migrations** — any `@Entity` change requires a DB version bump in `data/db/Db.kt` (`AppDatabase`), an `AutoMigration` there or a manual migration in `data/db/Migrations.kt`, and a new schema snapshot in `app/schemas/`. Run `MigrationTest`.
- **Package placement** — follow `docs/file-structure.md`.
- **Compatibility with older versions:**
  - New sync or settings fields need defaults.
  - A missing incoming field must not clear the local value.
  - Renamed serialized properties need `@SerialName` with the old name.
  - Unknown enum or type values fall back to a default instead of throwing.
  - Per-device view state (scroll, zoom, fit mode) is not synced.
- **Paths** — no new absolute file paths stored in the database or sync; store them relative to the Notable folder (`dbDirPath()`).
- **Export** — output must not depend on screen size or orientation, and must not draw editor-only guides.
- **Page data** — address pages by page id, and call `pageSaver.saveAll()` before reading a page from the database. Stroke pressure is normalized to [0, 1].
- **Errors and logging** — return `AppResult`/`DomainError` instead of throwing across layers, show errors through `AppEventBus` snacks, and never log passwords, tokens or settings values.
- **Strings** — user-visible text goes in `values/strings.xml`, with Polish in `values-pl/`.


### Kotlin Best Practices

- **Avoid `!!`** — never force unwrap nullable values unless absolutely necessary; prefer `?.`, `?:`, or explicit checks.
- **Prefer extension functions** — reusable logic should be implemented as extensions rather than utility classes.
- **Use expression functions** — prefer single-expression functions when the body is simple.
- **Model state with sealed classes** — especially for UI state or result types.
- **Prefer standard library functions** — use Kotlin collection APIs (`map`, `filter`, `fold`, etc.) instead of manual loops when possible.
- **Use higher-order functions** — prefer functional transformations over imperative iteration.
- **Use smart casts** — rely on Kotlin type checks (`is`) instead of manual casting (`as`).
- **Use delegated properties** — for common patterns like lazy initialization (`by lazy`).
- **Consistent naming and APIs** — keep parameter ordering and naming predictable across functions.
- **Static analysis required** — keep code compatible with linting tools (e.g., ktlint/detekt); do not suppress warnings without justification.



### Android / Architecture Practices

- **Prefer `Flow` for reactive streams** — use Kotlin Flow instead of LiveData for new code.
- **Avoid passing large objects between layers** — pass IDs or lightweight DTOs instead of entire models when possible.



## Package Layout (`com.ethran.notable/`)

| Package | Contents                                                                      |
|---|-------------------------------------------------------------------------------|
| `data/` | Room DB (`db/`), settings (`datastore/`), `events/`, `AppRepository`, `PageDataManager`, `PageSaver`, `PagePreviewStore` |
| `editor/` | `canvas/` (surface, Onyx input, refresh), `drawing/`, `state/`, `utils/`, `ui/` (incl. `toolbar/`), `EditorViewModel`, `PageView` |
| `gestures/` | Touch gesture classification and actions                                     |
| `sync/` | WebDAV sync                                                                   |
| `di/` | Hilt modules                                                                  |
| `ui/` | `views/` (screens), `viewmodels/`, `components/` (reusable), `dialogs/`, `theme/` |
| `io/` | Import/export engines, PDF rendering, thumbnails                              |
| `navigation/` | `NotableNavHost`, `NavigationDestination`, `notable://` deep links            |
| `utils/` | Generic shared helpers                                                        |

## Build & Test

```bash
./gradlew assembleDebug   # debug build (no signing required)
./gradlew test            # unit tests
```

Most tasks (code review, refactoring) do not require a build. Signed release builds are CI-only.
