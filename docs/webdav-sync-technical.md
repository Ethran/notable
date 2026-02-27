# WebDAV Sync - Technical Documentation

This document describes the architecture, protocol, data formats, and design decisions of Notable's WebDAV synchronization system. For user-facing setup and usage instructions, see [webdav-sync-user.md](webdav-sync-user.md).

**It was created by AI, and roughly checked for correctness.
Refer to code for actual implementation.**

Contents:
- Architecture overview and design rationale
- Why a custom WebDAV client
- Component overview
- Sync protocol (full sync flow)
- Data format specification
- Conflict resolution
- Security model
- Error handling and recovery
- Integration points
- Future work

---

## 1) Architecture Overview

### Design Goal

Enable bidirectional synchronization of notebooks, pages, strokes, images, backgrounds, and folder hierarchy between multiple devices via any standard WebDAV server (Nextcloud, ownCloud, NAS appliances, etc.).

### Why Per-Notebook JSON (Not Database Replication)

The sync system serializes each notebook as a set of JSON files rather than replicating the SQLite database directly. This was a deliberate choice:

- **SQLite is not designed for network concurrency.** Shipping the database file creates split-brain problems when two devices edit different notebooks simultaneously.
- **Granular failure isolation.** If one notebook fails to sync (corrupt data, network timeout mid-transfer), the rest succeed. A database-level sync is all-or-nothing.
- **External tooling.** The JSON format on the server is human-readable and machine-parseable, enabling external processing (e.g., PyTorch pipelines for handwriting analysis, scripted batch operations).
- **Selective sync.** Per-notebook granularity makes it straightforward to add selective sync in the future (sync only some notebooks to a device).
- **Standard WebDAV compatibility.** The protocol only requires GET, PUT, DELETE, MKCOL, and PROPFIND -- operations that every WebDAV server supports. No server-side logic or database is needed.

### Why Not CouchDB / Syncthing / Other Sync Frameworks?

Notable targets Onyx Boox e-ink tablets, which are locked-down Android devices. The sync solution must:

1. Work with servers the user already owns (Nextcloud is extremely common in this community).
2. Require no server-side component installation.
3. Run within a standard Android app without root or sideloaded services.
4. Use only HTTP/HTTPS for network communication (no custom protocols, no peer-to-peer).

WebDAV meets all four constraints. CouchDB would require server installation. Syncthing requires a background service and open ports. SFTP is not universally available. WebDAV is the lowest common denominator that actually works for this user base.

---

## 2) Why a Custom WebDAV Client

The `WebDAVClient` class implements WebDAV operations directly on OkHttp rather than using an existing Java/Android WebDAV library. This was not the first choice -- existing libraries were evaluated:

- **Sardine** (the most popular Java WebDAV library): Depends on Apache HttpClient, which was removed from the Android SDK in API 23. The `android-sardine` fork has not been maintained since 2019 and targets deprecated APIs.
- **jackrabbit-webdav**: Part of the Apache Jackrabbit project. Heavyweight dependency (~2MB+) designed for full JCR content repositories, not simple file sync. Also depends on Apache HttpClient.
- **Milton**: Server-side WebDAV framework, not a client library.
- **OkHttp-based alternatives**: No maintained library exists that wraps OkHttp for WebDAV specifically.

Notable's WebDAV needs are narrow: PUT, GET, DELETE, MKCOL, PROPFIND (Depth 0 and 1), and HEAD. These map directly to HTTP methods. The entire client is ~490 lines including XML parsing, which is smaller than any of the above libraries and has zero transitive dependencies beyond OkHttp (which the project already uses).

The implementation:
- Uses OkHttp (already a project dependency) for all HTTP operations.
- Parses PROPFIND XML responses with Android's built-in `XmlPullParser` (no additional XML libraries).
- Handles WebDAV-specific semantics: MKCOL returning 405 when a collection already exists, 404 on DELETE being acceptable (idempotent delete), namespace-aware XML parsing for `DAV:` responses.
- Provides both byte-array and streaming file download for memory-efficient handling of large files.

---

## 3) Component Overview

