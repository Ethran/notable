# Customizable Toolbar Architecture

The editor toolbar is **data-driven**: what appears, in what order, and in which zone is a
serialized layout the user edits, not code. Adding a tool means adding a registry entry (and,
for pens, a stroke style) — never editing the render loop. This document describes the shipped
design; open risks live in [`toolbar-known-issues.md`](toolbar-known-issues.md).

---

## Three concepts, kept separate

| Concept | What it is | Where it lives |
| :-- | :-- | :-- |
| **Toolbar element** | a placeable slot: icon, visibility rule, what it activates | `editor/ui/toolbar/model/` |
| **Tool** | an input `Mode` the editor can be in + its behavior | `EditorViewModel` / `ToolbarAction` |
| **Stroke style** | how a persisted stroke becomes pixels | `editor/drawing/StrokeStyleRegistry.kt` |

Stroke rendering is keyed by `stroke.pen` from the DB, **not** by any toolbar object — the
renderer re-draws persisted strokes on load/scroll/undo where no toolbar exists. So the toolbar
references pens by id only; the drawing side looks up the same id independently.

---

## Core Components

### 1. [ToolbarElement](../app/src/main/java/com/ethran/notable/editor/ui/toolbar/model/ToolbarElement.kt)

A sealed hierarchy — every placeable item is one subtype, so any element can go anywhere.

<details>
<summary>View the hierarchy</summary>

```kotlin
sealed interface ToolbarElement {
    val id: ToolbarElementId
    val icon: IconRef?                    // null only for DividerElement / PAGE_NAV
    val contentDescription: String
    val visibleWhen: VisibleWhen          // (ToolbarUiState, AppSettings) -> Boolean
    fun isSelected(state: ToolbarUiState): Boolean = false
}

// PenElement   — a pen preset: pen type + setting + StrokeSubmenuSpec; isSelected by presetId
// ShapeElement — one SHAPE button + shape picker (LINE today); isSelected: mode == Line
// ModeElement  — eraser / select; isSelected: mode == this.mode
// ActionElement— undo, redo, paste, reset-view, home, toggle; stateless
// CustomElement— PAGE_NAV / MENU / IMAGE_PICKER; bespoke rendering
// DividerElement (data object) — placeable separator, no button
```
</details>

- **`visibleWhen`** absorbs every legacy conditional (`hasClipboard` → PASTE,
  `showResetView` → RESET_VIEW, `notebookId != null` → PAGE_NAV). Settings are passed in, not
  read from `GlobalAppSettings`, so the model stays Compose-free and unit-testable; the
  renderer supplies `GlobalAppSettings.current` at composition time as a snapshot read.
- **`IconRef`** wraps the two icon sources in use: `Drawable(@DrawableRes)` and
  `Vector(ImageVector)` (Feather icons).

### 2. [ToolbarElements](../app/src/main/java/com/ethran/notable/editor/ui/toolbar/model/ToolbarElements.kt) — the registry

`all: Map<ToolbarElementId, ToolbarElement>` holds every **static** element, built once.
Pens are **not** here — they are user data, resolved on demand:

```kotlin
fun resolve(name: String, pens: List<ToolbarPen>): ToolbarElement? =
    if (name.startsWith("PEN:")) pens.find { it.id == name.removePrefix("PEN:") }?.let(::penElement)
    else ToolbarElementId.fromString(name)?.let { all[it] }   // null → caller skips the entry
```

`presentlyUsedToolIcon(state, pens)` — the collapsed-toolbar icon — is a registry lookup: the
first selected element's icon, else the active pen preset's icon, else the `line_dashed`
fallback (the old exhaustive `when(pen)` is gone).

### 3. [ToolbarPen](../app/src/main/java/com/ethran/notable/editor/ui/toolbar/model/ToolbarPen.kt) — pen presets

A pen button is a user-created **instance**, not a fixed id. The preset *is* the pen's
setting.

