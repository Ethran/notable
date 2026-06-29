# Onyx NeoFountainPenV2 — How Offline Rendering Works

This document explains how the Onyx fountain-pen-V2 stroke rendering works, why a
naive implementation does **not** match the firmware's live rendering, and how to
render strokes so they match exactly.

## Context

On Onyx e-ink devices, the firmware draws the stroke *live* with its own renderer
while the pen moves. Notable then has to re-draw that same stroke onto its own
surface so that when the screen is unfrozen there is no flicker. For this to work,
the offline redraw must be **pixel-identical** to what the firmware drew live.

The firmware draws live using the SDK's own config + render pipeline. Any deviation
from that pipeline (custom config, custom draw loop) produces a stroke that does not
match.

## Relevant SDK artifacts

- `onyxsdk-pen` (1.5.4, Maven) — contains `NeoPenRender`, `NeoFountainPenWrapper`.
  Note these live in the **pen** jar, not penbrush. The jar resolves to the Gradle
  transform cache (`~/.gradle/caches/.../onyxsdk-pen-1.5.4/jars/classes.jar`).
- `onyxsdk-penbrush` (1.1.0.1, local `app/libs/*.aar`) — contains `NeoFountainPenV2`,
  `NeoPenConfig`, `PenPathResult`, `PenResult`, `FountainShapes`, `NeoPen`.

The official demo (`OnyxAndroidDemo`) renders the fountain V2 pen in
`app/OnyxPenDemo/.../shape/BrushScribbleShape.java`. That class is the reference
implementation to mirror.

## How the SDK pipeline works (decompiled)

### `FountainShapes.createNeoPenV2(...)`

This is the factory the demo uses. It builds a `NeoPenConfig` with specific values —
these are what the firmware uses:

```
config.width            = width + 3.0f / createScale   // FOUNTAIN_PEN_V1_COMPENSATION = 3.0f
config.minWidth         = minWidth / createScale
config.pressureSensitivity = pressureSensitivity ?: 0.3f
config.smoothLevel      = smoothLevel ?: 0.6f
config.scalePrecision   = scalePrecision
config.tiltEnabled      = false
config.fastMode         = fastMode
config.displayScaleX    = displayScaleX
config.displayScaleY    = displayScaleY
return NeoFountainPenV2.Companion.create(config)
```

Signature:
`createNeoPenV2(width, minWidth, displayScaleX, displayScaleY, scalePrecision, createScale, pressureSensitivity: Float?, fastMode: Boolean, smoothLevel: Float?)`

Demo call: `createNeoPenV2(strokeWidth, MIN_FOUNTAIN_PEN_WIDTH, 1f, 1f, 1f, 1f, null, true, null)`.

Constants:
- `NeoFountainPenWrapper.MIN_FOUNTAIN_PEN_WIDTH = 1.0f`
- `FountainShapes.FOUNTAIN_PEN_V1_COMPENSATION = 3.0f`

> The `pressureSensitivity` (default 0.3) and `width` arguments are exactly the official
> app's **"Pressure sensitivity" (0–100%)** and **"Line width" (mm)** sliders — Fountain V2
> is `NEOPEN_PEN_TYPE_FOUNTAIN_V2 = 6`. See
> `docs/onyx-pressure-sensitivity-and-line-width.md` for the full mapping (incl. mm→px and
> the firmware-side `EACStrokeStyle`).

### `NeoFountainPenV2.Companion.create(config)`

```
handle = NeoPenNative.createPen(6, config.toNativeConfig())  // 6 = NEOPEN_PEN_TYPE_FOUNTAIN_V2
```

The pen **type is hardcoded to 6**, so you do not need to set `config.type` yourself.

`buildPenResult` returns a `Pair<PenResult, PenResult>`:
- `.first`  = the real ink (committed segment)
- `.second` = the prediction ink (trailing segment up to the latest point)

When `fastMode` is true these are `PenPointResult`; otherwise `PenPathResult`.

### `NeoPenRender`

This is the renderer. The important methods:

- `render(canvas, paint, points)` → calls `onTouchPointList(points)`, then
  `render(canvas, paint)`, then `reset()`.
- `onTouchPointList(points)`:
  - `onTouchDown(first, repaint=true)`
  - splits the middle points into batches of `POINT_LIST_BATCH_LIMIT = 1000` and
    calls `onTouchMove(batch, predict=null, repaint=true)` for each
  - `onTouchDone(last, repaint=true)`
  - accumulates every returned `Pair` into an internal list.
- `render(canvas, paint)`:
  - draws `.first` of **every** accumulated result,
  - **plus `.second` of the last result** (the trailing prediction segment).

That last point is critical — the tail of the stroke lives in the `.second` of the
final pair.

## Why a naive hand-rolled implementation does NOT match

The original Notable wrapper created the pen with a bare `NeoPenConfig` and drove
`onPenDown/onPenMove/onPenUp` manually, drawing only `.first`. Every divergence below
caused a visible mismatch:

