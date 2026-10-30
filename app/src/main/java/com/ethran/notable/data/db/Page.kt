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
import com.ethran.notable.data.datastore.PageMode
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.utils.logCallStack
import io.shipbook.shipbooksdk.Log
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
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    // Where the page was last shown, in page coordinates, one column per axis. An axis the reader
    // is free to move is worth saving; one the fit owns is re-derived on showing (PageView
    // .viewportToShow), so its column only ever holds what the fit asked for.
    val scrollY: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val scrollX: Int = 0,
    @ColumnInfo(index = true) val notebookId: String? = null,
    @ColumnInfo(defaultValue = "blank") val background: String = "blank", // path or native subtype
    @ColumnInfo(defaultValue = "native") val backgroundType: String = "native", // image, imageRepeating, coverImage, native
    val pageMode: PageMode? = null,
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
    suspend fun getPageWithDataById(pageId: String): PageWithData?

    // One statement for both axes: they are committed together as one viewport, and writing a pair
    // that did not change is cheaper than tracking which of the two did.
    @Query("UPDATE page SET scrollY=:scrollY, scrollX=:scrollX WHERE id =:pageId")
    suspend fun updateScroll(pageId: String, scrollY: Int, scrollX: Int)

    // Bump only the edit timestamp, without a read-modify-write of the whole row. updatedAt is
    // stored as epoch millis (Date <-> Long converter), so a Long here matches the column format.
    // It only moves forward: past a later time stamped meanwhile, such as one sync anchored an
    // upload to, so the page stays dirty for sync.
    @Query("UPDATE page SET updatedAt=MAX(:updatedAt, updatedAt + 1) WHERE id =:pageId")
    suspend fun touchUpdatedAt(pageId: String, updatedAt: Long)

    @Query("SELECT * FROM page WHERE notebookId is null AND parentFolderId is :folderId")
    fun getSinglePagesInFolder(folderId: String? = null): LiveData<List<Page>>

    @Query("SELECT id FROM page WHERE notebookId = :notebookId")
    suspend fun getPageIdsForNotebook(notebookId: String): List<String>

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

    suspend fun updateScroll(id: String, scrollY: Int, scrollX: Int) {
        return db.updateScroll(id, scrollY, scrollX)
    }

    /** Advance a page's edit timestamp — the per-page dirty signal for incremental sync. */
    suspend fun touchUpdatedAt(pageId: String, updatedAt: Long = System.currentTimeMillis()) {
        if (pageId.isEmpty()) return
        db.touchUpdatedAt(pageId, updatedAt)
    }

    suspend fun getById(pageId: String): Page? {
        if (pageId.isEmpty())
        {
            Log.e("PageRepository", "PageId is empty!!")
            logCallStack("PageRepository.getById")
            return null
        }
        val page = db.getById(pageId)
        if (page == null) {
            Log.w("PageRepository", "Page not found: $pageId")
        }
        return page
    }

    suspend fun getByIds(ids: List<String>): List<Page> {
        return db.getByIds(ids)
    }

    suspend fun getPageIdsForNotebook(notebookId: String): List<String> {
        return db.getPageIdsForNotebook(notebookId)
    }
    suspend fun getWithDataById(pageId: String): PageWithData? {
        val data = db.getPageWithDataById(pageId)
        if (data == null) {
            Log.w("PageRepository", "Page not found: $pageId")
            return null
        }
        // Normalize legacy raw-pressure rows on load; in-memory strokes are always [0,1].
        return data.copy(strokes = data.strokes.map { it.withNormalizedPressure() })
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
