# Editor Data Flow

This document describes how page content is loaded, cached, edited and saved in the editor.

**It was created by AI, and should be checked for correctness. Refer to code for actual implementation.**

Contents:
- Who owns what
- `PageDataManager`
- Showing a page
- Editing and saving
- Scroll, zoom and content height
- Caching and memory

---

## Who owns what

| What | Owner | Lifetime |
|---|---|---|
| Strokes, images, backgrounds of a page | `PageDataManager` (singleton), keyed by page id | while cached |
| Edits not yet in the database | `PageDataManager` (`PageChanges` per page) | until `PageSaver` writes them |
| Writing edits to the database | `PageSaver` (singleton) | the process |
| Which page an editor shows | `PageView` (`OpenPage` snapshot) | the editor |
| Keeping shown pages cached | `PageLeases` (views per page), through `PageDataManager.hold` | while any view shows the page |
| Scroll and zoom | `PageView` (`PageViewport`) | the editor; vertical scroll also in `Page.scroll` |

`PageDataManager` holds page data only. It knows nothing about which page is in front, nor tracks
how a page is scrolled or zoomed: it only keeps the viewport a cached bitmap shows and the scroll
to save. Every call names the page it is about.

---

## `PageDataManager`

`PageDataManager` (`data/PageDataManager.kt`) is the in-memory store of page content shared by the
editor and the code around it.

- **One lock.** All cached state is guarded by a single monitor. Nothing slow or re-entrant runs
  under it: no database reads, no file I/O, no other locks. Database reads happen outside, and only
  their results are stored.
- **Addressed by page id.** Reads (`getStrokes(pageId)`, `getImages(pageId)`, spatial queries) and
  edits (`addStrokes(pageId, …)`, `updateImages(pageId, …)`, …) all take the page id, so a call
  made for one page can never touch another page that was shown meanwhile.
- **Held pages.** `hold(pageId)` keeps a page until `release(pageId)`: it is never evicted, keeps
  its background when the background pool is trimmed, and is repainted when its background file
  changes. Which pages to hold is up to the caller: `PageLeases` (`editor/PageLeases.kt`) counts
  the views showing each page, as `PageLease`s, and holds the page while any of them is open.
- **Loading.** `requestPageLoadJoin(pageId)` loads a page for showing and waits for it;
  `prefetch(pageIds)` loads neighbours into spare budget without waiting. Concurrent requests for
  the same page share one load job. `cancelLoadsExcept(keep)` stops loads of pages left behind,
  but never of a held page.

---

## Showing a page

1. **Request.** `PageView.changePage(pageId)` sets the requested page. Page changes run one at a
   time in the order asked for; when several are asked for while one runs, only the last is shown.
2. **Reading the new page.** Off the main thread, `PageView` reads the page record and gets the
   bitmap for the viewport the page will be shown at: the viewport it had when this editor left it,
   or else unzoomed at its saved vertical scroll. It takes the page's bitmap off the shelf in
   `PageDataManager` if that shows this viewport at the view's size; otherwise it draws a new one
   from the page's preview or background.
3. **Swapping pages.** On the main thread, in one step under its page lock, `PageView`:
   - puts the old page's screen bitmap on the shelf (`onExit`), tagged with the viewport it shows,
     for the page's next showing and its preview, if the page's data is loaded;
   - takes the new page's lease and closes the old one;
   - puts in the new page's `OpenPage` snapshot (record and page number), its viewport and its
     bitmap.

   Scroll and zoom steps run on the main thread too, so a step never sees one page with another
   page's viewport or bitmap.
4. **Drawing.** The page's strokes and images load in the background and the screen is redrawn once
   they are in.
5. **Neighbours.** `EditorViewModel.onPageShown` is called once the page holds its lease; it
   cancels loads of pages left behind and prefetches the neighbours.
6. **Closing.** `PageView.disposeOldPage` puts the shown page's bitmap on the shelf, releases the lease and
   marks the view disposed. A disposed view shows, draws and loads nothing more, even if a page
   change was running. `EditorViewModel.onDispose` then stops loading the pages around it.

---

## Editing and saving

