# Native eraser indicator (pen side-button erasing)

How to make the **pen side-button eraser** render a visible stroke ("erasing
indicator") using the firmware's **native** rendering, instead of Notable's OpenGL
front-buffer workaround. Grounded in the decompiled `onyxsdk-pen` / `onyxsdk-device`
SDK and Notable's own code.

**Source authority:** **[sdk]** = decompiled Onyx `.aar`; **[notable]** = Notable code.

---

## 1. The problem

Notable supports two ways to erase:

- **Hand erase** — the user picks the eraser tool in the toolbar (`Mode.Erase`). The
  pen *tip* is then interpreted as an eraser. This goes through the normal
  raw-**drawing** path (`onRawDrawingTouchPointListReceived` → `onRawDrawingList`, branch
  `Mode.Erase`), and the firmware draws a normal stroke as feedback (Notable configures a
  grey `MARKER` for this in `updatePenAndStroke`). So hand-erase already has a native
  indicator. [notable]
- **Pen side-button erase** — the user holds the stylus button. The firmware fires the
  raw-**erasing** callbacks (`onBeginRawErasing` → `onRawErasingTouchPointListReceived`
  → `onEndRawErasing`). **By default the firmware draws nothing during button erasing.**

To give button-erase a visible indicator, Notable previously used an **OpenGL
front-buffer renderer** (`OpenGLRenderer` / `GLFrontBufferedRenderer`): while
`isErasing`, `DrawCanvas.dispatchTouchEvent` routed stylus events into the GL renderer,
which painted the indicator itself. This is the "nasty workaround that does not use
native rendering." It works, but it duplicates rendering the firmware can do natively.

The official (closed-source) Onyx note app instead makes the side-button produce the
same kind of native stroke as a normal pen action — a clean, low-latency erasing
indicator drawn by the firmware.

---

## 2. The native API [sdk]

`com.onyx.android.sdk.pen.TouchHelper`:

```java
public TouchHelper setEraserRawDrawingEnabled(boolean drawing, int eraserStyle)
```

- `drawing` — when `true`, the firmware **renders the eraser path natively** while you
  erase with the side button (just like a normal stroke). When `false`, button-erasing
  draws nothing (the default).
- `eraserStyle` — which stroke style to draw the eraser path with; uses the
  `TouchHelper.STROKE_STYLE_*` constants:

  | Constant | Value |
  |---|---|
  | `STROKE_STYLE_PENCIL` | 0 |
  | `STROKE_STYLE_FOUNTAIN` | 1 |
  | `STROKE_STYLE_MARKER` | 2 |
  | `STROKE_STYLE_NEO_BRUSH` | 3 |
  | `STROKE_STYLE_CHARCOAL` | 4 |
  | `STROKE_STYLE_DASH` | 5 |
  | `STROKE_STYLE_CHARCOAL_V2` | 6 |
  | `STROKE_STYLE_SQUARE_PEN` | 7 |

`TouchHelper` fans the call out to each `TouchRender`, which forwards it to the device
layer (`Device.setEraserRawDrawingEnabled`), which reflects into the system EPD
controller. The SDK's own `resetPenDefaultRawDrawing()` calls
`Device.currentDevice().setEraserRawDrawingEnabled(false, 5)` — confirming the default is
**disabled, DASH style**. [sdk]

### THE BIG GOTCHA: `setRawDrawingEnabled(true)` resets it [sdk]

The hardest bug to spot. Decompiling `TouchHelper` shows:

```java
public TouchHelper setRawDrawingEnabled(boolean enabled) {
    ...
    setRawDrawingRenderEnabled(enabled);
    setRawInputReaderEnable(enabled);
    resetPenDefaultRawDrawing();          // <-- this
    return this;
}

public void resetPenDefaultRawDrawing() {
    Device.currentDevice().setBrushRawDrawingEnabled(true);
    Device.currentDevice().setEraserRawDrawingEnabled(false, 5);   // <-- DISABLES it
}
```

So **every** `setRawDrawingEnabled(true)` call silently turns the native eraser channel
back **off** (style 5 = DASH). This bit Notable twice:

1. `setupSurface` originally enabled the eraser *before* its final
   `setRawDrawingEnabled(true)` → instantly reset to disabled.
2. `updateIsDrawing()` calls `setRawDrawingEnabled(true)` on every drawing resume → wipes
   it again even if (1) were fixed.

**Fix (two parts):**
- In `setupSurface`, call `setEraserRawDrawingEnabled(true, …)` **after**
  `setRawDrawingEnabled(true)`.
- **Re-assert** it in `onBeginRawErasing` (via the shared `enableNativeEraser(touchHelper)`
  helper) so it survives later `updateIsDrawing()` resets. This is the call that actually
  makes button-erase render on a running session.

### The eraser channel uses the helper's stroke color/width [sdk]

Second gotcha: even once enabled, `setEraserRawDrawingEnabled(true, …)` renders
**nothing visible** unless a colour/width is set — the native eraser channel draws using
the `TouchHelper`'s **current `setStrokeColor` / `setStrokeWidth`** state (the
`eraserStyle` arg only selects the *style*, not the colour). If those aren't set to
something visible when erasing begins, you get a blank screen.

The fix mirrors Notable's already-working **hand-erase** recipe (which renders a grey
`MARKER` at width 30): configure a visible stroke in `onBeginRawErasing`, and restore the
pen's settings in `onEndRawErasing`. Per request, the indicator colour is **black**:

