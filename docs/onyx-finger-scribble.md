# Onyx "Open Finger Scribble" — drawing with a finger

This documents how the Onyx demo's **"Open finger scribble"** toggle works, what it
does at the SDK level, and the two *different* finger/touch mechanisms that are easy to
confuse. Grounded in the official `OnyxAndroidDemo`
(`ScribbleFingerTouchDemoActivity`) and the decompiled `onyxsdk-pen` SDK.

**Source authority** (same tags as the other docs):
- **[demo]** — verified against `OnyxAndroidDemo` source.
- **[sdk]** — verified by decompiling the Onyx `.aar`s.

---

## 1. What "Open finger scribble" is

By default, Onyx raw-drawing mode (`TouchHelper` + `setRawDrawingEnabled(true)`) only
reacts to the **stylus**. Capacitive **finger** touches are ignored for drawing — they
fall through to the normal Android view system (scroll, buttons, etc.). This is what you
want 99% of the time: you rest your palm on the screen while writing and it doesn't
leave ink.

**"Open finger scribble"** is the checkbox (`cb_enable_finger`, label literally
"Open finger scribble") that flips this: it makes the **finger also produce raw-drawing
strokes**, so you can scribble with a fingertip, not just the pen. [demo]

```java
// ScribbleFingerTouchDemoActivity.enableFingerTouch(...)
public void enableFingerTouch(View view, boolean checked) {
    if (touchHelper == null) return;
    touchHelper.setRawDrawingEnabled(false);
    touchHelper.setRawDrawingEnabled(true);   // re-arm raw drawing around the change
    touchHelper.enableFingerTouch(checked);   // <-- the actual feature
}
```

The `setRawDrawingEnabled(false)`/`(true)` bracket is just to re-arm the raw-drawing
pipeline cleanly so the flag change takes effect for the next stroke.

---

## 2. How it works inside the SDK [sdk]

`TouchHelper.enableFingerTouch(enable)` fans the flag out to every `TouchRender`, which
forwards it to the native input reader (`AppTouchInputReader`). What is **verified** from
the decompiled `AppTouchInputReader`:

- It holds two boolean fields: one set by `setEnableFingerTouch(boolean)` (call it
  `enableFinger`) and one set by `setOnlyEnableFingerTouch(boolean)` (`onlyFinger`). [sdk]
- A single private filter method, keyed on `TouchUtils.isPenTouchType(event)` (which
  classifies the `MotionEvent` tool type as stylus/eraser vs finger), decides per event
  whether to feed it into the raw-drawing pipeline. The down/move/up handlers all gate on
  it (`if (filter(event)) { dispatch… }`). [sdk]
- Both `enableFingerTouchPressure(boolean)` and `setFingerTouchPressure(float)` exist for
  giving the (pressure-less) finger a synthetic pressure. [sdk]

