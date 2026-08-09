# CLAUDE.md — Notable

Android handwritten-notes app for **Onyx BOOX e-ink tablets**. Kotlin · Jetpack Compose · Hilt ·
Room · Onyx pen SDK. Performance and correctness on e-ink hardware shape every design decision.

## Read first

Two instruction files already exist and take precedence over anything here:

- **`.github/copilot-instructions.md`** — Kotlin style, MVVM/Hilt rules, package layout, review
  guidelines. Follow it.
- **`instructions.md`** — testing expectations and what must have coverage before a feature is
  "done".

This file covers what those two don't: the hardware constraints, the SDK hazards, and the
conventions that only show up by reading the code.

Docs in `docs/` are useful but carry an explicit *"created by AI, should be checked for
correctness — refer to code for actual implementation"* disclaimer. **Trust the code over the
docs.**

---

## 1. Hardware constraints (non-negotiable)

These are properties of the device, not choices. Code that violates them fails on-device while
looking correct in review.

### `TouchHelper` is a process-wide singleton

Raw-draw mode is global state of the EPD controller. Exactly one `SurfaceView` in the process
owns live ink. `DrawCanvas.kt:20` tracks the current owner in a top-level `var`:

```kotlin
// keep reference of the surface view presently associated to the singleton touchhelper
var referencedSurfaceView: String = ""
```

Never assume two canvases can both take pen input. Never create a second `TouchHelper`.

### Onyx SDK call ordering is load-bearing

Several SDK calls silently reset state that a previous call configured. The code documents these
where found — respect them and add to them:

- `setRawDrawingEnabled(true)` **resets the framework stroke config to firmware defaults**
  (brush channel on, eraser channel off). Anything style-related must be re-asserted *after* it —
  see `einkHelper.kt:171` and `OnyxInputHandler.kt:192`.
- `enableNativeEraser()` **MUST** follow every `setRawDrawingEnabled(true)` (`einkHelper.kt:223`).
- `setupSurface()` resets stroke style, so `updatePenAndStroke()` must run after it *inside the
  same coroutine* — otherwise a caller racing the `launch` overwrites the style
  (`OnyxInputHandler.kt:221`).

**When you discover a new ordering hazard, write it as a comment at the call site explaining
*why*, not just *what*.** That is the house style (§3).

### Guard Onyx-only paths

`DeviceCompat.isOnyxDevice` gates SDK use (8 call sites). `TouchHelper.create` is wrapped in
`try`/`catch` returning null, and every use is null-checked. Non-Onyx devices fall back to
`OpenGLRenderer`. Keep both paths working — issue #211 item 1 plans to separate them further.

### E-ink refresh is expensive and visible

Prefer targeted dirty rects over full redraws. Refresh modes go through `EpdController`
(`einkHelper.kt`). Unnecessary full-screen refreshes are a *user-visible defect* here, not a
micro-optimisation.

---

## 2. Missing documentation — do not trust these paths

Six of the seven `docs/*.md` files referenced from code comments **do not exist in the repo**.
They are not gitignored and were never committed; the maintainer appears to hold them locally.

```
MISSING: docs/crash-handling-plan.md              (referenced 6+ times)
MISSING: docs/onyx-sdk/onyx-native-eraser-indicator.md
MISSING: docs/onyx-sdk/onyx-neo-fountain-pen-v2.md
MISSING: docs/onyx-sdk/onyx-pen-styles-catalog.md
MISSING: docs/onyx-sdk/onyx-pen-up-refresh-and-screen-freeze.md
MISSING: docs/onyx-sdk/onyx-scribble-to-erase.md
PRESENT: docs/result-and-error-handling.md
```

If a comment points at one of these, the reasoning is **not** recoverable from the repo — derive
it from the code and say so rather than inventing a citation. Do not add new references to
non-existent docs.

---

## 3. Comment style

The distinctive convention here: comments explain **why**, cite the firmware behaviour that forced
the code, and link a doc. Match it, especially in `editor/canvas/` and `editor/utils/`.

```kotlin
// Re-assert the native eraser indicator because setRawDrawingEnabled(true) (called
// on every resume) resets it to disabled internally. The track style follows the active
// eraser type: the wide marker (style 8) for the pen/drag eraser, a dotted outline
// (DASH style 5) for the lasso/select eraser. See docs/onyx-sdk/onyx-native-eraser-indicator.md.
```

Undocumented firmware constants get a comment saying so honestly — including the honest
`// no idea` on unknown stroke params (`OnyxInputHandler.kt:178`). Don't fabricate certainty.

---

## 4. Conventions in code

