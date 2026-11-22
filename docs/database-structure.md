# Notable Persistent Data Model and Stroke Encoding Specification

This document defines the persistent data model of Notable and the storage of stroke point lists as implemented.
It describes the structures and fields that actually exist in the codebase.
It is a specification of the current structure, not a change log.
**It was created by AI, and roughly checked for correctness.
Refer to code for actual implementation.**

Contents:
- Entities and relationships
- Logical schemas, database version and migrations
- Stroke point list storage format

---

## 1) Entities

The database is a Room database (`AppDatabase`, `data/db/Db.kt`), stored as
`Documents/notabledb/app_database` on shared storage (`dbDirPath()` in `data/dbUtils.kt`). Files the
rows point to live next to it: inserted images in `Documents/notabledb/images`, copied backgrounds and
PDFs in `Documents/notabledb/backgrounds/<type>/`; rows store paths to them, not their bytes.

- Folder https://github.com/Ethran/notable/blob/main/app/src/main/java/com/ethran/notable/data/db/Folder.kt
  - Hierarchical container. Fields in code: `id` (String UUID PK), `title`, `parentFolderId` (nullable FK to Folder), `createdAt`, `updatedAt`.
- Notebook https://github.com/Ethran/notable/blob/main/app/src/main/java/com/ethran/notable/data/db/Notebook.kt
  - Ordered collection of pages. Fields: `id` (String UUID PK), `title`, `openPageId` (last opened page, nullable), `pageIds` (page order, `List<String>` stored as a JSON array), `parentFolderId` (nullable FK to Folder), `defaultBackground`, `defaultBackgroundType` (background for new pages), `defaultSheet` (nullable `SheetSize`, the sheet new pages get; null for infinite pages), `linkedExternalUri` (nullable; the file a notebook is linked to, e.g. a PDF or `.xopp`), `createdAt`, `updatedAt`.
- Page https://github.com/Ethran/notable/blob/main/app/src/main/java/com/ethran/notable/data/db/Page.kt
  - A page, either in a notebook (`notebookId`) or a standalone "quick page" in a folder. Fields: `id` (String UUID PK), `positionX`, `positionY` (the page point at the top-left corner of the view when the page was last shown on this device, in page pixels; device-local: a download never changes them, though `positionY` is still uploaded as the legacy `scroll` key), `notebookId` (nullable FK to Notebook), `background` (path or native template name), `backgroundType` (`BackgroundType.key`: `native`, `image`, `imagerepeating`, `coverImage`, `autoPdf`, `pdf<N>`), `sheet` (nullable `SheetSize`; null for an infinite canvas), `parentFolderId` (nullable FK to Folder), `createdAt`, `updatedAt`.
  - `SheetSize` (`data/model/SheetSize.kt`) is the page's `width` and `height` in page pixels, the units strokes and images are stored in. It is fixed when the page is created, from the notebook's `defaultSheet`. A notebook can mix pages with and without a sheet; a sheet is never invented for an infinite page, and a continuous (multi-page) layout applies only when every page has one.
  - `updatedAt` is the time of the page's last edit (strokes/images). Saving bumps it with `UPDATE page SET updatedAt = MAX(:now, updatedAt + 1)`, so it never moves backwards. Sync and page previews compare against it.
- Stroke https://github.com/Ethran/notable/blob/main/app/src/main/java/com/ethran/notable/data/db/Stroke.kt
  - Addressable record containing style and geometry inline. Fields: `id` (String UUID PK), `size` (Float), `pen` (`Pen` enum, stored by name), `color` (Int ARGB), `maxPressure` (Int, the denominator of the stored pressure values, see 3.4), bounding box floats (`top`, `bottom`, `left`, `right`), `points` (List<StrokePoint>), `pageId` (FK to Page), timestamps.
- StrokePoint (geometry payload, in `Stroke.kt`)
  - Inlined per-point samples: `x: Float`, `y: Float`, optional `pressure: Float?`, optional `tiltX: Int?`, `tiltY: Int?`, optional `dt: UShort?`, plus legacy serialized fields (`timestamp`, `size`) kept only so old JSON point lists still deserialize.
