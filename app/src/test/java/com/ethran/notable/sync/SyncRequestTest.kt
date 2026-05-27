package com.ethran.notable.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncRequestTest {

    @Test
    fun typeKey_matches_declared_constants_for_every_variant() {
        assertEquals(SyncRequest.TYPE_SYNC_ALL, SyncRequest.SyncAll.typeKey)
        assertEquals(SyncRequest.TYPE_FORCE_UPLOAD, SyncRequest.ForceUpload.typeKey)
        assertEquals(SyncRequest.TYPE_FORCE_DOWNLOAD, SyncRequest.ForceDownload.typeKey)
        assertEquals(
            SyncRequest.TYPE_UPLOAD_DELETION,
            SyncRequest.UploadDeletion("nb-1").typeKey
        )
        assertEquals(
            SyncRequest.TYPE_SYNC_NOTEBOOK,
            SyncRequest.SyncNotebook("nb-1").typeKey
        )
        assertEquals(
            SyncRequest.TYPE_SYNC_FROM_PAGE_ID,
            SyncRequest.SyncFromPageId("page-1").typeKey
        )
    }

    @Test
    fun identifier_disambiguates_parameterised_requests() {
        assertEquals("notebookId:nb-1", SyncRequest.UploadDeletion("nb-1").identifier)
        assertEquals("notebookId:nb-2", SyncRequest.SyncNotebook("nb-2").identifier)
        assertEquals("pageId:page-9", SyncRequest.SyncFromPageId("page-9").identifier)
    }

    @Test
    fun identifier_falls_back_to_default_for_parameterless_requests() {
        assertEquals("default", SyncRequest.SyncAll.identifier)
        assertEquals("default", SyncRequest.ForceUpload.identifier)
        assertEquals("default", SyncRequest.ForceDownload.identifier)
    }

    @Test
    fun two_requests_targeting_the_same_notebook_share_an_identifier() {
        // Same identifier across UploadDeletion/SyncNotebook is intentional — both
        // are scoped to a notebookId. Variants are disambiguated by typeKey, not identifier.
        val a = SyncRequest.UploadDeletion("nb-1")
        val b = SyncRequest.SyncNotebook("nb-1")
        assertEquals(a.identifier, b.identifier)
    }
}
