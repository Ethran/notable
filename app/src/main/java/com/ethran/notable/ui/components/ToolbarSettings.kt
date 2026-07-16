package com.ethran.notable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.editor.ui.toolbar.ToolbarButton
import com.ethran.notable.editor.ui.toolbar.model.IconRef
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElementId
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElements
import com.ethran.notable.editor.ui.toolbar.model.ToolbarLayout
import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.ui.theme.InkaTheme
import android.graphics.Color as AndroidColor

/**
 * The "Toolbar" settings panel (design doc §5.2): a live preview, the two layout zones
 * plus a "Hidden" section as one long-press-drag reorderable list, pen preset
 * create/edit/delete, add-divider, and reset.
 *
 * Every commit writes a **concrete** layout (never null), materializing
 * [ToolbarLayout.DEFAULT] on first edit — otherwise editing the default presets while
 * `toolbarLayout == null` could silently drop buttons whose seed ids disappeared.
 */
@Composable
fun ToolbarSettings(
    settings: AppSettings, onSettingsChange: (AppSettings) -> Unit
) {
    val pens = settings.toolbarPens
    val layout = (settings.toolbarLayout ?: ToolbarLayout.DEFAULT).validated(pens)

    fun commit(newLayout: ToolbarLayout, newPens: List<ToolbarPen> = pens) {
        onSettingsChange(
            settings.copy(toolbarLayout = newLayout.validated(newPens), toolbarPens = newPens)
        )
    }

    var addPenOpen by remember { mutableStateOf(false) }
    var editedPresetId by remember { mutableStateOf<String?>(null) }

    Column {
        ToolbarPreview(layout, pens)
        SettingsDivider()

        Text(
            text = stringResource(R.string.toolbar_settings_drag_hint),
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(vertical = 4.dp)
        )

        ReorderableElementList(
            layout = layout,
            pens = pens,
            onLayoutChange = { commit(it) },
            onEditPen = { editedPresetId = it },
            onDeletePen = { presetId ->
                val entry = ToolbarPen.LAYOUT_PREFIX + presetId
                commit(
                    ToolbarLayout(
                        layout.scrollable.filterNot { it == entry },
                        layout.pinned.filterNot { it == entry },
                    ),
                    pens.filterNot { it.id == presetId },
                )
            },
            onDeleteDivider = { zone, index ->
                fun List<String>.dropAt(i: Int) = filterIndexed { idx, _ -> idx != i }
                commit(
                    if (zone == Zone.SCROLLABLE)
                        layout.copy(scrollable = layout.scrollable.dropAt(index))
                    else layout.copy(pinned = layout.pinned.dropAt(index))
                )
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { addPenOpen = true }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.toolbar_settings_add_pen))
            }
            OutlinedButton(
                onClick = {
                    commit(layout.copy(scrollable = layout.scrollable + ToolbarElementId.DIVIDER.name))
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.toolbar_settings_add_divider))
            }
        }
        OutlinedButton(
            onClick = {
                onSettingsChange(
                    settings.copy(toolbarLayout = null, toolbarPens = ToolbarPen.DEFAULT_PENS)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.toolbar_settings_reset))
        }
        Spacer(Modifier.height(16.dp))
    }

    if (addPenOpen) {
        AddPenDialog(
            onPick = { pen ->
                val preset = ToolbarPen(
                    id = ToolbarPen.newId(),
                    pen = pen,
                    color = if (pen == Pen.MARKER) AndroidColor.LTGRAY else AndroidColor.BLACK,
                    size = if (pen == Pen.MARKER) 40f else 5f,
                )
                commit(
                    layout.copy(scrollable = layout.scrollable + preset.layoutEntry),
                    pens + preset,
                )
                addPenOpen = false
                editedPresetId = preset.id
            },
            onClose = { addPenOpen = false },
        )
    }

    settings.toolbarPens.find { it.id == editedPresetId }?.let { preset ->
        PenEditDialog(
            preset = preset,
            onChange = { updated ->
                commit(layout, pens.map { if (it.id == updated.id) updated else it })
            },
            onClose = { editedPresetId = null },
        )
    }
}

// ---------------------------------------------------------------------------
// Reorderable list
// ---------------------------------------------------------------------------

private enum class Zone { SCROLLABLE, PINNED, HIDDEN }

private sealed interface ToolbarRow {
    data class SectionHeader(val zone: Zone) : ToolbarRow

