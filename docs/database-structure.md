# Notable Persistent Data Model and Stroke Encoding Specification

This document defines the persistent data model of Notable and the canonical binary format used to store pen strokes. It describes structures, fields, types, and constraints. It is a specification of the structure, not a change log.

Contents:
- Entities and relationships
- Logical schemas
- Stroke blob binary format
- Spatial indexing
- Read/write semantics (normative behavior)

---

## 1) Entities

- Document
  - A logical container of pages. A document may be a single file or a collection in a directory depending on backend implementation.
- Page
  - A canvas with fixed pixel dimensions and DPI.
- Layer
  - A named Z-ordered collection of strokes on a page.
- Stroke (metadata)
  - Addressable record describing where a stroke belongs, its styling, and a reference to its encoded geometry payload.
- StrokeBlob (geometry payload)
  - Versioned, immutable binary encoding of a stroke’s sampled data (coordinates and optional channels).
- SpatialIndex
  - An index of stroke bounding boxes for region queries.

---

## 2) Logical Schemas

The canonical schema is defined below using SQLite types. Alternative backends must provide equivalent fields and constraints.

```sql
-- Pages
CREATE TABLE pages (
  id            INTEGER PRIMARY KEY,
  title         TEXT,
  width_px      INTEGER NOT NULL,   -- canvas width in pixels
  height_px     INTEGER NOT NULL,   -- canvas height in pixels
  dpi           INTEGER NOT NULL,   -- dots per inch
  created_at_ms INTEGER NOT NULL,   -- epoch ms
  updated_at_ms INTEGER NOT NULL
);

-- Layers
CREATE TABLE layers (
  id            INTEGER PRIMARY KEY,
  page_id       INTEGER NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
  z_index       INTEGER NOT NULL,   -- render order; lower paints first
  name          TEXT,
  visible       INTEGER NOT NULL DEFAULT 1, -- boolean 0/1
  locked        INTEGER NOT NULL DEFAULT 0, -- boolean 0/1
  created_at_ms INTEGER NOT NULL,
  updated_at_ms INTEGER NOT NULL
);

-- Strokes (metadata)
CREATE TABLE strokes (
  id            INTEGER PRIMARY KEY,
  page_id       INTEGER NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
  layer_id      INTEGER NOT NULL REFERENCES layers(id) ON DELETE CASCADE,

  -- Rendering/style metadata
  version       INTEGER NOT NULL,   -- stroke blob version (e.g., 2)
  flags         INTEGER NOT NULL,   -- mirrors blob header flags
  tool_type     INTEGER NOT NULL,   -- enum (see §2.1)
  color_rgba    INTEGER NOT NULL,   -- 0xAARRGGBB packed
  width_base_q  INTEGER NOT NULL,   -- base width in quantized units (see §3.2)
  style_hash    INTEGER,            -- optional 32-bit hash of style

  -- Geometry summary
  points_count  INTEGER NOT NULL,   -- number of sampled points (N)
  bbox_min_x_q  INTEGER NOT NULL,   -- stroke bbox, quantized (see §3.2)
  bbox_min_y_q  INTEGER NOT NULL,
  bbox_max_x_q  INTEGER NOT NULL,
  bbox_max_y_q  INTEGER NOT NULL,

  -- Storage
  blob_id       INTEGER NOT NULL REFERENCES stroke_blobs(id) ON DELETE CASCADE,

  -- Bookkeeping
  created_at_ms INTEGER NOT NULL,
  updated_at_ms INTEGER NOT NULL
);

-- Stroke geometry blobs (immutable)
CREATE TABLE stroke_blobs (
  id            INTEGER PRIMARY KEY,
  codec         TEXT NOT NULL,      -- e.g., "stroke.v2.delta+varint"
  bytes         BLOB NOT NULL,      -- binary payload (see §3)
  crc32         INTEGER             -- optional integrity checksum
);
```

Recommended indexes:
```sql
CREATE INDEX idx_strokes_page_layer ON strokes(page_id, layer_id);
CREATE INDEX idx_strokes_blob       ON strokes(blob_id);
CREATE INDEX idx_layers_page        ON layers(page_id);
```

### 2.1 Tool type enumeration
- 0 = pen
- 1 = highlighter
- 2 = brush
- 3 = pencil
- 4 = eraser (vector)
- 5 = marker
- 6..255 = reserved/extension

Implementations can extend this enum, but values must be stable across saves.

---

