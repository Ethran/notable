package com.ethran.notable.data.db

import android.util.Log
import androidx.room.RenameColumn
import androidx.room.TypeConverter
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.invoke
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE Page ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE Page ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")

        database.execSQL("ALTER TABLE Stroke ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE Stroke ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")

        database.execSQL("ALTER TABLE Notebook ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE Notebook ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE Page ADD COLUMN nativeTemplate TEXT NOT NULL DEFAULT 'blank'")
    }
}

val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "DELETE FROM Page " +
                    "WHERE notebookId IS NOT NULL " +
                    "AND notebookId NOT IN (SELECT id FROM Notebook);"
        )
    }
}

@RenameColumn.Entries(
    RenameColumn(
        tableName = "Page",
        fromColumnName = "nativeTemplate",
        toColumnName = "background"
    )
)
class AutoMigration30to31 : AutoMigrationSpec

@RenameColumn.Entries(
    RenameColumn(
        tableName = "Notebook",
        fromColumnName = "defaultNativeTemplate",
        toColumnName = "defaultBackground"
    )
)
class AutoMigration31to32 : AutoMigrationSpec


val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1) Create new table with the exact defaults Room expects
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `stroke_new` (
                `id` TEXT NOT NULL,
                `size` REAL NOT NULL,
                `pen` TEXT NOT NULL,
                `color` INTEGER NOT NULL DEFAULT 0xFF000000,
                `top` REAL NOT NULL,
                `bottom` REAL NOT NULL,
                `left` REAL NOT NULL,
                `right` REAL NOT NULL,
                `points` BLOB NOT NULL,
                `pageId` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`pageId`) REFERENCES `Page`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )

        // 2) Copy from old stroke, converting JSON->BLOB for points
        db.beginTransaction()
        val cursor = db.query(
            "SELECT id,size,pen,color,top,bottom,left,right,points,pageId,createdAt,updatedAt FROM `Stroke`"
        )
        val totalRows = cursor.count
        val progressSnackId = "migration_progress"

        // Initial snack (0%)
        if (totalRows > 0) {
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(
                    id = progressSnackId,
                    text = "Migrating strokes: 0% (0/$totalRows)",
                    duration = null
                )
            )
        } else {
            // Nothing to do
            cursor.close()
            return
        }

        var processed = 0
        var lastPercent = -1
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

            val insertSql = """
                INSERT INTO `stroke_new` 
                (id,size,pen,color,top,bottom,left,right,points,pageId,createdAt,updatedAt)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent()
            val stmt = db.compileStatement(insertSql)

            while (cursor.moveToNext()) {
                processed++
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

                // Decode JSON → List<StrokePoint>
                val pointsList = Json.decodeFromString<List<StrokePoint>>(pointsJson)
                val mask = computeStrokeMask(pointsList)
                val blob = encodeStrokePoints(pointsList, mask)

                stmt.clearBindings()
                stmt.bindString(1, id)
                stmt.bindDouble(2, size)
                stmt.bindString(3, pen)
                stmt.bindLong(4, color.toLong())
                stmt.bindDouble(5, top)
                stmt.bindDouble(6, bottom)
                stmt.bindDouble(7, left)
                stmt.bindDouble(8, right)
                stmt.bindBlob(9, blob)
                stmt.bindString(10, pageId)
                stmt.bindLong(11, createdAt)
                stmt.bindLong(12, updatedAt)
                stmt.executeInsert()
                val percent = (processed * 100) / totalRows
                if (percent > lastPercent) {
                    Log.d("MIGRATION", "Migrating strokes: $percent% ($processed/$totalRows)")
                    lastPercent = percent
                    SnackState.globalSnackFlow.tryEmit(
                        SnackConf(
                            id = progressSnackId,
                            text = "Migrating strokes: $percent% ($processed/$totalRows)",
                            duration = null
                        )
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            cursor.close()
            db.endTransaction()
        }

        // 3) Drop old table & rename
        db.execSQL("DROP TABLE `Stroke`")
        db.execSQL("ALTER TABLE `stroke_new` RENAME TO `Stroke`")

        // 4) Recreate index with the exact expected identifier
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_Stroke_pageId` ON `Stroke` (`pageId`)")
    }
}