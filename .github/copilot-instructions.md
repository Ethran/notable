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
| `data/` | Room DB (`db/`), DataStore (`datastore/`), `AppRepository`, `PageDataManager` |
| `editor/` | `canvas/`, `drawing/`, `state/` , `utils/`, `ui/`                             |
| `ui/` | `views/` (screens), `components/` (reusable), `theme/`                        |
| `io/` | Import/export engines                                                         |
| `navigation/` | `NotableNavHost`, `NavigationDestination`                                     |
| `utils/` | Generic shared helpers                                                        |

## Build & Test

```bash
./gradlew assembleDebug   # debug build (no signing required)
./gradlew test            # unit tests
```

Most tasks (code review, refactoring) do not require a build. Signed release builds are CI-only.
