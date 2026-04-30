# Snack (Snackbar) System

Snack system in Notable currently has three layers:

1. **UI render layer**: `SnackBar(state, dispatcher)` renders local and global snacks.
2. **UI dispatch layer**: `SnackDispatcher` (Hilt singleton) is the main API for app-wide messages.
3. **Domain event layer (in progress)**: `AppEventBus` publishes domain events that are translated to UI snacks by `AppEventUiBridge`.

## Key Components

- `SnackConf`: UI payload for snackbar rendering (`id`, `text`, `duration`, optional `content`, optional `actions`).
- `SnackDispatcher`: app-wide snackbar dispatch API (Hilt).
- `DefaultSnackDispatcher`: concrete implementation used by UI layer.
- `SnackState`: local composable-scoped snack state (`LocalSnackContext`).
- `AppEventBus`: domain-level event stream for low-level components.
- `AppEventUiBridge`: maps `AppEvent` -> `SnackConf` and sends via `SnackDispatcher`.

## Triggering Snacks

### Preferred: ViewModel / app services

Inject `SnackDispatcher` and call `showOrUpdateSnack`. For long-running operations, use specific `AppEvent`s if in domain layer.

### Local UI feedback

Use `LocalSnackContext.current` + `SnackState.displaySnack(...)` for view-local feedback.

### Low-level domain/data components

Publish `AppEvent` to `AppEventBus` (do not build `SnackConf` in domain code).

## Current Migration Status

- `AppEventBus` is implemented and bound in Hilt (`EventModule`).
- `AppEventUiBridge` is started from `MainActivity` and translates events to `SnackDispatcher` calls.
- `StrokeMigrationHelper` (`StrokeReencoding.kt`) uses `AppEventBus`.

## Best Practices

1. Keep domain events domain-focused (`what happened`), not UI-focused (`how to show it`).
2. Use `SnackDispatcher` in UI/ViewModel/application services; avoid `SnackState` static-style usage.
3. In low-level loops, emit compact events and deduplicate in bridge/UI when needed.
4. Keep event collection lifecycle-bound (`lifecycleScope` / `viewModelScope`) to avoid leaks.
