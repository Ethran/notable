package com.ethran.notable.data.db

import com.ethran.notable.data.model.SheetSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class NotebookTest {

    @Test
    fun getPageIndex_returns_index_of_existing_page() {
        val page1 = UUID.randomUUID().toString()
        val page2 = UUID.randomUUID().toString()
        
        val notebook = Notebook(pageIds = listOf(page1, page2))

        assertEquals(0, notebook.getPageIndex(page1))
        assertEquals(1, notebook.getPageIndex(page2))
    }

    @Test
    fun getPageIndex_returns_minus_one_for_missing_page() {
        val notebook = Notebook(pageIds = listOf("page-1", "page-2"))

        assertEquals(-1, notebook.getPageIndex("page-3"))
    }

    @Test
    fun getPageIndex_returns_index_for_empty_string_pageId() {
        // Technically an edge case: empty string ID 
        val notebook = Notebook(pageIds = listOf("page-1", "", "page-2"))

        assertEquals(1, notebook.getPageIndex(""))
    }

    @Test
    fun getPageIndex_returns_minus_one_if_empty_string_not_in_list() {
        val notebook = Notebook(pageIds = listOf("page-1", "page-2"))

        assertEquals(-1, notebook.getPageIndex(""))
    }

    @Test
    fun newPage_takes_the_notebooks_default_sheet() {
        val sheet = SheetSize(1860, 2632)

        assertEquals(sheet, Notebook(defaultSheet = sheet).newPage().sheet)
        assertNull("a notebook of infinite pages makes infinite pages", Notebook().newPage().sheet)
    }
}
