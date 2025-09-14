# Notable Persistent Data Model and Stroke Encoding Specification

This document defines the persistent data model of Notable and the storage of stroke point lists as implemented.
It describes the structures and fields that actually exist in the codebase.
It is a specification of the current structure, not a change log.
**It was created by AI, and roughly checked for correctness.
Refer to code for actual implementation.**

Contents:
- Entities and relationships
- Logical schemas
- Stroke point list storage format
- Spatial indexing
- Read/write semantics (normative behavior)

---

## 1) Entities

- Folder https://github.com/Ethran/notable/blob/main/app/src/main/java/com/ethran/notable/data/db/Folder.kt
  - Hierarchical container. Fields in code: `id` (String UUID PK), `title`, `parentFolderId` (nullable FK to Folder), `createdAt`, `updatedAt`.

- Page https://github.com/github.com/Ethran/notable/blob/main/app/src/main/java/com/ethran/notable/data/db/Page.kt
  - A document entry with optional notebook grouping. Fields: `id` (String UUID PK), `scroll`, `notebookId` (nullable FK), `background`, `backgroundType`, `parentFolderId` (nullable FK to Folder), `createdAt`, `updatedAt`.
- Stroke https://github.com/Ethran/notable/blob/main/app/src/main/java/com/ethran/notable/data/db/Stroke.kt
  - Addressable record containing style and geometry inline. Fields: `id` (String UUID PK), `size` (Float), `pen` (serialized `Pen`), `color` (Int ARGB), bounding box floats (`top`, `bottom`, `left`, `right`), `points` (List<StrokePoint>), `pageId` (FK), timestamps.
- StrokePoint (geometry payload)  https://github.com/Ethran/notable/blob/1c242de6a005abece5e2d246cdb9e90b34206611/app/src/main/java/com/ethran/notable/data/db/Stroke.kt#L18C12-L18C23
  - Inlined per-point samples: `x: Float`, `y: Float`, optional `pressure: Float?`, optional `tiltX: Int?`, `tiltY: Int?`, optional `dt: UShort?`, plus legacy serialized fields (`timestamp`, `size`) retained for backward compatibility.
- Notebook https://github.com/Ethran/notable/blob/main/app/src/main/java/com/ethran/notable/data/db/Notebook.kt
  - Referenced by `Page.notebookId` (grouping construct; details in code).
- Image https://github.com/Ethran/notable/blob/main/app/src/main/java/com/ethran/notable/data/db/Image.kt
  - Raster asset placed on a page (file defines its own fields in code).

---

## 2) Logical Schemas

The current implementation uses Room with UUID string primary keys (not integer autoincrement).
Strokes embed their point list directly (no separate blob table, no quantized integer bbox).

```sql
-- folders (Entity: Folder)
CREATE TABLE folder (
  id             TEXT PRIMARY KEY,
  title          TEXT NOT NULL,
  parentFolderId TEXT REFERENCES folder(id) ON DELETE CASCADE,
  createdAt      INTEGER NOT NULL,  -- epoch ms (Room Date)
  updatedAt      INTEGER NOT NULL
);

CREATE INDEX index_folder_parentFolderId ON folder(parentFolderId);


-- pages (Entity: Page)
CREATE TABLE page (
  id             TEXT PRIMARY KEY,
  scroll         INTEGER NOT NULL,
  notebookId     TEXT REFERENCES notebook(id) ON DELETE CASCADE,
  background     TEXT NOT NULL,
  backgroundType TEXT NOT NULL,
  parentFolderId TEXT REFERENCES folder(id) ON DELETE CASCADE,
  createdAt      INTEGER NOT NULL,
  updatedAt      INTEGER NOT NULL
);

CREATE INDEX index_page_notebookId     ON page(notebookId);
CREATE INDEX index_page_parentFolderId ON page(parentFolderId);


-- strokes (Entity: Stroke)
CREATE TABLE stroke (
  id        TEXT PRIMARY KEY,
  size      REAL NOT NULL,
  pen       TEXT NOT NULL,      -- serialized Pen
  color     INTEGER NOT NULL,   -- ARGB
  top       REAL NOT NULL,
  bottom    REAL NOT NULL,
  left      REAL NOT NULL,
  right     REAL NOT NULL,
  points    BLOB NOT NULL,      -- serialized List<StrokePoint>
  pageId    TEXT NOT NULL REFERENCES page(id) ON DELETE CASCADE,
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL
);

CREATE INDEX index_stroke_pageId ON stroke(pageId);
```

Notes:
- Column affinities shown reflect typical Room output; `points` may appear as `TEXT` or `BLOB` depending on the converter but is treated as opaque serialized data.

### 2.1 Tool / Pen type

https://github.com/Ethran/notable/blob/main/app/src/main/java/com/ethran/notable/editor/utils/pen.kt

