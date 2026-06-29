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
forwards it to the native input reader (`AppTouchInputReader.setEnableFingerTouch`). All
the interesting logic is one filter method in `AppTouchInputReader` that decides whether
to **ignore** an incoming `MotionEvent`:

```java
// decompiled & de-obfuscated; g = enableFingerTouch, h = onlyEnableFingerTouch
private boolean shouldSkip(MotionEvent event) {
    if (g) {                                   // finger drawing ENABLED
        return h && TouchUtils.isPenTouchType(event);
        //  h == false -> skip nothing  -> BOTH pen and finger draw
        //  h == true  -> skip the pen  -> ONLY finger draws
    }
    return !TouchUtils.isPenTouchType(event);  // finger drawing DISABLED (default):
                                               // skip everything that isn't the pen
}
```

`TouchUtils.isPenTouchType(event)` classifies the event by its `MotionEvent` tool type
(stylus/eraser vs finger). So the three reachable states are:

| State | API call | Filter result | Who can draw |
|---|---|---|---|
| **Default** | (none) — `g=false` | skip non-pen | **stylus only** |
| **Finger enabled** | `enableFingerTouch(true)` → `g=true, h=false` | skip nothing | **stylus + finger** |
| **Finger only** | `onlyEnableFingerTouch(true)` → `h=true` | skip pen | **finger only** |

So "Open finger scribble" = move from row 1 to row 2: the capacitive finger now feeds
the same raw-drawing path the stylus uses, producing identical `onRawDrawing*` callbacks
and identical ink.

### Finger has no pressure
A capacitive finger reports no real pen pressure. The SDK exposes:
- `enableFingerTouchPressure(boolean)` / `setFingerTouchPressure(float)` [sdk]

to assign a **synthetic constant pressure** to finger strokes, so pressure-sensitive pen
styles (fountain, brush) still render a sensible width when drawn with a finger.

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
