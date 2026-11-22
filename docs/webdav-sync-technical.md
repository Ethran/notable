# WebDAV Sync — Technical Documentation

This document describes Notable's WebDAV architecture, protocol, data formats, and design tradeoffs.
For setup and usage, see the [user guide](webdav-sync-user.md).

## 1. Architecture overview

`SyncOrchestrator` owns the process-wide mutex and normal-flow sequencing. Focused services handle
preflight checks, folder sync, notebook reconciliation, transfers, and replacement operations.
`WebDavClientFactoryPort` abstracts client construction, but transfer services still depend on the
concrete `WebDAVClient` and broad `AppRepository`. This coupling limits executor-level testing.

Transfer is page-based. The `page_sync_state` table identifies pages changed since the last committed
sync; a page is the smallest transferable unit. On servers where it was measured to be safe, *bulk
change detection* skips the per-notebook manifest request for notebooks whose remote directory ETag
has not moved since the last converged sync (section 3.6).

Every sync entry point first flushes the editor's pending edits to the database
(`PageSaver.saveAll()`); if that fails the sync stops, because sync reads pages and their edit
timestamps from Room.

Reconciliation has two regimes:

- **Clear winner.** When one `Notebook.updatedAt` is more than one second later, that side wins.
- **Concurrent edits.** When the timestamps tie within tolerance but the manifest ETag differs, the
  engine distinguishes independent page edits from same-page or structural conflicts. Independent
  edits merge; conflicts receive a `CONFLICT` badge and require user resolution. `ASK` is the only
  implemented conflict strategy.

The protocol has commit points but no atomic notebook snapshot. Pages precede the manifest on upload;
page replacements precede the notebook-row commit on download. This leaves several gaps:

- upload publishes page files (each one atomically via a staging file and `MOVE`, or, when `MOVE`
  is unsupported or fails transiently, by a direct `PUT` that is not atomic) before publishing
  `manifest.json`; if a page id is unchanged, an interrupted update can expose new page content
  through an *old* manifest;
- download replaces pages one at a time before committing the notebook row; a failed download is
  retried on the next sync because the timestamp stays stale, but partially replaced pages can
  already be visible locally in the meantime;
- media 404s and missing local media are deliberately treated as non-fatal, so a notebook can commit
  while still referring to a missing image or background.
## 2. Components

All sync code lives in `com.ethran.notable.sync`.

```
UI / lifecycle triggers
├── WorkManager path
│   ├── manual sync, app-start sync, periodic sync
│   ├── force upload/download
│   └── targeted notebook deletion
│       SyncScheduler → SyncWorker → SyncOrchestrator
└── in-process path
    ├── sync on editor close
    └── check on notebook open
        EditorViewModel → SyncOrchestrator

SyncOrchestrator
├── SyncPreflightService
├── FolderSyncService
├── NotebookReconciliationService → NotebookSyncPlanner
├── NotebookSyncService → PageSyncSelector
└── SyncForceService

conflict resolution (user-driven, holds the sync mutex)
SyncOrchestrator.resolvePageConflict / resolveNotebookConflict
    → NotebookSyncService (rebaseline sync rows)
    → NotebookReconciliationService (the follow-up transfer)

Test Connection (settings, in-process)
    SettingsViewModel → WebDAVClient.testConnection → CapabilityProbeService
        → ServerCapabilities stored in Kv (SYNC_SERVER_CAPABILITIES)

WebDavClientFactoryPort → WebDAVClient → shared OkHttpClient
AppRepository → Room repositories, notebook_sync_state and page_sync_state
SyncProgressReporter → settings progress UI and notebook badges
```