    /** A layout entry string: a [ToolbarElementId] name or `"PEN:<id>"`. */
    data class Element(val entry: String) : ToolbarRow
}

private val ROW_HEIGHT = 48.dp

private fun buildRows(layout: ToolbarLayout, pens: List<ToolbarPen>): List<ToolbarRow> {
    val placed = (layout.scrollable + layout.pinned).toSet()
    // Hidden = placeable statics absent from the layout, plus pen presets whose layout
    // entry was dragged out (the preset survives so it can be dragged back or deleted).
    val hidden = ToolbarElementId.entries.filter {
        it != ToolbarElementId.TOGGLE && it != ToolbarElementId.PEN &&
                it != ToolbarElementId.DIVIDER && it.name !in placed
    }.map { it.name } + pens.map { it.layoutEntry }.filter { it !in placed }

    return buildList {
        add(ToolbarRow.SectionHeader(Zone.SCROLLABLE))
        layout.scrollable.forEach { add(ToolbarRow.Element(it)) }
        add(ToolbarRow.SectionHeader(Zone.PINNED))
        layout.pinned.forEach { add(ToolbarRow.Element(it)) }
        add(ToolbarRow.SectionHeader(Zone.HIDDEN))
        hidden.forEach { add(ToolbarRow.Element(it)) }
    }
}

/** Rebuilds a layout from row order: entries belong to the section header above them. */
private fun rowsToLayout(rows: List<ToolbarRow>): ToolbarLayout {
    var zone = Zone.SCROLLABLE
    val scrollable = mutableListOf<String>()
    val pinned = mutableListOf<String>()
    for (row in rows) when (row) {
        is ToolbarRow.SectionHeader -> zone = row.zone
        is ToolbarRow.Element -> when (zone) {
            Zone.SCROLLABLE -> scrollable.add(row.entry)
            Zone.PINNED -> pinned.add(row.entry)
            Zone.HIDDEN -> Unit // hidden = absent from the layout
        }
    }
    return ToolbarLayout(scrollable, pinned)
}

/** The zone the row at [index] belongs to: the nearest section header above it. */
private fun zoneAt(rows: List<ToolbarRow>, index: Int): Zone {
    for (i in index downTo 0) (rows[i] as? ToolbarRow.SectionHeader)?.let { return it.zone }
    return Zone.SCROLLABLE
}

/**
 * All three sections rendered as one column so a single drag can reorder within a zone
 * *and* move across zones (an entry dragged past a header changes section). Slot-based:
 * rows swap in whole [ROW_HEIGHT] steps rather than free-floating — e-ink friendly, per
 * the design doc. Plain Column (not lazy): ~25 fixed-height rows, inside Settings' scroll.
 *
 * The drag detector sits on the whole Column, not on the rows: every slot swap
 * recomposes the list, and a per-row pointerInput would be re-keyed (its gesture
 * cancelled) the moment its row's index changed under the finger. All rows —
 * headers included — are exactly [ROW_HEIGHT] tall so `y / height` is the row index.
 */
