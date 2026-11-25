package com.ethran.notable.editor.canvas

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Onyx pen's raw-drawing switches (pen, render, input, session) and the firmware's live pen
 * style are called only in the [PenDevice] adapter, so one place shows, and logs, everything the
 * firmware is told. Likewise only [SurfaceWriter] locks and posts the canvas surface.
 */
class RawDrawingOwnershipTest {

    // With or without a receiver (`with(helper) { … }`), as a call or the Kotlin property setter.
    private val sdkCall = Regex(
        """\b(setRawDrawingEnabled|setRawDrawingRenderEnabled|setRawInputReaderEnable|""" +
                """openRawDrawing|closeRawDrawing|resetPenDefaultRawDrawing|bindHostView|""" +
                // The live pen style (TouchHelper and the device-wide style parameters).
                """setStrokeStyle|setStrokeColor|setEraserRawDrawingEnabled|setStrokeParameters|""" +
                """setLimitRect|setExcludeRect)\s*\(""" +
                """|\bisRawDrawingRenderEnabled\s*="""
    )

    @Test
    fun onlyThePenDeviceAdapterCallsTheSdk() {
        val offenders = callsOutside(sdkCall, owners = setOf("PenDevice.kt"))
        assertTrue(
            "Raw-drawing or pen-style SDK calls outside the PenDevice adapter:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /** What the panel shows is decided in one place; a second writer would bypass its rules. */
    @Test
    fun onlySurfaceWriterPostsTheCanvasSurface() {
        val offenders = callsOutside(Regex("""\b(lockCanvas|unlockCanvasAndPost)\s*\("""), setOf("SurfaceWriter.kt"))
        assertTrue(
            "Surface lock/post outside SurfaceWriter:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /** Code lines (not comments) matching [call] in source files not named in [owners]. */
    private fun callsOutside(call: Regex, owners: Set<String>): List<String> {
        // Unit tests run with the module directory as the working directory.
        val sources = File("src/main/java")
        assertTrue("sources not found from ${File("").absolutePath}", sources.isDirectory)
        return sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in owners }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) ->
                        val code = line.trimStart()
                        !code.startsWith("//") && !code.startsWith("*") && call.containsMatchIn(code)
                    }
                    .map { (i, line) -> "${file.path}:${i + 1}: ${line.trim()}" }
            }
            .toList()
    }
}
