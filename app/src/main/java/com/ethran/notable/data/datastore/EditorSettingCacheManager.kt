package com.ethran.notable.data.datastore

import com.ethran.notable.data.db.Kv
import com.ethran.notable.data.db.KvRepository
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.NamedSettings
import com.ethran.notable.editor.utils.Pen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

const val persistVersion = 2

@Singleton
class EditorSettingCacheManager
@Inject constructor(
    private val kvRepository: KvRepository
) {

    @Serializable
    data class EditorSettings(
        val version: Int = persistVersion,
        val isToolbarOpen: Boolean,
        val pen: Pen,
        val eraser: Eraser? = Eraser.PEN,
        val penSettings: NamedSettings,
        val mode: Mode
    )

    private val scope = CoroutineScope(Dispatchers.IO)

    suspend fun init() {
        val settingsJSon = withContext(Dispatchers.IO) {
            kvRepository.get("EDITOR_SETTINGS")
        }
        if (settingsJSon != null) {
            val settings = Json.decodeFromString<EditorSettings>(settingsJSon.value)
            if (settings.version == persistVersion) setEditorSettings(settings, false)
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
