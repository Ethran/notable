package com.ethran.notable.data.db

import com.ethran.notable.sync.SyncSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class ApplySyncSettingsUpdateTest {

    private val stored = SyncSettings(
        syncEnabled = true,
        serverUrl = "https://dav.example.com/notable",
        username = "user",
        password = "ENCRYPTED-BLOB",
        lastSyncTime = 1L,
    )

    @Test
    fun updates_other_fields_and_keeps_the_encrypted_password() {
        val result = applySyncSettingsUpdate(stored, null) { it.copy(lastSyncTime = 2L) }

        assertEquals(2L, result.lastSyncTime)
        assertEquals("ENCRYPTED-BLOB", result.password)
    }

    @Test
    fun a_blank_password_from_the_transform_does_not_overwrite_the_stored_one() {
        // What a failed decrypt looks like: getSyncSettings() hands out password = "".
        val decryptFailed = stored.copy(password = "", wifiOnly = true)

        val result = applySyncSettingsUpdate(stored, null) { decryptFailed }

        assertEquals(true, result.wifiOnly)
        assertEquals("ENCRYPTED-BLOB", result.password)
    }

    @Test
    fun a_new_password_replaces_the_stored_one() {
        val result = applySyncSettingsUpdate(stored, "NEW-BLOB") { it.copy(username = "other") }

        assertEquals("NEW-BLOB", result.password)
        assertEquals("other", result.username)
    }

    @Test
    fun the_transform_never_sees_the_stored_password() {
        var seen: String? = null
        applySyncSettingsUpdate(stored, null) { seen = it.password; it }

        assertEquals("", seen)
    }

    @Test
    fun nothing_stored_stays_empty() {
        val result = applySyncSettingsUpdate(SyncSettings(), null) { it.copy(serverUrl = "https://x") }

        assertEquals("", result.password)
        assertEquals("https://x", result.serverUrl)
    }
}
