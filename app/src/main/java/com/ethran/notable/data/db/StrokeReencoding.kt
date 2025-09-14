package com.ethran.notable.data.db

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import kotlinx.serialization.json.Json

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
    val db = AppDatabase.getDatabase(appContext).openHelper.writableDatabase
    if (!tableExists(db, "stroke_old")) return

    val totalInitial = countRemaining(db, "stroke_old")
    if (totalInitial == 0) {
        // Nothing left; drop the table defensively.
        db.execSQL("DROP TABLE IF EXISTS stroke_old")
        return
    }

    val batchSize = 1500
    val progressSnackId = "migration_progress"

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
        SnackState.Companion.cancelGlobalSnack.tryEmit(progressSnackId)
        val percent = (100.0 * (totalInitial - remaining).toFloat() / totalInitial.toFloat())
        SnackState.globalSnackFlow.tryEmit(
            SnackConf(
                id = progressSnackId,
                text = "Migrating strokes: ${"%.1f".format(percent)}% (${totalInitial - remaining}/$totalInitial)",
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
                (id,size,pen,color,top,bottom,left,right,points,pageId,createdAt,updatedAt)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
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
                    insertStmt.bindDouble(5, top)
                    insertStmt.bindDouble(6, bottom)
                    insertStmt.bindDouble(7, left)
                    insertStmt.bindDouble(8, right)
                    insertStmt.bindBlob(9, blob)
                    insertStmt.bindString(10, pageId)
                    insertStmt.bindLong(11, createdAt)
                    insertStmt.bindLong(12, updatedAt)
                    insertStmt.executeInsert()

                    deleteStmt.clearBindings()
                    deleteStmt.bindString(1, id)
                    deleteStmt.executeUpdateDelete()
                } catch (e: Exception) {
                    Log.e("StrokeMigration", "Failed to migrate stroke $id", e)
                }
            } while (cursor.moveToNext())

            db.setTransactionSuccessful()
        } finally {
            cursor.close()
            db.endTransaction()
        }
    }

    // Ensure index exists (should already from migration, but safe)
    db.execSQL("CREATE INDEX IF NOT EXISTS index_Stroke_pageId ON stroke(pageId)")
}

private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean {
    db.query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
        arrayOf(name)
    ).use { c -> return c.moveToFirst() }
}

private fun countRemaining(db: SupportSQLiteDatabase, name: String): Int {
    db.query("SELECT COUNT(*) FROM $name").use { c ->
        return if (c.moveToFirst()) c.getInt(0) else 0
    }
}