## 3) Stroke Blob Format

Codec identifier: "stroke.v2.delta+varint"

All strokes are serialized as immutable binary blobs with the following properties:
- Coordinates are quantized to fixed-point integers (Q units) before encoding.
- The first point is encoded absolute; subsequent points are delta-encoded.
- Deltas use ZigZag + VarInt encoding.
- Optional channels (pressure, tilt, time) are present if flagged in the header.

Unless stated otherwise, multi-byte fixed-width integers are little-endian.

### 3.1 Binary layout (byte order)

```
+----------------------+--------------------------------------------+
| Magic                | 2 bytes ASCII "ST" (0x53 0x54)             |
| Version              | 1 byte  = 0x02                             |
| Flags                | 1 byte  bitfield (see §3.4)                |
| PointCount           | VarInt  N                                  |
| ToolType             | VarInt  enum (§2.1)                        |
| ColorRGBA            | 4 bytes 0xAARRGGBB                         |
| WidthBaseQ           | VarInt  base width in quantized units      |
| BBox.min_x_q         | VarInt                                     |
| BBox.min_y_q         | VarInt                                     |
| BBox.max_x_q         | VarInt                                     |
| BBox.max_y_q         | VarInt                                     |
| StyleHash (opt)      | VarInt  if Flags.hasStyleHash              |
| SegmentCount (opt)   | VarInt  if Flags.hasSegments               |
| SegmentTable (opt)   | variable (§3.6)                            |
| GeometryStream       | variable (§3.5)                            |
| PressureStream (opt) | variable if Flags.hasPressure              |
| TiltStream (opt)     | variable if Flags.hasTilt                  |
| TimeStream (opt)     | variable if Flags.hasTime                  |
| CRC32 (opt)          | 4 bytes if Flags.hasCRC32                  |
+----------------------+--------------------------------------------+
```

### 3.2 Quantization
- Coordinate quantization factor Q = 64 (1/64 px resolution).
  - x_q = round(x_px * Q), y_q = round(y_px * Q).
- Base width quantization uses the same scale: width_base_q = round(width_px * Q).
- BBox is the axis-aligned bounding box of all (x_q, y_q) points.

All quantized coordinates and widths are signed 32-bit range at encode/decode time. They are serialized as VarInts (with ZigZag for signed values where applicable).

### 3.3 VarInt and ZigZag
- VarInt: 7-bit continuation encoding per byte (MSB=1 means more bytes).
- ZigZag for signed integers:
  - zigzag(n)   = (n << 1) ^ (n >> 31)
  - unzigzag(u) = (u >> 1) ^ -(u & 1)

### 3.4 Flags bitfield (1 byte)
- bit 0 (0x01): hasPressure
- bit 1 (0x02): hasTilt
- bit 2 (0x04): hasTime
- bit 3 (0x08): hasSegments
- bit 4 (0x10): hasStyleHash
- bit 5 (0x20): reserved (must be 0)
- bit 6 (0x40): reserved (must be 0)
- bit 7 (0x80): hasCRC32

Writers must set only supported bits. Readers must ignore unknown reserved bits if encountered in future versions.

### 3.5 Geometry stream (coordinates)

- First point (absolute):
  - x0_q: VarInt(zigzag(x0_q))
  - y0_q: VarInt(zigzag(y0_q))
- Subsequent points i = 1..N-1:
  - dx_i = x_i_q - x_(i-1)_q
  - dy_i = y_i_q - y_(i-1)_q
  - encode VarInt(zigzag(dx_i)), VarInt(zigzag(dy_i))
- Interleaving order:
  - [x0, y0, dx1, dy1, dx2, dy2, ..., dxN-1, dyN-1]

Note: No sentinel or RLE codes are defined in v2 geometry. All pairs are strictly present for N points.

### 3.6 Segmentation table (optional)

If `Flags.hasSegments` is set, a segment table precedes the geometry stream:

- SegmentCount: VarInt S
- For each segment k in [0..S-1]:
  - start_index: VarInt  (0-based index into points)
  - segment_type: 1 byte
    - 0 = polyline
    - 1 = quadratic Bezier
    - 2 = cubic Bezier
  - params: variable
    - For quadratic: (cx, cy) control point as deltas from the start point coordinates, each as VarInt(zigzag(delta_q)).
    - For cubic: (c1x, c1y, c2x, c2y) as deltas similarly encoded.

