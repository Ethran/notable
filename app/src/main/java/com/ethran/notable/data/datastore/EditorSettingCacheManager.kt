package com.ethran.notable.data.datastore

import com.ethran.notable.data.db.Kv
import com.ethran.notable.data.db.KvRepository
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.Eraser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton


// v3: pen identified by ToolbarPen preset id; per-pen settings live in
// AppSettings.toolbarPens (the preset is the setting), no longer cached here.
// Older versions are discarded on init (users just land on the default pen once).
const val persistVersion = 3

@Singleton
class EditorSettingCacheManager
@Inject constructor(
    private val kvRepository: KvRepository
) {

    @Serializable
    data class EditorSettings(
        val version: Int = persistVersion,
        val isToolbarOpen: Boolean,
        val penPresetId: String,
        val eraser: Eraser? = Eraser.PEN,
        val mode: Mode
    )

    private val scope = CoroutineScope(Dispatchers.IO)
    private val initMutex = Mutex()

    @Volatile
    private var isInitialized = false


    suspend fun init() {
        if (isInitialized) return

        initMutex.withLock {
            if (isInitialized) return

            val settingsJson = withContext(Dispatchers.IO) {
                kvRepository.get("EDITOR_SETTINGS")?.value
            }

            val settings = settingsJson
                ?.let { runCatching { Json.decodeFromString<EditorSettings>(it) }.getOrNull() }

            if (settings?.version == persistVersion) {
                setEditorSettings(settings, shouldPersist = false)
            }

            isInitialized = true
        }
    }


    private fun persist(settings: EditorSettings) {
        val settingsJson = Json.encodeToString(settings)
        scope.launch {
            kvRepository.set(Kv("EDITOR_SETTINGS", settingsJson))
        }
    }

    private var editorSettings: EditorSettings? = null
    fun getEditorSettings(): EditorSettings? {
        return editorSettings
    }

    fun setEditorSettings(
        newEditorSettings: EditorSettings,
        shouldPersist: Boolean = true
    ) {
        editorSettings = newEditorSettings
        if (shouldPersist) persist(newEditorSettings)
    }
}