> ⚠️ The exact boolean body of that filter is **not** quoted here: CFR decompiles this
> particular method into mangled code (stray `void var1_1`, inverted assignments), so the
> precise skip/keep polarity can't be trusted from the bytecode. The **observable
> behaviour** below is the reliable contract (it matches Onyx's documented semantics), so
> this doc states behaviour, not a reconstructed expression.

The three reachable states (by behaviour):

| State | API call | Who can draw |
|---|---|---|
| **Default** | (none) | **stylus only** |
| **Finger enabled** | `enableFingerTouch(true)` | **stylus + finger** |
| **Finger only** | `onlyEnableFingerTouch(true)` | **finger only** |

So "Open finger scribble" = move from row 1 to row 2: the capacitive finger now feeds
the same raw-drawing path the stylus uses, producing identical `onRawDrawing*` callbacks
and identical ink.

### Finger has no pressure
A capacitive finger reports no real pen pressure. The SDK exposes:
- `enableFingerTouchPressure(boolean)` / `setFingerTouchPressure(float)` [sdk]

to assign a **synthetic constant pressure** to finger strokes, so pressure-sensitive pen
styles (fountain, brush) still render a sensible width when drawn with a finger. The SDK's
default for this is **`PenConstant.DEFAULT_FINGER_TOUCH_PRESSURE = 1500.0f`** [sdk] (on a
~4096 max-pressure scale, i.e. roughly mid-range) — and `AppTouchInputReader` applies it in
its `setPressure(this.j)` path only when the finger-pressure flag (`this.i`) is enabled,
confirming it is an opt-in override of the real (zero) finger pressure. [sdk]

---

## 3. The OTHER finger mechanism — palm/finger rejection (don't confuse these)

The same demo *also* uses a completely separate, lower-level touch API, and the two are
easy to mix up:

```java
// ScribbleFingerTouchDemoActivity callback
onBeginRawDrawing(...) -> TouchUtils.disableFingerTouch(getApplicationContext());
onEndRawDrawing(...)   -> TouchUtils.enableFingerTouch(getApplicationContext());
```

`TouchUtils` here is a demo helper that calls the **EPD controller**, not `TouchHelper`:

```java
// disable: block capacitive touch over a screen region (the whole screen here)
EpdController.setAppCTPDisableRegion(context, new Rect[]{ fullScreenRect });
// enable: clear the block
EpdController.appResetCTPDisableRegion(context);
```

CTP = **C**apacitive **T**ouch **P**anel. This is **palm rejection at the hardware
panel level**: while a pen stroke is in progress, it tells the panel to drop *all*
finger/palm touches in the region so your resting hand can't generate spurious input or
fight the stylus. It is re-enabled the instant the stroke ends.

### The two are different layers, used together

| | `TouchHelper.enableFingerTouch` | `EpdController.setAppCTPDisableRegion` |
|---|---|---|
| Layer | raw-drawing input reader (`TouchHelper`) | EPD / capacitive panel driver |
| Question it answers | "Should a finger event become **ink**?" | "Should the panel deliver finger touches **at all** right now?" |
| Scope | the drawing surface | a screen region (here, full screen) |
| Lifetime | a persistent mode (the checkbox) | toggled per-stroke (begin/end raw drawing) |
| Purpose | let the user *draw* with a finger | *reject the palm* during a pen stroke |

They cooperate: even with finger-scribble **on**, the demo still disables the CTP region
during an active *pen* stroke (`onBeginRawDrawing`), so a palm landing mid-pen-stroke
doesn't inject a competing finger stroke; finger input resumes at `onEndRawDrawing`.

---

## 3c. A THIRD finger layer — the system handwriting handler [framework]

Decompiling the device `framework.jar` reveals a third, system-level finger mechanism that
sits *above* both of the above (it runs in the framework, not in the app):

`android.onyx.optimization.screennote.handler.BaseHandler` watches every note "draw view".
On a **finger down** it does:

```java
private void onFingerDownImpl(View view, EACNoteConfig cfg) {
    pauseEACScreenNote();            // setScreenHandWritingPenState(PEN_PAUSE = 3)
    ViewUpdateHelper.repaintEverything();
}
```

i.e. the firmware **pauses hardware handwriting and forces a clean full repaint the moment
a finger lands**, independent of anything the app does. The stylus down path resumes it
(`PEN_DRAWING = 2`). This is why, on stock Onyx behaviour, touching with a finger cleans up
the fast-mode ghosting — and why an app that wants finger-*drawing* has to opt in explicitly
(`enableFingerTouch`), since the default system reflex to a finger is "stop drawing, repaint".

## 3d. The raw input reader (`libonyx_touch_reader`) [framework]

`android.onyx.inputreader.RawInputReader` is the framework JNI bridge under the SDK's
`TouchHelper` (loads `libonyx_touch_reader.so`). Useful facts:

- The raw callback is `onTouchPointReceived(x, y, pressure, erasing, …, action, time)` with
  action codes: `PEN_DOWN=0, PEN_MOVE=1, PEN_RELEASE=2, PEN_RELEASE_OUT_LIMIT_REGION=3,
  PEN_INVALID=4, PEN_ACTIVE=5` (5 = hover). Note **`PEN_RELEASE_OUT_LIMIT_REGION`**: the
  driver distinguishes a normal pen-up from one that happens outside the limit rect.
- **The "erasing" flag is decided in hardware/driver**, not the app: `this.erasing = z`
  comes straight off the touch report. So whether a contact is an erase (eraser tip / side
  button) is determined below the framework — the app only reacts to it.
- Points are buffered in a `TouchPointList(600)` (initial capacity 600).
- `setLimitRect` / `setExcludeRect` each drive **two** layers at once: the native input mask
  (`nativeSetLimitRegion` / `nativeSetExcludeRegion`) **and** the firmware handwriting region
  (`ViewUpdateHelper.setScreenHandWritingRegionLimit/Exclude`). So the limit/exclude rects
  Notable passes in `setupSurface` clip both *input* and *firmware rendering*.
- Region mode: `nativeSetRegionMode(0)` = multi-region, `(1)` = single-region (each also
  mirrored to `ViewUpdateHelper.setScreenHandWritingRegionMode`).
- `nativeSetPenState(4)` (`PEN_INVALID`) is asserted on `pause()` / `resume()` / `quit()`.

See `docs/investigation.md` for the full framework trace.

## 4. The `create(view, stylus, callback)` overload [sdk]

The finger demo creates its helper with the 3-arg overload:

```java
touchHelper = TouchHelper.create(getHostView(), false, callback);
```

The boolean maps to an internal *feature* code: `stylus ? 2 : 1`
(`create(view, boolean, cb)` → `create(view, stylus?2:1, cb)`). Passing `false` selects
feature `1`. This is the registration-time feature flag; the runtime per-stroke
finger/pen behaviour is still governed by `enableFingerTouch` / `onlyEnableFingerTouch`
as described above.

---

## 5. Practical notes for Notable

- If Notable ever wants a "draw with finger" option, the lever is
  `touchHelper.enableFingerTouch(true)` (optionally `onlyEnableFingerTouch` for a
  finger-only mode), plus `enableFingerTouchPressure`/`setFingerTouchPressure` so
  pressure pens look right. Re-arm raw drawing around the change as the demo does.
- Keep finger-scribble and palm-rejection conceptually separate. Even with finger
  drawing enabled, you almost always still want to disable the CTP region during a pen
  stroke (begin/end raw drawing) for palm rejection — otherwise resting your hand while
  using the pen will draw with your palm.
- Default behaviour (no calls) is already "stylus only," which is the right default for a
  note app; finger-scribble is an opt-in.
