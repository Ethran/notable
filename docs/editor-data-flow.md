# Editor Data Flow

This document describes how page data, particularly strokes, is loaded, cached, and managed within the editor.

**It was created by AI, and should be checked for correctness. Refer to code for actual implementation.**

Contents:
- `PageDataManager`
- Data Loading Process

---

## `PageDataManager`

`PageDataManager` is an injected class that acts as the central repository for all page-related content. It abstracts the underlying data sources (like the Room database and file system) from the rest of the application.

- **Responsibilities**:
    - Fetching strokes, images, and other page elements from the database.
    - Caching frequently accessed data to improve performance, including memory management via component callbacks (`onTrimMemory`).
    - Persisting new or modified data back to the database.
- **Role**: It serves as a single source of truth for page content, ensuring data consistency and providing a clean API for the editor components to request and submit data.

---

## Data Loading Process

The process of loading a page's data into the editor follows a clear path orchestrated by the `EditorViewModel` and `PageDataManager`.

1.  **Initiation**: When the editor is opened for a specific page, the `EditorViewModel` receives the required `pageId` and `notebookId`.

2.  **Request**: The `EditorViewModel` or `PageView` issues a request to the `PageDataManager` to asynchronously load the strokes, images, and other content associated with the `pageId`.

3.  **Fetching & Caching**: `PageDataManager` retrieves the data from the database within an asynchronous Coroutine job.
    It utilizes an in-memory cache (`LinkedHashMap`) to avoid redundant database queries if the data has been recently accessed, and uses locking (`Mutex`) to avoid concurrent loads of the same page.

4.  **Delivery**: Once the data is retrieved and the loading job is marked complete, the cached data is available for consumption by the UI.

5.  **Rendering**: `DrawCanvas` interacts with `PageView` and `EditorViewModel` to fetch this loaded data, instructing its `Canvas` on what to render on the screen.


## Caching

`PageDataManager` uses several caching mechanisms to ensure responsive rendering, minimize database queries, and manage memory constraints effectively.

- **In-Memory Caches**: Uses `LinkedHashMap` to store strokes, images, and backgrounds for recently accessed pages. Maintains per-page indexes for fast lookups and uses `SoftReference` for temporary bitmaps.
- **Concurrency**: Database fetches for the same page are guarded by a `Mutex` to prevent duplicate loading jobs. 
- **Backgrounds & Invalidation**: Shared backgrounds are stored centrally to avoid redundant allocations. File observers monitor external background files, triggering automatic cache invalidation and UI re-rendering when changes occur.
- **Memory Management**: Tracks estimated memory usage against configured limits. Evicts older pages dynamically, integrating with Android's built-in memory signals (`onTrimMemory`, `onLowMemory`).
- **Preview Persistence**: Temporary bitmaps captured on page exit are batched and persisted off the main thread to generate thumbnails. 
