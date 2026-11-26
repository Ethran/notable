package com.ethran.notable.data

import android.content.Intent
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.db.Page

/** The editor route that is safe to restore during a fresh app launch. */
data class LastOpenedPageTarget(val pageId: String, val notebookId: String?)

/** Null when the setting is off or the page no longer exists. */
fun resolveLastOpenedPage(settings: AppSettings, page: Page?): LastOpenedPageTarget? {
    if (!settings.reopenLastNoteOnLaunch || settings.lastOpenedPageId.isNullOrBlank()) return null
    return page?.let { LastOpenedPageTarget(it.id, it.notebookId) }
}

/** Restores the saved page, clearing the saved id when the page is gone. */
suspend fun restoreLastOpenedPage(
    settings: AppSettings,
    getPage: suspend (String) -> Page?,
    clearSavedId: suspend () -> Unit,
): LastOpenedPageTarget? {
    if (!settings.reopenLastNoteOnLaunch) return null
    val pageId = settings.lastOpenedPageId ?: return null
    val target = resolveLastOpenedPage(settings, getPage(pageId))
    if (target == null) clearSavedId()
    return target
}

/** A launch intent that asks to open a link; one replayed from recents does not. */
fun carriesLinkToOpen(action: String?, data: String?, flags: Int): Boolean =
    action == Intent.ACTION_VIEW && data != null &&
        flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0

/** Only a fresh launch without a link reopens; a recreated activity or a link launch must not. */
fun shouldReopenOnLaunch(hasSavedState: Boolean, carriesLink: Boolean): Boolean =
    !hasSavedState && !carriesLink

/** Records [pageId] only while reopening is on. */
fun AppSettings.withLastOpenedPage(pageId: String) =
    if (reopenLastNoteOnLaunch) copy(lastOpenedPageId = pageId) else this

/** Turning the option off also forgets the saved page. */
fun AppSettings.withReopenLastNote(enabled: Boolean) =
    copy(reopenLastNoteOnLaunch = enabled, lastOpenedPageId = lastOpenedPageId.takeIf { enabled })
