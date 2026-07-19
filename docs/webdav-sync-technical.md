# WebDAV Sync - Technical Documentation

This document describes the architecture, protocol, data formats, and design decisions of Notable's
WebDAV synchronization system. For user-facing setup and usage instructions,
see [webdav-sync-user.md](webdav-sync-user.md).

**It was created by AI, and roughly checked for correctness.
Refer to code for actual implementation.**

## Contents

- [1) Architecture Overview](#1-architecture-overview)
- [2) Component Overview](#2-component-overview)
- [3) Sync Protocol](#3-sync-protocol)
- [4) Data Format Specification](#4-data-format-specification)
- [5) Conflict Resolution](#5-conflict-resolution)
- [6) Security Model](#6-security-model)
- [7) Error Handling and Recovery](#7-error-handling-and-recovery)
- [8) Integration Points](#8-integration-points)
- [9) Future Work](#9-future-work)

---

## 1) Architecture Overview

The current sync architecture is service-oriented: `SyncOrchestrator` coordinates the flow,
while focused services handle preflight checks, folder sync, notebook reconciliation/transfer,
and force operations. WebDAV client creation is abstracted behind
`WebDavClientFactoryPort` (`SyncPorts.kt`) to reduce direct infrastructure coupling.

Two invariants make the transfer layer trustworthy:

1. **Commit-marker ordering** — on upload the `manifest.json` is written *last* (after all pages);
   on download the local notebook timestamp is committed *last* (after all pages land). If the
   commit marker is present, everything it references is present. An interrupted sync therefore
   leaves both sides either fully old or fully new, never a half-written mix.
2. **Persisted per-notebook sync state** — a dedicated Room table `notebook_sync_state` records,
   per notebook, when a complete round-trip last committed. Badges, local-deletion detection, and
   the "already in sync" skip decision all read this one table (it replaced the former
   `SyncSettings.syncedNotebookIds` set). See section 5.9.

---

## 2) Component Overview

All sync code lives in `com.ethran.notable.sync`. The components and their responsibilities:

```
┌─────────────────────────────────────────────────────────────┐
│                     SyncOrchestrator                       │
│  Orchestrates full sync flow, holds syncMutex.             │
├─────────────────────────────────────────────────────────────┤
│  SyncPreflightService      FolderSyncService               │
│  NotebookReconciliationService  NotebookSyncService         │
│  SyncForceService          SyncProgressReporter (state)    │
├─────────────────────────────────────────────────────────────┤
│  WebDavClientFactoryPort -> WebDavClientFactoryAdapter     │
│  WebDAVClient (OkHttp, PROPFIND/XML)                       │
└─────────────────────────────────────────────────────────────┘
```

| File                                                                                                                | Role                                                                                                                                                               |
|---------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [`SyncOrchestrator.kt`](../app/src/main/java/com/ethran/notable/sync/SyncOrchestrator.kt)                           | Core orchestrator. Full sync flow, per-notebook trigger, deletion upload. Holds the shared `syncMutex` (companion object) for process-wide concurrency control. Delegates progress/state reporting to `SyncProgressReporter`. |
| [`SyncPreflightService.kt`](../app/src/main/java/com/ethran/notable/sync/SyncPreflightService.kt)                   | Pre-sync checks and server directory bootstrap (`/notable`, `/notebooks`, `/deletions`).                                                                           |
| [`FolderSyncService.kt`](../app/src/main/java/com/ethran/notable/sync/FolderSyncService.kt)                         | Folder hierarchy sync (folders.json merge + upsert).                                                                                                               |
| [`NotebookReconciliationService.kt`](../app/src/main/java/com/ethran/notable/sync/NotebookReconciliationService.kt) | Per-notebook conflict decision (upload/download/no-op) based on manifest timestamps. Reports per-item progress via `SyncProgressReporter.beginItem`/`endItem` (passing the notebook `id`). On an "already in sync" skip it refreshes the notebook's `notebook_sync_state` row. |
| [`NotebookSyncService.kt`](../app/src/main/java/com/ethran/notable/sync/NotebookSyncService.kt)                     | Per-notebook upload/download execution (pages-before-manifest ordering), remote-deletion application, local-deletion detection, new-notebook discovery. Writes `notebook_sync_state` rows at commit points. |
| [`SyncProgressReporter.kt`](../app/src/main/java/com/ethran/notable/sync/SyncProgressReporter.kt)                   | `@Singleton` owner of the `SyncState` `StateFlow`. Interface + `SyncProgressReporterImpl` + Hilt `@Binds` module + `SyncProgressReporterEntryPoint`. Write-side API: `beginStep`, `beginItem`, `endItem`, `finishSuccess`, `finishError`, `reset`. Read-side: `state`. Consumers inject `SyncProgressReporter` rather than touching `SyncOrchestrator` for state. |
| [`SyncState.kt`](../app/src/main/java/com/ethran/notable/sync/SyncState.kt)                                         | The `SyncState` sealed class, `ItemProgress`, `SyncStep` enum, and `SyncSummary` data model rendered by the sync UI.                                               |
| [`SyncForceService.kt`](../app/src/main/java/com/ethran/notable/sync/SyncForceService.kt)                           | Force upload/download flows (full side replacement) used by settings actions. Non-destructive: force-download verifies the server has content before wiping local; force-upload uploads before deleting server extras. |
| [`SyncPorts.kt`](../app/src/main/java/com/ethran/notable/sync/SyncPorts.kt)                                         | DI port/adapter for WebDAV client creation (`WebDavClientFactoryPort`) plus the shared singleton `OkHttpClient` (`SyncHttpModule`) — one connection pool for all sync operations. |
| [`WebDAVClient.kt`](../app/src/main/java/com/ethran/notable/sync/WebDAVClient.kt)                                   | HTTP/WebDAV operations built on a shared `OkHttpClient`. Connection testing, file uploads/downloads and metadata (ETag) support, `If-Match`-guarded uploads for optimistic concurrency, tri-state `exists()`. All methods funnel through one private `execute()` helper. |
| [`WebDavXml.kt`](../app/src/main/java/com/ethran/notable/sync/WebDavXml.kt)                                         | PROPFIND XML parsing (`parseHrefs`, `parseEntries`) and UUID validation, split out of `WebDAVClient`.                                                              |
| [`SyncPaths.kt`](../app/src/main/java/com/ethran/notable/sync/SyncPaths.kt)                                         | Centralized server path construction (root, notebooks, deletions, per-notebook manifest/pages/images/backgrounds, tombstones).                                     |
| [`NotebookSyncState.kt`](../app/src/main/java/com/ethran/notable/data/db/NotebookSyncState.kt)                     | Room entity + DAO + repository for the `notebook_sync_state` table: per-notebook commit bookkeeping (state, `lastSyncedAt`, `localUpdatedAtAtSync`, `remoteEtag`, `remoteUpdatedAt`). No foreign key to `Notebook` (must outlive local deletion). |
| [`NotebookSyncStatusStore.kt`](../app/src/main/java/com/ethran/notable/sync/NotebookSyncStatusStore.kt)             | Derives `Flow<Map<notebookId, SyncBadge>>` for the library cover badges by combining the notebook list, the `notebook_sync_state` table, and the live `SyncState`. Nothing extra is stored — the badge is a pure function of those three sources. |
| [`NotebookSerializer.kt`](../app/src/main/java/com/ethran/notable/sync/serializers/NotebookSerializer.kt)           | Serializes/deserializes notebooks, pages, strokes, and images to/from JSON. Stroke points are embedded as base64-encoded [SB1 binary](database-structure.md) data. |
| [`FolderSerializer.kt`](../app/src/main/java/com/ethran/notable/sync/serializers/FolderSerializer.kt)               | Serializes/deserializes the folder hierarchy to/from `folders.json`.                                                                                               |
| [`SyncWorker.kt`](../app/src/main/java/com/ethran/notable/sync/SyncWorker.kt)                                       | `CoroutineWorker` for WorkManager integration. Checks connectivity and credentials before delegating to `SyncOrchestrator`.                                        |
| [`SyncScheduler.kt`](../app/src/main/java/com/ethran/notable/sync/SyncScheduler.kt)                                 | Schedules/cancels periodic sync via WorkManager.                                                                                                                   |
| `KvProxy` (Room `kv` table) + [`CryptoHelper.kt`](../app/src/main/java/com/ethran/notable/data/db/CryptoHelper.kt) | Credentials are persisted to the app key-value Room table and encrypted using the app's `CryptoHelper` (AES-GCM keys held in the AndroidKeyStore).                 |
| [`ConnectivityChecker.kt`](../app/src/main/java/com/ethran/notable/sync/ConnectivityChecker.kt)                     | Queries Android `ConnectivityManager` for network/WiFi availability.                                                                                               |
| [`SyncLogger.kt`](../app/src/main/java/com/ethran/notable/sync/SyncLogger.kt)                                       | Maintains a ring buffer of recent log entries (exposed as `StateFlow`) for the sync UI.                                                                            |

---

## 3) Sync Protocol

### 3.1 Full Sync Flow (`syncAllNotebooks`)

A full sync executes the following steps in order. A coroutine `Mutex` prevents concurrent sync
operations on a single device (see section 7.2 for multi-device concurrency).

```
1. INITIALIZE
   ├── Acquire the process-wide sync mutex (tryLock; SYNC_IN_PROGRESS if already held)
   ├── Load SyncSettings; abort if sync disabled (CONFIG) or credentials blank (AUTH)
   ├── checkWifiConstraint (if wifiOnly and not on unmetered → WIFI_REQUIRED)
   ├── Construct WebDAVClient (shared OkHttpClient)
   ├── checkClockSkew (HEAD, read Date header; >30s → CLOCK_SKEW)
   └── Ensure /notable/, /notable/notebooks/, /notable/deletions/ exist on server (MKCOL)

2. SYNC FOLDERS
   ├── GET /notable/folders.json (if exists) + capture ETag
   ├── Merge: for each folder, keep the version with the later updatedAt
   ├── Upsert merged folders into local Room database (skipped when upload-only)
   └── PUT /notable/folders.json with If-Match (captured ETag)

3. APPLY REMOTE DELETIONS  (skipped entirely when upload-only)
   ├── PROPFIND /notable/deletions/ (Depth 1) → list of tombstone files with lastModified
   ├── For each tombstone (filename = deleted notebook UUID):
   │   ├── If local notebook was modified AFTER the tombstone's lastModified → SKIP (resurrection)
   │   └── Otherwise → delete local notebook AND its notebook_sync_state row
   ├── Prune tombstones older than 90 days (TOMBSTONE_MAX_AGE_DAYS)
   └── Return tombstonedIds set for use in later steps

4. SYNC EXISTING LOCAL NOTEBOOKS
   ├── Snapshot local notebook IDs (the "pre-download set", returned for later steps)
   └── For each local notebook (per-item progress carries the notebook id):
       ├── HEAD /notable/notebooks/{id}/manifest.json  (tri-state: present / absent / error)
       │   └── On "error" (network/unexpected status) → abort THIS notebook, never upload blind
       ├── If remote exists:
       │   ├── GET manifest.json + capture ETag, parse updatedAt
       │   ├── Compare timestamps (with ±1s tolerance):
       │   │   ├── Local newer → upload notebook (manifest PUT with If-Match = captured ETag)
       │   │   ├── Remote newer → download notebook (upload-only: skip → SyncUploadOnlySkip)
       │   │   └── Within tolerance → skip, but refresh the notebook_sync_state row (SYNCED)
       │   └── If server changed between GET and PUT, server returns 412 and sync reports CONFLICT
       └── If remote doesn't exist → upload notebook (no If-Match)

5. DOWNLOAD NEW NOTEBOOKS FROM SERVER  (skipped entirely when upload-only)
   ├── PROPFIND /notable/notebooks/ (Depth 1) → list of notebook directory UUIDs
   ├── Filter out: already-local (pre-download set), tombstoned, and IDs still present in
   │   notebook_sync_state (previously synced then locally deleted — do not resurrect)
   └── For each new notebook ID → download notebook

6. DETECT AND UPLOAD LOCAL DELETIONS
   ├── deletedLocally = notebook_sync_state IDs − pre-download snapshot
   ├── For each: if present on server, DELETE /notable/notebooks/{id}/
   ├── PUT zero-byte file to /notable/deletions/{id} (tombstone for other devices)
   └── On tombstone success → drop the notebook_sync_state row

7. FINALIZE
   ├── No bulk finalize: each notebook's sync-state row was written at its own commit point,
   │   and deletions dropped their rows in step 6.
   └── On overall success, persist SyncSettings.lastSyncTime = now
```

Per-notebook success/failure is isolated: a single notebook error is accumulated (via
`ErrorAccumulator`) and reported, but does not abort the remaining notebooks. Only top-level step
failures (preflight, directory bootstrap, folder sync, a hard error from a whole step) abort the
run via early return.

### 3.2 Per-Notebook Upload

Conflict detection is at the **notebook level** (manifest `updatedAt`). Individual pages are
uploaded as separate files, but if two devices have edited different pages of the same notebook, the
device with the newer `updatedAt` wins the entire notebook (see section 5.6).

**Ordering is deliberate: pages first, manifest last.** The manifest is the commit marker (it
carries `pageIds` and the `updatedAt` that drives all conflict resolution). Writing it last means
an interrupted upload leaves the *old* manifest in place, so no other device downloads a
half-written notebook. Any new page files uploaded before the interruption sit unreferenced until
the origin device re-syncs and rewrites the manifest (harmless orphans; Phase 7 GC would sweep
them).

```
uploadNotebook(notebook, manifestIfMatch?):
  1. MKCOL /notable/notebooks/{id}/pages/, images/, backgrounds/
  2. For each page (uploaded FIRST):
     a. Serialize page JSON (strokes embedded as base64-encoded SB1 binary)
     b. PUT /notable/notebooks/{id}/pages/{pageId}.json
     c. For each image on the page:
        - If local file exists and not already on server → PUT to images/
     d. If page has a custom background (not native template):
        - If local file exists and not already on server → PUT to backgrounds/
  3. If ANY page failed → do NOT publish the manifest (leave old commit marker; retry next sync)
  4. Otherwise PUT /notable/notebooks/{id}/manifest.json LAST, with If-Match (manifestIfMatch)
  5. On manifest success (commit point):
     a. markSynced() → write the notebook_sync_state row (SYNCED, at notebook.updatedAt)
     b. If a stale tombstone exists for this id (resurrected notebook) → DELETE it (best-effort)
```

### 3.3 Per-Notebook Download

**Ordering is deliberate: pages first, notebook-timestamp commit last.** If a page fails, the
local notebook keeps its old (or sentinel) timestamp, so the next sync sees "remote newer" and
re-downloads rather than treating the hole as "in sync". A brand-new notebook is first inserted
with an epoch-0 (`Date(0)`) timestamp so a partial download reads as older-than-remote.

```
downloadNotebook(notebookId):
  1. GET /notable/notebooks/{id}/manifest.json → parse to Notebook
  2. If the notebook is new locally → createEmpty() with updatedAt = Date(0) (sentinel).
     Existing notebooks keep their current (older) timestamp untouched for now.
  3. For each pageId in manifest.pageIds → downloadPage():
     a. GET /notable/notebooks/{id}/pages/{pageId}.json → parse to (Page, Strokes, Images)
     b. For each image referenced (only if not already present locally):
        - GET from images/ → save to local /Documents/notabledb/images/
        - Update image URI to local absolute path
     c. If custom background (not present locally): GET from backgrounds/ → save locally
     d. Persist the page ATOMICALLY via AppRepository.replaceDownloadedPage() — a single Room
        @Transaction that deletes old strokes/images, upserts the page, and inserts the new
        strokes/images, so a crash cannot leave the page half-swapped (P5).
  4. Commit (only if every page landed): updatePreservingTimestamp() writes the notebook row with
     the real remote timestamp, then markSynced() writes the notebook_sync_state row.
     On any page failure, the timestamp is left stale and the row is NOT written → retry next sync.
```

### 3.4 Single-Notebook Sync (`syncNotebook`)

Used for sync-on-close (triggered when the user closes the editor). Follows the same
timestamp-comparison logic as step 4 of the full sync, but operates on a single notebook without the
full deletion/discovery flow. It **holds the same process-wide mutex** (`tryLock`): if a full or
periodic sync is already running, the single-notebook sync returns `Success` immediately (skip — the
running sync will cover it) rather than racing it. It runs its own preflight (wifi + clock skew)
before reconciling the one notebook.

### 3.5 Deletion Propagation (`uploadDeletion`)

When a notebook is deleted locally, a targeted operation can immediately propagate the deletion to
the server without running a full sync:

1. If the notebook's directory exists on the server, DELETE it (existence-check errors are ignored
   here — DELETE is idempotent and a full sync reconciles any leftover).
