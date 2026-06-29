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

- `onyxsdk-pen` (1.5.4) — contains `NeoPenRender`, `NeoFountainPenWrapper`.
- `onyxsdk-penbrush` (1.1.1) — contains `NeoFountainPenV2`, `NeoPenConfig`,
  `PenPathResult`, `PenResult`, `FountainShapes`, `NeoPen`.

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
   - `fastMode` — demo uses **true**; the default `false` yields a different result
     type/geometry.

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

## Inspecting the SDK yourself

The SDK ships as `.aar` files (in `app/libs/` and the Gradle cache). To decompile:

```bash
# extract classes.jar from the aar
unzip -o onyxsdk-penbrush-1.1.1.aar -d out
# decompile with CFR
java -jar cfr.jar out/classes.jar \
  --jarfilter 'com.onyx.android.sdk.pen.utils.FountainShapes' \
  --outputdir decompiled
# or list signatures
javap -p com/onyx/android/sdk/pen/NeoPenConfig.class
```