1. The editor edits a page through `PageView`, which calls `PageDataManager` with the page id.
2. `PageDataManager` changes the cached page and records the outcome per stroke or image id in
   that page's `PageChanges` (the latest version, or null for a removed one), then wakes
   `PageSaver`. The edits are kept apart from the cache, so evicting a page never drops them, and a
   page loaded again gets them laid over its database rows.
3. `PageSaver` (`data/PageSaver.kt`) writes everything collected in one transaction once editing
   pauses, and, while the process runs, no later than a few seconds after the first unsaved edit.
   It stamps each edited page with the time of its last edit, or just past a later stamp put on
   the page meanwhile, and its notebook with the time of the save.
   A failed write keeps its changes unsaved and is retried, and a warning stays on screen until a
   save succeeds; changes to a page deleted meanwhile are dropped.
4. Saving is forced sooner when it matters:
   - `saveSoon()` when the editor closes and when the app goes to the background. It starts the
     save without waiting, so a process killed right after may not finish it;
   - `saveAll()` before export and sync, which read pages from the database; they stop, and
     report why, if it returns an error.

---

## Scroll, zoom and content height

- `PageView` owns a `StateFlow<PageViewport>` (`scroll`, `zoom`) for the shown page, and a map of
  the viewports of pages this editor has left, so returning to a page brings back its scroll and
  zoom.
- Each scroll or zoom step is for the page its gesture was made on. It first waits for drawing to
  finish, then reads that page's viewport and commits the new one without suspending, on the main
  thread, where page swaps run too, so nothing changes the viewport in between. A step for a page
  no longer shown is dropped instead of being applied to the new page. Scroll and zoom are ignored
  until the first page is shown.
- Each committed step records the vertical scroll with `PageDataManager.recordScroll(pageId, y)`;
  `PageSaver` writes it to `Page.scroll` with the other edits. A page shown for the first time in
  an editor opens at that saved scroll, unzoomed.
- `PageDataManager.contentHeight(pageId)` measures the bottom of the page's strokes and images.
  `PageView` keeps it in a `StateFlow` for the scroll indicator and recomputes it after loading and
  after each edit.

---

## Caching and memory

- **Pages.** One `PageCacheEntry` per page holds its strokes, images, background key and the
  screen bitmap the view left the page with (a `SoftReference`, tagged with its viewport).
  Eviction picks the least recently used pages that are neither held nor loading.
- **Screen bitmaps on a shelf.** A screen bitmap has one user at a time. While no one uses it, it
  sits on the page's shelf in `PageDataManager`. A view takes it off to draw into it and puts it
  back when it leaves the page; the preview save takes it off while it reads it and puts it back
  after. A view that finds the shelf empty draws a new bitmap. So no one draws into a bitmap that
  someone else is reading.
- **Budget.** Stroke and image pages may use a fraction of the app heap. A page's cost is
  estimated before loading from its stored stroke size. A page loaded for showing evicts unpinned
  pages to fit and loads even if it still doesn't; a prefetched page is loaded only into spare
  budget. The pure sizing and eviction policy lives in `data/PageCache.kt`.
- **Backgrounds.** Background bitmaps are pooled and shared between pages, on their own budget
  line, so a page full of cheap strokes is never evicted because backgrounds are large.
  `BackgroundFileWatcher` watches background files and reports changed pages; their backgrounds are
  dropped, for every page sharing them, then reloaded, and held pages repainted. A render begun
  before such a change is not cached. A file that fails to render is cached as failed, unless it is
  missing: then every draw checks for it again, so it is shown once it is there.
- **Memory signals.** `onTrimMemory` shrinks the cache back to its budget, or drops every
  unpinned page when the app is hidden; `onLowMemory` drops every unpinned page.
- **Previews.** `PagePreviewStore` (`data/PagePreviewStore.kt`, app-wide) writes the thumbnail and
  page preview of each page left, from the bitmap put on the shelf, within about 2 s after the page
  is left, one page at a time and even after the editor closes. A page shown again by then is not
  written; the view has its bitmap. Both are written only for an unzoomed page scrolled to its
  left edge, and the preview's file name carries the vertical scroll it was captured at. It counts as fresh
  while it is newer than the page's `updatedAt`, which `PageSaver` sets to the time of the page's
  last edit. Deleting a page deletes its previews, including those written after it was deleted.
