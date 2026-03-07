package com.ethran.notable.data.db

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.ethran.notable.APP_SETTINGS_KEY
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.utils.hasFilePermission
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton


@Entity
data class Kv(
    @PrimaryKey
    val key: String,
    val value: String
)

// DAO
@Dao
interface KvDao {
    @Query("SELECT * FROM kv WHERE `key`=:key")
    suspend fun get(key: String): Kv?

    @Query("SELECT * FROM kv WHERE `key`=:key")
    fun getLive(key: String): LiveData<Kv?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(kv: Kv)

    @Query("DELETE FROM kv WHERE `key`=:key")
    suspend fun delete(key: String)

}

@Singleton
class KvRepository @Inject constructor(
    private val db: KvDao,
    @param:ApplicationContext private val context: Context
) {

    private fun checkPermission() {
        if (!hasFilePermission(context)) {
            throw IllegalStateException("Storage permission not granted or DB not accessible.")
        }
    }

    suspend fun get(key: String): Kv? = withContext(Dispatchers.IO) {
        checkPermission()
        db.get(key)
    }

    fun getLive(key: String): LiveData<Kv?> {
        return db.getLive(key)
    }

    suspend fun set(kv: Kv) = withContext(Dispatchers.IO) {
        checkPermission()
        db.set(kv)
    }

    suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        checkPermission()
        db.delete(key)
    }

}



/**
 * A high-level proxy for the Key-Value database.
 *
 * This class handles:
 * 1. Serialization: Automatically converts Kotlin objects (like [AppSettings]) to/from JSON strings.
 * 2. State Management: Syncs database updates with [GlobalAppSettings] for immediate UI feedback.
 * 3. Reactive updates: Provides [LiveData] streams for specific keys.
 *
 * Use this class instead of [KvRepository] for app-level data like settings and UI states.
 */
@Singleton
class KvProxy @Inject constructor(
    private val kvRepository: KvRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val log = ShipBook.getLogger("KvProxy")

    fun <T> observeKv(key: String, serializer: KSerializer<T>, default: T): LiveData<T?> {
        return kvRepository.getLive(key).map {
            if (it == null) return@map default
            val jsonValue = it.value
            Json.decodeFromString(serializer, jsonValue)
        }
    }

    suspend fun <T> get(key: String, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        val kv = kvRepository.get(key)
            ?: return@withContext null //returns null when there is no database
        val jsonValue = kv.value
        Json.decodeFromString(serializer, jsonValue)
    }


    suspend fun <T> setKv(key: String, value: T, serializer: KSerializer<T>) {
        val jsonValue = Json.encodeToString(serializer, value)
        log.i("Setting $key to $value")
        kvRepository.set(Kv(key, jsonValue))
    }

    suspend fun setAppSettings(value: AppSettings) {
        setKv(APP_SETTINGS_KEY, value, AppSettings.serializer())
        GlobalAppSettings.update(value)
    }

}