1. **Wrong config (biggest cause).** Using a bare `NeoPenConfig` and only setting
   width/tilt/maxPressure means you miss:
   - the **+3px width compensation** (`width + 3.0/createScale`) → stroke too thin.
   - `minWidth`, `smoothLevel = 0.6`, `pressureSensitivity = 0.3` → native defaults
     used instead; `smoothLevel` in particular reshapes the path geometry.
   - `tiltEnabled` — fountain V2 uses **false**. Setting it `true` changes the outline.
   - `fastMode` — see the dedicated section below. For an **offline redraw use `false`**
     (smooth `PenPathResult`); `true` gives discrete `PenPointResult` dabs that look
     "point by point".

2. **Double pressure normalization.** Pre-dividing points by `maxTouchPressure`
   *and* calling `setMaxTouchPressure(...)` on the config makes the native code
   normalize a second time, collapsing the pressures. The demo leaves
   `config.maxTouchPressure` at default and only pre-normalizes the points
   (and only if any pressure > 1.0).

3. **Mutating the caller's points in place.** `points[i].pressure /= max` mutates the
   shared list. Because the stroke is re-rendered after each unfreeze, the pressures
   shrink on every redraw. The demo copies via `new TouchPoint(p)`.

4. **Dropping the stroke tail.** Drawing only `.first` of each result omits the
   `.second` of the last result (the trailing prediction ink), so the end of the
   stroke never reaches the final point. It also bypasses the SDK's 1000-point
   batching.

## The "point by point" bug: `fastMode` and result type (most important)

This is the single biggest cause of the offline redraw not matching the firmware, and it is
**independent** of timestamps/config sizing.

`NeoFountainPenV2.buildPenResult` returns a different `PenResult` subtype depending on
`config.fastMode`:

| `fastMode` | result type | what `.draw()` paints | use |
|---|---|---|---|
| `true`  | `PenPointResult` | discrete point/dab stamps | firmware **live** low-latency drawing |
| `false` | `PenPathResult`  | continuous smooth filled vector path | **offline redraw** |

The demo's `BrushScribbleShape` passes `fastMode = true` — but that class is for the **brush**
pen, and the demo has **no fountain-V2 shape** at all. Mirroring it for fountain brought the
wrong mode along: the redraw rendered as a string of dabs ("drawn point by point", faceted),
even though the config sizing was correct.

The old hand-rolled wrapper
(`NeoFountainPenV2.create(NeoPenConfig().apply { setWidth(..); setTiltEnabled(true); setMaxTouchPressure(..) })`)
looked smooth precisely because a bare `NeoPenConfig` **defaults `fastMode` to `false`**, so it
got `PenPathResult`. Its only real defect was sizing (no `+3px` compensation, no `minWidth`,
native default `smoothLevel`/`pressureSensitivity`).

**Fix:** keep `FountainShapes.createNeoPenV2(...)` for correct sizing, but pass
**`fastMode = false`**. `NeoPenRender` renders either subtype (`renderResult` just calls
`penResult.draw()`), so the render path is unchanged — you simply get the smooth path.

## The faceted-curve bug: missing per-point timestamps

Even with the wrapper above (config + render path identical to the demo), the offline
redraw can still look **faceted / segment-by-segment on tight, fast curves** while the
firmware's live stroke is smooth. The cause is **not** in the render pipeline — it is in
the input points.

- `NeoFountainPenV2` is a `NeoNativePen`; its smoothing/curve-subdivision happens in
  native code and uses the **inter-point velocity**, which it derives from each
  `TouchPoint`'s **timestamp**.
- The demo feeds the firmware's captured `touchPointList`, whose points carry **real,
  increasing per-point timestamps**.
- Notable persists strokes as `StrokePoint`, which has **no timestamp field**. When
  rebuilding `TouchPoint`s (`strokeToTouchPoints`), every point was stamped with the same
  `stroke.updatedAt.time`. Identical timestamps ⇒ velocity ≈ 0 everywhere ⇒ the native
  smoother stops subdividing and connects raw samples with straight segments. This is
  worst exactly where the user notices it: tight, fast curves (few raw samples, high real
  velocity).

**Fix (data first):** persist the real per-point timing so the original velocity profile
is available, instead of synthesizing a fake cadence at render time.