@Composable
private fun ReorderableElementList(
    layout: ToolbarLayout,
    pens: List<ToolbarPen>,
    onLayoutChange: (ToolbarLayout) -> Unit,
    onEditPen: (String) -> Unit,
    onDeletePen: (String) -> Unit,
    onDeleteDivider: (Zone, Int) -> Unit,
) {
    // Local order during a drag; rebuilt whenever the persisted settings change.
    var rows by remember(layout, pens) { mutableStateOf(buildRows(layout, pens)) }
    var draggingIndex by remember(layout, pens) { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }

    /** One-slot move of the dragged row; false when blocked (list end, or MENU into
     * Hidden — the validator would silently snap it back, so refuse the drop instead). */
    fun moveDragged(step: Int): Boolean {
        val from = draggingIndex ?: return false
        val to = from + step
        // Row 0 is the Scrollable header; nothing may move above it.
        if (to < 1 || to > rows.lastIndex) return false
        val moved = rows.toMutableList().apply { add(to, removeAt(from)) }
        val entry = (moved[to] as? ToolbarRow.Element)?.entry
        if (entry == ToolbarElementId.MENU.name && zoneAt(moved, to) == Zone.HIDDEN) return false
        rows = moved
        draggingIndex = to
        dragOffset -= step * rowHeightPx
        return true
    }

    Column(
        modifier = Modifier.pointerInput(layout, pens) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    val index = (offset.y / rowHeightPx).toInt()
                    if (index > 0 && rows.getOrNull(index) is ToolbarRow.Element) {
                        draggingIndex = index
                        dragOffset = 0f
                    }
                },
                onDrag = { change, amount ->
                    if (draggingIndex == null) return@detectDragGesturesAfterLongPress
                    change.consume()
                    dragOffset += amount.y
                    while (dragOffset > rowHeightPx * 0.6f && moveDragged(1)) Unit
                    while (dragOffset < -rowHeightPx * 0.6f && moveDragged(-1)) Unit
                },
                onDragEnd = {
                    if (draggingIndex != null) {
                        draggingIndex = null
                        dragOffset = 0f
                        onLayoutChange(rowsToLayout(rows))
                    }
                },
                onDragCancel = {
                    draggingIndex = null
                    dragOffset = 0f
                    rows = buildRows(layout, pens)
                },
            )
        }
    ) {
        rows.forEachIndexed { index, row ->
            when (row) {
                is ToolbarRow.SectionHeader -> SectionHeaderRow(row.zone)

                is ToolbarRow.Element -> {
                    val isDragged = index == draggingIndex
                    ElementRow(
                        entry = row.entry,
                        pens = pens,
                        onEditPen = onEditPen,
                        onDelete = deleteActionFor(
                            row.entry, rows, index, onDeletePen, onDeleteDivider
                        ),
                        modifier = Modifier
                            .zIndex(if (isDragged) 1f else 0f)
                            .graphicsLayer { translationY = if (isDragged) dragOffset else 0f },
                    )
                }
            }
        }
    }
}

/** Pens and dividers get a trailing delete button; statics are hidden by dragging. */
private fun deleteActionFor(
    entry: String,
    rows: List<ToolbarRow>,
    index: Int,
    onDeletePen: (String) -> Unit,
    onDeleteDivider: (Zone, Int) -> Unit,
): (() -> Unit)? = when {
    entry.startsWith(ToolbarPen.LAYOUT_PREFIX) ->
        ({ onDeletePen(entry.removePrefix(ToolbarPen.LAYOUT_PREFIX)) })

    entry == ToolbarElementId.DIVIDER.name -> {
        // Dividers repeat, so identify this one by its position within its zone.
        var zone = Zone.SCROLLABLE
        var indexInZone = 0
        for (i in 0 until index) when (val row = rows[i]) {
            is ToolbarRow.SectionHeader -> {
                zone = row.zone
                indexInZone = 0
            }

            is ToolbarRow.Element -> indexInZone++
        }
        val z = zone
        val i = indexInZone
        ({ onDeleteDivider(z, i) })
    }

    else -> null
}

@Composable
private fun SectionHeaderRow(zone: Zone) {
    // Exactly ROW_HEIGHT tall — the list's drag detector maps y-offset to row index.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.06f))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = stringResource(
                when (zone) {
                    Zone.SCROLLABLE -> R.string.toolbar_settings_section_scrollable
                    Zone.PINNED -> R.string.toolbar_settings_section_pinned
                    Zone.HIDDEN -> R.string.toolbar_settings_section_hidden
                }
            ),
            style = MaterialTheme.typography.subtitle2,
        )
    }
}