```kotlin
@Serializable
data class ToolbarPen(
    val id: String,               // generated, stable (8-char), never reused
    val pen: Pen,                 // base type: BALLPEN, PENCIL, BRUSH, FOUNTAIN, MARKER
    val color: Int, val size: Float,
    val colorOptions: List<Int>? = null,   // which colors its StrokeMenu offers (null → default)
    val sizeOptions: List<Float>? = null,  // which sizes (null → type default)
)
```

- Referenced from layouts as `"PEN:<id>"`. `DEFAULT_PENS` reproduces the historical eight
  buttons with stable seed ids (`ball`/`red`/`blue`/`green`/`pencil`/`brush`/`fountain`/`marker`);
  red/green/blue are plain `BALLPEN` presets with a color.
- **Selection is by preset id**, not `Pen` — two ballpens in different colors are distinct.
  `ToolbarUiState.penSettings` and the editor's `penPresetId` are preset-id-keyed. Stroke
  *rendering* is untouched: strokes still persist `pen`/`color`/`size`, and
  `StrokeStyleRegistry` stays keyed by `Pen`.
- Legacy `Pen.REDBALLPEN`/`BLUEBALLPEN`/`GREENBALLPEN` remain **for rendering only** (DB rows,
  Xopp/sync imports) — marked do-not-remove; no longer placeable.

### 4. [ToolbarLayout](../app/src/main/java/com/ethran/notable/editor/ui/toolbar/model/ToolbarLayout.kt)

Two ordered string lists — the two physical zones.

```kotlin
@Serializable
data class ToolbarLayout(val scrollable: List<String>, val pinned: List<String>)
```

- Entries are **strings, not enum ordinals**: unknown names drop on load (a newer export still
  imports), and reordering `ToolbarElementId` can't corrupt saved layouts.
- **`validated(pens)`** is the single sanitizer, run on every load and import:
  drops non-resolving names and `"PEN:<id>"` for absent presets; drops the structural `TOGGLE`
  and the bare `PEN` sentinel; dedups across both zones (first wins) **except `DIVIDER`**;
  enforces the one invariant — `MENU` must exist, appended to `pinned` if missing.

---

## Rendering: [ToolbarContent](../app/src/main/java/com/ethran/notable/editor/ui/toolbar/Toolbar.kt)

`ToolbarContent` reads the layout from the `GlobalAppSettings.current` snapshot (so setting
changes recompose), runs `.validated()`, and iterates:

```
Row(toolbar):
    render TOGGLE + divider           // structural, always first, never in the layout
    Row(weight 1, horizontalScroll):  layout.scrollable → visibleWhen → ToolbarElementView
    Row:                              layout.pinned     → visibleWhen → ToolbarElementView
```

[`ToolbarElementView`](../app/src/main/java/com/ethran/notable/editor/ui/toolbar/ToolbarElementView.kt)
is the one generic renderer — a `when(element)` over the subtypes. It draws the button via
`ToolbarButton`, handles selected state, and opens the element's declared submenu
(`StrokeMenu` for pens, the eraser popup for `ModeElement`). Composables are stateless except
transient popup-open state; **all mutation flows `ToolbarAction → onToolbarAction() → new
ToolbarUiState`**. The image picker routes through an `onPickImage` callback because its
activity-result launcher is Compose infrastructure owned by `ToolbarContent`, not a
`ToolbarAction`.

---

## Persistence

`AppSettings` gained two `@Serializable` fields, persisted and observed for free through the
existing `KvProxy` / Room `Kv` / `GlobalAppSettings.current` path:

```kotlin
val toolbarLayout: ToolbarLayout? = null   // null → ToolbarLayout.DEFAULT
val toolbarPens: List<ToolbarPen> = DEFAULT_PENS
```