| Area | Convention |
|---|---|
| **Logging** | `ShipBook.getLogger("<Component>")` as a private `val log`. All five levels used; `log.d`/`log.i` for lifecycle, `log.e` for failures. Not `android.util.Log` |
| **Errors** | `AppResult<D, E : DomainError>` sealed interface — return, don't throw. `DomainError` carries a mandatory `userMessage`. Errors combine with `+`. See `docs/result-and-error-handling.md` and `utils/AppResult.kt` |
| **DI** | Hilt. Modules in `di/` (`CoroutinesModule`, `EventModule`, `SnackModule`). ViewModels `@HiltViewModel` + `hiltViewModel()`. Never pass `Context` into a ViewModel |
| **Navigation** | Every screen declares a companion `object XDestination : NavigationDestination` with `route`, arg-name constants, and a `createRoute(...)` helper. Follow this for new screens |
| **Settings** | `GlobalAppSettings.current` (28 files) for app-wide config |
| **Concurrency** | Coroutines throughout. `Dispatchers.Main.immediate` for canvas work, `Dispatchers.Default` for follow-up. `CanvasEventBus.drawingInProgress` is a `Mutex` guarding stroke commits — take it, don't bypass it |
| **Build files** | Groovy DSL (`build.gradle`, **not** `.kts`) with a version catalog in `gradle/libs.versions.toml`. Add dependencies to the catalog, not inline |

### Signals are global and unaddressed

`CanvasEventBus` (`editor/canvas/CanvasEventBus.kt`) is an `object` holding ~20 flows —
`refreshUi`, `forceUpdate`, `clearPageSignal`, `reloadFromDb`, `changePage`,
`commitHistorySignal`, and more. `CanvasObserverRegistry` collects each against a **single**
captured `page`.

There is **no pane/page addressing on any signal**. Anything that assumes more than one editor
surface will double-handle every event. Issue #211 item 4 wants this documented; it is a known
weak point, so prefer adding signals reluctantly and consider whether the flow belongs on an
instance instead of the object.

---

## 5. Build and test

```bash
./gradlew assembleDebug          # debug build, no signing needed
./gradlew test                   # unit tests
./gradlew connectedAndroidTest   # instrumented — needs device/emulator
```

- Most review/refactor tasks need no build. Signed release builds are CI-only.
- On a JDK path error, **ask for `JAVA_HOME`** rather than guessing.
- Versions: `compileSdk 37`, `minSdk 29`, `targetSdk 35`, AGP 9.2.1, Kotlin 2.4.10.
  Onyx SDK: base 1.8.5.2 · device 1.3.5.2 · pen 1.5.4.1 (dependabot-managed as the `onyx-sdk` group).

### Room migrations

Any `@Entity` change requires **all** of: DB version bump, a migration in `AppDatabase.kt`, a new
schema snapshot in `app/schemas/`, and a passing `MigrationTest`. Current schema version is 36.

### Linting

`.github/copilot-instructions.md` says to stay "compatible with linting tools (e.g.
ktlint/detekt)" — but **neither is actually configured** in the build. Treat it as a style
expectation to honour manually; there is no automated gate to catch you.

---

## 6. Traps

- **`floatingEditor/`** is unused/historical. Never add code there.
- **`PageView` is a 939-line god class** (data proxying, coordinate transforms, rendering, and
  selection). Adding to it is the path of least resistance and the wrong move — extract instead.
- **`page.windowedBitmap` is view-sized, not page-sized** (`PageView.kt:93`) and is blitted with
  separate src/dst rects. Recreated on `updateDimensions`. Don't assume it holds the whole page.
- **Avoid `!!`** — the existing `touchHelper!!` uses are all guarded by an early
  `if (touchHelper == null) return`. Keep that pattern; don't introduce unguarded ones.
- **Don't block the main or GL thread with I/O.** On e-ink a stall is immediately visible as ink lag.

---

## 7. Contributing upstream

Base repo is [Ethran/notable](https://github.com/Ethran/notable) (GPL-3.0).

- **`dev2` is the live integration branch** (~28 commits ahead of `main`). Branch from and rebase
  against it; `main` lags.
- Small, single-concern PRs with descriptive titles. Architectural changes ship with a doc in
  `docs/`.
- External contributions are genuinely accepted (scribble-to-erase came from @niknal357).
- AI assistance is expected — `.github/copilot-instructions.md` exists — but **disclose it**.
- Check issue **#211** (maintainer's refactor plan) before touching `DrawCanvas`,
  `OnyxInputHandler`, or `PageView`. Item 1 plans to split Onyx and generic drawing paths, and
  will collide with edits there.