@Composable
private fun ElementRow(
    entry: String,
    pens: List<ToolbarPen>,
    onEditPen: (String) -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val preset = if (entry.startsWith(ToolbarPen.LAYOUT_PREFIX))
        pens.find { it.id == entry.removePrefix(ToolbarPen.LAYOUT_PREFIX) } else null

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(MaterialTheme.colors.background)
            .then(
                if (preset != null) Modifier.clickable { onEditPen(preset.id) } else Modifier
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Menu,
            contentDescription = null,
            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .size(18.dp)
        )

        if (preset != null) {
            ToolbarButton(
                penColor = Color(preset.color),
                iconId = (ToolbarElements.penIcon(preset.pen) as IconRef.Drawable).resId,
                onSelect = { onEditPen(preset.id) },
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(penNameRes(preset.pen)),
                style = MaterialTheme.typography.body1,
                modifier = Modifier.weight(1f)
            )
        } else {
            val id = ToolbarElementId.fromString(entry)
            val icon = id?.let { ToolbarElements.all[it]?.icon }
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                when (icon) {
                    is IconRef.Drawable -> ToolbarButton(iconId = icon.resId)
                    is IconRef.Vector -> ToolbarButton(vectorIcon = icon.imageVector)
                    null -> if (id == ToolbarElementId.DIVIDER) Box(
                        Modifier
                            .size(width = 2.dp, height = 24.dp)
                            .background(MaterialTheme.colors.onSurface)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = id?.let { stringResource(elementNameRes(it)) } ?: entry,
                style = MaterialTheme.typography.body1,
                modifier = Modifier.weight(1f)
            )
        }

        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.toolbar_settings_delete),
                    tint = MaterialTheme.colors.onSurface,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Preview strip
// ---------------------------------------------------------------------------

/**
 * Static rendition of the layout: real icons in real order, pens tinted with their
 * preset color, but non-interactive and unconditionally visible (visibleWhen predicates
 * like "has clipboard" can't be evaluated meaningfully here). Deliberately compact —
 * roughly half the real toolbar's scale, it's an overview, not a replica.
 */
@Composable
private fun ToolbarPreview(layout: ToolbarLayout, pens: List<ToolbarPen>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.3f))
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun previewIcon(icon: IconRef, background: Color? = null) {
            val tint =
                if (background == Color.Black || background == Color.DarkGray) Color.White
                else Color.Black
            Box(
                modifier = Modifier
                    .padding(1.dp)
                    .size(22.dp)
                    .background(
                        color = background ?: Color.Transparent,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when (icon) {
                    is IconRef.Drawable -> Icon(
                        painterResource(icon.resId), null,
                        tint = tint, modifier = Modifier.size(14.dp)
                    )

                    is IconRef.Vector -> Icon(
                        icon.imageVector, null,
                        tint = tint, modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        @Composable
        fun previewEntry(entry: String) {
            if (entry.startsWith(ToolbarPen.LAYOUT_PREFIX)) {
                pens.find { it.id == entry.removePrefix(ToolbarPen.LAYOUT_PREFIX) }?.let {
                    previewIcon(ToolbarElements.penIcon(it.pen), background = Color(it.color))
                }
                return
            }
            val id = ToolbarElementId.fromString(entry) ?: return
            if (id == ToolbarElementId.DIVIDER) {
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .size(width = 1.dp, height = 16.dp)
                        .background(MaterialTheme.colors.onSurface)
                )
                return
            }
            when (val icon = ToolbarElements.all[id]?.icon) {
                is IconRef.Drawable, is IconRef.Vector -> previewIcon(icon)
                null -> if (id == ToolbarElementId.PAGE_NAV) Text(
                    text = "1/1",
                    style = MaterialTheme.typography.caption,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        layout.scrollable.forEach { previewEntry(it) }
        Spacer(Modifier.width(12.dp))
        layout.pinned.forEach { previewEntry(it) }
    }
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

@Composable
private fun AddPenDialog(onPick: (Pen) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colors.background)
                .border(1.dp, MaterialTheme.colors.onSurface)
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.toolbar_settings_pick_pen_type),
                style = MaterialTheme.typography.h6,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            ToolbarPen.BASE_TYPES.forEach { pen ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(pen) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolbarButton(
                        iconId = (ToolbarElements.penIcon(pen) as IconRef.Drawable).resId,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(penNameRes(pen)))
                }
            }
        }
    }
}

/**
 * Per-pen StrokeMenu contents editor: which colors and which sizes this pen offers in
 * its toolbar popup. Multi-select toggles over the candidate sets; the pen's *current*
 * color/size is still picked from the toolbar's StrokeMenu, as always. At least one
 * color and two sizes must stay included (the discrete size slider needs a range).
 *
 * Toggles edit a local copy; the settings blob is written once, on dismiss — not per
 * tap. If an edit removes the option the pen currently draws with, the active
 * color/size is clamped to a surviving option so it never falls off its own palette.
 */
@Composable
private fun PenEditDialog(
    preset: ToolbarPen,
    onChange: (ToolbarPen) -> Unit,
    onClose: () -> Unit,
) {
    var edited by remember(preset.id) { mutableStateOf(preset) }
    val includedColors = edited.effectiveColorOptions()
    val includedSizes = edited.effectiveSizeOptions()

    fun commitAndClose() {
        if (edited != preset) onChange(
            edited.copy(
                color = if (edited.color in edited.effectiveColorOptions()) edited.color
                else edited.effectiveColorOptions().first(),
                size = if (edited.size in edited.effectiveSizeOptions()) edited.size
                else edited.effectiveSizeOptions().minBy { kotlin.math.abs(it - edited.size) },
            )
        )
        onClose()
    }

    Dialog(onDismissRequest = ::commitAndClose) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colors.background)
                .border(1.dp, MaterialTheme.colors.onSurface)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolbarButton(
                    penColor = Color(edited.color),
                    iconId = (ToolbarElements.penIcon(edited.pen) as IconRef.Drawable).resId,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(penNameRes(edited.pen)),
                    style = MaterialTheme.typography.h6,
                )
            }

            Text(
                text = stringResource(R.string.toolbar_settings_color),
                style = MaterialTheme.typography.subtitle2,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            ToolbarPen.COLOR_CANDIDATES.chunked(7).forEach { rowColors ->
                Row {
                    rowColors.forEach { colorInt ->
                        val included = colorInt in includedColors
                        Box(
                            modifier = Modifier
                                .padding(2.dp)
                                .size(36.dp)
                                .background(Color(colorInt))
                                .border(
                                    if (included) 3.dp else 1.dp,
                                    if (included) MaterialTheme.colors.onSurface
                                    else MaterialTheme.colors.onSurface.copy(alpha = 0.2f),
                                )
                                .clickable {
                                    // Re-added colors append at the end: the user's
                                    // stored palette order is preserved, not re-sorted
                                    // to candidate order.
                                    val updated =
                                        if (included) includedColors - colorInt
                                        else includedColors + colorInt
                                    if (updated.isNotEmpty())
                                        edited = edited.copy(colorOptions = updated)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (included) Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = if (colorInt == AndroidColor.BLACK ||
                                    colorInt == AndroidColor.DKGRAY ||
                                    colorInt == AndroidColor.BLUE
                                ) Color.White else Color.Black,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.toolbar_settings_size),
                style = MaterialTheme.typography.subtitle2,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            ToolbarPen.SIZE_CANDIDATES.chunked(7).forEach { rowSizes ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rowSizes.forEach { size ->
                        val included = size in includedSizes
                        ToolbarButton(
                            text = ToolbarElements.sizeLabel(size),
                            isSelected = included,
                            onSelect = {
                                val updated =
                                    if (included) includedSizes - size
                                    else (includedSizes + size).sorted()
                                if (updated.size >= 2)
                                    edited = edited.copy(sizeOptions = updated)
                            },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Names
// ---------------------------------------------------------------------------

private fun penNameRes(pen: Pen): Int = when (pen) {
    Pen.BALLPEN, Pen.REDBALLPEN, Pen.GREENBALLPEN, Pen.BLUEBALLPEN -> R.string.pen_name_ballpen
    Pen.PENCIL -> R.string.pen_name_pencil
    Pen.BRUSH -> R.string.pen_name_brush
    Pen.FOUNTAIN -> R.string.pen_name_fountain
    Pen.MARKER -> R.string.pen_name_marker
    Pen.DASHED -> R.string.pen_name_ballpen // not placeable; unreachable in practice
}

private fun elementNameRes(id: ToolbarElementId): Int = when (id) {
    ToolbarElementId.SHAPE -> R.string.toolbar_element_shape
    ToolbarElementId.ERASER -> R.string.toolbar_element_eraser
    ToolbarElementId.SELECT -> R.string.toolbar_element_select
    ToolbarElementId.IMAGE -> R.string.toolbar_element_image
    ToolbarElementId.PASTE -> R.string.toolbar_element_paste
    ToolbarElementId.RESET_VIEW -> R.string.toolbar_element_reset_view
    ToolbarElementId.UNDO -> R.string.toolbar_element_undo
    ToolbarElementId.REDO -> R.string.toolbar_element_redo
    ToolbarElementId.PAGE_NAV -> R.string.toolbar_element_page_nav
    ToolbarElementId.HOME -> R.string.toolbar_element_home
    ToolbarElementId.MENU -> R.string.toolbar_element_menu
    ToolbarElementId.DIVIDER -> R.string.toolbar_element_divider
    // Not placeable / not listed, but keep the when exhaustive:
    ToolbarElementId.TOGGLE, ToolbarElementId.PEN -> R.string.toolbar_element_menu
}

// ----------------------------------- //
// --------      Previews      ------- //
// ----------------------------------- //

@Preview(showBackground = true)
@Composable
private fun ToolbarSettingsPreview() {
    InkaTheme {
        ToolbarSettings(settings = AppSettings(version = 1), onSettingsChange = {})
    }
}
