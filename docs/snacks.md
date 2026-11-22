# Snack (Snackbar) System

Snack system in Notable has three layers:

1. **UI render layer**: `SnackBar(state, dispatcher)` (`ui/SnackBar.kt`) renders local and global snacks.
2. **UI dispatch layer**: `SnackDispatcher` (Hilt singleton) is the main API for app-wide messages.
3. **Domain event layer**: `AppEventBus` publishes domain events that are translated to UI snacks by `AppEventUiBridge`.

## Key Components

- `SnackConf`: UI payload for snackbar rendering (`id`, `text`, `duration`, optional `progress` 0..1, optional `content`, optional `actions`). Showing a snack with an existing `id` updates it in place.
- `SnackDispatcher` (`ui/SnackDispatcher.kt`): app-wide snackbar dispatch API (`showOrUpdateSnack`, `removeSnack`, `activeSnacks`).
- `DefaultSnackDispatcher`: its implementation, bound in Hilt by `SnackModule`.
- `SnackState`: local snack state, provided to composables through `LocalSnackContext` by `MainActivity`; also has `showSnackDuring` / `runWithSnack` helpers.
- `AppEventBus` (`data/events/`): domain-level event stream for low-level components; events are the `AppEvent` sealed interface.
- `AppEventUiBridge` (`ui/AppEventUiBridge.kt`): maps `AppEvent` -> `SnackConf` and sends via `SnackDispatcher`.
- `SyncWorkUiBridge` (`ui/SyncWorkUiBridge.kt`): turns finished WorkManager sync runs into snack events, which `MainActivity` shows through `SnackDispatcher`.

## Triggering Snacks

### Preferred: ViewModel / app services

Inject `SnackDispatcher` and call `showOrUpdateSnack`. For long-running operations, use specific `AppEvent`s if in domain layer.

### Local UI feedback

Use `LocalSnackContext.current` + `SnackState.displaySnack(...)` for view-local feedback.

### Low-level domain/data components

Publish `AppEvent` to `AppEventBus` (do not build `SnackConf` in domain code).

## Wiring

- `AppEventBus` is bound in Hilt (`EventModule`, `DefaultAppEventBus`).
- `AppEventUiBridge` is a singleton injected into `MainActivity`; on creation it starts collecting `AppEventBus` on the application scope and translates events to `SnackDispatcher` calls.
- Publishers include `PageSaver` (edits not saved), `PageDataManager` / `BackgroundFileWatcher`, `ImportEngine`, `ExportEngine`, `XoppFile`, `ThumbnailBackfillQueue`, `History`, `StrokeMigrationHelper` (`StrokeReencoding.kt`) and the version check (`utils/versionChecker.kt`).

## Best Practices

1. Keep domain events domain-focused (`what happened`), not UI-focused (`how to show it`).
2. Use `SnackDispatcher` in UI/ViewModel/application services; avoid `SnackState` static-style usage.
3. In low-level loops, emit compact events and deduplicate in bridge/UI when needed.
4. Keep event collection lifecycle-bound (`lifecycleScope` / `viewModelScope`) to avoid leaks.
