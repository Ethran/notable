# Editor Data Flow

This document describes how page data, particularly strokes, is loaded, cached, and managed within the editor.

**It was created by AI, and should be checked for correctness. Refer to code for actual implementation.**

Contents:
- `PageDataManager`
- Data Loading Process

---

## `PageDataManager`

`PageDataManager` is a singleton (`object`) that acts as the central repository for all page-related content. It abstracts the underlying data sources (like the Room database and file system) from the rest of the application.

- **Responsibilities**:
    - Fetching strokes, images, and other page elements from the database.
    - Caching frequently accessed data to improve performance.
    - Persisting new or modified data back to the database.
- **Role**: It serves as a single source of truth for page content, ensuring data consistency and providing a clean API for the editor components to request and submit data.

---

## Data Loading Process

The process of loading a page's data into the editor follows a clear path orchestrated by the `EditorControlTower`.

1.  **Initiation**: When the editor is opened for a specific page, the `EditorControlTower` is created with an `EditorState` that contains the required `pageId`.

2.  **Request**: The `EditorControlTower` uses its `CoroutineScope` to launch an asynchronous request to the `PageDataManager` for the strokes and other content associated with the `pageId`.

3.  **Fetching & Caching**: `PageDataManager` retrieves the data from the database. It may also utilize an in-memory cache to avoid redundant database queries if the data has been recently accessed.

4.  **Delivery**: Once the data is retrieved, it is passed to the `PageView`.

5.  **Rendering**: `PageView` then uses this data to instruct its `DrawCanvas` component on what to render on the screen. This separation ensures that the `PageView` manages the "what" (the data) while the `DrawCanvas` handles the "how" (the actual drawing operations).
