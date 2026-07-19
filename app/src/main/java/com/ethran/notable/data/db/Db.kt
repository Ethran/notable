package com.ethran.notable.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.ethran.notable.data.dbDirPath
import com.ethran.notable.data.getDbDirOrNull
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Date
import javax.inject.Singleton


class Converters {
    @TypeConverter
    fun fromListString(value: List<String>) = Json.encodeToString(value)

    @TypeConverter
    fun toListString(value: String) = Json.decodeFromString<List<String>>(value)


    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun fromStrokePoints(points: List<StrokePoint>?): ByteArray? {
        if (points == null) return null
        val mask = computeStrokeMask(points)
        return encodeStrokePoints(points, mask)
    }

    @TypeConverter
    fun toStrokePoints(bytes: ByteArray?): List<StrokePoint> {
        if (bytes == null || bytes.isEmpty()) return emptyList()
        return decodeStrokePoints(bytes)
    }
}


@Database(
    entities = [Folder::class, Notebook::class, Page::class, Stroke::class, Image::class, Kv::class, NotebookSyncState::class],
    version = 35,
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
        AutoMigration(31, 32, spec = AutoMigration31to32::class),
        AutoMigration(32, 33),
        AutoMigration(33, 34),
        AutoMigration(34, 35)
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
    abstract fun notebookSyncStateDao(): NotebookSyncStateDao

//    companion object {
//        private var INSTANCE: AppDatabase? = null
//
//        fun getDatabase(context: Context): AppDatabase {
//            if (INSTANCE == null) {
//                synchronized(this) {
//                    val dbDir = getDbDir()
//                    val dbFile = File(dbDir, "app_database")
//
//                    // Use Room to build the database
//                    INSTANCE =
//                        Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
//                            .allowMainThreadQueries() // Avoid in production
//                            .addMigrations(
//                                MIGRATION_16_17,
//                                MIGRATION_17_18,
//                                MIGRATION_22_23,
//                                MIGRATION_32_33
//                            )
//                            .build()
//
//                }
//            }
//            return INSTANCE!!
//        }
//    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {

        // getDbDir() throws when storage isn't usable (permission revoked, storage not
        // mounted yet, ...), which would crash Dagger's graph construction before the
        // welcome screen ever gets a chance to render. Room doesn't open the database
        // file until the first real query, and query paths verify storage access first
        // (KvRepository, StrokeMigrationHelper), so fall back to the expected path and
        // let the UI route to the welcome/setup screen instead.
        val dbDir = getDbDirOrNull() ?: dbDirPath()
        val dbFile = File(dbDir, "app_database")

        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            dbFile.absolutePath
        )
            .addMigrations(
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_22_23,
                MIGRATION_32_33
            )
            .build()
    }

    @Provides
    fun provideNotebookDao(db: AppDatabase): NotebookDao =
        db.notebookDao()

    @Provides
    fun providePageDao(db: AppDatabase): PageDao =
        db.pageDao()

    @Provides
    fun provideKvDao(db: AppDatabase): KvDao =
        db.kvDao()

    @Provides
    fun provideFolderDao(db: AppDatabase): FolderDao =
        db.folderDao()

    @Provides
    fun provideStrokeDao(db: AppDatabase): StrokeDao =
        db.strokeDao()

    @Provides
    fun provideImageDao(db: AppDatabase): ImageDao =
        db.ImageDao()

    @Provides
    fun provideNotebookSyncStateDao(db: AppDatabase): NotebookSyncStateDao =
        db.notebookSyncStateDao()



}