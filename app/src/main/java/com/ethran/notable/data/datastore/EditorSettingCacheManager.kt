package com.ethran.notable.data.datastore

import android.content.Context
import com.ethran.notable.data.db.Kv
import com.ethran.notable.data.db.KvRepository
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.NamedSettings
import com.ethran.notable.editor.utils.Pen
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
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

    fun init() {
        val settingsJSon = kvRepository.get("EDITOR_SETTINGS")
        if (settingsJSon != null) {
            val settings = Json.decodeFromString<EditorSettings>(settingsJSon.value)
            if (settings.version == persistVersion) setEditorSettings(settings, false)
        }
    }

    private fun persist(settings: EditorSettings) {
        val settingsJson = Json.encodeToString(settings)
        kvRepository.set(Kv("EDITOR_SETTINGS", settingsJson))
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