- Image https://github.com/Ethran/notable/blob/main/app/src/main/java/com/ethran/notable/data/db/Image.kt
  - Raster image placed on a page. Fields: `id` (String UUID PK), `x`, `y`, `width`, `height` (Int, page coordinates), `uri` (nullable; the image file, not its bytes), `pageId` (FK to Page), timestamps.
- Kv https://github.com/Ethran/notable/blob/main/app/src/main/java/com/ethran/notable/data/db/Kv.kt
  - Key/value store (`key` PK, `value` String) used for JSON-serialized settings (e.g. `EDITOR_SETTINGS`).
- NotebookSyncState / PageSyncState (`NotebookSyncState.kt`, `PageSyncState.kt`)
  - WebDAV sync bookkeeping: what each notebook / page looked like at its last committed sync. Deliberately **no** foreign keys, so the rows outlive a local delete and the next sync can propagate it. See `webdav-sync-technical.md`.

All foreign keys are `ON DELETE CASCADE`: deleting a folder deletes its subfolders, notebooks and pages;
deleting a notebook deletes its pages; deleting a page deletes its strokes and images.

---

## 2) Logical Schemas

Room with UUID string primary keys (not integer autoincrement). `Date` fields are stored as epoch
milliseconds (`INTEGER`). Strokes embed their point list directly as a BLOB (no separate point table).
The authoritative schema is the exported JSON in `app/schemas/com.ethran.notable.data.db.AppDatabase/`.

```sql
CREATE TABLE Folder (
  id             TEXT NOT NULL PRIMARY KEY,
  title          TEXT NOT NULL,
  parentFolderId TEXT REFERENCES Folder(id) ON DELETE CASCADE,
  createdAt      INTEGER NOT NULL,
  updatedAt      INTEGER NOT NULL
);
CREATE INDEX index_Folder_parentFolderId ON Folder(parentFolderId);

CREATE TABLE Notebook (
  id                    TEXT NOT NULL PRIMARY KEY,
  title                 TEXT NOT NULL,
  openPageId            TEXT,
  pageIds               TEXT NOT NULL,                     -- JSON array of page ids, in order
  parentFolderId        TEXT REFERENCES Folder(id) ON DELETE CASCADE,
  defaultBackground     TEXT NOT NULL DEFAULT 'blank',
  defaultBackgroundType TEXT NOT NULL DEFAULT 'native',
  defaultSheetWidth     INTEGER,                           -- defaultSheet; both null = infinite
  defaultSheetHeight    INTEGER,
  linkedExternalUri     TEXT,
  createdAt             INTEGER NOT NULL,
  updatedAt             INTEGER NOT NULL
);
CREATE INDEX index_Notebook_parentFolderId ON Notebook(parentFolderId);

CREATE TABLE Page (
  id             TEXT NOT NULL PRIMARY KEY,
  positionX      INTEGER NOT NULL DEFAULT 0,
  positionY      INTEGER NOT NULL DEFAULT 0,
  notebookId     TEXT REFERENCES Notebook(id) ON DELETE CASCADE,
  background     TEXT NOT NULL DEFAULT 'blank',
  backgroundType TEXT NOT NULL DEFAULT 'native',
  sheetWidth     INTEGER,                        -- sheet; both null = infinite canvas
  sheetHeight    INTEGER,
  parentFolderId TEXT REFERENCES Folder(id) ON DELETE CASCADE,
  createdAt      INTEGER NOT NULL,
  updatedAt      INTEGER NOT NULL
);
CREATE INDEX index_Page_notebookId     ON Page(notebookId);
CREATE INDEX index_Page_parentFolderId ON Page(parentFolderId);

CREATE TABLE Stroke (
  id          TEXT NOT NULL PRIMARY KEY,
  size        REAL NOT NULL,
  pen         TEXT NOT NULL,                     -- Pen enum name
  color       INTEGER NOT NULL DEFAULT 0xFF000000, -- ARGB
  maxPressure INTEGER NOT NULL DEFAULT 4096,     -- pressure denominator; 1 = normalized
  top         REAL NOT NULL,
  bottom      REAL NOT NULL,
  left        REAL NOT NULL,
  right       REAL NOT NULL,
  points      BLOB NOT NULL,                     -- SB-encoded List<StrokePoint> (section 3)
  pageId      TEXT NOT NULL REFERENCES Page(id) ON DELETE CASCADE,
  createdAt   INTEGER NOT NULL,
  updatedAt   INTEGER NOT NULL
);
CREATE INDEX index_Stroke_pageId ON Stroke(pageId);

CREATE TABLE Image (
  id        TEXT NOT NULL PRIMARY KEY,
  x         INTEGER NOT NULL,
  y         INTEGER NOT NULL,
  height    INTEGER NOT NULL,
  width     INTEGER NOT NULL,
  uri       TEXT,
  pageId    TEXT NOT NULL REFERENCES Page(id) ON DELETE CASCADE,
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL
);
CREATE INDEX index_Image_pageId ON Image(pageId);

CREATE TABLE Kv (
  key   TEXT NOT NULL PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE notebook_sync_state (
  notebookId           TEXT NOT NULL PRIMARY KEY,   -- no FK to Notebook
  state                TEXT NOT NULL,               -- 'SYNCED' or 'ERROR'
  lastSyncedAt         INTEGER NOT NULL,
  syncedLocalUpdatedAt INTEGER NOT NULL,            -- Notebook.updatedAt at the last commit
  remoteEtag           TEXT,                        -- manifest ETag, server spelling
  remoteUpdatedAt      INTEGER,
  lastError            TEXT,
  remoteDirEtag        TEXT,                        -- notebook directory ETag baseline (bulk detection)
  remoteDirServerKey   TEXT                         -- server identity the baseline was seen on
);

CREATE TABLE page_sync_state (
  pageId               TEXT NOT NULL PRIMARY KEY,   -- no FK to Page
  notebookId           TEXT NOT NULL,
  remoteEtag           TEXT,                        -- page file ETag, server spelling
  syncedLocalUpdatedAt INTEGER NOT NULL,            -- Page.updatedAt at the last commit
  lastSyncedAt         INTEGER NOT NULL
);
CREATE INDEX index_page_sync_state_notebookId ON page_sync_state(notebookId);
```

