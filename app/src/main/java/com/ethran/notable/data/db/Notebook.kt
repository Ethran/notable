package com.ethran.notable.data.db

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.ethran.notable.TAG
import com.ethran.notable.data.model.BackgroundType
import io.shipbook.shipbooksdk.Log
import java.util.Date
import java.util.UUID

@Entity(
    foreignKeys = [ForeignKey(
        entity = Folder::class,
        parentColumns = arrayOf("id"),
        childColumns = arrayOf("parentFolderId"),
        onDelete = ForeignKey.CASCADE
    )]
)
data class Notebook(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New notebook",
    val openPageId: String? = null,
    val pageIds: List<String> = listOf(),

    @ColumnInfo(index = true)
    val parentFolderId: String? = null,

    @ColumnInfo(defaultValue = "blank")
    val defaultBackground: String = "blank",
    @ColumnInfo(defaultValue = "native")
    val defaultBackgroundType: String = "native",

    // File that its linked to:
    val linkedExternalUri: String? = null,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date()
)

// DAO
@Dao
interface NotebookDao {
    @Query("SELECT * FROM notebook WHERE parentFolderId is :folderId")
    fun getAllInFolder(folderId: String? = null): LiveData<List<Notebook>>

    @Query("SELECT * FROM notebook WHERE id = (:notebookId)")
    fun getByIdLive(notebookId: String): LiveData<Notebook>

    @Query("SELECT * FROM notebook WHERE id = (:notebookId)")
    fun getById(notebookId: String): Notebook?

    @Query("UPDATE notebook SET openPageId=:pageId WHERE id=:notebookId")
    fun setOpenPageId(notebookId: String, pageId: String)

    @Query("UPDATE notebook SET pageIds=:pageIds WHERE id=:id")
    fun setPageIds(id: String, pageIds: List<String>)

    @Insert
    fun create(notebook: Notebook): Long

    @Update
    fun update(notebook: Notebook)

    @Query("DELETE FROM notebook WHERE id=:id")
    fun delete(id: String)
}

class BookRepository(context: Context) {
    var db = AppDatabase.getDatabase(context).notebookDao()
    private var pageDb = AppDatabase.getDatabase(context).pageDao()

    fun create(notebook: Notebook) {
        db.create(notebook)
        val page = Page(
            notebookId = notebook.id,
            background = notebook.defaultBackground,
            backgroundType = notebook.defaultBackgroundType
        )
        pageDb.create(page)

        db.setPageIds(notebook.id, listOf(page.id))
        db.setOpenPageId(notebook.id, page.id)
    }

    fun createEmpty(notebook: Notebook) {
        db.create(notebook)
    }

    fun update(notebook: Notebook) {
        Log.i(TAG, "updating DB")
        val updatedNotebook = notebook.copy(updatedAt = Date())
        db.update(updatedNotebook)
    }

    fun getAllInFolder(folderId: String? = null): LiveData<List<Notebook>> {
        return db.getAllInFolder(folderId)
    }

    fun getById(notebookId: String): Notebook? {
        return db.getById(notebookId)
    }

    fun getByIdLive(notebookId: String): LiveData<Notebook> {
        return db.getByIdLive(notebookId)
    }

    fun setOpenPageId(id: String, pageId: String) {
        db.setOpenPageId(id, pageId)
    }

    fun addPage(id: String, pageId: String, index: Int? = null) {
        val pageIds = (db.getById(id) ?: return).pageIds.toMutableList()
        if (index != null) pageIds.add(index, pageId)
        else pageIds.add(pageId)
        db.setPageIds(id, pageIds)
    }

    fun removePage(id: String, pageId: String) {
        val notebook = db.getById(id) ?: return
        val updatedNotebook = notebook.copy(
            // remove the page
            pageIds = notebook.pageIds.filterNot { it == pageId },
            // remove the "open page" if it's the one
            openPageId = if (notebook.openPageId == pageId) null else notebook.openPageId
        )
        db.update(updatedNotebook)
        Log.i(TAG, "Cleaned $id $pageId")
    }

    fun changePageIndex(id: String, pageId: String, index: Int) {
        val pageIds = (db.getById(id) ?: return).pageIds.toMutableList()
        var correctedIndex = index
        if (correctedIndex < 0) correctedIndex = 0
        if (correctedIndex > pageIds.size - 1) correctedIndex = pageIds.size - 1

        pageIds.remove(pageId)
        pageIds.add(correctedIndex, pageId)
        db.setPageIds(id, pageIds)
    }

    fun getPageIndex(id: String, pageId: String): Int? {
        val pageIds = (db.getById(id) ?: return null).pageIds
        val index = pageIds.indexOf(pageId)
        return if (index != -1) index else null
    }

    fun getPageAtIndex(id: String, index: Int): String? {
        val pageIds = (db.getById(id) ?: return null).pageIds
        if (index < 0 || index > pageIds.size - 1) return null
        return pageIds[index]
    }

    fun delete(id: String) {
        db.delete(id)
    }

}


fun Notebook.getBackgroundType(): BackgroundType {
    return BackgroundType.fromKey(defaultBackgroundType)
}

fun Notebook.newPage(): Page {
    return Page(
        notebookId = id,
        background = defaultBackground,
        backgroundType = defaultBackgroundType
    )
}