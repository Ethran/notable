package com.ethran.notable.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.ethran.notable.io.getDbDir
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Date


class Converters {
    @TypeConverter
    fun fromListString(value: List<String>) = Json.encodeToString(value)

    @TypeConverter
    fun toListString(value: String) = Json.decodeFromString<List<String>>(value)

    @TypeConverter
    fun fromListPoint(value: List<StrokePoint>) = Json.encodeToString(value)

    @TypeConverter
    fun toListPoint(value: String) = Json.decodeFromString<List<StrokePoint>>(value)

    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }
}


@Database(
    entities = [Folder::class, Notebook::class, Page::class, Stroke::class, Image::class, Kv::class],
    version = 32,
    autoMigrations = [
        AutoMigration(19, 20),
        AutoMigration(20, 21),
        AutoMigration(21, 22),
        AutoMigration(23, 24),
        AutoMigration(24, 25),
        AutoMigration(25, 26),
        AutoMigration(26, 27),
        AutoMigration(27, 28),
        AutoMigration(28, 29),
        AutoMigration(29, 30),
        AutoMigration(30, 31, spec = AutoMigration30to31::class),
        AutoMigration(31, 32, spec = AutoMigration31to32::class)

    ], exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun folderDao(): FolderDao
    abstract fun kvDao(): KvDao
    abstract fun notebookDao(): NotebookDao
    abstract fun pageDao(): PageDao
    abstract fun strokeDao(): StrokeDao
    abstract fun ImageDao(): ImageDao

    companion object {
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            if (INSTANCE == null) {
                synchronized(this) {
                    val dbDir = getDbDir()
                    val dbFile = File(dbDir, "app_database")

                    // Use Room to build the database
                    INSTANCE =
                        Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
                            .allowMainThreadQueries() // Avoid in production
                            .addMigrations(MIGRATION_16_17, MIGRATION_17_18, MIGRATION_22_23)
                            .build()

                }
            }
            return INSTANCE!!
        }
    }
}