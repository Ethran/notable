package com.ethran.notable.data.datastore

import android.content.Context
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.NamedSettings
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Kv
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val persistVersion = 2

object EditorSettingCacheManager {

    @Serializable
    data class EditorSettings(
        val version: Int = persistVersion,
        val isToolbarOpen: Boolean,
        val pen: Pen,
        val eraser: Eraser? = Eraser.PEN,
        val penSettings: NamedSettings,
        val mode: Mode
    )

    fun init(context: Context) {
        val settingsJSon = AppRepository(context).kvRepository.get("EDITOR_SETTINGS")
        if (settingsJSon != null) {
            val settings = Json.decodeFromString<EditorSettings>(settingsJSon.value)
            if (settings.version == persistVersion) setEditorSettings(context, settings, false)
        }
    }

    private fun persist(context: Context, settings: EditorSettings) {
        val settingsJson = Json.encodeToString(settings)
        AppRepository(context).kvRepository.set(Kv("EDITOR_SETTINGS", settingsJson))
    }

    private var editorSettings: EditorSettings? = null
    fun getEditorSettings(): EditorSettings? {
        return editorSettings
    }

    fun setEditorSettings(
        context: Context,
        newEditorSettings: EditorSettings,
        shouldPersist: Boolean = true
    ) {
        editorSettings = newEditorSettings
        if (shouldPersist) persist(context, newEditorSettings)
    }
}
