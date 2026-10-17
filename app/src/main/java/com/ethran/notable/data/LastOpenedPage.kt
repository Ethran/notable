package com.ethran.notable.data

import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.db.Page

/** The editor route that is safe to restore during a fresh app launch. */
data class LastOpenedPageTarget(val pageId: String, val notebookId: String?)

/** Null when the setting is off or the page no longer exists. */
fun resolveLastOpenedPage(settings: AppSettings, page: Page?): LastOpenedPageTarget? {
    if (!settings.reopenLastNoteOnLaunch || settings.lastOpenedPageId.isNullOrBlank()) return null
    return page?.let { LastOpenedPageTarget(it.id, it.notebookId) }
}
