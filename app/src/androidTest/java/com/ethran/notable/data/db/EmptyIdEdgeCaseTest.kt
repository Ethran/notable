package com.ethran.notable.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmptyIdEdgeCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var notebookDao: NotebookDao
    private lateinit var pageDao: PageDao

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        notebookDao = db.notebookDao()
        pageDao = db.pageDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test(timeout = 30000)
    fun insertAndRead_notebookWithEmptyPageIdList() = runBlocking {
        val notebook = Notebook(
            id = "notebook-1",
            pageIds = listOf("page-1", "", "page-2") // empty string as page id
        )
        
        notebookDao.create(notebook)
        
        val loaded = notebookDao.getById("notebook-1")
        assertNotNull(loaded)
        assertEquals(3, loaded!!.pageIds.size)
        assertEquals("", loaded.pageIds[1])
    }

    @Test(timeout = 30000)
    fun insertAndRead_pageWithEmptyId() = runBlocking {
        val emptyIdPage = Page(
            id = "", // Empty ID
            background = "blank",
            backgroundType = "native"
        )
        
        pageDao.create(emptyIdPage)
        
        val loaded = pageDao.getById("")
        assertNotNull("Should load page with empty string ID", loaded)
        assertEquals("", loaded!!.id)
    }
}

