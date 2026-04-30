package com.ethran.notable.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import java.util.Date
import java.util.UUID
import javax.inject.Inject


// Entity class for images
@Entity(
    foreignKeys = [ForeignKey(
        entity = Page::class,
        parentColumns = arrayOf("id"),
        childColumns = arrayOf("pageId"),
        onDelete = ForeignKey.CASCADE
    )]
)
data class Image(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    var x: Int = 0,
    var y: Int = 0,
    val height: Int,
    val width: Int,

    // use uri instead of bytearray
    //val bitmap: ByteArray,
    val uri: String? = null,

    @ColumnInfo(index = true)
    val pageId: String,

    val createdAt: Date = Date(),
    val updatedAt: Date = Date()
)

// DAO for image operations
@Dao
interface ImageDao {
    @Insert
    suspend fun create(image: Image): Long

    @Insert
    suspend fun create(images: List<Image>)

    @Update
    suspend fun update(image: Image)

    @Query("DELETE FROM Image WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<String>)

    @Transaction
    @Query("SELECT * FROM Image WHERE id = :imageId")
    suspend fun getById(imageId: String): Image
}

// Repository for image operations
class ImageRepository @Inject constructor(
    private val db: ImageDao
) {

    suspend fun create(image: Image): Long {
        return db.create(image)
    }

    suspend fun create(
        imageUri: String,
        //position on canvas
        x: Int,
        y: Int,
        pageId: String,
        //size on canvas
        width: Int,
        height: Int
    ): Long {
        // Prepare the Image object with specified placement
        val imageToSave = Image(
            x = x,
            y = y,
            width = width,
            height = height,
            uri = imageUri,
            pageId = pageId
        )

        // Save the image to the database
        return db.create(imageToSave)
    }

    suspend fun create(images: List<Image>) {
        db.create(images)
    }

    suspend fun update(image: Image) {
        db.update(image)
    }

    suspend fun deleteAll(ids: List<String>) {
        db.deleteAll(ids)
    }

    suspend fun getImageWithPointsById(imageId: String): Image {
        return db.getById(imageId)
    }
}