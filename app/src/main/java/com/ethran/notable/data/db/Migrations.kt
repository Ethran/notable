package com.ethran.notable.data.db

import android.util.Log
import androidx.room.RenameColumn
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
        db.query("PRAGMA cache_size=-65536").use { } // ~65MB page cache (negative => KB)

        // 1) Add the new column
        db.execSQL("ALTER TABLE `Stroke` ADD COLUMN `points_new` BLOB")

        // 2) Copy + encode into new column
        val cursor = db.query(
            "SELECT id,points FROM `Stroke`"
        )
        val totalRows = cursor.count
        if (totalRows == 0) {
            cursor.close()
            Log.d("MIGRATION_32_33", "No rows to migrate")
            return
        }

        var processed = 0
        var lastPercent = -1
        val idIdx = cursor.getColumnIndexOrThrow("id")
        val pointsIdx = cursor.getColumnIndexOrThrow("points")

        val updateSql = "UPDATE `Stroke` SET `points_new`=? WHERE `id`=?"
        val stmt = db.compileStatement(updateSql)

        // Reusable Json
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            encodeDefaults = false
        }
        val strokePointListSer =
            kotlinx.serialization.builtins.ListSerializer(StrokePoint.serializer())

        while (cursor.moveToNext()) {
            val id = cursor.getString(idIdx)
            val pointsJson = cursor.getString(pointsIdx) ?: "[]"

            val pointsList = json.decodeFromString(strokePointListSer, pointsJson)
            val mask = computeStrokeMask(pointsList)
            val blob = encodeStrokePoints(pointsList, mask)

            stmt.clearBindings()
            stmt.bindBlob(1, blob)
            stmt.bindString(2, id)
            stmt.executeUpdateDelete()

            processed++
            val percent = (processed * 100) / totalRows
            if (percent > lastPercent) {
                lastPercent = percent
                Log.d("MIGRATION_32_33", "Progress: $percent % ($processed/$totalRows)")
            }
        }
        stmt.close()
        cursor.close()
        Runtime.getRuntime().gc()
        Log.d("MIGRATION_32_33", "finished re-encodings.")

        // 3) Drop old column → recreate table without JSON column
        db.execSQL(
            """
            CREATE TABLE `Stroke_tmp` (
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
        db.execSQL(
            """
            INSERT INTO `Stroke_tmp`
            (id,size,pen,color,top,bottom,left,right,points,pageId,createdAt,updatedAt)
            SELECT id,size,pen,color,top,bottom,left,right,points_new,pageId,createdAt,updatedAt FROM `Stroke`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `Stroke`")
        db.execSQL("ALTER TABLE `Stroke_tmp` RENAME TO `Stroke`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_Stroke_pageId` ON `Stroke` (`pageId`)")
        Log.d("MIGRATION_32_33", "Finished migration")
    }
}
