package com.ethran.notable.editor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.testing.TestDatabaseFactory
import com.ethran.notable.testing.TestNotebookSeeder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EditorSeedingTests {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = TestDatabaseFactory.createInMemory(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test(timeout = 60000)
    fun seededNotebook_hasNonBlankPages() {
        runBlocking {
            withTimeout(30_000) {
                val seeded = TestNotebookSeeder.seedNotebook(db, pageCount = 3, strokesPerPage = 10)
                val pages = db.pageDao().getByIds(seeded.pageIds)
                assertTrue(pages.size == 3)

                val firstPageWithData = db.pageDao().getPageWithDataById(seeded.pageIds.first())
                requireNotNull(firstPageWithData)
                assertTrue(firstPageWithData.strokes.isNotEmpty())
            }
        }
    }
}