All sync code lives in `com.ethran.notable.sync`. The components and their responsibilities:

```
┌─────────────────────────────────────────────────────┐
│                    SyncEngine                        │
│  Orchestrates the full sync flow: folder sync,      │
│  deletion propagation, notebook upload/download,     │
│  conflict resolution, state machine, progress.       │
├───────────┬──────────────┬──────────────────────────┤
│           │              │                           │
│  WebDAVClient    Serializers         Infrastructure  │
│  ───────────     ──────────          ──────────────  │
│  OkHttp-based    NotebookSerializer  CredentialMgr   │
│  PUT/GET/DEL     FolderSerializer    SyncWorker      │
│  MKCOL/PROPFIND  DeletionsSerializer SyncScheduler   │
│  HEAD/streaming                      SyncLogger      │
│                                      ConnectChecker   │
└─────────────────────────────────────────────────────┘
```

| File | Lines | Role |
|------|-------|------|
| `SyncEngine.kt` | ~1130 | Core orchestrator. Full sync flow, per-notebook sync, deletion upload, force upload/download. State machine (`SyncState`) and mutex for concurrency control. |
| `WebDAVClient.kt` | ~490 | HTTP/WebDAV operations. PROPFIND XML parsing. Connection testing. Streaming downloads. |
| `NotebookSerializer.kt` | ~315 | Serializes/deserializes notebooks, pages, strokes, and images to/from JSON. Stroke points are embedded as base64-encoded [SB1 binary](database-structure.md) data. |
| `FolderSerializer.kt` | ~112 | Serializes/deserializes the folder hierarchy to/from `folders.json`. |
| `DeletionsSerializer.kt` | ~50 | Manages `deletions.json`, which tracks deleted notebook IDs with timestamps for conflict resolution. |
| `SyncWorker.kt` | ~89 | `CoroutineWorker` for WorkManager integration. Checks connectivity and credentials before delegating to `SyncEngine`. |
| `SyncScheduler.kt` | ~58 | Schedules/cancels periodic sync via WorkManager. |
| `CredentialManager.kt` | ~68 | Stores WebDAV credentials in `EncryptedSharedPreferences` (AES-256-GCM). |
| `ConnectivityChecker.kt` | ~34 | Queries Android `ConnectivityManager` for network/WiFi availability. |
| `SyncLogger.kt` | ~81 | Maintains a ring buffer of recent log entries (exposed as `StateFlow`) for the sync UI. |

---

## 4) Sync Protocol

### 4.1 Full Sync Flow (`syncAllNotebooks`)

A full sync executes the following steps in order. A coroutine `Mutex` prevents concurrent sync operations.

```
1. INITIALIZE
   ├── Load AppSettings and credentials
   ├── Construct WebDAVClient
   └── Ensure /notable/ and /notable/notebooks/ exist on server (MKCOL)

2. SYNC FOLDERS
   ├── GET /notable/folders.json (if exists)
   ├── Merge: for each folder, keep the version with the later updatedAt
   ├── Upsert merged folders into local Room database
   └── PUT /notable/folders.json (merged result)

3. APPLY REMOTE DELETIONS
   ├── GET /notable/deletions.json (if exists)
   ├── For each deleted notebook ID:
   │   ├── If local notebook was modified AFTER the deletion timestamp → SKIP (resurrection)
   │   └── Otherwise → delete local notebook
   └── Return DeletionsData for use in later steps

4. SYNC EXISTING LOCAL NOTEBOOKS
   ├── Snapshot local notebook IDs (the "pre-download set")
   └── For each local notebook:
       ├── HEAD /notable/notebooks/{id}/manifest.json
       ├── If remote exists:
       │   ├── GET manifest.json, parse updatedAt
       │   ├── Compare timestamps (with ±1s tolerance):
       │   │   ├── Local newer → upload notebook
       │   │   ├── Remote newer → download notebook
       │   │   └── Within tolerance → skip
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
   └── PUT updated /notable/deletions.json with new entries + timestamps

7. FINALIZE
   ├── Update syncedNotebookIds = current set of all local notebook IDs
   └── Persist to AppSettings
```

