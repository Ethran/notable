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
2. **Leaving the old page.** `PageDataManager.onExit` keeps the old page's screen bitmap, tagged
   with the viewport it shows, for the page's next showing and for its preview.
3. **Publishing the new page.** `PageView` reads the page record, then in one step under its page
   lock it:
   - takes the new page's lease and closes the old one;
   - publishes an `OpenPage` snapshot for the new page (record not loaded yet);
   - sets the viewport the new page had when this editor left it, or else unzoomed at the saved
     vertical scroll.

   It then publishes the loaded snapshot (record and page number). A snapshot that finishes
   loading after the view has moved on is dropped, so `OpenPage.pageId` and `OpenPage.record`
   always describe the same page.
4. **Drawing.** The cached bitmap is reused if it shows the same viewport at the view's size;
   otherwise a new one is drawn. The page's strokes and images load in the background and the
   screen is redrawn once they are in.
5. **Neighbours.** `EditorViewModel.onPageShown` is called once the page holds its lease; it
   cancels loads of pages left behind and prefetches the neighbours.
6. **Closing.** `PageView.disposeOldPage` keeps the bitmap, releases the lease and marks the view
   disposed. A disposed view shows, draws and loads nothing more, even if a page change was
   running. `EditorViewModel.onDispose` then stops loading the pages around it.

---

## Editing and saving

1. The editor edits a page through `PageView`, which calls `PageDataManager` with the page id.
2. `PageDataManager` changes the cached page and records the outcome per stroke or image id in
   that page's `PageChanges` (the latest version, or null for a removed one), then wakes
   `PageSaver`. The edits are kept apart from the cache, so evicting a page never drops them, and a
   page loaded again gets them laid over its database rows.
3. `PageSaver` (`data/PageSaver.kt`) writes everything collected in one transaction once editing
   pauses, and no later than a few seconds after the first unsaved edit. A failed write keeps its
   changes unsaved and is retried, and a warning stays on screen until a save succeeds; changes to
   a page deleted meanwhile are dropped.
4. Saving is forced sooner when it matters:
   - `saveSoon()` when the editor closes and when the app goes to the background;
   - `saveAll()` before export and sync, which read pages from the database; they stop, and
     report why, if it returns an error.

---

## Scroll, zoom and content height

- `PageView` owns a `StateFlow<PageViewport>` (`scroll`, `zoom`) for the shown page, and a map of
  the viewports of pages this editor has left, so returning to a page brings back its scroll and
  zoom.
- A scroll or zoom step reads the shown page and its viewport together, works from them, and
  commits only if the same page is still shown, its record is read, and its viewport hasn't
  changed since the step started. A step running while the page changes is dropped instead of
  being applied to the new page, and so are pinch steps still queued from before the change.
  Scroll and zoom are ignored until the page record is read.
- Each committed step records the vertical scroll with `PageDataManager.recordScroll(pageId, y)`;
  `PageSaver` writes it to `Page.scroll` with the other edits. A page shown for the first time in
  an editor opens at that saved scroll, unzoomed.
- `PageDataManager.contentHeight(pageId)` measures the bottom of the page's strokes and images.
  `PageView` keeps it in a `StateFlow` for the scroll indicator and recomputes it after loading and
  after each edit.

---

## Caching and memory

- **Pages.** One `PageCacheEntry` per page holds its strokes, images, background key and the last
  screen bitmap (a `SoftReference`, tagged with its viewport). Eviction picks the least recently
  used pages that are neither held nor loading.
- **Budget.** Stroke and image pages may use a fraction of the app heap. A page's cost is
  estimated before loading from its stored stroke size. A page loaded for showing evicts unpinned
  pages to fit and loads even if it still doesn't; a prefetched page is loaded only into spare
  budget. The pure sizing and eviction policy lives in `data/PageCache.kt`.
- **Backgrounds.** Background bitmaps are pooled and shared between pages, on their own budget
  line, so a page full of cheap strokes is never evicted because backgrounds are large.
  `BackgroundFileWatcher` watches background files and reports changed pages; their backgrounds are
  reloaded and held pages repainted.
- **Memory signals.** `onTrimMemory` shrinks the cache back to its budget, or drops every
  unpinned page when the app is hidden; `onLowMemory` drops every unpinned page.
- **Previews.** The bitmaps kept on page exit are written off the main thread as thumbnails and
  page previews. A preview is written only for an unzoomed page scrolled to its left edge, and its
  file name carries the vertical scroll it was captured at.
