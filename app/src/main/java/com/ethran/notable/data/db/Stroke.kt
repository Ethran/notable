package com.ethran.notable.data.db

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.room.*
import com.ethran.notable.editor.utils.Pen
import kotlinx.serialization.SerialName
import java.util.Date
import java.util.UUID
import javax.inject.Inject

/**
 * The value of [Stroke.maxPressure] marking that the stroke's point pressures are
 * normalized to [0, 1]. Every in-memory stroke must be in this state; rows written by
 * older versions carry their raw digitizer scale (e.g. 4096) and are converted on load
 * via [withNormalizedPressure].
 */
const val MAX_PRESSURE_NORMALIZED = 1

@kotlinx.serialization.Serializable
data class StrokePoint(
    val x: Float,                   // with scroll
    var y: Float,                   // with scroll
    val pressure: Float? = null,    // normalized to [0, 1]; raw digitizer scale in legacy rows
    val tiltX: Int? = null,         // tilt values in degrees, -90 to 90
    val tiltY: Int? = null,
    val dt: UShort? = null,         // delta time in milliseconds, from first point in stroke, not used yet.
    @SerialName("timestamp") private val legacyTimestamp: Long? = null,
    @SerialName("size") private val legacySize: Float? = null,
)

/**
 * Returns this stroke with pressure normalized to [0, 1] and [Stroke.maxPressure] set to
 * [MAX_PRESSURE_NORMALIZED]. Already-normalized strokes are returned as-is, so this is a
 * cheap idempotent guard applied wherever strokes enter memory (DB load, sync import).
 */
fun Stroke.withNormalizedPressure(): Stroke {
    if (maxPressure == MAX_PRESSURE_NORMALIZED) return this
    val max = maxPressure.toFloat()
    if (max <= 0f) return copy(maxPressure = MAX_PRESSURE_NORMALIZED)
    return copy(
        points = points.map { p ->
            if (p.pressure == null) p
            else p.copy(pressure = (p.pressure / max).coerceIn(0f, 1f))
        },
        maxPressure = MAX_PRESSURE_NORMALIZED
    )
}

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
    // Denominator of the stored pressure values: MAX_PRESSURE_NORMALIZED (1) for rows
    // written with normalized [0,1] pressure; the capture device's raw max (e.g. 4096)
    // for legacy rows. See withNormalizedPressure().
    @ColumnInfo(defaultValue = "4096")
    val maxPressure: Int = 4096,

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
    suspend fun create(stroke: Stroke): Long

    @Insert
    suspend fun create(strokes: List<Stroke>)

    @Update
    suspend fun update(stroke: Stroke)

    @Update
    suspend fun update(strokes: List<Stroke>)

    @Query("DELETE FROM stroke WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<String>)

    @Transaction
    @Query("SELECT * FROM stroke WHERE id =:strokeId")
    suspend fun getById(strokeId: String): Stroke

}

class StrokeRepository @Inject constructor(
    private val db: StrokeDao
) {

    suspend fun create(stroke: Stroke): Long {
        return db.create(stroke)
    }

    suspend fun create(strokes: List<Stroke>) {
        return db.create(strokes)
    }

    suspend fun update(stroke: Stroke) {
        return db.update(stroke)
    }

    suspend fun update(strokes: List<Stroke>) {
        return db.update(strokes)
    }

    suspend fun deleteAll(ids: List<String>) {
        ids.chunked(900).forEach { batch ->
            db.deleteAll(batch)
        }
    }

    suspend fun getStrokeWithPointsById(strokeId: String): Stroke {
        return db.getById(strokeId).withNormalizedPressure()
    }
}
