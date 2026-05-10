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
| [`NotebookReconciliationService.kt`](../app/src/main/java/com/ethran/notable/sync/NotebookReconciliationService.kt) | Per-notebook conflict decision (upload/download/no-op) based on manifest timestamps. Reports per-item progress via `SyncProgressReporter.beginItem`/`endItem`.     |
| [`NotebookSyncService.kt`](../app/src/main/java/com/ethran/notable/sync/NotebookSyncService.kt)                     | Per-notebook upload/download execution. Reports per-item progress via `SyncProgressReporter.beginItem`/`endItem` when downloading new notebooks.                   |
| [`SyncProgressReporter.kt`](../app/src/main/java/com/ethran/notable/sync/SyncProgressReporter.kt)                   | `@Singleton` owner of the `SyncState` `StateFlow`. Interface + `SyncProgressReporterImpl` + Hilt `@Binds` module + `SyncProgressReporterEntryPoint`. Write-side API: `beginStep`, `beginItem`, `endItem`, `finishSuccess`, `finishError`, `reset`. Read-side: `state`. Consumers inject `SyncProgressReporter` rather than touching `SyncOrchestrator` for state. |
| [`SyncForceService.kt`](../app/src/main/java/com/ethran/notable/sync/SyncForceService.kt)                           | Force upload/download flows (full side replacement) used by settings actions.                                                                                      |
| [`SyncPorts.kt`](../app/src/main/java/com/ethran/notable/sync/SyncPorts.kt)                                         | DI port/adapter for WebDAV client creation (`WebDavClientFactoryPort`).                                                                                            |
| [`WebDAVClient.kt`](../app/src/main/java/com/ethran/notable/sync/WebDAVClient.kt)                                   | HTTP/WebDAV operations. PROPFIND XML parsing. Connection testing. File uploads/downloads and metadata (ETag) support. `If-Match`-guarded uploads for optimistic concurrency. |
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
   ├── Load SyncSettings and credentials
   ├── Construct WebDAVClient
   └── Ensure /notable/, /notable/notebooks/, /notable/deletions/ exist on server (MKCOL)

2. SYNC FOLDERS
   ├── GET /notable/folders.json (if exists) + capture ETag
   ├── Merge: for each folder, keep the version with the later updatedAt
   ├── Upsert merged folders into local Room database
   └── PUT /notable/folders.json with If-Match (captured ETag)

3. APPLY REMOTE DELETIONS
   ├── PROPFIND /notable/deletions/ (Depth 1) → list of tombstone files with lastModified
   ├── For each tombstone (filename = deleted notebook UUID):
   │   ├── If local notebook was modified AFTER the tombstone's lastModified → SKIP (resurrection)
   │   └── Otherwise → delete local notebook
   └── Return tombstonedIds set for use in later steps

4. SYNC EXISTING LOCAL NOTEBOOKS
   ├── Snapshot local notebook IDs (the "pre-download set")
   └── For each local notebook:
       ├── HEAD /notable/notebooks/{id}/manifest.json
       ├── If remote exists:
       │   ├── GET manifest.json + capture ETag, parse updatedAt
       │   ├── Compare timestamps (with ±1s tolerance):
       │   │   ├── Local newer → upload notebook manifest with If-Match (captured ETag)
       │   │   ├── Remote newer → download notebook
       │   │   └── Within tolerance → skip
       │   ├── If server changed between GET and PUT, server returns 412 and sync reports CONFLICT
       │   └── (end comparison)
       └── If remote doesn't exist → upload notebook

5. DOWNLOAD NEW NOTEBOOKS FROM SERVER
   ├── PROPFIND /notable/notebooks/ (Depth 1) → list of notebook directory UUIDs
   ├── Filter out: already-local, already-deleted, previously-synced-then-locally-deleted
   └── For each new notebook ID → download notebook

6. DETECT AND UPLOAD LOCAL DELETIONS
   ├── Compare syncedNotebookIds (from last sync) against pre-download snapshot
   ├── Missing IDs = locally deleted notebooks
   ├── For each: DELETE /notable/notebooks/{id}/ on server
   └── PUT zero-byte file to /notable/deletions/{id} (tombstone for other devices)

7. FINALIZE
   ├── Update syncedNotebookIds = current set of all local notebook IDs
   └── Persist to SyncSettings
