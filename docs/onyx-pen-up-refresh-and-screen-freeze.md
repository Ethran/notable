# Onyx "Pen Up Refresh", Screen Freeze, and Refresh-Timing Quirks

This document explains the Onyx "pen up refresh" feature, the screen-freeze /
raw-drawing model it lives inside, recommended timings, the special considerations
when erasing, and a catalogue of the weird timing quirks discovered in the SDK and in
Notable's own code.

**Source authority.** Facts are tagged so guess-work isn't mistaken for ground truth:
- **[demo]** — verified against the official `OnyxAndroidDemo` source
  (`ScribblePenUpRefreshDemoActivity`, `ScribbleMoveEraserDemoActivity`,
  `RefreshScreenAction`, `ResumeRawDrawingRequest`, `PartialRefreshRequest`).
- **[sdk]** — verified by decompiling the Onyx `.aar`s (constants, signatures).
- **[notable]** — taken from Notable's own code, which is **reverse-engineered
  guess-work** and may be wrong. Where Notable disagrees with the demo/SDK, the
  demo/SDK wins.

Note: the official prose docs in `OnyxAndroidDemo/doc/*.md` do **not** mention pen-up
refresh at all — that feature lives only in the demo *source* and SDK constants.

---

## 1. The drawing model (why any of this exists)

On an Onyx e-ink device, handwriting uses a two-layer trick:

1. **Live firmware layer.** While the pen moves, the firmware draws the stroke
   directly onto the EPD at very low latency. The host app's `SurfaceView` is
   effectively "frozen" — the firmware is painting over it. This is "raw drawing
   mode" (`TouchHelper.setRawDrawingEnabled(true)`).
2. **Host bitmap layer.** When a stroke completes, the app receives the points
   (`onRawDrawingTouchPointListReceived`) and must redraw that stroke onto its **own**
   bitmap/surface (see `onyx-neo-fountain-pen-v2.md` for how to make that redraw match
   the firmware). Then it "unfreezes" so the host surface becomes authoritative again.

The frozen firmware ink and the host's bitmap must agree. The whole class of bugs in
this document comes from the two layers getting **out of sync in time**: the host
unfreezes/refreshes before its bitmap has been updated, or the firmware's internal
buffer hasn't caught up to what the host just drew.

Key terms in Notable:
- `resetScreenFreeze()` — toggles `touchHelper.isRawDrawingRenderEnabled` false→true.
  This is the "unfreeze": it tells the firmware to stop owning the screen so the host
  surface shows through.
- `drawCanvasToView()` — blits the host bitmap onto the `SurfaceView`.
- `refreshUi()` — does `drawCanvasToView()` then `resetScreenFreeze()`.

---

## 2. What "Pen Up Refresh" is

When `setRawDrawingEnabled(true)`, the firmware owns the screen and paints fast,
low-quality ink (A2-ish waveform) for minimal latency. That fast ink **ghosts** — it
leaves residue and looks rough. "Pen up refresh" is the firmware's built-in cleanup:
a short time **after the pen lifts**, the firmware fires a partial high-quality refresh
of the rectangle that was just drawn, replacing the rough low-latency ink with clean
ink.

### Why this feature exists (the purpose)

It exists to resolve a hard, unavoidable trade-off specific to e-ink. An EPD can update
a region either **fast or well, never both**:

- **While the pen is moving, latency is everything.** If ink doesn't appear under the
  nib within ~10–20 ms it feels broken. The only way to hit that is a *fast waveform*
  (A2/DU-class): 1-bit black/white, no grayscale, no anti-aliasing, and it **leaves
  ghosting/residue** because it doesn't fully settle the e-ink particles.
- **A clean stroke needs a slow waveform.** Grayscale edges, anti-aliasing and
  ghost-free pixels require a GC/REGAL-class update that takes ~150–300 ms — far too slow
  to run *while* writing.

So the device deliberately writes ugly-but-instant ink live, and then needs a *second
pass* to upgrade that region to clean ink once it's allowed to be slow. **Pen up refresh
is that automatic second pass.** Its whole reason to exist is to answer two questions the
app would otherwise have to answer by hand:

1. **"When is it safe to do the slow, pretty refresh?"** — i.e. when has the user
   actually paused? Doing the slow refresh mid-stroke would stutter and fight the live
   ink. The firmware answers this with the **pen-up timer**: only refresh after the pen
   has been lifted for *N* ms (so a burst of quick strokes is coalesced into one cleanup,
   not one flicker per stroke).
2. **"Which area needs cleaning?"** — it hands you the dirty `RectF` so only the written
   region is refreshed, not the whole screen.

Put differently: **the purpose is to get low-latency writing AND clean final ink without
the app having to detect end-of-writing, debounce it, track the dirty region, and
schedule a high-quality partial update itself.** It's the firmware automating the
"settle the ghosting once the user stops" step that every serious e-ink note app needs.

Why it's *optional* (a switch): an app may already do its own end-of-stroke refresh
(this is exactly Notable's case — see §2 "Notable status"), in which case the built-in
pass would be redundant or even conflict with the app's own refresh timing. So the SDK
lets you turn it off and take over, or turn it on and let the firmware handle it.

> **[framework] Confirmed in `framework.jar`.** The system note handler
> (`android.onyx.optimization.screennote.handler.BaseHandler`) implements exactly this: on
> stylus-up it schedules, after `EACNoteConfig.getRepaintLatency()` ms, a
> `ViewUpdateHelper.handwritingRepaint(view, l,t,r,b, false)` and sets the pen state to
> `PEN_PAUSE`. `repaintLatency` **defaults to 500 ms**, matching the SDK's
> `PenConstant.DEFAULT_PEN_UP_REFRESH_TIME_MS` below. The pen state is a firmware-tracked
> machine: `PEN_STOP=0, PEN_START=1, PEN_DRAWING=2, PEN_PAUSE=3, PEN_ERASING=4`. See
> `docs/investigation.md` for the full framework trace.

### API

On `TouchHelper`:
- `setPenUpRefreshEnabled(boolean)` — turn the feature on/off.
- `setPenUpRefreshTimeMs(int)` — how long after pen-up before the refresh fires.

Callback on `RawInputCallback`:
- `onPenUpRefresh(RectF refreshRect)` — fired when the timer elapses. The app is
  expected to repaint `refreshRect` from its own bitmap in a high-quality update mode.

### The demo's handler (reference implementation)

In `ScribblePenUpRefreshDemoActivity`:

```java
@Override
public void onPenUpRefresh(RectF refreshRect) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
    getRxManager().enqueue(
        new PartialRefreshRequest(this, surfaceview1, refreshRect).setBitmap(bitmap),
        ...);
}
```

`PartialRefreshRequest` does the canonical partial-refresh dance:

```java
EpdController.setViewDefaultUpdateMode(surfaceView, UpdateMode.HAND_WRITING_REPAINT_MODE);
Canvas canvas = surfaceView.getHolder().lockCanvas(renderRect);
canvas.clipRect(renderRect);
renderBackground(canvas, viewRect);
canvas.drawBitmap(bitmap, rect, rect, null);   // host bitmap -> surface
surfaceView.getHolder().unlockCanvasAndPost(canvas);
EpdController.resetViewUpdateMode(surfaceView);
```

i.e. set a high-quality handwriting update mode, blit the host bitmap into the dirty
rect, post, then reset the update mode.

> **Notable status:** `OnyxInputHandler.onPenUpRefresh()` currently just calls
> `super.onPenUpRefresh()` (a no-op). Notable instead drives refresh itself from
> `onRawDrawingTouchPointListReceived` via `refreshUi()` / `partialRefreshRegionOnce()`.
> The pen-up-refresh callback is an alternative hook we are not using yet.

### 2a. Why the demo has *two* "Pen Up Refresh" controls — [demo]

This is a common point of confusion: the demo shows a button labelled **"Scribble Pen
up Refresh"** in one place and a toggle labelled **"Pen Up Refresh"** in another. They
are **not two different features** — they are the *same* SDK feature
(`setPenUpRefreshEnabled` / `onPenUpRefresh`) surfaced in two separate demo screens.

The `OnyxPenDemo` module actually contains two parallel demo "apps":

1. **The Scribble demo family** — a plain `TouchHelper`-based set of screens. Its menu
   (`ScribbleDemoActivity`, layout `activity_sribble_demo.xml`) lists many demos, one
   button per feature. The button **"Scribble Pen up Refresh"** is just a **navigation
   entry**:

   ```java
   public void button_pen_up_refresh(View view) {
       go(ScribblePenUpRefreshDemoActivity.class);   // it only opens the dedicated screen
   }
   ```

   It toggles nothing. Its subtitle is `desc_pen_up_refresh` ("Triggers a screen refresh
   when the pen is lifted. Includes adjustable delay settings to balance performance and
   ghosting."). The "Scribble" prefix just means "this belongs to the scribble-demo
   family." The screen it opens (`ScribblePenUpRefreshDemoActivity`) then has its **own**
   in-screen `enable_pen_up_refresh` **checkbox** + a **delay seekbar**, wired directly
   to `touchHelper.setPenUpRefreshEnabled(...)` / `setPenUpRefreshTimeMs(...)`.

2. **The integrated PenManager demo** — a richer demo (`PenDemoActivity`) with a
   floating menu (`layout_float_menu.xml`). There, **"Pen Up Refresh"**
   (`@string/pen_up_refresh`, the `penUpCheck` view) is a **live on/off toggle**:

   ```java
   private void onPenUpCheckImpl(boolean isChecked) {
       getPenBundle().setEnablePenUpRefresh(isChecked);
       refreshScreen();
   }
   // ...and the callback honours the flag:
   public void onPenUpRefresh(RectF refreshRect) {
       if (!getPenBundle().isEnablePenUpRefresh()) return;   // gated by the toggle
       new CommonPenAction<>(new PartialRefreshRequest(getPenManager(), refreshRect)).execute();
   }
   ```

**Summary of the mapping:**

| Label seen | Where | What it is | Backing call |
|---|---|---|---|
| "Scribble Pen up Refresh" | `ScribbleDemoActivity` menu list | **navigation button** to the standalone demo | `go(ScribblePenUpRefreshDemoActivity)` |
| enable checkbox + seekbar | inside `ScribblePenUpRefreshDemoActivity` | enable + delay for the standalone demo | `setPenUpRefreshEnabled` / `setPenUpRefreshTimeMs` |
| "Pen Up Refresh" | `PenDemoActivity` floating menu | **live enable/disable toggle** | `setEnablePenUpRefresh` → gates `onPenUpRefresh` |

So both ultimately drive the single SDK feature. The standalone screen exists to
demonstrate it in isolation **and** to expose the **delay slider** (400–2000 ms); the
PenManager floating-menu toggle exists to show enabling/disabling it inside a fuller
note-taking-style demo. There is no behavioural difference in the feature itself — only
in which demo harness wraps it.

---

## 3. Recommended timing values

**[sdk]** From `com.onyx.android.sdk.data.PenConstant`:

| Constant | Value |
|---|---|
| `DEFAULT_PEN_UP_REFRESH_TIME_MS` | **500** |
| `MIN_PEN_UP_REFRESH_TIME_MS` | 400 |
| `MAX_PEN_UP_REFRESH_TIME_MS` | 2000 |
| `PEN_UP_REFRESH_STEP` | 100 |

So the firmware-sanctioned range is **400–2000 ms**, default **500 ms**, adjustable in
100 ms steps.

### How to choose

- **500 ms (default)** is the right starting point. It's long enough that a normal
  writer has lifted and paused, so the clean refresh doesn't interrupt an in-progress
  word.
- **Lower toward 400 ms** for snappier cleanup if users complain ink stays "rough"
  too long. Going below 400 ms is not allowed by the SDK and risks the refresh firing
  mid-stroke-sequence (between two quick strokes), causing a flash.
- **Raise toward 700–1000 ms** for fast note-takers who chain many short strokes; a
  longer timer avoids a high-quality refresh firing in the middle of rapid writing
  (which both flickers and steals CPU/EPD time from the next stroke).
- The timer is "time since pen lifted," so it naturally coalesces a burst of strokes:
  each new pen-down before the timer elapses pushes the clean refresh out.

**Recommendation for Notable:** if/when we adopt pen-up-refresh, default to 500 ms and
expose it as an advanced setting clamped to [400, 2000] in 100 ms steps.

---

## 4. Erasing and resume timing

Reported symptom: a stroke is erased, the user immediately draws on top of the now-empty
area, and the just-erased stroke "reappears" / the new stroke behaves as if the old
content were still there. There *is* effectively a buffer: the firmware keeps its own
handwriting layer that updates asynchronously, and re-entering raw drawing before it has
absorbed the change composites the next stroke against stale content.

The important correction from reading the official demo: **the demo does not fix this
with a long timed delay sprinkled around the erase.** It fixes it *structurally* with
the `RawDrawingRenderEnabled` toggle, and only applies a small, device-specific resume
delay at one precise place. Below is what the demo actually does.

### How the official demo erases — [demo] `ScribbleMoveEraserDemoActivity`

```java
onBeginRawErasing(...)      -> touchHelper.setRawDrawingRenderEnabled(false);  // stop firmware owning screen
                              drawBitmap();                                    // blit host bitmap to surface NOW
onRawErasingTouchPointMoveReceived(p) -> // accumulate; every 100 points:
                              eraseBitmap(path);   // PorterDuff.CLEAR into host bitmap
                              drawBitmap();         // re-post host bitmap
onRawErasingTouchPointListReceived(list) -> eraseBitmap(path);   // final batch
onEndRawErasing(...)       -> touchHelper.setRawDrawingRenderEnabled(true);   // hand screen back
```

Key points:
- The erase is committed to the **host bitmap** (CLEAR xfermode) and the bitmap is
  **synchronously** locked/drawn/posted to the surface on the callback thread — no
  background thread, no race.
- `setRawDrawingRenderEnabled(false)` is flipped the instant erasing begins, so the
  firmware stops compositing its layer; the host surface is authoritative during the
  whole erase. It is re-enabled only at `onEndRawErasing`.
- There is **no `Thread.sleep` / `delay` inside the erase callbacks at all.**

### Where the demo *does* delay — [demo] `RefreshScreenAction` / `ResumeRawDrawingRequest`

For the general "refresh the screen, then resume the pen" flow, the demo runs this
exact sequence on its Rx single thread:

```java
RendererToScreenRequest(...).execute();        // 1. blit host bitmap to screen
ThreadUtils.mySleep(delayResumePenTimeMs);     // 2. wait
penManager.setRawDrawingRenderEnabled(true);   // 3. re-enable render
penManager.setRawInputReaderEnable(true);      //    and input
```

The delay is **`DELAY_ENABLE_RAW_DRAWING_MILLS`**, defined as:

```java
DELAY_ENABLE_RAW_DRAWING_MILLS = DeviceInfoUtil.isColorDevice()
        ? COLOR_DEVICE_PEN_RESUME_DELAY_TIME_MS
        : COMMON_PEN_RESUME_DELAY_TIME_MS;
```

**[sdk]** `com.onyx.android.sdk.data.note.NoteConstant`:

| Constant | Value | Meaning |
|---|---|---|
| `COMMON_PEN_RESUME_DELAY_TIME_MS` | **150** | monochrome resume delay |
| `COLOR_DEVICE_PEN_RESUME_DELAY_TIME_MS` | **500** | Kaleido color resume delay |
| `ERASE_DELAY_RESUME_PEN_TIME` | **500** | resume delay specific to *erasing* |
| `DELAY_FLOAT_MOVE_RESUME_PEN_TIME_MS` | 500 | resume delay after a floating-toolbar move |
| `PAGE_PEN_RESUME_DELAY_TIME` | 600 | page-level (e.g. page turn) resume delay |
| `COMMON_DEVICE_QUIT_FAST_MODE_DELAY_TIME_MS` | 5000 | delay before leaving fast mode |

Note `ERASE_DELAY_RESUME_PEN_TIME = 500`: the SDK uses a **500 ms** resume delay after an
erase (vs 150 ms monochrome for normal pen resume), i.e. the firmware is given longer to
absorb the cleared region before raw drawing is handed back — directly relevant to the
"erased stroke reappears" bug below.

So the **authoritative** wait between "screen refreshed" and "raw drawing re-enabled" is
**150 ms on monochrome, 500 ms on color** — applied *after* posting the bitmap and
*before* `setRawDrawingRenderEnabled(true)`.

> ⚠️ **Correction to Notable [notable].** Notable's `DeviceCompat.delayBeforeResumingDrawing()`
> uses **300 ms** for monochrome / 500 ms for color. The color value matches the SDK,
> but the **300 ms monochrome value is guess-work and is double the official 150 ms**
> (`COMMON_PEN_RESUME_DELAY_TIME_MS`). Consider aligning to the SDK constants.

### The full erase→draw cycle and its two timing windows — [demo] `PenDemoActivity`

`ScribbleMoveEraserDemoActivity` (above) is the *minimal* erase demo and **omits the
timing**. The full `PenDemoActivity` shows the real, timed pipeline. Tracing one
draw → erase → draw cycle answers the exact questions:

**Phase 1 — drawing (screen frozen):** raw drawing enabled; the firmware owns the screen
and draws live. App mirrors each finished stroke into its bitmap.

**Phase 2 — erasing with the pen button (still frozen, until lifted):**
- `onBeginRawErasing` / `onRawErasingPointMove` → `StrokeErasingRequest`, which is created
  with **`setPauseRawDraw(false)`** — i.e. **raw drawing is *not* paused during the erase**.
  Erasing runs live while the pen is down: hit-test & mark shapes transparent, render to the
  bitmap (and optionally show an erase-circle track). The screen stays frozen the whole time.
- **Q: is there a wait to "finish the stroke" before the screen refreshes?** **No.** Nothing
  sleeps while erasing or at pen-lift-before-refresh. On pen up
  (`onRawErasingTouchPointListReceived`) it goes straight to `StrokesEraseFinishedRequest`:
  `BaseRequest.beforeExecute` calls `setRawDrawingEnabled(false)` (pause render **and**
  input), the bitmap is re-rendered (white + surviving shapes, erased ones gone), and
  `afterExecute` blits it to screen **immediately**. So: erase commit → screen shows result,
  with no timed delay in that step.

**Phase 3 — between "erased result shown" and the next scribble:**
- After the bitmap is on screen, `RefreshScreenAction` posts
  `PenEvent.resumeRawDrawing(DELAY_ENABLE_RAW_DRAWING_MILLS)` → `ResumeRawDrawingRequest`,
  whose `execute()` is:

  ```java
  ThreadUtils.mySleep(delayResumePenTimeMs);   // <-- THE wait, before re-enabling
  updatePenParam();                            // restore strokeStyle/width/color/penUpRefresh
  updateDrawExcludeRect();
  setRawDrawingRenderEnabled(true);            // hand screen back to firmware
  setRawInputReaderEnable(true);               // accept input again
  ```

- **Q: is there a wait before the next scribble can start?** **Yes — this is the one that
  matters.** Raw-drawing render *and input* stay disabled for
  `DELAY_ENABLE_RAW_DRAWING_MILLS` = **150 ms (monochrome) / 500 ms (color)**
  (`PenEvent.DELAY_ENABLE_RAW_DRAWING_MILLS = isColorDevice() ? COLOR_DEVICE_PEN_RESUME_DELAY_TIME_MS : COMMON_PEN_RESUME_DELAY_TIME_MS`).
  Until that elapses the firmware is not re-armed, so a new stroke genuinely cannot begin —
  giving the EPD time to absorb the refreshed (erased) image before it re-freezes for the
  next stroke.

**What the simple `ScribbleMoveEraserDemoActivity` left out (verified in the full demo + SDK):**
1. **The post-refresh resume delay** (`mySleep(150/500 ms)`) before re-enabling raw drawing —
   the minimal demo just flips `setRawDrawingRenderEnabled(true)` in `onEndRawErasing` with no
   wait. This is the missing piece behind "draw immediately after erase → stale content".
2. **`updatePenParam()` on resume** — re-applies stroke style/width/color/pen-up-refresh,
   because re-enabling raw drawing runs `resetPenDefaultRawDrawing()` and wipes them
   (see `docs/onyx-native-eraser-indicator.md` for the same reset gotcha).
3. **Pausing raw *input* too** during the finish/resume window (`setRawInputReaderEnable`),
   not just render — so stray points during the refresh can't be injected.
4. A dedicated SDK constant the demos don't even use: **`NoteConstant.ERASE_DELAY_RESUME_PEN_TIME = 500`** — an erase-specific resume delay (the generic path uses 150/500; the production note app appears to use the 500 ms erase value regardless of mono/color). [sdk]
5. **[framework]** Underneath, `BaseHandler` also runs its own post-stroke cleanup: erasing
   sets pen state `PEN_ERASING (4)`, and on stylus-up it schedules
   `ViewUpdateHelper.handwritingRepaint(...)` after `EACNoteConfig.repaintLatency` (**500 ms**
   default). So there are effectively *two* settle timers — the app's resume delay and the
   firmware's repaint latency.

### The actual fix for the "erased stroke reappears" bug

Mirror the demo's *ordering and mechanism*, not a bigger delay:

1. On erase begin, `setRawDrawingRenderEnabled(false)` so the firmware stops owning the
   screen for the duration of the erase.
2. Commit the erase to the host bitmap (`PorterDuff.CLEAR`) and **synchronously**
   lock/draw/post it to the surface — on the callback thread, not a racing background
   coroutine.
3. Re-enable with the demo's sequence: post bitmap → `mySleep(150 mono / 500 color)` →
   `setRawDrawingRenderEnabled(true)` → `setRawInputReaderEnable(true)`.
4. Only then accept the next stroke.

Notable's current path is more fragile than the demo's: `resetScreenFreeze` launches on
`Dispatchers.Default` and flips render enabled inside `delayBeforeResumingDrawing`, so if
the erase bitmap write hasn't completed before that coroutine re-enables render, the race
window is open. The demo avoids this by doing the bitmap post **synchronously** and
gating resume behind the single-threaded Rx queue. Serializing erase-commit → post →
delay → re-enable (e.g. under `CanvasEventBus.drawingInProgress`/`waitForDrawing()`, on
one thread) is what closes the bug — increasing the delay only masks it.

> **Status [notable].** The erase path now does this: `OnyxInputHandler.onRawErasingList`
> posts the indicator-clear, then runs `CanvasRefreshManager.refreshAfterErase(...)`, which
> on a **single coroutine** does `isRawDrawingRenderEnabled=false` → post the erased bitmap
> and **await** it landing on the surface → `delayBeforeResumingDrawing(isErasing=true)`
> (**500 ms**, `ERASE_DELAY_RESUME_PEN_TIME`) → `isRawDrawingRenderEnabled=true`. This
> replaces the old racing pair (`refreshUi`'s async `drawCanvasToView` post on the main
> thread vs `resetScreenFreeze` on `Dispatchers.Default`) and the 300 ms mono erase delay.
> Still outstanding: collapsing the double refresh and pausing raw *input* during the window.

> Notable's own comments already smell this race: in
> `einkHelper.partialRefreshRegionOnce` ("onyx library has its own buffer that needs to
> be updated. Otherwise we will refresh to correct, then incorrect and then correct
> state") and in `OnyxInputHandler.onRawDrawingList` ("sometimes UI will get refreshed
> and frozen before we draw all the strokes … because of doing it in separate thread").
> Both point at the same root cause the demo sidesteps with synchronous ordering. [notable]

---

## 5. Other quirks found during the search

These are scattered through the SDK and Notable; collected here so they're not lost.

### Stroke geometry / config
- **Width compensation `+3.0px`** and specific `smoothLevel`/`pressureSensitivity`
  defaults are baked into the fountain-V2 pen — see `onyx-neo-fountain-pen-v2.md`.
- `PenConstant` default smooth level is **0.2** (`DEFAULT_SMOOTH_LEVEL`), but the
  fountain-V2 factory uses **0.6** — different code paths use different defaults.
- `PenConstant.SHAPE_LIMIT_RENDER_TOUCH_POINT_COUNT = 20000` and
  `NeoPenRender.POINT_LIST_BATCH_LIMIT = 1000`: very long strokes are batched/limited;
  don't assume one stroke == one render call.
- `FILTER_REPEAT_MOVE_POINT_*`: the firmware filters out near-duplicate move points
  (speed < 0.005, pressure delta < 2.0). Don't expect to receive every raw sample.

### Refresh / update-mode unreliability
- **The Onyx library is unstable.** `tryToSetRefreshMode()` wraps
  `setViewDefaultUpdateMode` in try/catch because it throws `NullPointerException` /
  `IllegalArgumentException` on devices/modes that don't support a mode. Always guard
  EPD calls.
- `onSurfaceInit` tries `HAND_WRITING_REPAINT_MODE` and **falls back to `REGAL`** if it
  fails — mode availability is device-dependent.
- `refreshScreen()` using `EpdController.repaintEveryThing(REGAL_PLUS)` is documented in
  Notable as **doing nothing** ("TODO: It does nothing, I have no idea why").
- `PEN_DEACTIVATE_TIME_INTERVAL_MS = 100`: there's a ~100 ms debounce around pen
  activation/deactivation events.

### Threading / freeze ordering (the root of most flicker bugs)
- "sometimes UI will get refreshed and frozen before we draw all the strokes" — doing
  the bitmap draw on a separate thread races the freeze. Notable now serializes draw
  work under `CanvasEventBus.drawingInProgress` and `waitForDrawing()`; `refreshUi`
  even warns `"Drawing is still in progress there might be a bug."` if the lock is held.
- `refreshUi` deliberately **skips unfreezing when not in drawing mode** (Select/menus),
  to avoid fighting other refreshers.
- Color Kaleido devices need **longer** settle times everywhere: the SDK uses
  **500 ms** resume delay on color vs **150 ms** on monochrome
  (`COLOR_DEVICE_PEN_RESUME_DELAY_TIME_MS` / `COMMON_PEN_RESUME_DELAY_TIME_MS`). [sdk]
- Animation mode (`applyTransientUpdate(ANIMATION_X)` / `clearTransientUpdate`) is used
  for scrolling; remember to turn it back off (debounced ~500 ms) or you keep ghosting.

---

## 6. TL;DR

- **Pen up refresh** = firmware replaces fast/rough live ink with a clean partial
  refresh a configurable time after pen-up. Default **500 ms**, range **400–2000 ms**,
  step **100 ms**. Handle `onPenUpRefresh(rect)` by blitting your bitmap into `rect`
  using `HAND_WRITING_REPAINT_MODE`.
- **Erase reappear bug** = host re-enables raw drawing before the firmware buffer
  absorbs the erase. The demo's fix is *structural*: `setRawDrawingRenderEnabled(false)`
  for the whole erase, commit the erase to the bitmap and **synchronously** post it, then
  resume with `post bitmap → mySleep(150 mono / 500 color) → setRawDrawingRenderEnabled(true)`.
  Serialize the steps on one thread; a bigger delay only masks a race.
- **Authoritative resume delay** = **150 ms monochrome / 500 ms color** (`NoteConstant`).
  Notable's 300 ms mono value is guess-work and twice the official number.
- The Onyx EPD API is flaky — guard every call (try/catch), expect device-specific
  behavior, and budget more time on color panels.
