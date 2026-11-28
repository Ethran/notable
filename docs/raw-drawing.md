# Raw drawing and the canvas surface

**It was created by AI, and should be checked for correctness. Refer to code for actual implementation.**

On Onyx, the app posts the editor's page bitmap to a `SurfaceView` and the
firmware draws live pen ink on a separate layer. The order matters. On a BOOX
Note Air 4C, restarting firmware rendering immediately after a plain post could
hide the update. Leaving input active while firmware rendering was frozen could
also leave strokes without live ink.

## Ownership

- [`RawDrawingController`](../app/src/main/java/com/ethran/notable/editor/canvas/RawDrawingController.kt)
  decides what to show and when to enable pen input and rendering. Its events and
  timer run on the main thread. Start at `applyState()` for the write and pen order.
- `SurfaceWriter` locks, draws and posts the editor surface. `PenDevice` wraps
  the Onyx SDK. Without an Onyx pen, the controller uses `NoPenDevice` and OpenGL
  provides live ink.
- Call `CanvasRefreshManager.refreshUi(dirtyRect)` to request a redraw in screen
  coordinates. Pass `null` for the whole page. `OnyxInputHandler` reports strokes
  and commits erases after updating the page bitmap.
- Nothing else posts the surface or calls the raw-drawing SDK;
  `RawDrawingOwnershipTest` fails if something does.

## What gets shown

- **Page redraw.** If the pen is active, the controller freezes firmware rendering,
  posts the whole page to preserve live ink, then exits raw drawing. With drawing
  still wanted, it waits 300 ms on monochrome Onyx or 500 ms on colour Onyx before
  enabling the pen. A first-attempt post when the pen is already off, such as after a
  popup closes, allows immediate enable if there is no active settle. A successful
  page post during a settle or after a failure holds the pen off for at least the
  post-settle interval.
- **Erase.** With the pen active, the controller blocks input and forces through
  a post covering the erase and any firmware-only ink before clearing the
  firmware layer. The erase settle is 150 ms for a stroke erase and 500 ms for
  an area erase or scribble-to-erase.
- **QuickNav.** A preview owns the surface for its scrub. Page redraws wait and
  the pen stays off. Ending the owning scrub requests a full page redraw.
- **Write failure.** The requested content stays pending. The idle canvas can
  retry up to five times at 500 ms intervals. If recovery gives up on a page
  write, drawing can resume when otherwise allowed. A surface rebuild requests
  a fresh post of the page or active preview.

For diagnosis, filter for `RawDrawing` (controller decisions and SDK calls) and
`DrawCanvas` (surface submission, including `submitted`). A successful
submission means the canvas was drawn and posted. The controller's settles
account for the panel update that follows. See `RawDrawingControllerTest` for
timing and event sequences, `QuickNavScrubTest` for scrub IDs, and
`RawDrawingOwnershipTest` for the source scan of SDK calls and surface posts.