`ignoreUnknownKeys` + optional-with-default means old blobs decode to defaults and old app
versions ignore the new keys. Pen-setting edits (`ChangePenSetting` from `StrokeMenu`) write
back to the preset synchronously (ordering rapid slider edits) and persist async. The
ViewModel mirrors `toolbarPens` into `ToolbarUiState.penSettings` via a `snapshotFlow`, and
**reselects the first surviving preset** when the active id disappears — so deleting a pen
mid-session needs no special-casing.

---

## Settings editor: [ToolbarSettings](../app/src/main/java/com/ethran/notable/ui/components/ToolbarSettings.kt)

A "Toolbar" tab (between Gestures and Debug) editing the draft layout, committed via
`onSettingsChange(settings.copy(toolbarLayout = …, toolbarPens = …))`. Every commit writes a
**concrete** `validated()` layout (never null), materializing `DEFAULT` on first edit so
editing default presets can't silently drop buttons.

- **One reorderable list, three sections** (Scrollable / Pinned / Hidden): entries belong to
  the header above them, so a single long-press drag both reorders within a zone and moves
  across zones. **Slot-based** (whole `ROW_HEIGHT` swaps, no free-floating ghost) for e-ink.
  The drag detector sits on the **list Column**, not per-row — a per-row `pointerInput` gets
  re-keyed (gesture cancelled) when its index shifts mid-drag. Headers are also exactly
  `ROW_HEIGHT` so `y / ROW_HEIGHT` is the row index.
- **Hidden** = placeable statics absent from the layout, plus orphaned pen presets (entry
  dragged out, preset kept). Dropping a divider there deletes it; `MENU` is refused entry
  (with a snackbar) since the validator would re-append it.
- **Add pen / edit / delete**: add picks a base type → new preset with type defaults; the edit
  dialog multi-selects **which options the pen's StrokeMenu offers** (not its current
  color/size, which stays in the toolbar StrokeMenu), buffering to a local copy and committing
  once on dismiss; removed active options are clamped to a surviving one.
- **Reset** sets `toolbarLayout = null` + `DEFAULT_PENS`. **Export/Import** — see below.
- **Live preview**: a compact static icon strip of the real layout (not the interactive
  `ToolbarContent`, which needs a UiState/action loop that doesn't exist in Settings).

---

## Export / Import: [ToolbarLayoutFile](../app/src/main/java/com/ethran/notable/editor/ui/toolbar/model/ToolbarLayoutFile.kt)

A self-identifying JSON envelope, written/read via SAF pickers
(`CreateDocument` / `OpenDocument`).

```json
{ "type": "notable-toolbar-layout", "version": 1, "layout": { … }, "pens": [ … ] }
```

`decode(text): ImportResult` rejects malformed JSON, a wrong `type`, or a newer `version` with
a user-presentable `IllegalArgumentException`; it deduplicates pen ids and resets degenerate
option lists, runs `validated(pens)`, and returns a `droppedCount` (validator-appended `MENU`
not counted) so the caller can report normalized entries in a snackbar instead of failing.
Import replaces `toolbarLayout` **and** `toolbarPens` wholesale, behind a confirmation dialog
(irreversible); the editor's snapshotFlow reselects a surviving preset afterward.

---

## Adding a tool (the payoff)

| To add… | Do this | Don't touch |
| :-- | :-- | :-- |
| A static tool (button/action) | one `ToolbarElements.all` entry + a `ToolbarElementId` | `ToolbarContent`, `ToolbarElementView` |
| A new pen type | a `Pen` value + a `StrokeStyleRegistry.forPen` entry | the toolbar model |
| A new shape | extend the `Shape` enum | the picker grows automatically |
| A text tool (future) | `Mode.Text` + a `TextElement` spec + its submenu | the render loop |

A unit test asserts **every `ToolbarElementId` resolves** and **every stroke-producing `Pen`
has a `StrokeStyle`**, so wiring a tool halfway fails at test time, not runtime
(`ToolbarElementsTest`, `StrokeStyleRegistryTest`).
</content>
