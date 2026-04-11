package com.ethran.notable.data.db

import android.content.Context
import android.database.sqlite.SQLiteBlobTooBigException
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.utils.hasFilePermission
import com.onyx.android.sdk.api.device.epd.EpdController
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.serialization.json.Json
import javax.inject.Inject

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
class StrokeMigrationHelper @Inject constructor(
    private val database: AppDatabase,
    private val appEventBus: AppEventBus,
    @param:ApplicationContext private val appContext: Context
) {

    fun reencodeStrokePointsToSB1() {

        if (!hasFilePermission(appContext)) {
            appEventBus.tryEmit(AppEvent.StrokeMigrationPermissionMissing)
            log.e("No file permission!!!")
            return
        }
        val db = database.openHelper.writableDatabase
        if (!tableExists(db, "stroke_old")) return

        val totalInitial = countRemaining(db, "stroke_old")
        if (totalInitial == 0) {
            // Nothing left; drop the table defensively.
            db.execSQL("DROP TABLE IF EXISTS stroke_old")
            return
        }

        var batchSize = 1500
        val maxPressure = EpdController.getMaxTouchPressure().toLong()

        while (true) {
            val remaining = countRemaining(db, "stroke_old")
            log.d("Remaining rows: $remaining")
            if (remaining == 0) {
                // Finished
                db.execSQL("DROP TABLE IF EXISTS stroke_old")
                appEventBus.tryEmit(AppEvent.StrokeMigrationCompleted)
                break
            }
            appEventBus.tryEmit(
                AppEvent.StrokeMigrationProgress(
                    migrated = totalInitial - remaining,
                    total = totalInitial,
                    batchSize = batchSize
                )
            )

            // Select a batch deterministically (ORDER BY rowid) to avoid potential starvation
            val cursor = db.query(
                "SELECT id,size,pen,color,top,bottom,left,right,points,pageId,createdAt,updatedAt " + "FROM stroke_old ORDER BY rowid LIMIT $batchSize"
            )

            try {
                db.beginTransaction()

                if (!cursor.moveToFirst()) {
                    log.d("No rows in stroke_old")
                    cursor.close()
                    continue
                }

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
                            appEventBus.tryEmit(
                                AppEvent.GenericError("Failed to delete oversize stroke $id")
                            )
                            throw delEx
                        }
                    } catch (rowEx: Exception) {
                        log.e("Failed stroke id=$id; leaving for retry.", rowEx)
                        appEventBus.tryEmit(
                            AppEvent.GenericError("Failed stroke id=$id; leaving for retry.")
                        )
                        throw rowEx
                    }
                } while (cursor.moveToNext())

                db.setTransactionSuccessful()
            } catch (rowBlob: SQLiteBlobTooBigException) {
                // Single-row still too large: mark & skip
                log.e("Oversize batch $batchSize, trying again with half batchsize.", rowBlob)
                appEventBus.tryEmit(
                    AppEvent.GenericError("Oversize batch $batchSize, trying again with half batchsize.")
                )


                if (batchSize == 1) {
                    log.e(
                        "Migration failed due to oversized stroke data. reducing batchSize didn't help. ",
                        rowBlob
                    )

                    if (GlobalAppSettings.current.destructiveMigrations) {
                        db.endTransaction() //end fail transaction
                        //begin new transaction
                        db.beginTransaction()
                        if (!deleteOversizeData(db)) break
                        else db.setTransactionSuccessful()
                    } else {
                        appEventBus.tryEmit(
                            AppEvent.GenericError(
                                "Migration failed due to oversized stroke data. Reducing batch size did not help. Enable destructive migrations in Debug Settings."
                            )
                        )
                        break
                    }

                } else {
                    batchSize /= 2
                }
                require(batchSize != 0) { "Batch size cannot be 0" }

            } catch (rowEx: Exception) {
                // Leave it; remains in stroke_old
                appEventBus.tryEmit(
                    AppEvent.GenericError(
                        "Stroke reencoding failed for batch $batchSize, retrying with smaller batch."
                    )
                )
                log.e("Batch failed (size=$batchSize)", rowEx)
                batchSize /= 2
                if (batchSize < 2) {
                    appEventBus.tryEmit(
                        AppEvent.GenericError(
                            "Migration failed (batchSize=$batchSize), reducing batch size did not help."
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
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)
        ).use { c -> return c.moveToFirst() }
    }

    @Suppress("SameParameterValue")
    private fun countRemaining(db: SupportSQLiteDatabase, name: String): Int {
        db.query("SELECT COUNT(*) FROM $name").use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun deleteOversizeData(db: SupportSQLiteDatabase): Boolean {
        try {
            log.d("Deleting oversize rows")
            val sql = """
      DELETE FROM stroke_old
      WHERE rowid IN (
        SELECT rowid FROM stroke_old
        ORDER BY rowid LIMIT 1
      )
    """.trimIndent()
            db.execSQL(sql)
            appEventBus.tryEmit(
                AppEvent.StrokeMigrationWarning(corruptedPoints = 1)
            )
            return true
        } catch (delEx: Exception) {
            log.e("Failed to delete oversize row(s) during destructive migration", delEx)
            appEventBus.tryEmit(
                AppEvent.GenericError("Failed to delete oversize stroke(s): ${delEx.message}")
            )
            return false
        }
    }
}
