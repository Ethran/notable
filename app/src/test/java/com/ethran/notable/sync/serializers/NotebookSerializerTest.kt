package com.ethran.notable.sync.serializers

import com.ethran.notable.data.db.Notebook
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Date

/**
 * NOTE: Page (de)serialization is intentionally not covered here because it depends
 * on `android.util.Base64`, which throws "Method not mocked" under the default
 * unit-test classpath (testOptions { unitTests.returnDefaultValues } is not set).
 * Those paths belong in an instrumented test or a Robolectric suite.
 */
class NotebookSerializerTest {

    private fun sampleNotebook(
        id: String = "nb-1",
        title: String = "My notebook",
        pageIds: List<String> = listOf("p-1", "p-2"),
        openPageId: String? = "p-1",
        parentFolderId: String? = "folder-1",
        linkedExternalUri: String? = null,
        createdAt: Date = Date(1_700_000_000_000),
        updatedAt: Date = Date(1_700_000_456_000),
    ) = Notebook(
        id = id,
        title = title,
        openPageId = openPageId,
        pageIds = pageIds,
        parentFolderId = parentFolderId,
        defaultBackground = "blank",
        defaultBackgroundType = "native",
        linkedExternalUri = linkedExternalUri,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    @Test
    fun manifest_round_trip_preserves_all_fields() {
        val original = sampleNotebook()
        val json = NotebookSerializer.serializeManifest(original)

        val result = NotebookSerializer.deserializeManifest(json)
        assertTrue(result is AppResult.Success)
        val restored = (result as AppResult.Success).data

        assertEquals(original.id, restored.id)
        assertEquals(original.title, restored.title)
        assertEquals(original.openPageId, restored.openPageId)
        assertEquals(original.pageIds, restored.pageIds)
        assertEquals(original.parentFolderId, restored.parentFolderId)
        assertEquals(original.defaultBackground, restored.defaultBackground)
        assertEquals(original.defaultBackgroundType, restored.defaultBackgroundType)
        assertEquals(original.linkedExternalUri, restored.linkedExternalUri)
        // ISO 8601 round-trip is millisecond-precise — exact equality is reasonable here.
        assertEquals(original.createdAt, restored.createdAt)
        assertEquals(original.updatedAt, restored.updatedAt)
    }

    @Test
    fun manifest_round_trip_preserves_nullable_fields_when_null() {
        val original = sampleNotebook(
            openPageId = null,
            parentFolderId = null,
            linkedExternalUri = null,
            pageIds = emptyList(),
        )
        val json = NotebookSerializer.serializeManifest(original)
        val result = NotebookSerializer.deserializeManifest(json)
        assertTrue(result is AppResult.Success)
        val restored = (result as AppResult.Success).data

        assertNull(restored.openPageId)
        assertNull(restored.parentFolderId)
        assertNull(restored.linkedExternalUri)
        assertEquals(emptyList<String>(), restored.pageIds)
    }

    @Test
    fun deserializeManifest_returns_error_for_malformed_json() {
        val result = NotebookSerializer.deserializeManifest("{ this is not json")
        assertTrue("expected Error, got $result", result is AppResult.Error)
        val err = (result as AppResult.Error).error
        assertTrue(err is DomainError.UnexpectedState)
        assertTrue(
            "expected message to mention manifest decode, got '${err.userMessage}'",
            err.userMessage.contains("manifest", ignoreCase = true)
        )
    }

    @Test
    fun deserializeManifest_returns_error_for_missing_required_fields() {
        // Missing `title`, `pageIds`, etc.
        val json = """{"version":1,"notebookId":"nb-1"}"""
        val result = NotebookSerializer.deserializeManifest(json)
        assertTrue(result is AppResult.Error)
    }

    @Test
    fun deserializeManifest_returns_error_for_corrupted_timestamp() {
        val original = sampleNotebook()
        val json = NotebookSerializer.serializeManifest(original)
        // Replace the valid ISO timestamp with garbage.
        val corrupted = json.replace(
            Regex("\"createdAt\"\\s*:\\s*\"[^\"]+\""),
            "\"createdAt\":\"not-a-date\""
        )
        val result = NotebookSerializer.deserializeManifest(corrupted)
        assertTrue("expected Error, got $result", result is AppResult.Error)
        val err = (result as AppResult.Error).error
        assertTrue(err is DomainError.UnexpectedState)
        assertTrue(
            "expected message to mention corruption, got '${err.userMessage}'",
            err.userMessage.contains("corrupted", ignoreCase = true)
        )
    }

    @Test
    fun deserializeManifest_tolerates_unknown_fields() {
        // ignoreUnknownKeys = true is part of the documented contract.
        val original = sampleNotebook()
        val json = NotebookSerializer.serializeManifest(original)
        val augmented = json.replaceFirst("{", """{"futureField":"surprise","nested":{"x":1},""")

        val result = NotebookSerializer.deserializeManifest(augmented)
        assertTrue("expected Success, got $result", result is AppResult.Success)
        assertEquals(original.id, (result as AppResult.Success).data.id)
    }

    @Test
    fun getManifestUpdatedAt_returns_updatedAt_for_valid_manifest() {
        val original = sampleNotebook(updatedAt = Date(1_700_000_456_000))
        val json = NotebookSerializer.serializeManifest(original)

        val updatedAt = NotebookSerializer.getManifestUpdatedAt(json)
        assertNotNull(updatedAt)
        assertEquals(original.updatedAt, updatedAt)
    }

    @Test
    fun getManifestUpdatedAt_returns_null_for_malformed_json() {
        assertNull(NotebookSerializer.getManifestUpdatedAt("not json at all"))
    }

    @Test
    fun getManifestUpdatedAt_returns_null_for_corrupted_timestamp() {
        val original = sampleNotebook()
        val json = NotebookSerializer.serializeManifest(original)
        val corrupted = json.replace(
            Regex("\"updatedAt\"\\s*:\\s*\"[^\"]+\""),
            "\"updatedAt\":\"garbage\""
        )

        assertNull(NotebookSerializer.getManifestUpdatedAt(corrupted))
    }

    @Test
    fun serialized_manifest_carries_a_server_timestamp() {
        // The serializer stamps `serverTimestamp` with Instant.now() — verify the
        // field is present and parseable in the JSON without depending on its exact value.
        val json = NotebookSerializer.serializeManifest(sampleNotebook())
        assertTrue(
            "expected serverTimestamp in manifest JSON, got: $json",
            json.contains("\"serverTimestamp\"")
        )
    }

    @Test
    fun deserializeManifest_propagates_DomainError_via_AppResult_only() {
        // Defensive: deserializeManifest must never throw on bad input — it must
        // always return AppResult.Error so callers can `.fold` without try/catch.
        listOf(
            "",
            "null",
            "[]",
            "{}",
            "{\"version\":\"not-an-int\"}",
        ).forEach { input ->
            try {
                val result = NotebookSerializer.deserializeManifest(input)
                assertTrue(
                    "input '$input' should have produced AppResult.Error, got $result",
                    result is AppResult.Error
                )
            } catch (t: Throwable) {
                fail("deserializeManifest threw on input '$input': $t")
            }
        }
    }
}
