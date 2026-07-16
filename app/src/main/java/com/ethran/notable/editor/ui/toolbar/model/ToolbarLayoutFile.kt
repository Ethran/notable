package com.ethran.notable.editor.ui.toolbar.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The export/import file format for a toolbar layout (design doc §4): the layout plus
 * the pen presets it references, wrapped in a small self-identifying envelope so a
 * mispicked JSON file is rejected with a clear message instead of half-importing.
 *
 * Decoding tolerates unknown keys (a file exported from a newer app version still
 * imports — its unknown layout entries are then dropped by [ToolbarLayout.validated]).
 */
@Serializable
data class ToolbarLayoutFile(
    val type: String = TYPE,
    val version: Int = VERSION,
    val layout: ToolbarLayout,
    val pens: List<ToolbarPen>,
) {
    companion object {
        const val TYPE = "notable-toolbar-layout"
        const val VERSION = 1

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun encode(layout: ToolbarLayout, pens: List<ToolbarPen>): String =
            json.encodeToString(serializer(), ToolbarLayoutFile(layout = layout, pens = pens))

        /**
         * Parses and sanitizes an imported file. Malformed JSON, a wrong `type`, or a
         * newer `version` throws [IllegalArgumentException] with a user-presentable
         * message; entries the validator drops are reported via [droppedCount] so the
         * caller can mention them in a snackbar rather than failing the import.
         *
         * Pens are sanitized symmetrically with the layout: duplicate ids are dropped
         * (first wins — `"PEN:<id>"` resolution takes the first match anyway), and
         * option lists that violate the editor's invariants (≥1 color, ≥2 sizes — the
         * discrete size slider needs a range) fall back to null, i.e. the defaults.
         */
        fun decode(text: String): ImportResult {
            val file = try {
                json.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("Not a toolbar layout file", e)
            }
            require(file.type == TYPE) { "Not a toolbar layout file" }
            require(file.version <= VERSION) {
                "File version ${file.version} is newer than this app supports ($VERSION)"
            }
            val pens = file.pens.distinctBy { it.id }.map { pen ->
                pen.copy(
                    colorOptions = pen.colorOptions?.distinct()?.takeIf { it.isNotEmpty() },
                    sizeOptions = pen.sizeOptions?.distinct()?.sorted()
                        ?.takeIf { it.size >= 2 },
                )
            }
            val validated = file.layout.validated(pens)
            val original = file.layout.scrollable + file.layout.pinned
            val kept = validated.scrollable.size + validated.pinned.size -
                    if (ToolbarElementId.MENU.name in original) 0 else 1 // validator appends MENU
            return ImportResult(validated, pens, original.size - kept)
        }
    }

    data class ImportResult(
        val layout: ToolbarLayout,
        val pens: List<ToolbarPen>,
        val droppedCount: Int,
    )
}
