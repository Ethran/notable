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

- Page https://github.com/Ethran/notable/blob/main/app/src/main/java/com/ethran/notable/data/db/Page.kt
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
https://github.com/Ethran/notable/blob/dev/app/src/main/java/com/ethran/notable/data/db/ (implementation in `stroke_mask_helpers.kt` / formerly `stroke_encoding.kt`)

We store `List<StrokePoint>` in a custom binary Structure-of-Arrays (SoA) format (NOT JSON).  
Format name (informal): SB1 (Stroke Binary v1). All multi-byte values are little-endian.

Each `StrokePoint` logical fields (per code):
- `x: Float`
- `y: Float`
- `pressure: Float?`
- `tiltX: Int?`
- `tiltY: Int?`
- `dt: UShort?`
- legacy (not serialized in SB1): `timestamp: Long?`, `size: Float?`

Optional fields are UNIFORM per stroke: either present for every point or absent for all (enforced).

### 3.1 Header

```
Offset  Size  Field
0       1     MAGIC0 = 'S' (0x53)
1       1     MAGIC1 = 'B' (0x42)
2       1     VERSION = 1
3       1     MASK (bitfield, see 3.2)
4       4     COUNT (Int, number of points, encoder requires >= 1)
8       1     COMPRESSION (0 = none, 1 = LZ4)
-- header total = 9 bytes
```

Notes:
- Decoder rejects `version > 1`.
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
X_DATA   [X_SIZE] (UTF-8 bytes)
Y_SIZE   (int32)
Y_DATA   [Y_SIZE] (UTF-8 bytes)
[ pressure[count] ]  (if mask bit 0; each int16)
[ tiltX[count] ]      (if bit 1; each int8)
[ tiltY[count] ]      (if bit 2; each int8)
[ dt[count] ]         (if bit 3; each uint16 stored in 2 bytes)
```

Order is fixed: X, Y, pressure, tiltX, tiltY, dt.

### 3.4 Field encodings

- Coordinates (`x`, `y`):
  - Encoded independently via a polyline algorithm (precision = 2) → text string → UTF-8 bytes.
  - `X_SIZE` / `Y_SIZE` are the byte lengths of those UTF-8 sequences.
- `pressure`: stored as signed 16-bit integer (`short`). The encoder casts `Float -> Int -> Short`. Decoder converts `Short -> Float`.
- `tiltX`, `tiltY`: stored as signed bytes (`int8`).
- `dt`: stored as unsigned 16-bit (`uint16`) in a Java/Kotlin `short` slot; decoder uses `toUShort()`.
- Reserved sentinel: `0xFFFF` (65535) for potential future per-point null `dt`.
Current decoder does NOT remap it to null; applications should avoid using 65535 if future null semantics are desired.

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
3. Read `VERSION`; reject if `> 1`.
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
1. Reject empty list.
2. Compute `MASK` from first point (presence flags).
3. Validate uniform presence across all points (throws on mismatch).
4. Encode X & Y via polyline (precision=2) → UTF-8 bytes.
5. Compute `rawBodySize`; write raw body into a thread-local ByteBuffer.
6. Optionally LZ4-compress per compression policy.
7. Write header (9 bytes): magic, version, mask, count, compression flag.
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

## 4) Ideas for Improvements

- Additional polyline / simplification pipelines:
  - [PolylineUtils gist (Kotlin, encoding + RDP)](https://gist.github.com/ghiermann/ed692322088bb39166a669a8ed3a6d14)
  - [Ramer–Douglas–Peucker (Kotlin)](https://rosettacode.org/wiki/Ramer-Douglas-Peucker_line_simplification#Kotlin)
  - [Google Encoded Polyline Algorithm docs](https://developers.google.com/maps/documentation/utilities/polylinealgorithm?csw=1)
- Ignore unused attributes depending on tool mode:
  - Ballpoint: x, y only
  - Fountain pen: + pressure
  - Pencil: + pressure + tilt