The stored field is `pen` (serialized form of `Pen`).

---

## 3) List<StrokePoint> storage format
https://github.com/Ethran/notable/blob/dev/app/src/main/java/com/ethran/notable/data/db/StrokePointConverter.kt

We store `List<StrokePoint>` in a custom binary Structure-of-Arrays (SoA) format (NOT JSON).  
Format name (informal): SB1 (Stroke Binary v1). All multi-byte values are little-endian.

Each `StrokePoint` record fields (per code):
- `x: Float`
- `y: Float`
- `pressure: Float?`
- `tiltX: Int?`
- `tiltY: Int?`
- `dt: UShort?`
- legacy serialized (not used in SB1 binary but exist in older JSON form): `timestamp: Long?` (private), `size: Float?` (private)

### 3.1 Header

```
Offset  Size  Field
0       1     MAGIC0 = 'S' (0x53)
1       1     MAGIC1 = 'B' (0x42)
2       1     VERSION = 1
3       1     MASK (bitfield, see below)
4       4     COUNT (Int, number of points, >= 1)
-- header total = 8 bytes
```

### 3.2 MASK bitfield

Bit positions (set = field present for ALL points):

- bit 0: pressure
- bit 1: tiltX
- bit 2: tiltY
- bit 3: dt

Invariants (enforced by `validateUniform`):
- If a bit is set, every point has non-null value for that field.
- If a bit is clear, every point has null for that field.
  This “uniform presence” rule defines SB1. Future versions may allow sparse/null-per-point encodings.

### 3.3 Body layout (SoA sections)

Immediately after the header, arrays appear in fixed order:

1. `x[COUNT]` as `float32`
2. `y[COUNT]` as `float32`
3. If MASK pressure bit: `pressure[COUNT]` as `float32`
4. If MASK tiltX bit: `tiltX[COUNT]` as `int32`
5. If MASK tiltY bit: `tiltY[COUNT]` as `int32`
6. If MASK dt bit: `dt[COUNT]` as `uint16`

Total size calculation:
```
size = 8
     + COUNT * 8                        // x + y
     + (MASK.pressure ? COUNT * 4 : 0)
     + (MASK.tiltX    ? COUNT * 4 : 0)
     + (MASK.tiltY    ? COUNT * 4 : 0)
     + (MASK.dt       ? COUNT * 2 : 0)
```

### 3.4 dt encoding

- Stored as unsigned 16-bit (`uint16`).
- Code reserves sentinel 0xFFFF for a potential future “null” dt; current SB1 uniform rule prevents per-point nulls when dt bit set.
- Valid application dt values therefore map into `[0, 65534]`.

### 3.5 Decoding rules

Decoder steps (`decodeStrokePoints`):
1. Verify minimum length (≥ 8).
2. Check magic `'S' 'B'`.
3. Read version; if `> 1` → unsupported.
4. Read MASK (byte) and COUNT (int ≥ 0).
5. Read coordinate arrays (must have `COUNT * 8` bytes).
6. Conditionally read optional arrays according to MASK (validate remaining size).
7. Materialize `List<StrokePoint>` combining parallel arrays.
8. If `failOnTrailing = true`, any leftover bytes cause error.

### 3.6 Encoding rules

`encodeStrokePoints`:
1. Require non-empty point list.
2. Derive `MASK` from first point (`computeStrokeMask`).
3. Validate uniformity across all points (`validateUniform`).
4. Allocate buffer with exact size.
5. Write header, then mandatory and optional arrays in order.

### 3.7 Forward compatibility

- Decoder permits (but currently rejects) future higher version numbers (`version > 1` → error).
- Reserved sentinel for dt allows future relaxation of uniformity when combined with a new version.

### 3.8 Error handling (per code)

Errors thrown as `IllegalArgumentException` for:
- Empty list (encode)
- Non-uniform optional fields
- Bad magic
- Unsupported version
- Negative count
- Truncated sections
- Trailing bytes (only if `failOnTrailing = true`)

## 4) Ideas for Improvements

- [PolylineUtils gist (Kotlin, encoding + RDP)](https://gist.github.com/ghiermann/ed692322088bb39166a669a8ed3a6d14)
- [Ramer–Douglas–Peucker on Rosetta Code (Kotlin)](https://rosettacode.org/wiki/Ramer-Douglas-Peucker_line_simplification#Kotlin)
- [Google’s Encoded Polyline Algorithm](https://developers.google.com/maps/documentation/utilities/polylinealgorithm?csw=1)

### Notes
- Store **offsets (deltas)** between consecutive points instead of absolute values → smaller numbers, better compression.
- Use **curve simplification** (e.g. Ramer–Douglas–Peucker) to remove redundant points.
- Ignore unused stroke attributes:
  - Ballpoint: only `x, y`
  - Fountain pen: add `pressure`
  - Pencil: add `pressure + tilt`