### 2.0 Version and migrations

The current database version is **38**. `AppDatabase` declares:
- Manual migrations (`Migrations.kt`): `16→17` (timestamps), `17→18` (`Page.nativeTemplate`),
  `22→23` (delete pages whose notebook no longer exists), `32→33` (stroke table rebuilt with `points`
  as an SB BLOB and `maxPressure`; the old table is kept as `stroke_old`).
- Auto migrations for every other step from 19 to 38, with specs for renames:
  `30→31` (`Page.nativeTemplate` → `background`), `31→32` (`Notebook.defaultNativeTemplate` →
  `defaultBackground`), `35→36` (adds `page_sync_state`, renames
  `notebook_sync_state.localUpdatedAtAtSync` → `syncedLocalUpdatedAt`), `37→38` (`Page.scroll` →
  `positionY`; adds `positionX`, the page sheet and the notebook default sheet, all existing pages
  infinite). `36→37` needs no spec: it only adds `remoteDirEtag` / `remoteDirServerKey`.

After the `32→33` migration, `StrokeMigrationHelper.reencodeStrokePointsToBinary()`
(`StrokeReencoding.kt`, started from `MainActivity`) moves the rows from `stroke_old` (JSON point
lists) into `Stroke` in batches, re-encoding them to SB and normalizing pressure, then drops
`stroke_old`. It is idempotent.

### 2.1 Tool / Pen type

https://github.com/Ethran/notable/blob/main/app/src/main/java/com/ethran/notable/editor/utils/pen.kt

The stored field is `pen` (serialized form of `Pen`).

---
## 3) List<StrokePoint> storage format
https://github.com/Ethran/notable/blob/main/app/src/main/java/com/ethran/notable/data/db/StrokePointConverter.kt (polyline coding in `EncodePolyline.kt`; Room calls it through `Converters` in `Db.kt`)

We store `List<StrokePoint>` in a custom binary Structure-of-Arrays (SoA) format (NOT JSON).  
Format name (informal): SB (Stroke Binary). The encoder writes version 2 (SB2); the decoder also reads
version 1 (SB1), which differs only in the pressure channel (3.4). All multi-byte values are little-endian.
The same encoding is used for strokes in WebDAV sync page files.

