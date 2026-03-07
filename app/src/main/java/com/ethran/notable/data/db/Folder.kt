package com.ethran.notable.data.db

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.distinctUntilChanged
import androidx.lifecycle.map
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import io.shipbook.shipbooksdk.ShipBook
import java.util.Date
import java.util.UUID
import javax.inject.Inject

@Entity(
    foreignKeys = [ForeignKey(
        entity = Folder::class,
        parentColumns = arrayOf("id"),
        childColumns = arrayOf("parentFolderId"),
        onDelete = ForeignKey.CASCADE
    )]
)
data class Folder(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Folder",

    @ColumnInfo(index = true)
    val parentFolderId: String? = null,

    val createdAt: Date = Date(),
    val updatedAt: Date = Date()
)

// DAO
@Dao
interface FolderDao {
    @Query("SELECT * FROM folder WHERE parentFolderId IS :folderId")
    fun getChildrenFolders(folderId: String?): LiveData<List<Folder>>

    @Query("SELECT * FROM folder WHERE id IS :folderId")
    suspend fun get(folderId: String): Folder?

    @Query("SELECT * FROM folder WHERE id IS :folderId")
    fun getLive(folderId: String): LiveData<Folder?>


    @Insert
    suspend fun create(folder: Folder): Long

    @Update
    suspend fun update(folder: Folder)

    @Query("DELETE FROM folder WHERE id=:id")
    suspend fun delete(id: String)
}

class FolderRepository @Inject constructor(
    private val db: FolderDao
) {
    private val log = ShipBook.getLogger("FolderRepository")
    private val nullLiveData = MutableLiveData<String?>(null)

    suspend fun create(folder: Folder) {
        db.create(folder)
    }

    suspend fun update(folder: Folder) {
        db.update(folder)
    }

    fun getAllInFolder(folderId: String? = null): LiveData<List<Folder>> {
        return db.getChildrenFolders(folderId)
    }

    suspend fun getParent(folderId: String? = null): String? {
        if (folderId == null)
            return null
        val folder = db.get(folderId)
        return folder?.parentFolderId
    }

    fun getParentLive(folderId: String? = null): LiveData<String?> {
        if (folderId == null) {
            log.w("getParentLive called with null folderId")
            return nullLiveData
        }
        return db.getLive(folderId)
            .map { it?.parentFolderId }
            .distinctUntilChanged()
    }

    suspend fun get(folderId: String): Folder? {
        val folder = db.get(folderId)
        if (folder == null) log.e("Folder not found: $folderId")
        return folder
    }

    suspend fun getWithChildren(folderId: String): Folder? {
        return db.get(folderId)
    }


    suspend fun delete(id: String) {
        db.delete(id)
    }

}