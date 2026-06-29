# Onyx pen: pressure sensitivity & line width (the official app's two sliders)

The official Onyx note app exposes a **Pen** with two settings:

- **Line width** — `0.10 mm` … `2.00 mm`
- **Pressure sensitivity** — `0% (Off)` … `100%`

This documents where those two values live in the SDK/firmware, what they actually control,
and how to reproduce them in Notable. Grounded in the decompiled `onyxsdk-pen` /
`onyxsdk-penbrush` and the device `framework.jar`.

**Source tags:** **[sdk]** decompiled `.aar`; **[framework]** decompiled `framework.jar`.

---

## 1. Which pen is it? — Fountain V2 [sdk]

The configurable-pressure "Pen" is the **Fountain V2** pen. `NeoPenConfig` enumerates the
native pen types, and Fountain V2 is the one fed by `FountainShapes.createNeoPenV2(...)`
(see `docs/onyx-neo-fountain-pen-v2.md`):

```
NEOPEN_PEN_TYPE_BRUSH        = 1
NEOPEN_PEN_TYPE_FOUNTAIN     = 2
NEOPEN_PEN_TYPE_MARKER       = 3
NEOPEN_PEN_TYPE_CHARCOAL     = 4
NEOPEN_PEN_TYPE_CHARCOAL_V2  = 5
NEOPEN_PEN_TYPE_FOUNTAIN_V2  = 6   // <-- "Pen" with line width + pressure sensitivity
NEOPEN_PEN_TYPE_PENCIL       = 7
NEOPEN_PEN_TYPE_BALLPOINT    = 8
NEOPEN_PEN_TYPE_SQUARE       = 9
NEOPEN_PEN_TYPE_BRUSH_SIGN   = 10
```

It is Fountain V2 (not V1) because **only the V2 path carries a `pressureSensitivity`
parameter** that a UI slider can drive; V1 has no such knob. A constant-width pen (ballpoint)
ignores pressure entirely, so a 0–100% sensitivity slider only makes sense on a
pressure-modulated pen like fountain.

---

## 2. The full pen config and its defaults [sdk]

`com.onyx.android.sdk.pen.NeoPenConfig` (penbrush) — every field the native renderer reads,
with its default:

| Field | Default | Meaning |
|---|---|---|
| `type` | 1 | pen type (6 = Fountain V2) |
| `width` | 3.0 | **base stroke width, in pixels** (this is the "line width") |
| `minWidth` | 0.001 | floor width (Fountain V2 uses `MIN_FOUNTAIN_PEN_WIDTH = 1.0`) |
| `maxTouchPressure` | 1.0 | pressure normaliser; points must be pre-divided by this |
| `pressureSensitivity` | **0.3** | **how strongly pressure modulates width (the slider)** |
| `velocitySensitivity` | 0.5 | how strongly speed modulates width (not exposed in UI) |
| `smoothLevel` | 0.6 | curve smoothing |
| `dpi` | 320.0 | pixels-per-inch used for any physical sizing |
| `tiltScale` | 3.0 | tilt → width factor (`tiltEnabled = false` for fountain) |
| `velocityAmplifier`, `velocityIgnoreThreshold`, `velocityLowerBound`, `velocityUpperBound`, `startPointLimit`, `startLengthLimit`, `endVelocitySensitivity` | 0.0 | fine velocity-shaping knobs |
| `scalePrecision`, `displayScaleX/Y` | 1.0 | render scaling |

All of these are copied into the native `PenConfig` in `NeoPenConfig.toNativeConfig()`
(verified): `it.setPressureSensitivity(this.pressureSensitivity)`,
`it.setVelocitySensitivity(...)`, `it.setWidth(...)`, etc. The actual width-from-pressure
math runs in `libpennative` (JNI, not inspectable), but the inputs are exactly these.

---

## 3. Pressure sensitivity (the 0%–100% slider) [sdk]

- It is **`NeoPenConfig.pressureSensitivity`**, a float in `[0, 1]`.
- Semantics: it scales how much the per-point pressure pushes the stroke width away from the
  base `width`. **`0.0` = "Off"** (pressure ignored → constant-width line); higher = pressure
  has more effect on width. The app's **`0% … 100%` maps to `0.0 … 1.0`** (the slider value
  divided by 100).
- **Two different defaults — mind the gap:**
  - `NeoPenConfig.pressureSensitivity` (the raw struct field) defaults to **0.3**. This is
    what `FountainShapes.createNeoPenV2(..., pressureSensitivity = null, ...)` falls back to,
    so Notable's current strokes (which pass `null`) run at **0.3**.
  - **`PenConstant.DEFAULT_PRESSURE_SENSITIVITY = 0.375`** is the SDK/official-app default
    (≈37.5% on the slider). There is also an explicit feature flag
    **`PenConstant.ENABLE_CONFIG_PEN_PRESSURE_SENSITIVITY = true`** — i.e. the official app
    treats pressure sensitivity as a user-configurable setting, defaulting to 0.375.
  - So if you want to match the official app exactly, default the slider to **0.375**, not
    0.3. [sdk]
- Sibling knobs (same source) for completeness: `PenConstant.DEFAULT_VELOCITY_SENSITIVITY =
  1.0` (the `NeoPenConfig` field default is 0.5) and `PenConstant.DEFAULT_SMOOTH_LEVEL = 0.2`
  (the field/`createNeoPenV2` default is 0.6). The app-level `PenConstant` defaults and the
  raw struct field defaults genuinely differ — the app overrides the struct.