Segments partition or reference subranges of the point sequence for rendering without refitting. When absent, consumers treat the stroke as a polyline over all points.

### 3.7 Optional channels

All channels align 1:1 with the N points.

- Pressure (if Flags.hasPressure)
  - Quantization: p in [0..1] → uint8 p_q = round(p * 255).
  - Encoding:
    - First: p0_q as a single byte (0..255).
    - Subsequent i: dp_i = p_i_q - p_(i-1)_q; encode as VarInt(zigzag(dp_i)).
- Tilt (if Flags.hasTilt)
  - Components tx, ty (e.g., degrees) quantized to int8 per component:
    - t_q = clamp(round(t_degrees), -128..127).
  - Encoding per component independent delta streams:
    - First: tx0_q, ty0_q as 1 byte each (signed).
    - Subsequent: VarInt(zigzag(dtx_i)), VarInt(zigzag(dty_i)).
  - Ordering: all tx values then all ty values OR interleaved per sample. In v2, tilt is interleaved per sample after geometry if present: [..., dxi, dyi, dtxi, dtyi] for i≥1, with tx0_q, ty0_q emitted immediately after x0,y0.
- Time (if Flags.hasTime)
  - First: t0_ms absolute epoch milliseconds as VarInt (unsigned).
  - Subsequent: dt_i = t_i_ms - t_(i-1)_ms; encode dt_i as VarInt (non-negative).

Note: Implementations must follow the precise ordering chosen above when Flags indicate presence.

### 3.8 CRC32 (optional)
- If `Flags.hasCRC32` is set, a 32-bit little-endian CRC32 of all prior bytes in the blob is appended. Consumers must verify when present and reject corrupted blobs.

---

## 4) Spatial Indexing

Two canonical forms are defined; implementations must provide at least one.

- SQLite R*Tree table:
  ```sql
  CREATE VIRTUAL TABLE stroke_rtree USING rtree(
    stroke_id,  -- INTEGER PRIMARY KEY, references strokes(id)
    min_x, max_x,
    min_y, max_y
  );
  ```
  - Units: quantized coordinates (same Q as §3.2).
  - On every insert/update/delete to `strokes`, triggers maintain `stroke_rtree`.

- Grid buckets (file/in-memory):
  - The canvas is partitioned into fixed-size tiles in quantized units.
  - Each tile stores a compact list of stroke_ids intersecting the tile’s rect.
  - Bucket lists are updated on stroke insert/delete and rebuilt during compaction.

---

## 5) Read/Write Semantics

- Insert stroke:
  - Quantize samples → compute bbox → encode blob → insert into `stroke_blobs` → insert into `strokes` with metadata mirrored from blob header → add bbox to SpatialIndex.
- Read for rendering:
  - Query SpatialIndex with view rect (quantized).
  - Fetch candidate `strokes` rows.
  - Decode only needed blobs. Clients may keep decoded SoA buffers cached; decoded buffers are not persisted.
- Delete stroke:
  - Remove `strokes` row (or tombstone in file-backed store) and update SpatialIndex; blob can be GC’d or vacuumed as backend-specific maintenance.

---

## 6) Data Types and Constraints

- ids: INTEGER, unique per table.
- Timestamps: INTEGER epoch milliseconds.
- color_rgba: 32-bit ARGB packed integer.
- Quantized coordinates/widths: signed 32-bit range pre-encoding; serialized with VarInt(ZigZag) as specified.
- points_count: N ≥ 1.
- bbox_min_x_q ≤ bbox_max_x_q; bbox_min_y_q ≤ bbox_max_y_q.
- version: must equal 2 for v2 blobs; consumers may support multiple versions concurrently based on `stroke_blobs.codec`.

---

## 7) In-Memory Decoded Representation (non-persistent)

For efficient processing, decoders should produce Structure-of-Arrays (SoA):

- xs_q: int32[N]
- ys_q: int32[N]
- pressure_q: uint8[N] (optional)
- tilt_tx_q: int8[N], tilt_ty_q: int8[N] (optional)
- time_ms: uint64[N] or uint32 base + uint32 deltas depending on consumer (optional)

These arrays are derived from the blob and must not be persisted.

---

## 8) Compatibility

- The `codec` field in `stroke_blobs` identifies the encoding. Consumers must parse only when `codec` is recognized.
- Future versions may add new flags or sections; unknown flags must not be set by v2 writers and must be ignored (but preserved) by tolerant readers only when the `version` semantics allow it.

---