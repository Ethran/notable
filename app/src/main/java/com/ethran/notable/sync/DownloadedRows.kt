package com.ethran.notable.sync

import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.model.SheetSize

/** The largest sheet side accepted from the server, in page pixels; a larger one is corrupt. */
internal const val MAX_SYNCED_SHEET_SIDE = 20_000

/**
 * The sheet to store for a downloaded page or notebook. A sheet is fixed once created, so a missing
 * or invalid incoming sheet keeps the [local] one: an app version without sheets uploads pages and
 * manifests without them, and that must not turn a sheet into an infinite canvas.
 */
internal fun downloadedSheet(remote: SheetSize?, local: SheetSize?): SheetSize? =
    remote?.takeIf { it.width in 1..MAX_SYNCED_SHEET_SIDE && it.height in 1..MAX_SYNCED_SHEET_SIDE }
        ?: local

/**
 * The row to store for a downloaded [remote] page, given this device's [local] row (null when the
 * page is new here). The view position is device-local, like `openPageId`: the local one is kept,
 * and a new page opens at the top.
 */
internal fun downloadedPageRow(remote: Page, local: Page?): Page = remote.copy(
    positionX = local?.positionX ?: 0,
    positionY = local?.positionY ?: 0,
    sheet = downloadedSheet(remote.sheet, local?.sheet),
)

/** The row to store for a downloaded [remote] manifest, given this device's [local] row. */
internal fun downloadedNotebookRow(remote: Notebook, local: Notebook?): Notebook = remote.copy(
    openPageId = local?.openPageId,
    defaultSheet = downloadedSheet(remote.defaultSheet, local?.defaultSheet),
)

/**
 * Whether two manifests disagree on any authoritative notebook field that cannot be merged page by
 * page — every serialized field except page *content* (which merges per page) and `updatedAt`, the
 * change clock the tolerance window already reasons about rather than a value to conflict on.
 * Device-local navigation state (`openPageId`) is not serialized at all, so it never enters here.
 * `defaultSheet` is left out too: it is fixed once created and a download never clears it
 * ([downloadedSheet]), so a difference only means one side came from an app version without sheets.
 *
 * A pure download would overwrite the local side of any of the compared fields, so a divergence
 * here is surfaced as a whole-notebook conflict rather than silently resolved.
 */
internal fun structurallyDiverges(local: Notebook, remote: Notebook): Boolean =
    local.pageIds != remote.pageIds ||
        local.title != remote.title ||
        local.parentFolderId != remote.parentFolderId ||
        local.defaultBackground != remote.defaultBackground ||
        local.defaultBackgroundType != remote.defaultBackgroundType ||
        local.linkedExternalUri != remote.linkedExternalUri ||
        local.createdAt != remote.createdAt
