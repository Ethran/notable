package com.ethran.notable.editor.canvas

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Raw drawing has one owner. A second place that turns the Onyx pen, its render or its input on
 * or off brings back the races [RawDrawingController] removed (a delayed "on" landing after an
 * "off", a frozen screen), so the SDK calls may appear only in the [PenDevice] adapter.
 */
class RawDrawingOwnershipTest {

    private val sdkCall = Regex(
        """\.(setRawDrawingEnabled\(|isRawDrawingRenderEnabled\s*=|setRawInputReaderEnable\(|""" +
                """openRawDrawing\(|closeRawDrawing\()"""
    )
    private val owners = setOf("TouchHelperPenDevice.kt", "RawDrawingController.kt")

    @Test
    fun onlyThePenDeviceAdapterCallsTheSdk() {
        // Unit tests run with the module directory as the working directory.
        val sources = File("src/main/java")
        assertTrue("sources not found from ${File("").absolutePath}", sources.isDirectory)
        val offenders = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in owners }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) ->
                        val code = line.trimStart()
                        !code.startsWith("//") && !code.startsWith("*") && sdkCall.containsMatchIn(code)
                    }
                    .map { (i, line) -> "${file.path}:${i + 1}: ${line.trim()}" }
            }
            .toList()
        assertTrue(
            "Raw-drawing SDK calls outside the PenDevice adapter:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