Each `StrokePoint` logical fields (per code):
- `x: Float`
- `y: Float`
- `pressure: Float?`
- `tiltX: Int?`
- `tiltY: Int?`
- `dt: UShort?`
- legacy (not serialized in SB): `timestamp: Long?`, `size: Float?`

Optional fields are UNIFORM per stroke: either present for every point or absent for all (enforced).

### 3.1 Header

```
Offset  Size  Field
0       1     MAGIC0 = 'S' (0x53)
1       1     MAGIC1 = 'B' (0x42)
2       1     VERSION = 2 (1 still readable)
3       1     MASK (bitfield, see 3.2)
4       4     COUNT (Int, number of points, encoder requires >= 1)
8       1     COMPRESSION (0 = none, 1 = LZ4)
-- header total = 9 bytes
```

Notes:
- Decoder rejects `version > 2`.
- `COUNT` is stored even when fields compress well.
- Encoder rejects empty lists.

### 3.2 MASK bitfield

Bit positions (set = field present for ALL points):

- bit 0 (0x01): pressure
- bit 1 (0x02): tiltX
- bit 2 (0x04): tiltY
- bit 3 (0x08): dt

Uniformity invariants:
- If a bit is set, every point has non-null value for that field.
- If a bit is clear, every point has null for that field.
  Future versions may relax this (would require version bump).

### 3.3 Body layout (SoA sections)

After the 9-byte header:

If `COMPRESSION == 1 (LZ4)`:
```
[ RAW_BODY_SIZE (int32) ]
[ LZ4_COMPRESSED_BODY_BYTES ]
```
`RAW_BODY_SIZE` = exact size in bytes of the uncompressed raw body described below.

If `COMPRESSION == 0`:
```
[ RAW_BODY (uncompressed) ]
```

Raw (uncompressed) body structure:

```
X_SIZE   (int32)                  // byte length of encoded X polyline
X_DATA   [X_SIZE] (ASCII bytes)
Y_SIZE   (int32)
Y_DATA   [Y_SIZE] (ASCII bytes)
[ pressure[count] ]  (if mask bit 0; each 2 bytes: uint16 in SB2, int16 in SB1)
[ tiltX[count] ]      (if bit 1; each int8)
[ tiltY[count] ]      (if bit 2; each int8)
[ dt[count] ]         (if bit 3; each uint16 stored in 2 bytes)
```

Order is fixed: X, Y, pressure, tiltX, tiltY, dt.

### 3.4 Field encodings

- Coordinates (`x`, `y`):
  - Encoded independently with Google's encoded-polyline algorithm (precision = 2, i.e. values are
    rounded to 0.01 and stored as deltas from the previous value), written directly as ASCII bytes
    in one pass (`encode`/`decode` in `EncodePolyline.kt`).
  - `X_SIZE` / `Y_SIZE` are the byte lengths of those sequences.
- `pressure`:
  - SB2: pressure normalized to [0, 1], stored as uint16 fixed-point `round(p * 65535)`. Values
    outside [0, 1] are clamped (and logged as an error, since callers must normalize first). Such
    strokes are stored with `Stroke.maxPressure = 1`.
  - SB1: raw digitizer value as int16; the row's `maxPressure` (typically 4096) is the denominator.
  - Decoding returns SB2 values as [0, 1] and SB1 values raw. `Stroke.withNormalizedPressure()`
    (applied when strokes are loaded from the DB or imported by sync) divides by `maxPressure` and
    sets it to 1, so every in-memory stroke has normalized pressure.
- `tiltX`, `tiltY`: stored as signed bytes (`int8`).
- `dt`: stored as unsigned 16-bit (`uint16`) in a Java/Kotlin `short` slot; the encoder clamps to
  0..65534, the decoder uses `toUShort()`.
- Reserved sentinel: `0xFFFF` (65535) for potential future per-point null `dt`.
  The current decoder does NOT remap it to null.

### 3.5 Size calculations

