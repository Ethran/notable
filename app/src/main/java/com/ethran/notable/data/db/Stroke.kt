package com.ethran.notable.data.db

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.room.*
import com.ethran.notable.editor.utils.Pen
import kotlinx.serialization.SerialName
import java.util.Date
import java.util.UUID

@kotlinx.serialization.Serializable
data class StrokePoint(
    val x: Float,                   // with scroll
    var y: Float,                   // with scroll
    val pressure: Float? = null,    // relative pressure values 1 to 4096, usually whole number
    val tiltX: Int? = null,         // tilt values in degrees, -90 to 90
    val tiltY: Int? = null,
    val dt: UShort? = null,         // delta time in milliseconds, from first point in stroke, not used yet.
    @SerialName("timestamp") private val legacyTimestamp: Long? = null,
    @SerialName("size") private val legacySize: Float? = null,
)

@Entity(
    foreignKeys = [ForeignKey(
        entity = Page::class,
        parentColumns = arrayOf("id"),
        childColumns = arrayOf("pageId"),
        onDelete = ForeignKey.CASCADE
    )]
)
data class Stroke(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val size: Float,
    val pen: Pen,
    @ColumnInfo(defaultValue = "0xFF000000")
    val color: Int = 0xFF000000.toInt(),
    @ColumnInfo(defaultValue = "4096")
    val maxPressure: Int = 4096,   // might be useful for synchronization between devices

    var top: Float,
    var bottom: Float,
    var left: Float,
    var right: Float,

    val points: List<StrokePoint>,

    @ColumnInfo(index = true)
    val pageId: String,

    val createdAt: Date = Date(),
    val updatedAt: Date = Date()
)

// DAO
@Dao
interface StrokeDao {
    @Insert
    fun create(stroke: Stroke): Long

    @Insert
    fun create(strokes: List<Stroke>)

    @Update
    fun update(stroke: Stroke)

    @Update
    fun update(strokes: List<Stroke>)

    @Query("DELETE FROM stroke WHERE id IN (:ids)")
    fun deleteAll(ids: List<String>)

    @Transaction
    @Query("SELECT * FROM stroke WHERE id =:strokeId")
    fun getById(strokeId: String): Stroke

}

class StrokeRepository(context: Context) {
    var db = AppDatabase.getDatabase(context).strokeDao()

    fun create(stroke: Stroke): Long {
        return db.create(stroke)
    }

    fun create(strokes: List<Stroke>) {
        return db.create(strokes)
    }

    fun update(stroke: Stroke) {
        return db.update(stroke)
    }

    fun update(strokes: List<Stroke>) {
        return db.update(strokes)
    }

    fun deleteAll(ids: List<String>) {
        return db.deleteAll(ids)
    }

    fun getStrokeWithPointsById(strokeId: String): Stroke {
        return db.getById(strokeId)
    }
}