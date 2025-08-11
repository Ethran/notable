package com.ethran.notable.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @Test
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

    @Test
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

        // 2. Reopen DB with version 31 to trigger migration
        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .build().openHelper.writableDatabase

        // 3. Verify renamed column exists with expected data
        val cursor = migratedDb.query("SELECT background FROM Page WHERE id = 'page1'")
        cursor.use {
            assertTrue(it.moveToFirst())
            val background = it.getString(0)
            assertEquals("grid", background)
        }
    }


}