```kotlin
// onBeginRawErasing
touchHelper!!.setStrokeStyle(penToStroke(Pen.MARKER))
    ?.setStrokeWidth(30f)
    ?.setStrokeColor(Color.BLACK)
// onEndRawErasing
updatePenAndStroke()   // restore pen style/width/color
```

The indicator is **transient**: after pen-up, `onRawErasingList()` repaints the affected
region from the page bitmap (which contains no indicator), so the black track disappears
as soon as the erase is committed — it only exists as live feedback during the gesture.

> Note: the official `OnyxAndroidDemo` never calls `setEraserRawDrawingEnabled`; its
> erase "track" is app-drawn (`EraseRenderer.drawEraseCircle`). So this native path is
> firmware-dependent — hence the try/catch and the preserved OpenGL fallback. [demo]

> **[framework] Root cause confirmed in `framework.jar`.** The system handwriting handler
> `BaseHandler.applyStrokeParam()` pushes stroke colour/style/width to SurfaceFlinger
> **only for non-eraser strokes** — it early-returns when
> `strokeStyle == 5` (`isEarsingStroke`). Style `5` is the eraser (the same value the SDK's
> `resetPenDefaultRawDrawing()` passes as `setEraserRawDrawingEnabled(false, 5)`). So by
> default the firmware applies *no paint* while erasing, which is exactly why button-erase
> drew nothing until the dedicated native eraser channel was enabled. `setStrokeStyle` /
> `setEraserRawDrawingEnabled` themselves are `ViewUpdateHelper` Binder transactions to
> SurfaceFlinger (codes 16711688 and 1048833). See `docs/investigation.md`.

---

## 3. What was changed in Notable

The native path is now enabled, and the OpenGL workaround is **commented out (not
deleted)** so it remains as a reference implementation of non-native erase rendering.

### 3.1 Enable native rendering — `editor/utils/einkHelper.kt` (`setupSurface`)

After (not before — see "THE BIG GOTCHA") the final `setRawDrawingEnabled(true)`:

```kotlin
touchHelper.setRawDrawingEnabled(true)
enableNativeEraser(touchHelper)   // shared helper, see below
```

```kotlin
fun enableNativeEraser(touchHelper: TouchHelper?) {
    if (touchHelper == null) return
    try {
        touchHelper.setEraserRawDrawingEnabled(true, TouchHelper.STROKE_STYLE_MARKER)
    } catch (t: Throwable) {
        log.w("setEraserRawDrawingEnabled not supported on this device: ${t.message}")
    }
}
```

Wrapped in try/catch because the Onyx SDK is unstable across devices/firmware (same
reasoning as `tryToSetRefreshMode`). The same `enableNativeEraser` helper is also called
from `onBeginRawErasing` to re-assert the flag after `updateIsDrawing()` resets it.

### 3.2 Disable the OpenGL workaround (kept commented)

- `editor/canvas/OnyxInputHandler.kt` — in `onBeginRawErasing`, the indicator is
  configured by the shared `applyEraserIndicatorStyle()` helper so it **matches the active
  eraser type**: `Eraser.PEN` → black `MARKER` width 30; `Eraser.SELECT` (lasso) → dashed
  `BLACK` line (`Pen.DASHED`, width 3, via `Device.setStrokeParameters`). The same helper
  styles the hand eraser in `updatePenAndStroke` (Mode.Erase), the only difference being
  the pen-eraser colour (grey for hand-erase, black for the button indicator). This sets a
  visible stroke so the native eraser channel actually renders (see "The eraser channel
  uses the helper's stroke color/width" above); `onEndRawErasing` restores the pen via
  `updatePenAndStroke()`. The
  `GlobalAppSettings.current.openGLRendering` blocks and `glRenderer` calls are commented
  out. `isErasing` is still set so the rest of the erase logic is unchanged.
- `editor/canvas/DrawCanvas.kt` — in `dispatchTouchEvent`, the routing condition changed
  from `if (!DeviceCompat.isOnyxDevice || inputHandler.isErasing)` to
  `if (!DeviceCompat.isOnyxDevice)`. **Non-Onyx devices still use OpenGL as their only
  renderer**; only the Onyx erase-routing into OpenGL was removed. Original line kept
  commented above.

Each edit is tagged with a `// NATIVE ERASER INDICATOR:` comment pointing back here.

---

## 4. Tuning / reverting

- **Change the indicator look:** swap the `eraserStyle` argument (e.g. `STROKE_STYLE_DASH`
  for a dashed track, `STROKE_STYLE_PENCIL` for a thin line). Stroke width/colour follow
  the helper's current `setStrokeWidth` / `setStrokeColor`.
- **Revert to the OpenGL workaround:** uncomment the blocks in `onBeginRawErasing`,
  `onEndRawErasing`, and the original condition in `DrawCanvas.dispatchTouchEvent`, and
  set `setEraserRawDrawingEnabled(false, …)` (or remove the call).
- **Keep both as a setting:** the cleanest long-term option is to branch on
  `GlobalAppSettings.current.openGLRendering` — native when off, OpenGL demo when on —
  so the reference path stays exercised.

---

## 5. Related

- `docs/onyx-pen-up-refresh-and-screen-freeze.md` — raw-drawing/freeze model and the
  `setRawDrawingRenderEnabled` toggle the erase path also uses.
- `docs/onyx-finger-scribble.md` — another `TouchHelper` input-routing feature.
