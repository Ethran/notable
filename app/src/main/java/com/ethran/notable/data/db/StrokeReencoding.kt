package com.ethran.notable.data.db

import android.content.Context
import android.database.sqlite.SQLiteBlobTooBigException
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.views.hasFilePermission
import com.onyx.android.sdk.api.device.epd.EpdController
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.serialization.json.Json

private val log = ShipBook.getLogger("StrokeReencode")

/**
 * Runtime backfill:
 *  - Reads legacy rows from stroke_old (JSON points)
 *  - Re-encodes to binary (SB1) and inserts into stroke
 *  - Deletes migrated rows
 *  - Drops stroke_old when empty
 *
 * Idempotent: safe to call multiple times; exits early if stroke_old missing or already empty.
 */
fun reencodeStrokePointsToSB1(appContext: Context) {
    if (!hasFilePermission(appContext)) {
        SnackState.globalSnackFlow.tryEmit(
            SnackConf(
                id = "FilePermissions",
                text = "No file permissions! Please grant file permissions and restart the app",
                duration = 10000
            )
        )
        log.e("No file permission!!!")
        return
    }
    val db = AppDatabase.getDatabase(appContext).openHelper.writableDatabase
    if (!tableExists(db, "stroke_old")) return

    val totalInitial = countRemaining(db, "stroke_old")
    if (totalInitial == 0) {
        // Nothing left; drop the table defensively.
        db.execSQL("DROP TABLE IF EXISTS stroke_old")
        return
    }

    var batchSize = 1500
    val progressSnackId = "migration_progress"
    val maxPressure = EpdController.getMaxTouchPressure().toLong()

    while (true) {
        val remaining = countRemaining(db, "stroke_old")
        if (remaining == 0) {
            // Finished
            db.execSQL("DROP TABLE IF EXISTS stroke_old")
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(
                    id = progressSnackId,
                    text = "Stroke migration complete.",
                    duration = 3000
                )
            )
            break
        }
        SnackState.cancelGlobalSnack.tryEmit(progressSnackId)
        val percent = (100.0 * (totalInitial - remaining).toFloat() / totalInitial.toFloat())
        SnackState.globalSnackFlow.tryEmit(
            SnackConf(
                id = progressSnackId,
                text = "Migrating strokes: ${"%.1f".format(percent)}% (${totalInitial - remaining}/$totalInitial) batch=$batchSize",
                duration = null
            )
        )

        // Select a batch deterministically (ORDER BY rowid) to avoid potential starvation
        val cursor = db.query(
            "SELECT id,size,pen,color,top,bottom,left,right,points,pageId,createdAt,updatedAt " +
                    "FROM stroke_old ORDER BY rowid LIMIT $batchSize"
        )

        if (!cursor.moveToFirst()) {
            cursor.close()
            continue
        }

        db.beginTransaction()
        try {
            val idIdx = cursor.getColumnIndexOrThrow("id")
            val sizeIdx = cursor.getColumnIndexOrThrow("size")
            val penIdx = cursor.getColumnIndexOrThrow("pen")
            val colorIdx = cursor.getColumnIndexOrThrow("color")
            val topIdx = cursor.getColumnIndexOrThrow("top")
            val bottomIdx = cursor.getColumnIndexOrThrow("bottom")
            val leftIdx = cursor.getColumnIndexOrThrow("left")
            val rightIdx = cursor.getColumnIndexOrThrow("right")
            val pointsIdx = cursor.getColumnIndexOrThrow("points")
            val pageIdIdx = cursor.getColumnIndexOrThrow("pageId")
            val createdIdx = cursor.getColumnIndexOrThrow("createdAt")
            val updatedIdx = cursor.getColumnIndexOrThrow("updatedAt")

            val insertStmt = db.compileStatement(
                """
                INSERT OR IGNORE INTO stroke
                (id,size,pen,color,maxPressure,top,bottom,left,right,points,pageId,createdAt,updatedAt)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent()
            )
            val deleteStmt = db.compileStatement("DELETE FROM stroke_old WHERE id=?")

            do {
                val id = cursor.getString(idIdx)
                val size = cursor.getDouble(sizeIdx)
                val pen = cursor.getString(penIdx)
                val color = cursor.getInt(colorIdx)
                val top = cursor.getDouble(topIdx)
                val bottom = cursor.getDouble(bottomIdx)
                val left = cursor.getDouble(leftIdx)
                val right = cursor.getDouble(rightIdx)
                val pointsJson = cursor.getString(pointsIdx) ?: "[]"
                val pageId = cursor.getString(pageIdIdx)
                val createdAt = cursor.getLong(createdIdx)
                val updatedAt = cursor.getLong(updatedIdx)

                try {
                    val pointsList = Json.decodeFromString<List<StrokePoint>>(pointsJson)
                    val mask = computeStrokeMask(pointsList)
                    val blob = encodeStrokePoints(pointsList, mask)

                    insertStmt.clearBindings()
                    insertStmt.bindString(1, id)
                    insertStmt.bindDouble(2, size)
                    insertStmt.bindString(3, pen)
                    insertStmt.bindLong(4, color.toLong())
                    insertStmt.bindLong(5, maxPressure)
                    insertStmt.bindDouble(6, top)
                    insertStmt.bindDouble(7, bottom)
                    insertStmt.bindDouble(8, left)
                    insertStmt.bindDouble(9, right)
                    insertStmt.bindBlob(10, blob)
                    insertStmt.bindString(11, pageId)
                    insertStmt.bindLong(12, createdAt)
                    insertStmt.bindLong(13, updatedAt)
                    insertStmt.executeInsert()

                    deleteStmt.clearBindings()
                    deleteStmt.bindString(1, id)
                    deleteStmt.executeUpdateDelete()
                } catch (rowBlob: SQLiteBlobTooBigException) {
                    log.e("Oversize stroke $id; deleting from stroke_old.", rowBlob)
                    try {
                        deleteStmt.clearBindings()
                        deleteStmt.bindString(1, id)
                        deleteStmt.executeUpdateDelete()
                    } catch (delEx: Exception) {
                        log.e("Failed to delete oversize stroke id=$id", delEx)
                        SnackState.globalSnackFlow.tryEmit(
                            SnackConf(
                                id = "oversize_$id",
                                text = "Failed to delete oversize stroke $id",
                                duration = 4000
                            )
                        )
                        throw delEx
                    }
                } catch (rowEx: Exception) {
                    log.e("Failed stroke id=$id; leaving for retry.", rowEx)
                    SnackState.globalSnackFlow.tryEmit(
                        SnackConf(
                            id = "oversize_$id",
                            text = "Failed stroke id=$id; leaving for retry.",
                            duration = 4000
                        )
                    )
                    throw rowEx
                }
            } while (cursor.moveToNext())

            db.setTransactionSuccessful()
        } catch (rowBlob: SQLiteBlobTooBigException) {
            // Single-row still too large: mark & skip
            log.e("Oversize batch $batchSize, trying again with half batchsize.", rowBlob)
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(
                    id = "oversize_$batchSize",
                    text = "Oversize batch $batchSize, trying again with half batchsize.",
                    duration = 4000
                )
            )
            batchSize /= 2
            require(batchSize != 0) { "Batch size cannot be 0" }
            if (batchSize == 1) {
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(
                        id = "oversize_$batchSize",
                        text = "Migration failed due to oversized stroke data.",
                        duration = 4000
                    )
                )
                break
            }
        } catch (rowEx: Exception) {
            // Leave it; remains in stroke_old
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(
                    id = "oversize_$batchSize",
                    text = "Stroke Reencoding, batch size $batchSize, trying again with smaller batchSize",
                    duration = 4000
                )
            )
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(
                    id = "oversize2_$batchSize",
                    text = "Error message: $rowEx",
                    duration = 4000
                )
            )
            log.e("Batch failed (size=$batchSize)", rowEx)
            batchSize /= 2
            if (batchSize < 2) {
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(
                        id = "oversize_$batchSize",
                        text = "Migration failed(batchSize=$batchSize), reducing batchSize didn't help",
                        duration = 4000
                    )
                )
                break
            }
        } finally {
            cursor.close()
            db.endTransaction()
        }
    }

    // Ensure index exists (should already from migration, but safe)
    db.execSQL("CREATE INDEX IF NOT EXISTS index_Stroke_pageId ON stroke(pageId)")
}

@Suppress("SameParameterValue")
private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean {
    db.query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
        arrayOf(name)
    ).use { c -> return c.moveToFirst() }
}

@Suppress("SameParameterValue")
private fun countRemaining(db: SupportSQLiteDatabase, name: String): Int {
    db.query("SELECT COUNT(*) FROM $name").use { c ->
        return if (c.moveToFirst()) c.getInt(0) else 0
    }
}