`StrokePoint` already has a `dt: UShort?` field ("delta time in ms, from the first point in
the stroke") and the SB1 binary format already has a DT channel (uint16) — it was just
never populated. `copyInput` now fills it from the firmware `TouchPoint.timestamp`:

```kotlin
val baseTime = touchPoints.first().timestamp
touchPoints.map {
    val deltaMs = (it.timestamp - baseTime).coerceIn(0L, 65534L) // 0xFFFF reserved
    it.toStrokePoint(scroll, scale).copy(dt = deltaMs.toUShort())
}
```

Storing only the **delta** (uint16, 2 bytes) rather than an absolute timestamp (long,
8 bytes) is the cheap, lossless-enough representation; it is then LZ4-compressed with the
rest of the stroke body. No DB migration is needed: `points` is a binary blob and the SB1
v1 format already defined the DT channel, so old strokes (dt absent) and new strokes (dt
present) coexist.

**Deferred:** actually *consuming* `dt` in `strokeToTouchPoints` to feed the native
smoother. This is intentionally not wired up yet — the data is captured first so it exists
for strokes drawn from now on.

### Absolute vs delta vs zero-based timestamps — only deltas matter [sdk][framework]

When you do wire it up, you can use a **0-based timeline** (`timestamp = dt`, i.e. the first
point at 0) — there is **no need** to add `stroke.updatedAt.time` or any wall-clock base.
Evidence from the decompiled stack:

- **Which pens use the timestamp:** every Neo* pen wrapper packs it into the native point
  array — `PenUtils.getPointDoubleArray(List)` lays out 7 doubles per point
  `[x, y, pressure, size, tiltX, tiltY, timestamp]` (and the float variant
  `[x, y, pressure, size, timestamp]`). It is **stored raw, never delta-converted in Java** —
  the native pen does the diffing. The pens that actually *consume* it are the
  velocity-modulated ones (Fountain V1/V2, Brush, Charcoal — anything driven by
  `NeoPenConfig.velocitySensitivity`, default 0.5). Constant-width pens (ballpoint) and
  Notable's custom ball-pen renderer ignore it.
- **It's used only as velocity** (`Δdistance / Δtimestamp`). Nothing compares the timestamp
  to the wall clock or `SystemClock`, so a constant offset cancels out: a 0-based timeline
  and an absolute one produce identical velocity → identical ink.
- **Smaller is actually safer.** The framework's *live* stroke API ships the time as a
  **`float`** (`ViewUpdateHelper.addStrokePoint(…, float time)`), and the raw source is
  `MotionEvent.getEventTime()` — absolute uptime in the billions of ms, which a 32-bit float
  cannot resolve to the millisecond. Onyx getting away with that only works because the
  native side cares about *differences*; small/zero-based values keep full precision. The
  NeoPen offline path uses `double` so it's fine either way, but there is no downside to
  zero-basing and it keeps the numbers small.

So: feed `dt` straight in as the timestamp. Just keep it **monotonically non-decreasing**
with the right per-point gaps; equal consecutive values (Δ = 0) give a degenerate velocity,
but the native side guards that (`velocityIgnoreThreshold`) and it's unrelated to the choice
of time base.

## The correct approach

Mirror the demo: build the pen via `FountainShapes.createNeoPenV2(...)` and render via
`NeoPenRender(neoPen).render(canvas, paint, points)`. This runs the exact same code
path the firmware uses, so the redraw matches.

Additional requirements:
- Pass a **FILL** paint (`Paint.Style.FILL`, `strokeWidth = 0`). Fountain V2 produces
  closed/filled paths, not stroked ones.
- Feed pressures in `[0, 1]`; normalize on a **copy** of the points, never the
  original list.
- `TouchPoint` here is `com.onyx.android.sdk.data.note.TouchPoint`, which is accepted
  by `NeoPenRender.render` (the demo passes the same type) and has a copy constructor
  `new TouchPoint(p)`.

See `app/src/main/java/com/ethran/notable/editor/drawing/NeoFountainPenV2Wrapper.kt`
for the implementation.

## Why the live stroke and the offline redraw are two different renderers [framework]

Decompiling the device `framework.jar` makes the architecture explicit, and explains why an
offline redraw can ever differ from what you saw while writing:

- **Live ink is drawn by the firmware, not by `NeoPen`.** While the pen moves, the SDK
  streams points straight to SurfaceFlinger via
  `android.onyx.ViewUpdateHelper.startStroke / addStrokePoint / finishStroke(baseWidth, x, y,
  pressure, size, time)` (Binder transaction codes 16711697/8/9). Each call *returns the
  firmware's own updated stroke width* — the firmware owns the live low-latency rendering and
  its width model.
- **The offline redraw is drawn by `NeoFountainPenV2`/`NeoPenRender` in-process** onto our
  surface (this doc). It is a *re-implementation* of the same stroke, not a replay of the
  firmware's pixels.

So matching is inherently "two renderers agreeing": the only way the redraw lines up with
the live ink is to feed the in-process pen the exact same config the firmware uses (hence
`createNeoPenV2`) and the exact same point stream — and, critically, to pick the result type
that looks like a *finished* stroke (`fastMode = false`, see above) rather than the live dab
stream the firmware emits. The live `startStroke`/`addStrokePoint` path also carries a
per-point `time`, which is the firmware analogue of the `dt` we now persist (see the
timestamp section).

## Inspecting the SDK yourself

The SDK ships as `.aar` files (in `app/libs/` and the Gradle cache). To decompile:

```bash
# penbrush classes (NeoFountainPenV2, FountainShapes, NeoPenConfig) — local aar:
unzip -o app/libs/onyxsdk-penbrush-1.1.0.1.aar -d out
java -jar cfr.jar out/classes.jar --outputdir decompiled

# pen classes (NeoPenRender, NeoFountainPenWrapper) — Maven, in the Gradle cache:
J=$(find ~/.gradle/caches -path '*onyxsdk-pen-1.5.4/jars/classes.jar' | head -1)
java -jar cfr.jar "$J" --outputdir decompiled-pen
# or list signatures
javap -p com/onyx/android/sdk/pen/NeoPenConfig.class
```