### 4.2 Per-Notebook Upload

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

### 4.3 Per-Notebook Download

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

### 4.4 Single-Notebook Sync (`syncNotebook`)

Used for sync-on-close (triggered when the user closes the editor). Follows the same timestamp-comparison logic as step 4 of the full sync, but operates on a single notebook without the full deletion/discovery flow.

### 4.5 Deletion Propagation (`uploadDeletion`)

When a notebook is deleted locally, a targeted operation can immediately propagate the deletion to the server without running a full sync:

1. GET `deletions.json` from server.
2. Add the notebook ID with current ISO 8601 timestamp.
3. DELETE the notebook's directory from server.
4. PUT updated `deletions.json`.
5. Remove notebook ID from `syncedNotebookIds`.

---

## 5) Data Format Specification

### 5.1 Server Directory Structure

```
/notable/                           ← WEBDAV_ROOT_DIR, appended to user's server URL
├── deletions.json                  ← Tracks deleted notebooks with timestamps
├── folders.json                    ← Complete folder hierarchy
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

### 5.2 manifest.json

```json
{
    "version": 1,
    "notebookId": "550e8400-e29b-41d4-a716-446655440000",
    "title": "My Notebook",
    "pageIds": ["page-uuid-1", "page-uuid-2"],
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

### 5.3 Page JSON (`pages/{uuid}.json`)

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

- `strokes[].pointsData`: Base64-encoded SB1 binary format. See [database-structure.md](database-structure.md) section 3 for the full SB1 specification. This is the same binary format used in the local Room database, base64-wrapped for JSON transport.
- `strokes[].color`: ARGB integer (e.g., `-16777216` = opaque black).
- `strokes[].pen`: Enum name from the `Pen` type (BALLPOINT, FOUNTAIN, PENCIL, etc.).
- `images[].uri`: Relative path on the server (e.g., `images/filename.jpg`). Converted to/from absolute local paths during upload/download.
- `notebookId`: May be `null` for Quick Pages (standalone pages not belonging to a notebook).

### 5.4 folders.json

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

### 5.5 deletions.json

```json
{
    "deletedNotebooks": {
        "notebook-uuid-1": "2025-12-20T14:22:33Z",
        "notebook-uuid-2": "2025-12-21T08:00:00Z"
    },
    "deletedNotebookIds": []
}
```

- `deletedNotebooks`: Map of notebook UUID to ISO 8601 deletion timestamp. The timestamp is critical for conflict resolution (see section 6).
- `deletedNotebookIds`: Legacy field from an earlier format that did not track timestamps. Retained for backward compatibility. New deletions always use the timestamped map.

### 5.6 JSON Configuration

All serializers use `kotlinx.serialization` with:
- `prettyPrint = true`: Human-readable output, debuggable on the server.
- `ignoreUnknownKeys = true`: Forward compatibility. If a future version adds fields, older clients can still parse the JSON without crashing.

---

## 6) Conflict Resolution

### 6.1 Strategy: Last-Writer-Wins with Resurrection

The sync system uses **timestamp-based last-writer-wins** at the notebook level. This is a deliberate simplicity tradeoff:

- **Simpler than CRDT or operational transform.** These are powerful but add substantial complexity and are difficult to get right for a handwriting/drawing app where strokes are the atomic unit.
- **Appropriate for the use case.** Most Notable users have one or two devices. Simultaneous editing of the same notebook on two devices is rare. When it does happen, the most recent edit is almost always the one the user wants.
- **Predictable behavior.** Users can reason about "I edited this last, so my version wins" without understanding distributed systems theory.

### 6.2 Timestamp Comparison

When both local and remote versions of a notebook exist:

```
diffMs = local.updatedAt - remote.updatedAt

if diffMs > +1000ms  → local is newer  → upload
if diffMs < -1000ms  → remote is newer → download
if |diffMs| <= 1000ms → within tolerance → skip (considered equal)
```

The 1-second tolerance exists because timestamps pass through ISO 8601 serialization (which truncates to seconds) and through different system clocks. Without tolerance, rounding artifacts would cause spurious upload/download cycles.

### 6.3 Deletion vs. Edit Conflicts

The most dangerous conflict in any sync system is: device A deletes a notebook while device B (offline) edits it. Without careful handling, the edit is silently lost.

Notable handles this with **timestamped deletions and resurrection**:

1. When a notebook is deleted, the deletion timestamp is recorded in `deletions.json`.
2. During sync, when applying remote deletions to local data:
   - If the local notebook's `updatedAt` is **after** the deletion timestamp, the notebook is **resurrected** (not deleted locally, and it will be re-uploaded during the upload phase).
   - If the local notebook's `updatedAt` is **before** the deletion timestamp, the notebook is deleted locally (it was not edited after deletion -- safe to remove).
3. This ensures that edits made after a deletion are never silently discarded.

### 6.4 Folder Merge

Folders use a simpler per-folder last-writer-wins merge:
- All remote folders are loaded into a map.
- Local folders are merged in: if a local folder has a later `updatedAt` than its remote counterpart, the local version wins.
- The merged set is written to both the local database and the server.

### 6.5 Local Deletion Detection

Detecting that a notebook was deleted locally (as opposed to never existing) requires comparing the current set of local notebook IDs against the set from the last successful sync (`syncedNotebookIds` in AppSettings):

```
locallyDeleted = syncedNotebookIds - currentLocalNotebookIds
```

This comparison uses a **pre-download snapshot** of local notebook IDs -- taken before downloading new notebooks from the server. This is critical: without it, a newly downloaded notebook would appear "new" in the current set and would not be in `syncedNotebookIds`, causing it to be misidentified as a local deletion.

### 6.6 Known Limitations

- **Page-level conflicts are not merged.** If two devices edit different pages of the same notebook, the entire notebook is overwritten by the newer version. Stroke-level or page-level merging is a potential future enhancement.
- **No conflict UI.** There is no mechanism to present both versions to the user and let them choose. Last-writer-wins is applied automatically.
- **Folder deletion is not cascaded across devices.** Deleting a folder locally does not propagate to other devices via `deletions.json` (only notebook deletions are tracked).

---

## 7) Security Model

### 7.1 Credential Storage

Credentials are stored using Android's `EncryptedSharedPreferences` (from `androidx.security:security-crypto`):

- **Master key**: AES-256-GCM, managed by Android Keystore.
- **Key encryption**: AES-256-SIV (deterministic authenticated encryption for preference keys).
- **Value encryption**: AES-256-GCM (authenticated encryption for preference values).
- Credentials are stored separately from the main app database (`KvProxy`), ensuring they are always encrypted at rest regardless of device encryption state.

### 7.2 Transport Security

- The WebDAV client communicates over HTTPS (strongly recommended in user documentation).
- HTTP URLs are accepted but not recommended. The client does not enforce HTTPS -- this is left to the user's discretion since some users run WebDAV on local networks.
- OkHttp handles TLS certificate validation using the system trust store.

### 7.3 Logging

- `SyncLogger` never logs credentials or authentication headers.
- Debug logging of PROPFIND responses is truncated to 1500 characters to prevent sensitive directory listings from filling logs.

---

## 8) Error Handling and Recovery

### 8.1 Error Types

```kotlin
enum class SyncError {
    NETWORK_ERROR,      // IOException - connection failed, timeout, DNS resolution
    AUTH_ERROR,         // Credentials missing or invalid
    CONFIG_ERROR,       // Settings missing or sync disabled
    SERVER_ERROR,       // Unexpected server response
    CONFLICT_ERROR,     // (Reserved for future use)
    SYNC_IN_PROGRESS,  // Another sync is already running (mutex held)
    UNKNOWN_ERROR       // Catch-all for unexpected exceptions
}
```

### 8.2 Concurrency Control

A companion-object-level `Mutex` prevents concurrent sync operations across all `SyncEngine` instances. If `syncAllNotebooks()` is called while a sync is already running, it returns immediately with `SyncResult.Failure(SYNC_IN_PROGRESS)`.

### 8.3 Failure Isolation

Failures are isolated at the notebook level:
- If a single notebook fails to upload or download, the error is logged and sync continues with the remaining notebooks.
- If a single page fails to download within a notebook, the error is logged and the remaining pages are still processed.
- Only top-level failures (network unreachable, credentials invalid, server directory structure creation failed) abort the entire sync.

### 8.4 Retry Strategy (Background Sync)

`SyncWorker` (WorkManager) implements retry with the following policy:
- **Network unavailable**: Return `Result.retry()` (WorkManager will back off and retry).
- **Sync already in progress**: Return `Result.success()` (not an error -- another sync is handling it).
- **Network error during sync**: Retry up to 3 attempts, then fail.
- **Other errors**: Retry up to 3 attempts, then fail.
- WorkManager's exponential backoff handles retry timing.

### 8.5 WebDAV Idempotency

The WebDAV client handles server quirks:
- `MKCOL` returning 405 (Method Not Allowed) is treated as success -- it means the collection already exists.
- `DELETE` returning 404 (Not Found) is treated as success -- the resource is already gone.
- Both operations are thus idempotent and safe to retry.

### 8.6 State Machine

Sync state is exposed as a `StateFlow<SyncState>` for UI observation:

```
Idle → Syncing(step, progress, details) → Success(summary) → Idle
                                        → Error(error, step, canRetry)
```

- `Syncing` includes a `SyncStep` enum and float progress (0.0-1.0) for progress indication.
- `Success` auto-resets to `Idle` after 3 seconds.
- `Error` persists until the next sync attempt.

---

## 9) Integration Points

### 9.1 WorkManager (Background Sync)

`SyncScheduler` enqueues a `PeriodicWorkRequest` with:
- Default interval: 5 minutes (configurable).
- Network constraint: `NetworkType.CONNECTED` (won't run without network).
- Policy: `ExistingPeriodicWorkPolicy.KEEP` (doesn't restart if already scheduled).

### 9.2 Settings

Sync configuration lives in `AppSettings.syncSettings`:
- `syncEnabled`: Master toggle.
- `serverUrl`: WebDAV endpoint.
- `syncedNotebookIds`: Set of notebook UUIDs from the last successful sync (used for local deletion detection).
- Credentials are stored separately in `CredentialManager` (not in AppSettings).

### 9.3 Editor Integration (Sync on Close)

`EditorControlTower` can trigger `syncNotebook(notebookId)` when a note is closed, providing near-real-time sync without waiting for the periodic schedule.

### 9.4 Dependencies

| Dependency | Version | Purpose |
|-----------|---------|---------|
| `com.squareup.okhttp3:okhttp` | 4.12.0 | HTTP client for all WebDAV operations |
| `androidx.security:security-crypto` | 1.1.0-alpha06 | Encrypted credential storage |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.9.0 | JSON serialization/deserialization |
| `androidx.work:work-runtime-ktx` | (project version) | Background sync scheduling |

---

## 10) Future Work

Potential enhancements beyond the current implementation, roughly ordered by impact:

1. **Page-level sync granularity.** Compare and sync individual pages rather than whole notebooks to reduce bandwidth and improve conflict handling for multi-page notebooks.
2. **Stroke-level merge.** When two devices edit different pages of the same notebook, merge non-overlapping changes instead of last-writer-wins at the notebook level.
3. **Conflict UI.** Present both local and remote versions when a conflict is detected and let the user choose.
4. **Selective sync.** Allow users to choose which notebooks sync to which devices.
5. **Compression.** Gzip large JSON files before upload to reduce bandwidth.
6. **Incremental page sync.** Track per-page timestamps and only upload/download pages that actually changed within a notebook.
7. **Quick Pages sync.** Pages with `notebookId = null` (standalone pages not in any notebook) are not currently synced.
8. **Sync progress UI.** Expose per-notebook progress during large syncs.
9. **Device screen size scaling.** Notes created on one Boox tablet size may need coordinate scaling on a different model.

---

**Version**: 1.0
**Last Updated**: 2026-02-27
