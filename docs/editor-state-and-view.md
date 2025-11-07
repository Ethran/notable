# Editor State and View Management

This document explains how the editor identifies which page to display and how the view components are managed.

**It was created by AI, and should be checked for correctness. Refer to code for actual implementation.**

Contents:
- `EditorState`
- `EditorControlTower`
- `PageView`
- `DrawCanvas`

---

## `EditorState`

The `EditorState` class is a simple, immutable data holder that represents the "context" of the editor session. Its primary role is to answer the question: "What are we currently editing?"

```kotlin
class EditorState(
    val bookId: String? = null, 
    val pageId: String, 
    val pageView: PageView
)
```

- It holds the unique `pageId` which is the key identifier for all data loading and state management operations.
- The presence of a `bookId` provides additional context if the page belongs to a notebook.

---

## `EditorControlTower`

The `EditorControlTower` is the central coordinator for the editor. It holds the `EditorState` and uses it to manage all other components.

- **Role**:
    - Initializes the editor environment based on the provided `EditorState`.
    - Orchestrates the flow of data from `PageDataManager` to the `PageView`.
    - Manages editor-wide concerns like the undo/redo `History`.

---

## `PageView`

`PageView` is the main Android `View` for the editor. It is responsible for setting up the drawing surface and handling user interactions like touch input and gestures (zooming, panning).

```kotlin
class PageView(
    val context: Context,
    val coroutineScope: CoroutineScope,
    var id: String, // The pageId it is currently displaying
    var viewWidth: Int,
    var viewHeight: Int,
    val snackManager: SnackState
)
```

- It owns the `DrawCanvas` and other UI elements.
- It translates raw touch events into drawing commands or navigation gestures, which are then processed by the `EditorControlTower` or `DrawCanvas`.

---

## `DrawCanvas`

The `DrawCanvas` is a specialized component, likely a custom `View` or a class that operates directly on a `Canvas`, dedicated to the low-level task of rendering.

```kotlin
// Example structure
class DrawCanvas(context: Context) : View(context) {
    // ...
}
```

- **Responsibilities**:
    - Directly handles `Canvas` drawing operations (e.g., `drawPath`).
    - Renders the strokes, images, and background that it receives from the `PageView`.
    - It is optimized for high-performance drawing and does not contain business logic. Its only job is to draw what it's told to draw.
