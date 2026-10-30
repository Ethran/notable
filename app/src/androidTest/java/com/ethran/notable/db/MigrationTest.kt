package com.ethran.notable.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ethran.notable.data.db.AppDatabase
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @Test(timeout = 10000)
    fun simpleTest() {
        assertTrue(true)
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        listOf(), // Add AutoMigrationSpecs here if any
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate30To31_autoMigration() {
        val dbName = "migration-test"

        // 1. Create DB with version 30 schema
        val db = helper.createDatabase(dbName, 30)

// Insert required parent data first
        db.execSQL(
            """
    INSERT INTO Notebook (
        id,
        title,
        openPageId,
        pageIds,
        parentFolderId,
        defaultNativeTemplate,
        createdAt,
        updatedAt
    ) VALUES (
        'notebook1',
        'Test Notebook',
        NULL,
        '[]',
        NULL,
        'blank',
        1620000000000,
        1620000000000
    )
    """.trimIndent()
        )


        db.execSQL(
            """
    INSERT INTO Folder (id, title, createdAt, updatedAt)
    VALUES ('TEST_FOLDER_ID', 'Test Folder', 1620000000, 1620000000)
    """.trimIndent()
        )

        // Insert with column name from version 30: 'nativeTemplate'
        db.execSQL(
            """
    INSERT INTO Page (
        id,
        notebookId,
        nativeTemplate,
        parentFolderId,
        scroll,
        createdAt,
        updatedAt
    ) VALUES (
        'page1',
        'notebook1',
        'grid',
        'TEST_FOLDER_ID',
        0.0,
        1620000000,
        1620000000
    )
    """.trimIndent()
        )


        db.close()

        // 2. Reopen DB with version 31 (latest AppDatabase version) to trigger migration
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        val migratedDb = roomDb.openHelper.writableDatabase

        // 3. Verify renamed column exists with expected data
        val cursor = migratedDb.query("SELECT background FROM Page WHERE id = 'page1'")
        cursor.use {
            assertTrue(it.moveToFirst())
            val background = it.getString(it.getColumnIndexOrThrow("background"))
            assertEquals("grid", background)
        }

        roomDb.close()
    }

    /**
     * 35 -> 36 does two things: adds the `page_sync_state` table, and renames
     * `notebook_sync_state.localUpdatedAtAtSync` to `syncedLocalUpdatedAt`.
     *
     * The rename must **carry the value over** — Room recreates the table and copies rows, and a
     * silently dropped anchor would reset every notebook to "dirty" and re-upload the whole library
     * on the next sync. So this asserts the stored anchor, not just the column name.
     */
    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate35To36_addsPageSyncStateAndRenamesSyncAnchor() {
        val dbName = "migration-test-36"

        // 1. Create the v35 schema (page_sync_state does not exist yet) and seed a sync-state row
        //    using the pre-rename column name.
        val oldDb = helper.createDatabase(dbName, 35)
        oldDb.execSQL(
            """
            INSERT INTO notebook_sync_state
                (notebookId, state, lastSyncedAt, localUpdatedAtAtSync, remoteEtag, remoteUpdatedAt, lastError)
            VALUES ('notebook1', 'SYNCED', 1620000000001, 1620000000000, '"nb-etag"', 1620000000002, NULL)
            """.trimIndent()
        )
        oldDb.close()

        // 2. Reopen at the latest version to trigger the 35 -> 36 auto-migration.
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        val migratedDb = roomDb.openHelper.writableDatabase

        // 3. The renamed anchor is readable under the new name, with its original value.
        migratedDb.query(
            "SELECT syncedLocalUpdatedAt, lastSyncedAt, remoteEtag FROM notebook_sync_state WHERE notebookId = 'notebook1'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(1620000000000L, it.getLong(it.getColumnIndexOrThrow("syncedLocalUpdatedAt")))
            // lastSyncedAt is a separate column and must not be confused with the anchor.
            assertEquals(1620000000001L, it.getLong(it.getColumnIndexOrThrow("lastSyncedAt")))
            assertEquals("\"nb-etag\"", it.getString(it.getColumnIndexOrThrow("remoteEtag")))
        }

        // 4. The new table exists and accepts a row with the expected columns.
        migratedDb.execSQL(
            """
            INSERT INTO page_sync_state (pageId, notebookId, remoteEtag, syncedLocalUpdatedAt, lastSyncedAt)
            VALUES ('page1', 'notebook1', '"etag-1"', 1620000000010, 1620000000011)
            """.trimIndent()
        )
        migratedDb.query(
            "SELECT remoteEtag, syncedLocalUpdatedAt FROM page_sync_state WHERE pageId = 'page1'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("\"etag-1\"", it.getString(it.getColumnIndexOrThrow("remoteEtag")))
            assertEquals(1620000000010L, it.getLong(it.getColumnIndexOrThrow("syncedLocalUpdatedAt")))
        }

        roomDb.close()
    }

    /**
     * 36 -> 37 adds the two nullable bulk-detection baseline columns
     * (`remoteDirEtag`, `remoteDirServerKey`) to `notebook_sync_state`. It is purely additive: existing
     * rows must be preserved untouched and the new columns must initialize to NULL, so a device that
     * upgrades mid-life keeps every notebook's sync anchor and simply has no directory baseline yet.
     */
    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate36To37_addsDirBaselineColumnsAsNull() {
        val dbName = "migration-test-37"

        // 1. Create the v36 schema and seed a synced row (no baseline columns exist yet).
        val oldDb = helper.createDatabase(dbName, 36)
        oldDb.execSQL(
            """
            INSERT INTO notebook_sync_state
                (notebookId, state, lastSyncedAt, syncedLocalUpdatedAt, remoteEtag, remoteUpdatedAt, lastError)
            VALUES ('notebook1', 'SYNCED', 1620000000001, 1620000000000, '"nb-etag"', 1620000000002, NULL)
            """.trimIndent()
        )
        oldDb.close()

        // 2. Reopen at the latest version to trigger the 36 -> 37 auto-migration.
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        val migratedDb = roomDb.openHelper.writableDatabase

        // 3. The existing row survives and the two new columns are present and NULL.
        migratedDb.query(
            "SELECT remoteEtag, remoteDirEtag, remoteDirServerKey FROM notebook_sync_state WHERE notebookId = 'notebook1'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("\"nb-etag\"", it.getString(it.getColumnIndexOrThrow("remoteEtag")))
            assertTrue(it.isNull(it.getColumnIndexOrThrow("remoteDirEtag")))
            assertTrue(it.isNull(it.getColumnIndexOrThrow("remoteDirServerKey")))
        }

        // 4. The new columns are writable.
        migratedDb.execSQL(
            "UPDATE notebook_sync_state SET remoteDirEtag = '\"dir-etag\"', remoteDirServerKey = 'srv' WHERE notebookId = 'notebook1'"
        )
        migratedDb.query(
            "SELECT remoteDirEtag, remoteDirServerKey FROM notebook_sync_state WHERE notebookId = 'notebook1'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("\"dir-etag\"", it.getString(it.getColumnIndexOrThrow("remoteDirEtag")))
            assertEquals("srv", it.getString(it.getColumnIndexOrThrow("remoteDirServerKey")))
        }

        roomDb.close()
    }

    /**
     * 37 -> 38 splits the page scroll in two: `scroll` becomes `scrollY`, and `scrollX` is added.
     *
     * `scroll` only ever held the vertical scroll, so the rename has to **carry the value over** — a
     * reader who had scrolled 750 down a page must not land back at its top after upgrading. The new
     * axis starts at 0, which is the horizontal position every page had on upgrade anyway.
     */
    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate37To38_renamesScrollToScrollY_andAddsScrollXAtZero() {
        val dbName = "migration-test-38"

        // 1. Create the v37 schema and seed a page scrolled down it, using the old column name.
        val oldDb = helper.createDatabase(dbName, 37)
        oldDb.execSQL(
            """
            INSERT INTO Page (id, scroll, background, backgroundType, createdAt, updatedAt)
            VALUES ('page1', 750, 'blank', 'native', 1620000000000, 1620000000000)
            """.trimIndent()
        )
        oldDb.close()

        // 2. Reopen at the latest version to trigger the 37 -> 38 auto-migration.
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        val migratedDb = roomDb.openHelper.writableDatabase

        // 3. The saved scroll is readable under the new name, with its value, and the new axis is 0.
        migratedDb.query(
            "SELECT scrollY, scrollX FROM Page WHERE id = 'page1'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(750, it.getInt(it.getColumnIndexOrThrow("scrollY")))
            assertEquals(0, it.getInt(it.getColumnIndexOrThrow("scrollX")))
        }

        // 4. The new column is writable, and writing both axes leaves the row's own scroll intact.
        migratedDb.execSQL("UPDATE Page SET scrollY = 800, scrollX = 120 WHERE id = 'page1'")
        migratedDb.query("SELECT scrollY, scrollX FROM Page WHERE id = 'page1'").use {
            assertTrue(it.moveToFirst())
            assertEquals(800, it.getInt(it.getColumnIndexOrThrow("scrollY")))
            assertEquals(120, it.getInt(it.getColumnIndexOrThrow("scrollX")))
        }

        roomDb.close()
    }
}