| File | Role |
|---|---|
| [`SyncScheduler.kt`](../app/src/main/java/com/ethran/notable/sync/SyncScheduler.kt) | Creates unique one-time work and one unique periodic job. Periodic work uses a minimum 15-minute interval and a `CONNECTED` or `UNMETERED` constraint. |
| [`SyncWorker.kt`](../app/src/main/java/com/ethran/notable/sync/SyncWorker.kt) | `CoroutineWorker` for WorkManager integration. Performs connectivity/settings checks, decodes `SyncRequest`, invokes the orchestrator, and maps `DomainError` to WorkManager success/failure/retry. |
| [`SyncOrchestrator.kt`](../app/src/main/java/com/ethran/notable/sync/SyncOrchestrator.kt) | Owns normal-flow sequencing, the process-wide sync mutex (companion-object `Mutex`), settings reads, progress transitions, and the successful full-sync timestamp. |
| [`SyncPreflightService.kt`](../app/src/main/java/com/ethran/notable/sync/SyncPreflightService.kt) | Enforces the unmetered constraint, checks server clock skew, and ensures the root collections exist. A full sync uses `ensureServerReady`: one root `PROPFIND` answers both (its `Date` header gives the skew; a `HEAD` is the fallback when that header is missing). |
| [`CapabilityProbeService.kt`](../app/src/main/java/com/ethran/notable/sync/CapabilityProbeService.kt) / [`ServerCapabilities.kt`](../app/src/main/java/com/ethran/notable/sync/ServerCapabilities.kt) | Measures, from Test Connection only, the server's non-standard ETag behaviour that the fast paths rely on, and the record it stores (section 3.6). |
| [`SyncSettings.kt`](../app/src/main/java/com/ethran/notable/sync/SyncSettings.kt) / [`SyncRequest.kt`](../app/src/main/java/com/ethran/notable/sync/SyncRequest.kt) | The persisted settings record (Kv key `SYNC_SETTINGS`) and the WorkManager request types (`SyncAll`, `ForceUpload`, `ForceDownload`, `UploadDeletion`, `SyncNotebook`, `SyncFromPageId`). |
| [`FolderSyncService.kt`](../app/src/main/java/com/ethran/notable/sync/FolderSyncService.kt) | Reads, merges, applies, and conditionally writes `folders.json`. |
| [`NotebookReconciliationService.kt`](../app/src/main/java/com/ethran/notable/sync/NotebookReconciliationService.kt) | Fetches remote manifest metadata and executes the planner's decision for local notebooks. |
| [`NotebookSyncPlanner.kt`](../app/src/main/java/com/ethran/notable/sync/NotebookSyncPlanner.kt) | Pure per-notebook decision — `Upload` / `Download` / `Skip` / `Reconcile` / `SkipUploadOnly` / `SkipDownloadOnly` (unit-tested). |
| [`PageSyncSelector.kt`](../app/src/main/java/com/ethran/notable/sync/PageSyncSelector.kt) | Pure per-page dirty-selection: which pages changed since their last committed sync (unit-tested). |
| [`NotebookSyncService.kt`](../app/src/main/java/com/ethran/notable/sync/NotebookSyncService.kt) | Notebook/page/media transfer, per-page conflict detection and resolution rebaselining, notebook tombstones, new-notebook discovery, and local/remote garbage collection. |
| [`SyncConflictStrategy.kt`](../app/src/main/java/com/ethran/notable/sync/SyncConflictStrategy.kt) | The `ASK` / `SERVER_WINS` / `LOCAL_WINS` strategy enum (only `ASK` implemented) plus the resolution value types (`PageConflictResolution`, `NotebookConflictResolution`, `NotebookConflict`). |
| [`ETag.kt`](../app/src/main/java/com/ethran/notable/sync/ETag.kt) | Value type for a WebDAV ETag: parses the server's spelling (weak `W/` prefix, quotes), compares by validator rather than raw string, and derives the `If-Match` write guard. |
| [`SyncForceService.kt`](../app/src/main/java/com/ethran/notable/sync/SyncForceService.kt) | "Replace server with local" and "replace local with server" flows. |
| [`WebDAVClient.kt`](../app/src/main/java/com/ethran/notable/sync/WebDAVClient.kt) | Synchronous OkHttp WebDAV operations: `HEAD`, `GET`, conditional `GET`, `PUT` (optionally `If-Match` or `If-None-Match: *`), `MKCOL`, `DELETE`, `MOVE` (destination `If` guard, `Overwrite: T/F`), depth-1 `PROPFIND` listings and depth-0 `PROPFIND` for one resource's ETag, funneled through one private `execute()` helper. PROPFIND names `getetag`/`getlastmodified`/`resourcetype` explicitly (Nextcloud omits `getetag` for `allprop`); response ETags fall back to `OC-ETag`. |
| [`WebDavXml.kt`](../app/src/main/java/com/ethran/notable/sync/WebDavXml.kt) | PROPFIND XML parsing (hrefs, entries) and UUID validation. |
| [`SyncPorts.kt`](../app/src/main/java/com/ethran/notable/sync/SyncPorts.kt) | DI port/adapter for WebDAV client creation, plus the shared singleton `OkHttpClient` — one connection pool for all sync operations. |
| [`SyncPaths.kt`](../app/src/main/java/com/ethran/notable/sync/SyncPaths.kt) | Centralized server path construction (root, notebooks, deletions, per-notebook manifest/pages/images/backgrounds, tombstones). |
| [`NotebookSyncStatusStore.kt`](../app/src/main/java/com/ethran/notable/sync/NotebookSyncStatusStore.kt) | Derives per-notebook badges from Room state, notebook timestamps, and live progress. Nothing extra is stored — the badge is a pure function of those three sources. |
| [`ui/SyncWorkUiBridge.kt`](../app/src/main/java/com/ethran/notable/ui/SyncWorkUiBridge.kt) | Converts completed WorkManager jobs into terminal snack messages. |
| [`SyncProgressReporter.kt`](../app/src/main/java/com/ethran/notable/sync/SyncProgressReporter.kt) / [`SyncState.kt`](../app/src/main/java/com/ethran/notable/sync/SyncState.kt) | Owns the `SyncState` `StateFlow` consumed by the settings UI and the badge store. |
| [`serializers/NotebookSerializer.kt`](../app/src/main/java/com/ethran/notable/sync/serializers/NotebookSerializer.kt) | Serializes/deserializes notebooks, pages, strokes, and images to/from JSON. |
| [`serializers/FolderSerializer.kt`](../app/src/main/java/com/ethran/notable/sync/serializers/FolderSerializer.kt) | Serializes/deserializes the folder hierarchy to/from `folders.json`. |
| `notebook_sync_state` (Room, `data/db`) | Per-notebook commit bookkeeping table — see [section 5.10](#510-per-notebook-and-per-page-sync-state). |
| `page_sync_state` (Room, `data/db`) | Per-page commit bookkeeping (ETag + change anchor) that drives dirty-page selection and per-page conflict detection — see [section 5.10](#510-per-notebook-and-per-page-sync-state). |
| [`KvProxy`](../app/src/main/java/com/ethran/notable/data/db/Kv.kt) + [`CryptoHelper`](../app/src/main/java/com/ethran/notable/data/db/CryptoHelper.kt) | Sync settings and measured server capabilities are persisted to the app key-value Room table; the password is encrypted with an AndroidKeyStore-backed AES-GCM key. |
| [`ConnectivityChecker.kt`](../app/src/main/java/com/ethran/notable/sync/ConnectivityChecker.kt) | Queries Android `ConnectivityManager` for network/Wi-Fi availability. |
| [`SyncLogger.kt`](../app/src/main/java/com/ethran/notable/sync/SyncLogger.kt) | Maintains a ring buffer of the last 50 log entries, exposed as a `StateFlow`, for the sync UI. |

## 3. Sync protocol

### 3.1 Full sync flow (`syncAllNotebooks`)

`SyncOrchestrator.syncAllNotebooks()` uses `tryLock()` on the companion-object `Mutex`. If it cannot
acquire the lock, it returns `SyncInProgress` rather than queueing behind the running sync.

```
1. INITIALIZE
   ├── Flush pending editor edits to Room (PageSaver.saveAll); stop on failure
   ├── Read persisted settings; require sync enabled and username/password non-blank
   ├── If Wi-Fi-only is enabled, require an unmetered active network
   ├── Build a client from the configured base URL and credentials
   ├── One depth-1 PROPFIND of /notable: its Date header gives the clock skew (rejected over
   │   30s; HEAD of the base URL as fallback when Date is missing), its listing tells which of
   │   /notable, /notable/notebooks and /notable/deletions must be created (MKCOL)
   ├── One depth-1 PROPFIND of /notable/notebooks; the id set is shared by steps 4 and 5, and
   │   each directory's ETag feeds bulk change detection
   └── Bulk change detection is on when the "Fast change detection" setting is on AND the stored
       ServerCapabilities belong to this server (URL + username) with collectionEtagPropagates

2. SYNC FOLDERS
   ├── If folders.json exists, fetch it with its ETag
   ├── Merge: a local folder replaces its remote counterpart only when its updatedAt is later
   ├── Apply the merged set locally, unless upload-only is enabled
   └── Write it back with If-Match, unless download-only is enabled
       (a weak or absent server ETag drops the precondition rather than failing; the merge is a
        union, so an unguarded write cannot drop a remote folder)
       (if the file is absent, non-empty local folders are uploaded unless download-only)

3. APPLY REMOTE NOTEBOOK TOMBSTONES  (skipped in upload-only mode)
   ├── List /notable/deletions with getlastmodified
   ├── For each UUID tombstone: delete the local notebook, UNLESS its updatedAt is after the
   │   tombstone time (resurrection — see section 5.7). A tombstone without a lastModified
   │   deletes only a notebook with no unsynced local edits
   ├── Remove the notebook's notebook_sync_state and page_sync_state rows after local deletion
   ├── Best-effort delete tombstones older than 90 days — this still runs in download-only mode,
   │   so that mode is not strictly read-only on the server
   └── Return the tombstoned-id set, used to suppress re-downloading those ids in step 5

4. SYNC EXISTING LOCAL NOTEBOOKS
   └── For each local notebook (per-item progress carries the notebook id):
       ├── Existence = "id in the shared PROPFIND set?" (no per-notebook HEAD)
       ├── If remote absent → upload, unless download-only is enabled
       └── If remote present:
           ├── Bulk detection on and the directory ETag matches the stored baseline → the
           │   remote is known unchanged; no manifest request (section 3.6)
           ├── Else with a stored ETag, GET manifest.json with If-None-Match; a 304 means
           │   "unchanged"
           ├── Otherwise GET the manifest and its ETag
           └── NotebookSyncPlanner.decide(...) returns Upload / Download / Skip / Reconcile /
               SkipUploadOnly / SkipDownloadOnly (see section 5.9 for the exact rule)
   A missing manifest inside an existing remote directory is treated as an interrupted remote
   upload: normal/upload-only mode re-uploads the local notebook; download-only mode skips it.
   A failed notebook is marked ERROR and the others still continue; the run then finishes
   (deletions, new notebooks, finalize) but is reported as failed.

5. DOWNLOAD NEW REMOTE NOTEBOOKS  (skipped in upload-only mode)
   Candidate ids = remote ids − ids present before download − tombstoned ids
                   − all ids still present in notebook_sync_state (not in download-only mode)
   (the last subtraction stops a locally-deleted, previously-synced notebook from being
   re-downloaded before its tombstone uploads; a download-only device is a mirror, so there a
   missing local notebook is restored instead)

6. UPLOAD LOCAL NOTEBOOK DELETIONS  (skipped in download-only mode)
   deletedLocally = notebook_sync_state ids − local ids captured before downloads
   If that looks like lost local state rather than deletions (at least 10 notebooks AND more
   than half of all synced ones), refuse with a non-recoverable error instead of deleting.
   For each id: delete the remote notebook directory, PUT a zero-byte tombstone, then drop the
   notebook and page sync-state rows once the tombstone PUT succeeds

7. FINALIZE
   ├── Bidirectional mode only: best-effort delete orphan remote notebook directories that have
   │   no manifest, aren't local, and whose directory lastModified is older than seven days
   └── On full success, persist SyncSettings.lastSyncTime = now (not updated by single-notebook
       sync, deletion work, or force operations)
```

### 3.2 Per-notebook upload

1. Ensure the `pages`, `images`, and `backgrounds` collections exist.
2. Upload only the **dirty** pages — the ones `PageSyncSelector` reports changed since their
   `page_sync_state` anchor. Editing one page of an 800-page notebook PUTs one page, not 800; skipped
   pages keep their existing row. For each dirty page: load its strokes and image rows, stream compact
   page JSON to a cache file one element at a time (bounding upload memory), and publish it:
   PUT the file to a unique staging name `pages/{pageId}.json.{random-uuid}.tmp`, then `MOVE` it
   over `pages/{pageId}.json` with the page's stored ETag as the destination `If` guard. Each managed
   image/background is uploaded only when a preceding `HEAD` says it is absent.
   A page unchanged *locally* can still be **missing on the server** (another device deleted its file
   or the whole remote directory). One depth-1 `PROPFIND` of `pages/` — read once for names and ETags —
   force-uploads any manifest page absent from that listing, published non-overwriting
   (`Overwrite: F` on the MOVE, `If-None-Match: *` on the PUT fallback) so a page another device
   recreated first is rejected instead of overwritten. A page with no sync row that *is* listed is
   guarded with the listed ETag. If the listing can't be read, every page is re-uploaded (safe and
   self-healing).
   A guarded page 412 is settled on content: the remote page is fetched and byte-compared with ours.
   Identical bytes (our own earlier uncommitted upload, or a re-spelled ETag) adopt the remote ETag;
   different bytes are a real concurrent edit → `SyncConflict`, and the remaining pages are not
   uploaded. If `MOVE` is unsupported or fails transiently, the page falls back to a direct PUT with
   the same precondition.
3. If any page transfer failed, do **not** publish the manifest — the old commit marker stays in
   place and the notebook is retried on the next sync.
4. Otherwise publish the manifest last: PUT `manifest.json.tmp`, then `MOVE` it over `manifest.json`,
   using the destination `If` header when a usable (strong) ETag is held. A 412 is a real concurrency
   conflict and is propagated — but only if a precondition was actually sent; any other MOVE failure
   falls back to a direct guarded PUT. The new manifest ETag is the tmp PUT's ETag when the server was
   measured to preserve ETags across `MOVE` (`movePreservesEtag`), otherwise it is read back with a
   depth-0 `PROPFIND`. Page rows whose PUT/MOVE reported no ETag are backfilled from one `PROPFIND`
   of `pages/`.
5. On manifest success, in one Room transaction (`commitNotebookSync`): mark the notebook `SYNCED`,
   write the uploaded pages' rows, and drop rows of pages that left the notebook. Then best-effort
   remove a resurrection tombstone, and — only when the manifest write was guarded — list and prune
   unreferenced remote page/media files (an unguarded, last-writer-wins commit could otherwise delete a
   page a concurrent device just uploaded).

The code does not verify that every id in `Notebook.pageIds` was actually returned from Room, so a
missing local page can leave a manifest reference with no uploaded page file.

### 3.3 Per-notebook download

1. GET and deserialize `manifest.json`, retaining its ETag.
2. If the notebook is new locally, insert it with `updatedAt = Date(0)` — a sentinel that guarantees
   a partial download still reads as "remote newer" and gets retried.
3. List `pages/` once for its ETags and fetch **only the pages whose ETag differs** from our stored
   `page_sync_state` row (a page unchanged on the server is skipped and keeps its local content).
   A page is also fetched when it has no row, is not present locally, or has no ETag in the listing
   (a failed/empty listing therefore fetches everything). For each fetched page: GET and fully
   deserialize the page JSON, download absent images and managed backgrounds (each background once per
   notebook), then replace that page, its strokes, and its image rows in one Room transaction. The
   page row keeps this device's view position and, if the file has no valid sheet, its local sheet
   (`downloadedPageRow`, `sync/DownloadedRows.kt`).
4. If every fetched page completed without a transient/DB error, write the notebook row from the
   manifest (keeping this device's `openPageId`, and its `defaultSheet` when the manifest has no valid
   one: `downloadedNotebookRow`) and commit in one transaction: mark the notebook
   `SYNCED` at the remote timestamp, upsert the fetched pages' `page_sync_state` rows, and drop rows
   for departed pages. Then best-effort prune local pages no longer in the manifest. A download killed
   before this commit writes no sync-state rows, so the next sync re-fetches the same pages. What it
   did write stays: pages already replaced and, for a notebook new to this device, the placeholder
   notebook row created with `updatedAt` 0, which keeps the notebook looking stale until a commit.

A media 404 is logged and dropped — it does not block the notebook commit. Other media failures do.
Corrupt individual stroke/image DTOs are skipped by the serializer rather than failing the whole
page.

### 3.4 Single-notebook sync and targeted deletion

Sync-on-close and check-on-open run in-process (application `CoroutineScope`), not through
WorkManager. Sync-on-close reuses the same per-notebook reconciliation as step 4 above on a single
notebook, with its own clock-skew `HEAD` and a `HEAD` of the manifest for existence (no bulk
detection); if the orchestrator's mutex is already held by a full/periodic sync, it quietly returns
success rather than racing it, and an unmet Wi-Fi constraint is likewise a silent no-op.
Check-on-open is a read-only conditional manifest `GET` that never takes the mutex and treats every
error/ambiguity as "not newer"; when the server is newer it shows a snackbar whose **Sync now** closes
the editor and then runs the single-notebook sync. Targeted notebook deletion routes through
WorkManager but does not take the orchestrator's mutex either.

### 3.5 Triggers, cancellation, and UI notifications

| Trigger | Execution path | Scope | Notes |
|---|---|---|---|
| Sync Now | WorkManager | Full | One-time unique work, `ExistingWorkPolicy.KEEP`. |
| App start | WorkManager | Full | Runs only when `syncEnabled && syncOnAppStart`. |
| Periodic | WorkManager | Full | Approximate WorkManager schedule, 15–240 minutes in the UI, `ExistingPeriodicWorkPolicy.UPDATE`. |
| Note close | In-process, application scope | One notebook | Skips successfully if the sync mutex is busy; no WorkManager retry/output handling. |
| Check on open | In-process, application scope | Read-only manifest check | Does not take the sync mutex. |
| Notebook deletion | WorkManager | One tombstone/delete | Does not take the orchestrator's mutex. |
| Force upload/download | WorkManager | All data on one side | Uses the mutex; shows one "Syncing notebooks" step with per-notebook progress. |
| Conflict resolution | In-process | One notebook | Waits for the mutex instead of skipping (5.5). |

All work currently carries the same `sync-work` tag. The settings **Cancel** button calls
`cancelAllWorkByTag`, which therefore also cancels the periodic schedule for the rest of the current
app session; `MainActivity` recreates it on a later app start, but it is not immediately restored by
Cancel itself. Blocking OkHttp calls may not stop the instant Cancel is pressed, since coroutine
cancellation is not guaranteed to interrupt an in-flight synchronous request.

### 3.6 Capability probe and bulk change detection

Two fast paths depend on ETag behaviour that WebDAV does not guarantee, so they are enabled only
after being **measured** on the configured server:

- **Probe.** *Test Connection* first does a `HEAD` of the base URL (auth + clock skew); if that
  succeeds, `CapabilityProbeService.probe` builds a scratch tree `/notable/.capability-probe/notebooks/
  probe/{manifest.json, pages/page.json}` (the same depth as real notebooks), performs an overwrite,
  a tmp-PUT + `MOVE` publish, a deep create and a deep overwrite, reading the `notebooks/` collection
  ETag after each, then deletes the tree. The result is stored as `ServerCapabilities` (Kv key
  `SYNC_SERVER_CAPABILITIES`) with `serverKey` = SHA-256 of server URL + username, and shown in the
  settings as a "Server capabilities" breakdown. Any failure stores all flags `false`. Ordinary syncs
  never probe.
- **`collectionEtagPropagates`** — the collection ETag moved on **all four** mutations. This gates bulk
  change detection.
- **`movePreservesEtag`** — the `MOVE` succeeded and the destination kept the tmp file's exact ETag.
  This lets manifest publish skip the depth-0 `PROPFIND` readback.
- `issuesEtagOnPut` / `issuesStrongEtags` — diagnostics only.

A capability record is used only when its `serverKey` matches the current client's; one from another
server or account is ignored.

**Bulk change detection** (full sync only, when enabled per step 1 of 3.1): the `PROPFIND` of
`/notable/notebooks` already returns every notebook directory's ETag. `notebook_sync_state` keeps a
baseline (`remoteDirEtag`, `remoteDirServerKey`). A notebook whose row is `SYNCED`, whose baseline
was recorded for the current server, and whose baseline weakly matches the listed directory ETag is
treated as "remote unchanged" without fetching its manifest; the planner then decides on local
changes alone.

The baseline is stored only where the listing is known to show the converged state: after a `Skip`, a
download, a download-only merge, and a newly downloaded notebook. It is **not** stored after an upload
(our own write moved the directory ETag, and re-reading it could capture a concurrent writer's), so the
sync after an upload does one ordinary conditional manifest `GET`, whose `304` then re-establishes the
baseline. The ordinary sync-state writes (`markSynced`, `markError`, `markConflict`,
`markRemoteAhead`) build a fresh row and so clear the baseline, and `updateRemoteDirBaseline` only
writes it onto a `SYNCED` row. Storing a baseline never fails a sync. The **Fast change detection** setting turns the fast path off.

## 4. Data format

### 4.1 Server directory structure

All paths are relative to the user-entered WebDAV base URL. `WebDAVClient` trims the base URL's
trailing slash and appends the path, percent-encoding each path segment.

```
/notable/
├── folders.json
├── .capability-probe/           # scratch tree of Test Connection; deleted after the probe
├── deletions/
│   └── {notebook-uuid}          # zero bytes; server lastModified is the deletion time
└── notebooks/
    └── {notebook-uuid}/
        ├── manifest.json
        ├── manifest.json.tmp    # transient publish path; may remain after an interruption
        ├── pages/{page-uuid}.json
        ├── pages/{page-uuid}.json.{uuid}.tmp   # transient page staging; swept by remote GC
        ├── images/{basename}
        └── backgrounds/{basename}
```

### 4.2 manifest.json

```json
{
  "version": 1,
  "notebookId": "550e8400-e29b-41d4-a716-446655440000",
  "title": "Example",
  "pageIds": ["89a61d25-f4ea-4a68-b52b-8b85504a16d4"],
  "parentFolderId": null,
  "defaultBackground": "blank",
  "defaultBackgroundType": "native",
  "defaultSheet": { "width": 1860, "height": 2632 },
  "linkedExternalUri": null,
  "createdAt": "2026-07-27T10:00:00Z",
  "updatedAt": "2026-07-27T10:05:00.123Z",
  "serverTimestamp": "2026-07-27T10:05:01.456Z"
}
```

- `pageIds` is an ordered list, defining page order within the notebook.
- `serverTimestamp` is actually the uploading device's `Instant.now()` at serialization time. The
  sync decision does **not** use it — comparisons are always against `updatedAt`.
- `openPageId` (which page the notebook last had open) is **device-local navigation state and is not
  serialized** — it never travels between devices, and a download preserves the local value rather
  than overwriting it. Older manifests that still carry the key are tolerated and ignored
  (`ignoreUnknownKeys = true`).
- `defaultSheet` is the sheet new pages get, in page pixels (see 4.3). It is left out when new pages
  are infinite.

### 4.3 Page JSON (`pages/{uuid}.json`)

Page JSON schema version is `1`. It contains `id`, a nullable `notebookId` (`null` for standalone
Quick Pages, which are never synced), background fields, nullable `parentFolderId`, `scroll`, an
optional `sheet`, timestamps, and arrays of strokes and images.

**Sheets.** `sheet` is `{ "width": …, "height": … }` in page pixels, the units strokes and images
use; it is left out for an infinite page. A sheet is fixed when the page is created, so a download
never clears one: a missing sheet, or one with a side ≤ 0 or above 20 000 px, keeps the local value
(`downloadedSheet`). That protects sheets from app versions before sheets, which upload pages and
manifests without the key. `defaultSheet` is not compared for structural conflicts (5.3) for the same
reason.

**View position.** The view position is device-local, like `openPageId`, and is never read from the
file: a download keeps the local position, and a page new to the device opens at the top. The vertical
position is still written as `"scroll"` (`legacyScroll` in the DTO), because app versions before
sheets require the key and fail to parse a page without it. Reading treats it as optional, so a later
version can stop writing it once those versions are no longer supported.

Each stroke carries geometry/style metadata (`id`, `size`, `pen`, `color`, `maxPressure`, bounding
box, timestamps) plus `pointsData` — the stroke binary encoding returned by `encodeStrokePoints`,
Base64-wrapped for JSON transport. The encoder writes SB version **2** (normalized pressure, LZ4 above
a size/ratio threshold); the decoder also reads version 1, and imported strokes are normalized with
`withNormalizedPressure()`. The format is specified in [database-structure.md](database-structure.md).

Image URIs are serialized as a relative `parentDirectory/basename` string. Transfer uses the basename
for the remote image path and rewrites downloaded image records to local absolute paths.

### 4.4 folders.json

```json
{
  "version": 1,
  "folders": [
    {
      "id": "folder-uuid",
      "title": "My Folder",
      "parentFolderId": null,
      "createdAt": "2026-06-15T10:30:00Z",
      "updatedAt": "2026-07-20T14:22:33Z"
    }
  ],
  "serverTimestamp": "2026-07-27T08:00:00Z"
}
```

`parentFolderId` references another folder's `id` for nesting, or `null` for root-level folders. The
merge does not use `serverTimestamp` — only each folder's own `updatedAt`.

### 4.5 Tombstone files (`deletions/{uuid}`)

Each deleted notebook has a zero-byte file at `/notable/deletions/{notebook-uuid}`. It has no
content; the server's own `lastModified` timestamp on the file provides the deletion time used for
conflict resolution (section 5.7). Independent per-notebook tombstone files mean two devices can each
write a deletion without racing over a shared file the way a single `deletions.json` would.

### 4.6 JSON configuration

All serializers use `kotlinx.serialization` with `ignoreUnknownKeys = true` for forward compatibility.
Manifest and folder JSON are pretty-printed; streamed page JSON is compact to keep upload memory
bounded.

## 5. Conflict resolution

### 5.1 Last-writer-wins and concurrent reconciliation

There is no CRDT or operational transform. The system handles divergence in two ways:

- **Clear timestamp winner:** upload or download the newer notebook (5.2).
- **Tied timestamps with a changed ETag:** reconcile pages, merging independent edits and flagging
  same-page or structural conflicts (5.3). `ASK` is implemented; `SERVER_WINS` and `LOCAL_WINS`
  remain placeholders for future automatic resolution.

The unit that flags, badges, and resolves is the notebook; the unit that actually transfers is the
page.

### 5.2 Timestamp comparison

When both local and remote versions of a notebook exist:

```
diffMs = local.updatedAt - remote.updatedAt

if diffMs > +1000ms  → local is newer  → upload
if diffMs < -1000ms  → remote is newer → download
if |diffMs| <= 1000ms → within tolerance → the ETag decides (see 5.3)
```

Within tolerance, an unchanged manifest ETag means the sides already agree (skip); a *changed* ETag
means concurrent edits that the tie cannot prove equal, so the planner returns `Reconcile`.

### 5.3 Concurrent edits: independent merge vs. genuine conflict

`NotebookReconciliationService.reconcileConcurrentEdits` classifies the divergence:

- **Independent edits** — the manifests match structurally and no single page was touched on both
  sides. Pull remote changes, then push local-only changes. Skip publication when nothing remains
  locally dirty, preventing manifest ETag ping-pong.
- **Genuine conflict** — either a *page conflict* (the same page edited locally **and** remotely since
  the last common sync) or a *structural conflict* (the manifests disagree on page set, order, title,
  parent folder, default background, linked file, or `createdAt`; an unreadable remote manifest counts
  as structural). `defaultSheet` is not compared: a download never clears it (4.3), so a difference
  only means one side came from an app version without sheets. Mark the notebook `CONFLICT` and leave both copies unchanged.

Conflicts are only *detected* in two-way sync, because that's the only mode that produces a
`Reconcile` (see 5.9).

### 5.4 The `ASK` conflict model

Under `ASK`, the resolution UI reads conflict details through the read-only
`SyncOrchestrator.notebookConflict` call:

- **Page conflicts** are resolved one page at a time via `PageConflictResolution`:
  - `REPLACE_WITH_SERVER` — take the server's copy of that page;
  - `UPLOAD_DB` — keep the local copy and overwrite the server's;
  - `SKIP` — leave the page diverged; it stays flagged and is asked again next sync.
- **Structural conflicts** are resolved whole-notebook via `NotebookConflictResolution`:
  - `TAKE_SERVER` — download the server notebook over the local one;
  - `KEEP_LOCAL` — keep the local structure and overwrite the server's.

The `CONFLICT` badge clears once the last conflict is resolved and its transfer commits.

### 5.5 Applying a resolution

A resolution reuses the normal transfer path in two phases:

1. **Rebaseline.** Rewrite the page or notebook sync-state row so reconciliation sees a normal change:
   - `REPLACE_WITH_SERVER` drops the stored page ETag and lifts the change anchor to the local page's
     own timestamp, causing a download.
   - `UPLOAD_DB` adopts the server's *current* page ETag as the base while leaving local content dirty
     so the locally dirty page uploads against the latest server version.
   - `KEEP_LOCAL` re-anchors the manifest and every page row to the server's current ETags **without**
     advancing the change anchor → the still-newer local copy uploads next, guarded by up-to-date
     ETags. The newer local notebook then uploads instead of receiving a 412. `TAKE_SERVER` downloads
     immediately and needs no second phase.
2. **Transfer.** `SyncOrchestrator.runResolutionTransfer` runs normal whole-notebook reconciliation.

Both phases hold the sync mutex. If transfer fails, the rebaselined state remains pending for the
next sync. Resolving one page therefore re-plans the whole notebook without duplicating transfer and
commit logic.

### 5.6 Conflicts and one-directional sync

Resolution may need either upload or download, so `SyncOrchestrator.resolutionPreflight` rejects it in
a one-directional mode with `SyncDirectionalConflict`. The `CONFLICT` badge remains until the user
returns to two-way sync. Directional sync does not create new conflicts because it never executes a
live `Reconcile` action.

### 5.7 Deletion vs. edit conflicts (resurrection)

Notebook deletions use zero-byte tombstone files rather than a shared deletions list. When applying a
remote tombstone: if the local notebook's `updatedAt` is **after** the tombstone's `lastModified`,
the notebook is kept and re-uploaded ("resurrected") instead of deleted, and the tombstone is removed
from the server on that notebook's next successful upload. Otherwise the local notebook is deleted
and its `notebook_sync_state` row is dropped. This favors not losing a post-deletion edit over a
deletion always sticking.

### 5.8 Folder merge

Folders use a simpler per-folder last-writer-wins merge: all remote folders load into a map, and a
local folder replaces its remote counterpart only when its `updatedAt` is later. There is no folder
tombstone, so an absent folder is indistinguishable from a new remote folder and can reappear after
being deleted on one device and synced from another — notebooks inside it are not deleted merely
because the folder reappears.

### 5.9 Reconciliation decision (`NotebookSyncPlanner`)

`NotebookSyncPlanner.decide(...)` is a pure, unit-tested function that takes the local `updatedAt`,
the stored sync-state anchor, and the remote manifest facts, and returns one of `Upload`, `Download`,
`Skip`, `Reconcile`, `SkipUploadOnly`, or `SkipDownloadOnly`:

- unchanged remote (a `304`, or a bulk-detection match) + local timestamp more than 1 second past
  the stored anchor, or no anchor → `Upload`; otherwise `Skip`;
- changed remote, one side clearly newer → `Upload` / `Download` per the 1-second rule in section 5.2;
- changed remote, timestamps tied within tolerance → `Reconcile` (a tie is not proof of page equality,
  so the executor merges per page rather than mark it synced over possibly-stale pages — see 5.3);
- upload-only suppresses downloads and records `REMOTE_AHEAD` instead of a misleading `SYNCED`, and
  degrades a `Reconcile` to that same skip (it can't pull the remote half);
- download-only suppresses uploads (keeps the `NOT_SYNCED` badge, since local changes genuinely aren't
  on the server) and degrades a `Reconcile` to its download half.

Because a one-directional mode never yields a live `Reconcile`, conflicts are only flagged in two-way
sync. `NotebookReconciliationService` does the I/O and executes the decision. Upload-only and
download-only are mutually exclusive in the settings UI.

### 5.10 Per-notebook and per-page sync state

Two Room tables (keyed by id, **no** foreign key to `Notebook`/`Page` — the rows must outlive local
deletion) are the source of truth for sync bookkeeping.

`notebook_sync_state` — per-notebook commit marker and badge state:

| Column | Meaning |
|---|---|
| `notebookId` | Primary key. |
| `state` | `SYNCED`, `ERROR`, `REMOTE_AHEAD`, or `CONFLICT`. |
| `lastSyncedAt` | Device time at the latest state write, including errors/remote-ahead/conflict. |
| `syncedLocalUpdatedAt` | Local notebook timestamp captured at the last committed sync — the dirty anchor. |
| `remoteEtag` | Last known manifest ETag, sent as `If-None-Match` on the next reconcile. |
| `remoteUpdatedAt` | Last known remote manifest timestamp. |
| `lastError` | Last transfer error message, if any. |
| `remoteDirEtag` | Notebook directory ETag at the last converged sync — the bulk-detection baseline (3.6). |
| `remoteDirServerKey` | `serverKey` of the server that baseline was observed on; a baseline from another server is never used. |

`page_sync_state` — per-page commit marker driving dirty-page selection (3.2) and page-conflict
detection (5.3):

| Column | Meaning |
|---|---|
| `pageId` | Primary key. |
| `notebookId` | Indexed; groups a notebook's page rows. |
| `remoteEtag` | The page file's server ETag at the last committed sync, in the server's own spelling — parsed and compared through `ETag`, never `==`. |
| `syncedLocalUpdatedAt` | The change anchor: `Page.updatedAt` at that commit. A strict `>` against the current value answers "edited since?" (same clock on both sides, so no tolerance needed). |
| `lastSyncedAt` | Wall-clock of the last committed sync; informational, never compared. |

Committed state is written **only inside** the notebook's commit transaction (after the manifest is
published on upload / the atomic page swap on download); the notebook row additionally records
`ERROR`, `REMOTE_AHEAD`, and `CONFLICT` outcomes, and conflict resolution rebaselines rows (5.5). A sync killed before a notebook's commit writes no sync-state rows for that
notebook (notebooks committed earlier in the same run keep theirs), so the next sync re-transfers the
same pages — "skip" is exactly as trustworthy as the notebook badge.

`NotebookSyncStatusStore` derives a badge for every notebook: `ERROR` if the row is in error;
`CONFLICT` if it's flagged; `NOT_SYNCED` if there is no row or local time is more than one second past
the anchor; `REMOTE_AHEAD` for a still-unedited upload-only skip; otherwise `SYNCED`; during a full
sync, the current item becomes `SYNCING` and other non-synced items become `SCHEDULED`. The store
never checks remote state itself — outside an active sync, badges describe local state relative to the
last recorded commit, not guaranteed present-day server equality. The first sync after an upgrade that
introduced these tables simply repopulates them; there is no migration path from a predecessor.

### 5.11 Media handling

Media (images and page backgrounds) is transferred alongside pages, but with rules that keep one
missing or unsyncable file from wedging a whole notebook:

- a media 404 is non-fatal: it's logged and dropped rather than accumulated, so the notebook still
  commits and stops being retried for that file; other media failures still block the commit;
- a locally referenced media file that can't be found is logged but does not stop the upload of the
  rest of the notebook;
- backgrounds stored as an absolute path are skipped on upload and download. Only a background path
  relative to `notabledb/backgrounds` is transferred, but the background picker and PDF import both
  store absolute paths (`notabledb/backgrounds/<type>/…` or the linked file), so in practice image and
  PDF backgrounds are not synced; the page keeps the path, which resolves only where that file exists.
  Native templates are a name, not a file, and need no transfer.

A notebook that committed with a media 404 is **not** automatically re-fetched if that media later
appears on the server; a force download or a local edit that re-triggers an upload/download is the
current recovery path.

## 6. Security

### 6.1 Credential storage

Credentials are persisted via the app's key-value Room table (`kv`, through `KvProxy`). The password
is encrypted using an AES-GCM key stored in AndroidKeyStore; on read, a decryption failure returns
settings with a blank password rather than throwing (sync then treats it as missing credentials). Writes go
through `KvProxy.updateSyncSettings`, which never sees the plaintext password and keeps the stored
encrypted value unless a new password is given, so a failed decrypt never erases the saved password.
In the settings UI a blank password field means "unchanged".

### 6.2 Transport security

HTTPS is recommended but not enforced — HTTP base URLs are accepted, since some users run WebDAV on a
trusted local network. TLS uses OkHttp/system certificate validation; there is no custom CA or
certificate-pinning UI.

### 6.3 Logging

`SyncLogger` keeps a memory-only ring buffer of the last 50 entries, exposed as a `StateFlow` for the
UI. It omits credentials and is not a durable audit log.

### 6.4 Server-side data

Notebook data on the WebDAV server is **not** end-to-end encrypted by Notable. Server-side encryption,
retention, backups, and account security depend entirely on the provider.

## 7. Error handling and recovery

### 7.1 Error types

HTTP calls return `AppResult<…, DomainError>`. The variants relevant to sync:

```kotlin
DomainError.NetworkError(message)           // thrown request/URL exception
DomainError.SyncAuthError                    // credentials missing or rejected
DomainError.SyncConfigError                  // sync disabled / not configured
DomainError.SyncClockSkew(seconds)           // device clock differs from server by >30s
DomainError.SyncWifiRequired                 // wifiOnly set but not on an unmetered network
DomainError.SyncInProgress                   // another sync already holds the mutex
DomainError.SyncConflict                     // PUT/MOVE precondition failure (HTTP 412)
DomainError.SyncDirectionalConflict          // conflict resolution attempted in upload/download-only
DomainError.SyncError(message, recoverable)  // generic non-success HTTP status
DomainError.RemoteMissing(path)              // GET 404; non-recoverable, non-fatal for media
```

Multiple per-notebook errors are aggregated into `MultipleErrors`, which is `recoverable` only when
every child is, so an aggregate containing a permanent error is not retried by the worker.

### 7.2 Concurrency control

The orchestrator's mutex covers full sync, single-notebook sync, force operations, and conflict
resolution. It does not
cover check-on-open or targeted notebook deletion (section 3.4). There is no cross-device locking —
WebDAV provides no atomic multi-file transaction, which is why the ordering guarantees in
[section 1](#1-architecture-overview) matter.

### 7.3 Retry strategy (`SyncWorker`)

- Network unavailable (pre-check): retried immediately through WorkManager.
- `NetworkError` during sync: retried up to 3 worker attempts, then failed.
- Auth/config/clock/Wi-Fi/conflict errors: never retried, fail immediately.
- Other errors: retried only when `recoverable`, else failed immediately.
- Disabled sync, missing credentials, or an unmet unmetered constraint: treated as a successful skip.
- `SyncInProgress`: treated as completed work with a non-success informational payload, not a hard
  failure — a manual Sync Now can legitimately collide with a running periodic sync.
- The worker also has a broad `catch (Exception)`, which includes cancellation exceptions; an
  unexpected exception is retried up to 3 attempts.

### 7.4 WebDAV idempotency

`MKCOL` returning 405 is treated as success — per RFC 4918 that means the collection already exists
(only accepted on `MKCOL`; a 405 on any other operation is an error). `DELETE` returning 404 is
treated as success — the resource is already gone. Both are therefore safe to retry.

## 8. Integrations

| Dependency | Purpose |
|---|---|
| `com.squareup.okhttp3:okhttp` | HTTP client for all WebDAV operations (shared `OkHttpClient` instance). |
| AndroidKeyStore (via `CryptoHelper`) | AES-GCM keys for encrypting sync passwords at rest. |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | JSON serialization/deserialization for manifests, folders, and pages. |
| `androidx.work:work-runtime-ktx` | Background sync scheduling (one-time and periodic). |

## 9. Limitations and test coverage

- Same-page conflicts require choosing one whole page; there is no stroke-level merge.
- `SERVER_WINS` and `LOCAL_WINS` remain persisted placeholders. The engine always uses `ASK`.
- Conflict resolution requires two-way sync, and folder deletions do not propagate.
- Weak or missing ETags cannot guard writes. When a server supplies only those, `ETag.writeGuard`
  drops the precondition and conflict handling degrades to last-writer-wins (logged once per notebook
  upload in the sync log), and remote GC after upload is skipped.
- Custom image and PDF backgrounds are stored as absolute paths and so are not synced (5.11).
- When an app version without sheets edits a sheet page and uploads it, the server copy loses the
  sheet. Devices that already have the page keep theirs (4.3), but a device that downloads the page
  for the first time gets an infinite page. The server copy gets its sheet back when a newer device
  uploads that page again, which happens only after an edit to it.
- Conflict resolution relies on synchronized device clocks; normal preflight rejects skew over 30
  seconds.
- `WebDavClientFactoryPort` abstracts client construction only. Transfer services still accept the
  concrete `WebDAVClient`, and all of them reach through the broad `AppRepository` — the main
  obstacle to executor-level tests today.
- Unit tests cover serializers (including page and manifest files in the format before sheets, and
  parsing by the page DTO of those versions), the download row rules (`DownloadedRowsTest`), the
  pure planner and page selector, `ETag` parsing/comparison, the
  capability-probe verdict, the bulk fast-path gate, the mass-deletion guard, new-notebook selection,
  `WebDAVClient` requests, path encoding, progress reporting, logger behavior, factory construction,
  HTTP-date parsing, and `finalizeSyncResult`.
- There are no executor-level tests for `NotebookSyncService`, `NotebookReconciliationService`,
  `FolderSyncService`, `SyncForceService`, scheduler cancellation, trigger integration, or remote JSON
  identity/path validation. The seam these need is a narrow transfer port in place of the concrete
  `WebDAVClient` and the broad `AppRepository`.