2. PUT a zero-byte file to `/notable/deletions/{id}` (the server's own `lastModified` on this file
   serves as the deletion timestamp for other devices' conflict resolution).
3. On tombstone success, drop the notebook's `notebook_sync_state` row.

---

## 4) Data Format Specification

### 4.1 Server Directory Structure

```
/notable/                           ← Appended to user's server URL
├── folders.json                    ← Complete folder hierarchy
├── deletions/                     ← Deletion tracking (zero-byte files)
│   └── {uuid}                      ← One per deleted notebook; server lastModified = deletion time
└── notebooks/
    └── {uuid}/                     ← One directory per notebook, named by UUID
        ├── manifest.json           ← Notebook metadata
        ├── pages/
        │   └── {uuid}.json         ← Page data with embedded strokes
        ├── images/
        │   └── {filename}          ← Image files referenced by pages
        └── backgrounds/
            └── {filename}          ← Custom background images
```

### 4.2 manifest.json

```json
{
  "version": 1,
  "notebookId": "550e8400-e29b-41d4-a716-446655440000",
  "title": "My Notebook",
  "pageIds": [
    "page-uuid-1",
    "page-uuid-2"
  ],
  "openPageId": "page-uuid-1",
  "parentFolderId": "folder-uuid-or-null",
  "defaultBackground": "blank",
  "defaultBackgroundType": "native",
  "linkedExternalUri": null,
  "createdAt": "2025-06-15T10:30:00Z",
  "updatedAt": "2025-12-20T14:22:33Z",
  "serverTimestamp": "2025-12-21T08:00:00Z"
}
```

- `version`: Schema version for forward compatibility. Currently `1`.
- `pageIds`: Ordered list -- defines page ordering within the notebook.
- `serverTimestamp`: Set at serialization time. Used for sync comparison.
- All timestamps are ISO 8601 UTC.

### 4.3 Page JSON (`pages/{uuid}.json`)

```json
{
  "version": 1,
  "id": "page-uuid",
  "notebookId": "notebook-uuid",
  "background": "blank",
  "backgroundType": "native",
  "parentFolderId": null,
  "scroll": 0,
  "createdAt": "2025-06-15T10:30:00Z",
  "updatedAt": "2025-12-20T14:22:33Z",
  "strokes": [
    {
      "id": "stroke-uuid",
      "size": 3.0,
      "pen": "BALLPOINT",
      "color": -16777216,
      "maxPressure": 4095,
      "top": 100.0,
      "bottom": 200.0,
      "left": 50.0,
      "right": 300.0,
      "pointsData": "U0IBCgAAAA...",
      "createdAt": "2025-12-20T14:22:00Z",
      "updatedAt": "2025-12-20T14:22:33Z"
    }
  ],
  "images": [
    {
      "id": "image-uuid",
      "x": 0,
      "y": 0,
      "width": 800,
      "height": 600,
      "uri": "images/abc123.jpg",
      "createdAt": "2025-12-20T14:22:00Z",
      "updatedAt": "2025-12-20T14:22:33Z"
    }
  ]
}
```

- `strokes[].pointsData`: Base64-encoded SB1 binary format.
  See [database-structure.md](database-structure.md) section 3 for the full SB1 specification. This
  is the same binary format used in the local Room database, base64-wrapped for JSON transport.
- `strokes[].color`: ARGB integer (e.g., `-16777216` = opaque black).
- `strokes[].pen`: Enum name from the `Pen` type (BALLPOINT, FOUNTAIN, PENCIL, etc.).
- `images[].uri`: Relative path on the server (e.g., `images/filename.jpg`). Converted to/from
  absolute local paths during upload/download.
- `notebookId`: May be `null` for Quick Pages (standalone pages not belonging to a notebook).

### 4.4 folders.json

```json
{
  "version": 1,
  "folders": [
    {
      "id": "folder-uuid",
      "title": "My Folder",
      "parentFolderId": null,
      "createdAt": "2025-06-15T10:30:00Z",
      "updatedAt": "2025-12-20T14:22:33Z"
    }
  ],
  "serverTimestamp": "2025-12-21T08:00:00Z"
}
```

- `parentFolderId`: References another folder's `id` for nesting, or `null` for root-level folders.
- Folder hierarchy must be synced before notebooks because notebooks reference `parentFolderId`.

### 4.5 Tombstone Files (`deletions/{uuid}`)

Each deleted notebook has a zero-byte file at `/notable/deletions/{notebook-uuid}`. The file has no
content; the server's own `lastModified` timestamp on the file provides the deletion time used for
conflict resolution (section 5.3).

**Why tombstones instead of a shared `deletions.json`?** Two devices syncing simultaneously would
both read `deletions.json`, append their entry, and write back — the second writer clobbers the
first. With tombstones, each deletion is an independent PUT to a unique path, so there is nothing to
race over.

Current implementation does not include a `deletions.json` migration path; tombstones are the only
supported deletion propagation mechanism.

### 4.6 JSON Configuration

All serializers use `kotlinx.serialization` with:

- `prettyPrint = true`: Human-readable output, debuggable on the server.
- `ignoreUnknownKeys = true`: Forward compatibility. If a future version adds fields, older clients
  can still parse the JSON without crashing.

---

## 5) Conflict Resolution

### 5.1 Strategy: Last-Writer-Wins with Resurrection

The sync system uses **timestamp-based last-writer-wins** at the notebook level. This is a
deliberate simplicity tradeoff:

- **Simpler than CRDT or operational transform.** These are powerful but add substantial complexity
  and are difficult to get right for a handwriting/drawing app where strokes are the atomic unit.
- **Appropriate for the use case.** Most Notable users have one or two devices. Simultaneous editing
  of the same notebook on two devices is rare. When it does happen, the most recent edit is almost
  always the one the user wants.
- **Predictable behavior.** Users can reason about "I edited this last, so my version wins" without
  understanding distributed systems theory.

### 5.2 Timestamp Comparison

When both local and remote versions of a notebook exist:

```
diffMs = local.updatedAt - remote.updatedAt

if diffMs > +1000ms  → local is newer  → upload
if diffMs < -1000ms  → remote is newer → download
if |diffMs| <= 1000ms → within tolerance → skip (considered equal)
```

The 1-second tolerance exists because timestamps pass through ISO 8601 serialization (which
truncates to seconds) and through different system clocks. Without tolerance, rounding artifacts
would cause spurious upload/download cycles.

### 5.3 Deletion vs. Edit Conflicts

The most dangerous conflict in any sync system is: device A deletes a notebook while device B (
offline) edits it. Without careful handling, the edit is silently lost.

Notable handles this with **tombstone-based resurrection**:

1. When a notebook is deleted, a zero-byte tombstone file is PUT to `/notable/deletions/{id}`. The
   server records a `lastModified` timestamp on the tombstone at the time of the PUT.
2. During sync, when applying remote tombstones:
    - If the local notebook's `updatedAt` is **after** the tombstone's `lastModified`, the notebook
      is **resurrected** (not deleted locally, and it will be re-uploaded during the upload phase;
      the tombstone is deleted from the server).
    - If the local notebook's `updatedAt` is **before** the tombstone's `lastModified`, the notebook
      is deleted locally (safe to remove).
3. This ensures that edits made after a deletion are never silently discarded.

**Prior art**: This is the same technique used by [Saber](https://github.com/saber-notes/saber) (
`lib/data/nextcloud/saber_syncer.dart`), which treats any zero-byte remote file as a tombstone. The
key property is that tombstones are independent per-notebook files, so two devices can write
tombstones simultaneously without racing over a shared file.

### 5.4 Folder Merge

Folders use a simpler per-folder last-writer-wins merge:

- All remote folders are loaded into a map.
- Local folders are merged in: if a local folder has a later `updatedAt` than its remote
  counterpart, the local version wins.
- The merged set is written to both the local database and the server.

### 5.5 Move Operations

- **Notebook moved to a different folder**: Updates `parentFolderId` on the notebook, which bumps
  `updatedAt`. The manifest is re-uploaded on the next sync, propagating the move.
- **Pages rearranged within a notebook**: Updates the `pageIds` order in the manifest, which bumps
  `updatedAt`. Same mechanism -- manifest re-uploads on next sync.

### 5.6 Local Deletion Detection

Detecting that a notebook was deleted locally (as opposed to never existing) requires comparing the
current set of local notebook IDs against the set of notebooks previously recorded as synced. That
record now lives in the `notebook_sync_state` table (it used to be `syncedNotebookIds` inside the
`SyncSettings` blob):

```
locallyDeleted = notebook_sync_state IDs - currentLocalNotebookIds
```

This comparison uses a **pre-download snapshot** of local notebook IDs -- taken before downloading
new notebooks from the server. This is critical: without it, a newly downloaded notebook would
appear "new" in the current set and, not yet having a sync-state row, could be misidentified as a
local deletion.

### 5.7 Known Limitations

- **Page-level conflicts are not merged.** If two devices edit different pages of the same notebook,
  the entire notebook is overwritten by the newer version. Stroke-level or page-level merging is a
  potential future enhancement.
- **No conflict UI.** There is no mechanism to present both versions to the user and let them
  choose. Last-writer-wins is applied automatically.
- **Folder deletion is not cascaded across devices.** Deleting a folder locally does not propagate
  to other devices (only notebook deletions are tracked via tombstones).
- **Concurrent updates can return conflict (`412 Precondition Failed`).** `folders.json` and
  `manifest.json` updates are protected by `If-Match`. This prevents silent overwrite, but can abort
  a sync step with `CONFLICT` when another device changes the resource between GET and PUT.
- **Depends on reasonably synchronized device clocks.** Timestamp comparison is the foundation of
  conflict resolution. If two devices have significantly different clock settings, the wrong version
  may win. This is mitigated by the clock skew detection described in 5.8, which blocks sync when
  the device clock differs from the server by more than 30 seconds.

### 5.8 Clock Skew Detection

Because the sync system relies on `updatedAt` timestamps set by each device's local clock, clock
disagreements between devices can cause the wrong version to win during conflict resolution. For
example, if Device A's clock is 5 minutes ahead, its edits will always appear "newer" even if Device
B edited more recently.

**Validation:** Before every sync (both full sync and single-notebook sync-on-close), the engine
makes a HEAD request to the WebDAV server and reads the HTTP `Date` response header. This is
compared against the device's `System.currentTimeMillis()` to compute the skew.

**Threshold:** If the absolute skew exceeds 30 seconds (`CLOCK_SKEW_THRESHOLD_MS`), the sync is
aborted with a `CLOCK_SKEW` error. This threshold is generous enough to tolerate normal NTP drift
but strict enough to catch misconfigured clocks.

**Escape hatch:** Force upload and force download operations are **not** gated by clock skew
detection. These are explicit user actions that bypass normal sync logic entirely, so timestamp
comparison is irrelevant -- the user is choosing which side wins wholesale.

**UI feedback:** The settings "Test Connection" button also checks clock skew. If the connection
succeeds but skew exceeds the threshold, a warning is displayed telling the user how many seconds
their clock differs from the server.

### 5.9 Per-Notebook Sync State (`notebook_sync_state`)

A dedicated Room table (schema v35, keyed by notebook id, **no** foreign key to `Notebook`) is the
single source of truth for per-notebook sync bookkeeping. It is written **only at commit points**:

| Column | Meaning |
|--------|---------|
| `notebookId` | Primary key. |
| `state` | `SYNCED` or `ERROR` (`SyncStateValue`). |
| `lastSyncedAt` | Wall-clock time the row was last written. |
| `localUpdatedAtAtSync` | The local `Notebook.updatedAt` captured at the last committed sync — the anchor for "has this notebook been edited since it was synced?". |
| `remoteUpdatedAt` | The remote manifest `updatedAt` at last sync. |
| `remoteEtag` | Reserved for ETag-based change detection (currently written as `null`; Phase 5). |

It has no foreign key so the row survives local deletion of the notebook — the next sync still needs
to see "was synced, now gone" to propagate a tombstone. The table replaced the former
`SyncSettings.syncedNotebookIds` set; there was **no migration** — the first sync after upgrade
simply repopulates it.

**Badges.** `NotebookSyncStatusStore` derives a `Flow<Map<notebookId, SyncBadge>>` by combining the
notebook list, this table, and the live `SyncState`. It stores nothing extra; the badge is a pure
function:

- `SYNCED` — a row exists and `notebook.updatedAt` is within 1s of `localUpdatedAtAtSync`.
- `NOT_SYNCED` — no row, or the notebook was edited since its last committed sync (and no sync is
  running).
- `SYNCING` — a sync is running and this is the exact notebook being transferred
  (`SyncState.Syncing.item.id == notebook.id`).
- `SCHEDULED` — a sync is running but it is not yet this notebook's turn.
- `ERROR` — the row's `state == ERROR`.

The badge is rendered as a corner icon on the notebook cover in the library.

---

## 6) Security Model

### 6.1 Credential Storage

Credentials (server URL, username, password) are persisted via the app `KvProxy` (Room `kv` table).
Passwords are encrypted/decrypted using the app's `CryptoHelper` which uses an AES-GCM key stored in
the AndroidKeyStore. The `KvProxy` stores the encrypted password blob in the Room table; on read it
attempts to decrypt and returns an empty password on decryption failure. This project does not use
`EncryptedSharedPreferences` for sync credentials.

### 6.2 Transport Security

- The WebDAV client communicates over HTTPS (strongly recommended in user documentation).
- HTTP URLs are accepted but not recommended. The client does not enforce HTTPS -- this is left to
  the user's discretion since some users run WebDAV on local networks.
- OkHttp handles TLS certificate validation using the system trust store.

### 6.3 Logging

- `SyncLogger` never logs credentials or authentication headers. It keeps a recent-history buffer (last 50 entries) exposed as a `StateFlow` for the UI and forwards logs to ShipBook.
- PROPFIND responses are parsed by `WebDAVClient` but the logger does not perform automatic truncation of response bodies in the current implementation.

---

## 7) Error Handling and Recovery

### 7.1 Error Types

Sync uses the app-wide `AppResult<D, DomainError>` result type (see
[result-and-error-handling.md](result-and-error-handling.md)) rather than a sync-specific enum. The
relevant `DomainError` variants (`utils/AppResult.kt`):

```kotlin
DomainError.NetworkError(message)        // IOException / thrown exception during a request
DomainError.SyncAuthError                // credentials missing or rejected (401)
DomainError.SyncConfigError              // sync disabled / not configured
DomainError.SyncClockSkew(seconds)       // device clock differs from server by >30s (see 5.8)
DomainError.SyncWifiRequired             // wifiOnly set but not on an unmetered network
DomainError.SyncInProgress               // another sync already holds the mutex
DomainError.SyncConflict                 // If-Match precondition failed (HTTP 412)
DomainError.SyncUploadOnlySkip(title)    // upload-only run had nothing to upload for a notebook
DomainError.SyncError(message, recoverable)  // generic server/logic failure
DomainError.DatabaseError(message)       // local Room failure
DomainError.NotFound(resource)           // e.g. notebook row missing
```

`SyncUploadOnlySkip` is an *expected* outcome modelled (for now) as an error and laundered back to
`Success` by `isOnlyUploadSkip()` / `finalizeSyncResult`. This errors-as-control-flow shape is a
known wart, slated to be removed when upload-only is re-derived as a planned action (see the
improvement plan, Phase 6). Multiple per-notebook errors are aggregated into `MultipleErrors` via
`ErrorAccumulator`.

### 7.2 Concurrency Control

A companion-object-level `Mutex` in `SyncOrchestrator` prevents concurrent sync operations on a
single device. If a sync is already running, `syncAllNotebooks()`, `forceUploadAll()`, and
`forceDownloadAll()` return `SyncResult.Failure(SYNC_IN_PROGRESS)`.

There is no cross-device locking -- WebDAV does not provide atomic multi-file transactions. See the
concurrency note in section 5.7.

### 7.3 Failure Isolation

Failures are isolated at the notebook level:

- If a single notebook fails to upload or download, the error is logged and sync continues with the
  remaining notebooks.
- If a single page fails to download within a notebook, the error is logged and the remaining pages
  are still processed.
- Only top-level failures (network unreachable, credentials invalid, server directory structure
  creation failed) abort the entire sync.

### 7.4 Retry Strategy (Background Sync)

`SyncWorker` (WorkManager) implements retry with the following policy:

- **Network unavailable** (pre-check): Return `Result.retry()` (WorkManager will back off and retry).
- **`SyncInProgress`**: Return `Result.success()` with `success=false` payload (not a hard failure --
  another sync is handling it).
- **`NetworkError` during sync**: `Result.retry()` up to 3 attempts (`MAX_RETRY_ATTEMPTS`), then
  `Result.failure()`.
- **Non-retryable sync errors** (`SyncAuthError`, `SyncConfigError`, `SyncClockSkew`,
  `SyncWifiRequired`, `SyncConflict`): Return `Result.failure()` immediately (no retry loop).
- **Other/unknown errors**: `Result.retry()` up to 3 attempts *if the error is `recoverable`*, else
  `Result.failure()`.
- Disabled sync / wifi-only-not-met / blank credentials are detected before dispatch and return
  `Result.success()` with a `skipped` payload.
- WorkManager's exponential backoff handles retry timing.

### 7.5 WebDAV Idempotency

The WebDAV client handles standard server responses that are not errors:

- `MKCOL` returning 405 (Method Not Allowed) is treated as success -- per RFC 4918, this means the
  collection already exists. This is only accepted on `MKCOL`; a 405 on any other operation is
  treated as an error.
- `DELETE` returning 404 (Not Found) is treated as success -- the resource is already gone.
- Both operations are thus idempotent and safe to retry.

### 7.6 State Machine

Sync state is exposed as a `StateFlow<SyncState>` for UI observation:

```
Idle → Syncing(step, stepProgress, details, item?) → Success(summary) → Idle
                                                  → Error(error, step, canRetry)
```

- `Syncing` includes a `SyncStep` enum (`INITIALIZING`, `SYNCING_FOLDERS`, `APPLYING_DELETIONS`, `SYNCING_NOTEBOOKS`, `DOWNLOADING_NEW`, `UPLOADING_DELETIONS`, `FINALIZING`), a float `stepProgress` (0.0–1.0), a `details` string, and an optional `item: ItemProgress?` (`index`, `total`, `name`, and the notebook `id`) set by services that loop over notebooks (`NotebookReconciliationService`, `NotebookSyncService`). The `id` lets `NotebookSyncStatusStore` mark exactly the notebook currently in transfer as `SYNCING` while the rest of the queue shows `SCHEDULED`.
- `SyncState` is owned by `SyncProgressReporter` (Hilt `@Singleton`). `SyncSettingsTab` renders it via `SyncProgressPanel`, using helpers `SyncStep.displayName()`, `overallProgressOf(Syncing)`, and `stepBandEnd(SyncStep)` to map per-step progress onto an overall bar.
- `Success` auto-resets to `Idle` after 3 seconds, launched off the caller's path so `syncAllNotebooks()` returns immediately.
- `Error` persists until the next sync attempt.
- On a successful full sync the orchestrator persists `SyncSettings.lastSyncTime`, so the settings "Last synced" line reflects background and periodic syncs, not just manual ones.

---

## 8) Integration Points

### 8.1 Dependencies

| Dependency                                         | Purpose                               |
|----------------------------------------------------|---------------------------------------|
| `com.squareup.okhttp3:okhttp`                      | HTTP client for all WebDAV operations |
| `Android KeyStore (used via CryptoHelper)`         | Encrypt/decrypt sync passwords (AES-GCM keys managed by AndroidKeyStore) |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | JSON serialization/deserialization    |
| `androidx.work:work-runtime-ktx`                   | Background sync scheduling            |

---

## 9) Future Work

Potential enhancements beyond the current implementation, roughly ordered by impact:

1. **ETag-based change detection.** Extend ETags to notebook manifests: store the ETag from each
   GET, send `If-None-Match` on the next sync -- a 304 avoids downloading the full manifest. This
   would also make clock skew detection unnecessary for change detection.
2. **Conflict recovery strategy.** On `CONFLICT` (412), add an automatic re-GET/reconcile/retry path
   for selected operations instead of finishing current run as skipped.
3. **Page-level sync granularity.** Compare and sync individual pages rather than whole notebooks to
   reduce bandwidth and improve conflict handling for multi-page notebooks.
4. **Stroke-level merge.** When two devices edit different pages of the same notebook, merge
   non-overlapping changes instead of last-writer-wins at the notebook level.
5. **Conflict UI.** Present both local and remote versions when a conflict is detected and let the
   user choose.
6. **Selective sync.** Allow users to choose which notebooks sync to which devices.
7. **Compression.** Gzip large JSON files before upload to reduce bandwidth.
8. **Quick Pages sync.** Pages with `notebookId = null` (standalone pages not in any notebook) are
   not currently synced.
9. **Device screen size scaling.** Notes created on one Boox tablet size may need coordinate scaling
   on a different model.

---

**Version**: 1.6
**Last Updated**: 2026-07-19 — synced with code after Phases 1–4 (commit-marker ordering,
tri-state `exists()`, `notebook_sync_state` table + badges, mutex on single-notebook sync,
non-destructive force ops, shared `OkHttpClient`, `DomainError`/`AppResult` throughout).