Raw body size:
```
rawBodySize =
    4 + X_DATA.size +
    4 + Y_DATA.size +
    (mask.pressure ? count * 2 : 0) +
    (mask.tiltX    ? count * 1 : 0) +
    (mask.tiltY    ? count * 1 : 0) +
    (mask.dt       ? count * 2 : 0)
```

Total serialized size (final byte array):
```
totalSize =
    9 /* header */ +
    (compression == LZ4 ? 4 /* RAW_BODY_SIZE */ : 0) +
    (compression == LZ4 ? compressedSize : rawBodySize)
```

### 3.6 Compression

- Algorithm: LZ4 (jpountz high compressor).
- Candidate only if `rawBodySize >= 512` bytes.
- Only accepted if `compressedSize <= rawBodySize * 0.75` (i.e., ≥ 25% saving).
- If accepted:
  - `COMPRESSION = 1`
  - A 4-byte `RAW_BODY_SIZE` precedes compressed payload.
- Otherwise:
  - `COMPRESSION = 0`
  - Raw body follows immediately (no size prefix).

### 3.7 Decoding rules

Steps (`decodeStrokePoints`):
1. Validate minimum length (`>= HEADER_SIZE + 8` as a sanity check).
2. Read magic `'S' 'B'`.
3. Read `VERSION`; reject if `> 2`. The version selects how the pressure channel is read (3.4).
4. Read `MASK` and `COUNT`; reject if `COUNT < 0`.
5. Read `COMPRESSION` flag.
6. If compressed: read `RAW_BODY_SIZE`, decompress remainder via LZ4 into a buffer of that size.
7. From the (decompressed or raw) body buffer:
  - Read `X_SIZE`, `X_DATA`, decode to list of Float.
  - Read `Y_SIZE`, `Y_DATA`, decode.
  - Verify `xs.size == count && ys.size == count`.
  - Conditionally read optional arrays by mask; ensure sufficient remaining bytes; error on truncation.
8. Construct points by parallel index.
9. If uncompressed path and trailing bytes remain → error (compressed path size is exact by construction).
10. Return list.

### 3.8 Encoding rules

`encodeStrokePoints`:
1. Reject empty list and a first point with `y > 10_000_000`.
2. Compute `MASK` from first point (presence flags).
3. Validate uniform presence across all points (throws on mismatch).
4. Encode X & Y via polyline (precision=2) → ASCII bytes.
5. Compute `rawBodySize`; write raw body into a thread-local ByteBuffer (pressure as SB2 fixed-point).
6. Optionally LZ4-compress per compression policy.
7. Write header (9 bytes): magic, version (2), mask, count, compression flag.
8. If compressed: write `RAW_BODY_SIZE`.
9. Append body (compressed or raw).
10. Return final byte array.

### 3.9 Forward compatibility

- Decoder rejects higher versions.
- `dt` sentinel (0xFFFF) reserved for future nullable per-point representation.
- Potential future extensions: per-field metadata, sparse fields, alternative quantization.

### 3.10 Error handling

Throws `IllegalArgumentException` for:
- Empty encode list.
- Non-uniform optional fields.
- Page size heuristic trigger (`y > 10_000_000f` in first point).
- Bad magic bytes.
- Unsupported version.
- Negative count.
- Truncated coordinate or optional sections.
- Invalid raw size when compressed (`<= 0`).
- Trailing bytes (uncompressed path).
- Body size mismatch sanity check.
- Missing compressed data.

An unknown compression flag fails with `IllegalStateException`.

## 4) Ideas for Improvements

- Additional polyline / simplification pipelines:
  - [PolylineUtils gist (Kotlin, encoding + RDP)](https://gist.github.com/ghiermann/ed692322088bb39166a669a8ed3a6d14)
  - [Ramer–Douglas–Peucker (Kotlin)](https://rosettacode.org/wiki/Ramer-Douglas-Peucker_line_simplification#Kotlin)
  - [Google Encoded Polyline Algorithm docs](https://developers.google.com/maps/documentation/utilities/polylinealgorithm?csw=1)
- Ignore unused attributes depending on tool mode:
  - Ballpoint: x, y only
  - Fountain pen: + pressure
  - Pencil: + pressure + tilt