> ### Pressure must be normalised first
> The native pen expects per-point pressure in **`[0, 1]`**. Raw `TouchPoint.pressure` from
> the digitizer is `1 … 4096` (`EpdController.getMaxTouchPressure()` ≈ 4096). The pen
> wrappers divide each point's pressure by `maxTouchPressure` before rendering. If you set
> `pressureSensitivity > 0` but feed un-normalised pressure, every point saturates and the
> width looks constant/maxed. (Notable already normalises on a copy — see
> `NeoFountainPenV2Wrapper.copyAndNormalizePressure`.)

---

## 4. Line width (the 0.10 mm – 2.00 mm slider) [sdk]

The `0.10 … 2.00 mm` range is **not a guess — it's hard-coded in `PenConstant`**:

| Constant | Value | Meaning |
|---|---|---|
| `MIN_NORMAL_STROKE_WIDTH` | **0.1** | min line width (mm) — the slider floor |
| `MAX_NORMAL_STROKE_WIDTH` | **2.0** | max line width (mm) — the slider ceiling |
| `DEFAULT_STROKE_WIDTH_MM` | 0.5 | default "Pen" width (mm) |
| `NORMAL_STROKE_WIDTH_GAP` | 0.05 | slider step (mm) |
| `MIN_/MAX_MARKER_STROKE_WIDTH` | 0.5 / 8.0 | the *marker* pen's separate mm range |
| `DEFAULT_DPI` | 320.0 | nominal density used for mm↔px |

So the official "Pen" slider is literally `MIN_NORMAL_STROKE_WIDTH … MAX_NORMAL_STROKE_WIDTH`
(0.10–2.00 mm) in 0.05 mm steps, default 0.5 mm.

- The render value is **`NeoPenConfig.width`, in pixels**, converted from mm with the density:

  ```
  widthPx = widthMm / 25.4 * dpi          // dpi = PenConstant.DEFAULT_DPI = 320 (nominal)
  ```

  At the nominal 320 dpi: `0.10 mm ≈ 1.26 px`, `0.50 mm ≈ 6.3 px`, `2.00 mm ≈ 25.2 px`.
- Caveat: `PenConstant` also defines `DEFAULT_STROKE_WIDTH = 7.2f` (px) paired with
  `DEFAULT_STROKE_WIDTH_MM = 0.5f`, which implies ≈14.4 px/mm (≈366 dpi), **not** 320. So the
  *actual* device conversion is denser than the nominal `DEFAULT_DPI`; for an exact match use
  the device's real ppi (or reproduce the 7.2 px ⇄ 0.5 mm ratio) rather than 320. Other
  per-pen px defaults: `BRUSH 8.4`, `MARKER 48.0`, `CHARCOAL 3.6`, `PENCIL 6.0`,
  with `MAX_RENDER_STROKE_WIDTH = 80.0`.

- Note `FountainShapes.createNeoPenV2` adds a compensation term: the native pen actually gets
  `config.width = width + 3.0 / createScale` and `config.minWidth = minWidth / createScale`.
  So the value you pass as `width` is the **nominal** nib; the firmware draws it slightly
  thicker by design (the +3 px `FOUNTAIN_PEN_V1_COMPENSATION`). Account for this if you want a
  mm value to land exactly.

---

## 5. The firmware (live) side of the same two values [framework]

When the official app draws live, it does **not** use `NeoPenConfig`; it pushes the style to
SurfaceFlinger through the framework (see `docs/investigation.md`). The relevant carrier is
`android.onyx.optimization.data.v2.EACStrokeStyle`:

```java
int   strokeStyle;                 // pen/eraser style id
float strokeWidth = 3.0f;          // base width (px)  <-- the line-width slider
int   strokeColor = 0xFF000000;
List<Float> strokeExtraArgs;       // generic float[] of extra brush params
```

`BaseHandler.applyStrokeParam()` forwards these to
`ViewUpdateHelper.setStrokeWidth(strokeWidth)` and
`ViewUpdateHelper.setStrokeParameters(strokeStyle, strokeExtraArgs)` (Binder codes 16711687
and 1049089). So **line width is `strokeWidth`**, and the **pressure-sensitivity value is
carried inside `strokeExtraArgs`** (the firmware's per-style parameter array — the same
mechanism Notable already uses to pass the dash pattern for the lasso eraser). The exact slot
layout of `strokeExtraArgs` for fountain is decided in the native EPD layer and is not
exposed in Java.

The takeaway: **live (firmware) and offline (NeoPen) are two renderers** that each need the
same two numbers — `strokeWidth`/`width` and the pressure sensitivity — supplied through their
own channel.

---

## 6. How to expose these in Notable

Notable renders fountain offline via `NeoFountainPenV2Wrapper` →
`FountainShapes.createNeoPenV2(width, minWidth, …, pressureSensitivity, fastMode, smoothLevel)`.
Today it passes `pressureSensitivity = null` (→ 0.3) and a pixel `strokeWidth`.

To mirror the official app's two sliders:

- **Line width (mm):** convert to px with the device ppi and pass as `width`
  (`widthPx = mm / 25.4 * ppi`). Remember the `+3 px` fountain compensation.
- **Pressure sensitivity (0–100%):** pass `pressureSensitivity = percent / 100f` instead of
  `null`. `0f` reproduces the app's **"Off"** (constant-width line); `1f` = full response;
  `0.3f` is the SDK default.
- Keep feeding **normalised pressure** (`pressure / maxTouchPressure`) or the slider will look
  like it does nothing.

`velocitySensitivity` (0.5) and the velocity-bound knobs stay at their defaults — the official
app doesn't expose them, and they shape the fast-stroke taper that makes a fountain pen feel
right.

---

## 7. Related

- `docs/onyx-neo-fountain-pen-v2.md` — the Fountain V2 render pipeline and `createNeoPenV2`.
- `docs/investigation.md` — framework `EACStrokeStyle` / `ViewUpdateHelper` stroke params.
