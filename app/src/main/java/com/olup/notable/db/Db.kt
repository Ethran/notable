package com.olup.notable.db

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.encodeToString
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
    version = 30,
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
                    // TODO: request only notable folder.
                    // Request storage permission for Android 10 (Q) and below
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        if (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            ActivityCompat.requestPermissions(
                                (context as Activity),
                                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                                1001
                            )
                        }
                    }
                    // Request "Manage all files" permission for Android R (API 30) and above
                    else if (!Environment.isExternalStorageManager()) {
                        requestManageAllFilesPermission(context)
                    }
                    val documentsDir =
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    val dbDir = File(documentsDir, "notabledb")
                    if (!dbDir.exists()) {
                        dbDir.mkdirs()
                    }
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

// TODO: Ask only for what is needed
@RequiresApi(Build.VERSION_CODES.R)
fun requestManageAllFilesPermission(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
    intent.data = Uri.fromParts("package", context.packageName, null)
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK // Add this flag
    context.startActivity(intent)
}