```

### 3.2 Per-Notebook Upload

Conflict detection is at the **notebook level** (manifest `updatedAt`). Individual pages are
uploaded as separate files, but if two devices have edited different pages of the same notebook, the
device with the newer `updatedAt` wins the entire notebook (see section 5.6).

```
uploadNotebook(notebook):
  1. MKCOL /notable/notebooks/{id}/pages/
  2. MKCOL /notable/notebooks/{id}/images/
  3. MKCOL /notable/notebooks/{id}/backgrounds/
  4. PUT /notable/notebooks/{id}/manifest.json  (serialized notebook metadata)
  5. For each page:
     a. Serialize page JSON (strokes embedded as base64-encoded SB1 binary)
     b. PUT /notable/notebooks/{id}/pages/{pageId}.json
     c. For each image on the page:
        - If local file exists and not already on server → PUT to images/
     d. If page has a custom background (not native template):
        - If local file exists and not already on server → PUT to backgrounds/
```

### 3.3 Per-Notebook Download

```
downloadNotebook(notebookId):
  1. GET /notable/notebooks/{id}/manifest.json → parse to Notebook
  2. Upsert Notebook into local Room database (preserving remote timestamp)
  3. For each pageId in manifest.pageIds:
     a. GET /notable/notebooks/{id}/pages/{pageId}.json → parse to (Page, Strokes, Images)
     b. For each image referenced:
        - GET from images/ → save to local /Documents/notabledb/images/
        - Update image URI to local absolute path
     c. If custom background:
        - GET from backgrounds/ → save to local backgrounds folder
     d. If page already exists locally:
        - Delete old strokes and images from Room
        - Update page
     e. If page is new:
        - Create page in Room
     f. Insert strokes and images
```

### 3.4 Single-Notebook Sync (`syncNotebook`)

Used for sync-on-close (triggered when the user closes the editor). Follows the same
timestamp-comparison logic as step 4 of the full sync, but operates on a single notebook without the
full deletion/discovery flow.

### 3.5 Deletion Propagation (`uploadDeletion`)

When a notebook is deleted locally, a targeted operation can immediately propagate the deletion to
the server without running a full sync:

1. DELETE the notebook's directory from server.
2. PUT a zero-byte file to `/notable/deletions/{id}` (the server's own `lastModified` on this file
   serves as the deletion timestamp for other devices' conflict resolution).
3. Remove notebook ID from `syncedNotebookIds`.

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
current set of local notebook IDs against the set from the last successful sync (`syncedNotebookIds`
in SyncSettings):

```
locallyDeleted = syncedNotebookIds - currentLocalNotebookIds
```

This comparison uses a **pre-download snapshot** of local notebook IDs -- taken before downloading
new notebooks from the server. This is critical: without it, a newly downloaded notebook would
appear "new" in the current set and would not be in `syncedNotebookIds`, causing it to be
misidentified as a local deletion.

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

```kotlin
enum class SyncError {
    NETWORK_ERROR,      // IOException - connection failed, timeout, DNS resolution
    AUTH_ERROR,         // Credentials missing or invalid
    CONFIG_ERROR,       // Settings missing or sync disabled
    CLOCK_SKEW,         // Device clock differs from server by >30s (see 5.8)
    SYNC_IN_PROGRESS,   // Another sync is already running (mutex held)
    CONFLICT,           // ETag precondition failed (HTTP 412)
    UNKNOWN_ERROR       // Catch-all for unexpected exceptions
}
```

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

- **Network unavailable**: Return `Result.retry()` (WorkManager will back off and retry).
- **Sync already in progress**: Return `Result.success()` (not an error -- another sync is handling
  it).
- **Network error during sync**: Retry up to 3 attempts, then fail.
- **Non-retryable sync errors** (`AUTH_ERROR`, `CONFIG_ERROR`, `CLOCK_SKEW`, `WIFI_REQUIRED`, `CONFLICT`): Return
  `Result.success()` to avoid useless retry loops.
- **Other/unknown errors**: Retry up to 3 attempts, then fail.
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

- `Syncing` includes a `SyncStep` enum, a float `stepProgress` (0.0–1.0) for the current step, a `details` string, and an optional `item: ItemProgress?` (`index`, `total`, `name`) set by services that loop over notebooks (`NotebookReconciliationService`, `NotebookSyncService`).
- `SyncState` is owned by `SyncProgressReporter` (Hilt `@Singleton`). `SyncSettingsTab` renders it via `SyncProgressPanel`, using helpers `SyncStep.displayName()`, `overallProgressOf(Syncing)`, and `stepBandEnd(SyncStep)` to map per-step progress onto an overall bar.
- `Success` auto-resets to `Idle` after 3 seconds.
- `Error` persists until the next sync attempt.

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

**Version**: 1.5
**Last Updated**: 2026-04-18
