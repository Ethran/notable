# Architecture Issue: PageView is a God Class

## Description
The `PageView.kt` class (over 900 lines) has taken on too many responsibilities, becoming a "God Object." It violates the Single Responsibility Principle by trying to handle:
- **Data Repository Proxying**: Proxying methods for fetching, adding, or deleting strokes and images via `PageDataManager`.
- **Coordinate Transformations**: Calculating zoom, scrolling, screen offsets, and relative geometry logic (`toScreenCoordinates`, `toPageCoordinates`).
- **Rendering & Caching**: Maintaining `windowedBitmap`, `windowedCanvas`, drawing the page background (`drawBgToCanvas`), and handling large background processing jobs (`loadPage()`).
- **Selection Handling**: Calculating lasso selection boxes and checking point bounds (`applyPageCutOffset`).

This makes the `PageView` class extremely difficult to read, maintain, and test.

## Recommended Fix
Extract responsibilities from `PageView` into distinct, cohesive components:
1. **`PageGeometryManager` or `ViewportState`**: A standalone class dedicated to tracking the zoom level, scroll offset, and transforming points between screen and page coordinate systems.
2. **`PageRenderer`**: A dedicated rendering component to manage the `windowedBitmap` cache and draw the page layers (`drawOnCanvasFromPage`, background grid).
3. **Data Delegation**: Remove the proxy methods from `PageView`. Let `EditorViewModel` directly talk to `PageDataManager` for DB operations like `addStrokes()`, instead of passing through `PageView`. `PageView` should only receive data passively to display it, not manage its lifecycle.
