package com.ethran.notable.sync.serializers

import com.ethran.notable.data.db.Folder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Date

class FolderSerializerTest {

    @Test
    fun serialize_and_deserialize_preserves_folder_fields() {
        val createdAt = Date(1_700_000_000_000)
        val updatedAt = Date(1_700_000_123_000)
        val folders = listOf(
            Folder(
                id = "f-root",
                title = "Root",
                parentFolderId = null,
                createdAt = createdAt,
                updatedAt = updatedAt
            ),
            Folder(
                id = "f-child",
                title = "Child",
                parentFolderId = "f-root",
                createdAt = createdAt,
                updatedAt = updatedAt
            )
        )

        val json = FolderSerializer.serializeFolders(folders)
        val restored = FolderSerializer.deserializeFolders(json)

        assertEquals(2, restored.size)
        assertEquals("f-root", restored[0].id)
        assertEquals("Root", restored[0].title)
        assertEquals(null, restored[0].parentFolderId)

        assertEquals("f-child", restored[1].id)
        assertEquals("Child", restored[1].title)
        assertEquals("f-root", restored[1].parentFolderId)
    }

    @Test
    fun deserialize_ignores_unknown_fields_in_json() {
        val json = """
            {
              "version": 1,
              "folders": [
                {
                  "id": "f-1",
                  "title": "Folder 1",
                  "parentFolderId": null,
                  "createdAt": "2024-01-01T10:00:00Z",
                  "updatedAt": "2024-01-01T10:00:00Z",
                  "extra": "ignored"
                }
              ],
              "serverTimestamp": "2024-01-01T10:00:00Z",
              "rootExtra": "ignored"
            }
        """.trimIndent()

        val restored = FolderSerializer.deserializeFolders(json)

        assertEquals(1, restored.size)
        assertEquals("f-1", restored.first().id)
        assertEquals("Folder 1", restored.first().title)
    }

    @Test
    fun getServerTimestamp_returns_value_for_valid_json() {
        val json = FolderSerializer.serializeFolders(emptyList())

        val timestamp = FolderSerializer.getServerTimestamp(json)

        assertNotNull(timestamp)
    }
}

