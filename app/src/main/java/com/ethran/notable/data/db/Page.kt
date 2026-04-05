package com.ethran.notable.data.db

import androidx.lifecycle.LiveData
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.ethran.notable.data.model.BackgroundType
import java.util.Date
import java.util.UUID
import javax.inject.Inject

@Entity(
    foreignKeys = [ForeignKey(
        entity = Folder::class,
        parentColumns = arrayOf("id"),
        childColumns = arrayOf("parentFolderId"),
        onDelete = ForeignKey.CASCADE
    ), ForeignKey(
        entity = Notebook::class,
        parentColumns = arrayOf("id"),
        childColumns = arrayOf("notebookId"),
        onDelete = ForeignKey.CASCADE
    )]
)
data class Page(
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val scroll: Int = 0,
    @ColumnInfo(index = true) val notebookId: String? = null,
    @ColumnInfo(defaultValue = "blank") val background: String = "blank", // path or native subtype
    @ColumnInfo(defaultValue = "native") val backgroundType: String = "native", // image, imageRepeating, coverImage, native
    @ColumnInfo(index = true) val parentFolderId: String? = null,
    val createdAt: Date = Date(), val updatedAt: Date = Date()
)

data class PageWithData(
    @Embedded val page: Page,
    @Relation(
        parentColumn = "id",
        entityColumn = "pageId",
        entity = Stroke::class
    ) val strokes: List<Stroke>,
    @Relation(
        parentColumn = "id",
        entityColumn = "pageId",
        entity = Image::class
    ) val images: List<Image>
)


// DAO
@Dao
interface PageDao {
    @Query("SELECT * FROM page WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<Page>

    @Query("SELECT * FROM page WHERE id = (:pageId)")
    suspend fun getById(pageId: String): Page?

    @Transaction
    @Query("SELECT * FROM page WHERE id =:pageId")
    suspend fun getPageWithDataById(pageId: String): PageWithData

    @Query("UPDATE page SET scroll=:scroll WHERE id =:pageId")
    suspend fun updateScroll(pageId: String, scroll: Int)

    @Query("SELECT * FROM page WHERE notebookId is null AND parentFolderId is :folderId")
    fun getSinglePagesInFolder(folderId: String? = null): LiveData<List<Page>>

    @Insert
    suspend fun create(page: Page): Long

    @Update
    suspend fun update(page: Page)

    @Query("DELETE FROM page WHERE id = :pageId")
    suspend fun delete(pageId: String)
}

class PageRepository @Inject constructor(
    private val db: PageDao
) {
    suspend fun create(page: Page): Long {
        return db.create(page)
    }

    suspend fun updateScroll(id: String, scroll: Int) {
        return db.updateScroll(id, scroll)
    }

    suspend fun getById(pageId: String): Page? {
        return db.getById(pageId)
    }

    suspend fun getByIds(ids: List<String>): List<Page> {
        return db.getByIds(ids)
    }
    suspend fun getWithDataById(pageId: String): PageWithData {
        return db.getPageWithDataById(pageId)
    }


    fun getSinglePagesInFolder(folderId: String? = null): LiveData<List<Page>> {
        return db.getSinglePagesInFolder(folderId)
    }

    suspend fun update(page: Page) {
        return db.update(page)
    }

    suspend fun delete(pageId: String) {
        return db.delete(pageId)
    }


}

fun Page.getBackgroundType(): BackgroundType {
    return BackgroundType.fromKey(backgroundType)
}

// TODO: make it better
suspend fun Page.getParentFolder(bookRepository: BookRepository): String? {
    return if (notebookId != null) {
        val notebook = bookRepository.getById(notebookId)
        notebook?.parentFolderId
    } else {
        parentFolderId
    }
}
