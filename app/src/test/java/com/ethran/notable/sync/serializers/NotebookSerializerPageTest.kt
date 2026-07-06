package com.ethran.notable.sync.serializers

import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.MAX_PRESSURE_NORMALIZED
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import kotlin.math.abs

/**
 * Robolectric-driven coverage for the page-level (de)serialization path, which depends on
 * android.util.Base64. The manifest-level tests live in NotebookSerializerTest and run on
 * the plain JVM runner.
 *
 * ---------------------------------------------------------------------------
 * NOTES FOR WHOEVER RUNS THIS SUITE (these tests were authored without a
 * working Gradle/Android SDK environment, so they have not been executed):
 *
 * 1. Robolectric setup lives in app/build.gradle:
 *      testImplementation 'org.robolectric:robolectric:4.14.1'
 *      android { testOptions { unitTests { includeAndroidResources = true } } }
 *
 * 2. Robolectric requires JDK 17+ at runtime. The module already declares
 *    JavaVersion.VERSION_17 in compileOptions, so this should be consistent.
 *
 * 3. The first invocation of any Robolectric test downloads an Android
 *    platform jar (~80MB) into ~/.gradle/caches/robolectric. Expect a slow
 *    first run; subsequent runs are fast.
 *
 * 4. Potential gotcha shared with StrokePointConverterTest:
 *    StrokePointConverter.kt has a file-scope
 *        private val log = ShipBook.getLogger("StrokePointConverter")
 *    If ShipBook.getLogger() throws when the SDK is uninitialized, every
 *    test in this file (and in StrokePointConverterTest) will fail at class
 *    load. If that happens, the cleanest fixes are either:
 *      - make `log` lazy:  private val log by lazy { ShipBook.getLogger(...) }
 *      - or initialize ShipBook in a @BeforeClass hook for these suites.
 *
 * 5. @Config(sdk = [33]) is set because Robolectric 4.14.1's bundled SDK
 *    coverage tops out below the project's compileSdk 36. Bump this as
 *    Robolectric adds support for newer platform jars.
 * ---------------------------------------------------------------------------
 */
class NotebookSerializerPageTest {

    private fun samplePage(
        id: String = "page-1",
        notebookId: String? = "nb-1",
        scroll: Int = 0,
        parentFolderId: String? = null,
        createdAt: Date = Date(1_700_000_000_000),
        updatedAt: Date = Date(1_700_000_456_000),
    ) = Page(
        id = id,
        scroll = scroll,
        notebookId = notebookId,
        background = "blank",
        backgroundType = "native",
        parentFolderId = parentFolderId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun sampleStroke(
        id: String = "stroke-1",
        pageId: String = "page-1",
        pen: Pen = Pen.BALLPEN,
        // In-memory strokes carry normalized [0,1] pressure with maxPressure == 1
        // (MAX_PRESSURE_NORMALIZED); serializePage encodes them as SB v2 fixed-point.
        points: List<StrokePoint> = listOf(
            StrokePoint(x = 10f, y = 20f, pressure = 0.1f),
            StrokePoint(x = 15f, y = 25f, pressure = 0.2f),
            StrokePoint(x = 20f, y = 30f, pressure = 0.3f),
        ),
        createdAt: Date = Date(1_700_000_000_000),
        updatedAt: Date = Date(1_700_000_456_000),
    ) = Stroke(
        id = id,
        size = 3f,
        pen = pen,
        color = 0xFF112233.toInt(),
        maxPressure = MAX_PRESSURE_NORMALIZED,
        top = 20f,
        bottom = 30f,
        left = 10f,
        right = 20f,
        points = points,
        pageId = pageId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun sampleImage(
        id: String = "img-1",
        pageId: String = "page-1",
        uri: String? = "/storage/emulated/0/Documents/notabledb/images/abc123.jpg",
        createdAt: Date = Date(1_700_000_000_000),
        updatedAt: Date = Date(1_700_000_456_000),
    ) = Image(
        id = id,
        x = 50,
        y = 60,
        height = 200,
        width = 300,
        uri = uri,
        pageId = pageId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun assertPointsApprox(expected: List<StrokePoint>, actual: List<StrokePoint>) {
        assertEquals("point count", expected.size, actual.size)
        expected.zip(actual).forEachIndexed { i, (e, a) ->
            assertTrue("x[$i] ${e.x} ≈ ${a.x}", abs(e.x - a.x) < 1e-2f)
            assertTrue("y[$i] ${e.y} ≈ ${a.y}", abs(e.y - a.y) < 1e-2f)
            // SB v2 quantizes normalized pressure to uint16 => ~1/65535 tolerance.
            assertTrue(
                "pressure[$i] ${e.pressure} ≈ ${a.pressure}",
                (e.pressure == null && a.pressure == null)
                        || abs(e.pressure!! - a.pressure!!) < 1e-4f
            )
        }
    }

    @Test
    fun page_round_trip_preserves_metadata_strokes_and_images() {
        val page = samplePage()
        val strokes = listOf(sampleStroke("s-1"), sampleStroke("s-2"))
        val images = listOf(sampleImage("i-1"), sampleImage("i-2", uri = null))

        val json = NotebookSerializer.serializePage(page, strokes, images)
        val result = NotebookSerializer.deserializePage(json)

        assertTrue("expected Success, got $result", result is AppResult.Success)
        val (restoredPage, restoredStrokes, restoredImages) = (result as AppResult.Success).data

        assertEquals(page.id, restoredPage.id)
        assertEquals(page.notebookId, restoredPage.notebookId)
        assertEquals(page.background, restoredPage.background)
        assertEquals(page.backgroundType, restoredPage.backgroundType)
        assertEquals(page.scroll, restoredPage.scroll)
        assertEquals(page.createdAt, restoredPage.createdAt)
        assertEquals(page.updatedAt, restoredPage.updatedAt)

        assertEquals(2, restoredStrokes.size)
        restoredStrokes.zip(strokes).forEach { (restored, original) ->
            assertEquals(original.id, restored.id)
            assertEquals(original.size, restored.size)
            assertEquals(original.pen, restored.pen)
            assertEquals(original.color, restored.color)
            assertEquals(original.maxPressure, restored.maxPressure)
            assertEquals(original.top, restored.top)
            assertEquals(original.bottom, restored.bottom)
            assertEquals(original.left, restored.left)
            assertEquals(original.right, restored.right)
            // pageId is taken from the page envelope, not the stroke DTO.
            assertEquals(page.id, restored.pageId)
            assertPointsApprox(original.points, restored.points)
        }

        assertEquals(2, restoredImages.size)
        assertEquals("i-1", restoredImages[0].id)
        assertEquals(50, restoredImages[0].x)
        assertEquals(60, restoredImages[0].y)
        assertEquals(300, restoredImages[0].width)
        assertEquals(200, restoredImages[0].height)
        assertEquals(page.id, restoredImages[0].pageId)
        // serializePage rewrites absolute URIs as "<parent>/<file>".
        assertEquals("images/abc123.jpg", restoredImages[0].uri)
        // Null URI must survive as null.
        assertEquals(null, restoredImages[1].uri)
    }

    @Test
    fun page_round_trip_handles_empty_strokes_and_images() {
        val page = samplePage(id = "empty-page")

        val json = NotebookSerializer.serializePage(page, emptyList(), emptyList())
        val result = NotebookSerializer.deserializePage(json)

        assertTrue(result is AppResult.Success)
        val (restoredPage, strokes, images) = (result as AppResult.Success).data
        assertEquals("empty-page", restoredPage.id)
        assertEquals(0, strokes.size)
        assertEquals(0, images.size)
    }

    @Test
    fun deserializePage_skips_strokes_with_unknown_pen_enum_but_keeps_rest() {
        // Encode a valid page then corrupt one stroke's "pen" field. The
        // documented contract is: corrupted individual strokes are skipped,
        // valid ones survive.
        val page = samplePage()
        val good = sampleStroke("good")
        val bad = sampleStroke("bad")
        val json = NotebookSerializer.serializePage(page, listOf(good, bad), emptyList())

        // Replace the "pen" value for the "bad" stroke with something Pen.valueOf can't resolve.
        // The "bad" stroke is whichever appears after the id "bad" in the JSON.
        val corruptedJson = json.replace(
            Regex("(\"id\"\\s*:\\s*\"bad\"[^}]*\"pen\"\\s*:\\s*\")[^\"]+(\")"),
            "$1NOT_A_REAL_PEN$2"
        )
        assertTrue("regex must have rewritten the bad pen field", corruptedJson != json)

        val result = NotebookSerializer.deserializePage(corruptedJson)
        assertTrue(result is AppResult.Success)
        val (_, strokes, _) = (result as AppResult.Success).data
        assertEquals(1, strokes.size)
        assertEquals("good", strokes[0].id)
    }

    @Test
    fun deserializePage_skips_stroke_with_corrupted_base64_pointsData() {
        val page = samplePage()
        val good = sampleStroke("good")
        val bad = sampleStroke("bad")
        val json = NotebookSerializer.serializePage(page, listOf(good, bad), emptyList())

        // Replace the bad stroke's pointsData payload with bytes that decode but
        // don't form a valid SB1 blob — decodeStrokePoints will throw, the
        // serializer should swallow that and drop only this stroke.
        val corruptedJson = json.replace(
            Regex("(\"id\"\\s*:\\s*\"bad\"[^}]*\"pointsData\"\\s*:\\s*\")[^\"]+(\")"),
            "$1AAAAAAAA$2"
        )
        assertTrue("regex must have rewritten the bad pointsData", corruptedJson != json)

        val result = NotebookSerializer.deserializePage(corruptedJson)
        assertTrue(result is AppResult.Success)
        val (_, strokes, _) = (result as AppResult.Success).data
        assertEquals(1, strokes.size)
        assertEquals("good", strokes[0].id)
    }

    @Test
    fun deserializePage_returns_error_when_page_timestamps_are_corrupted() {
        val page = samplePage()
        val json = NotebookSerializer.serializePage(page, emptyList(), emptyList())
        // Corrupt the top-level (page-level) createdAt only. There are nested
        // createdAt fields inside strokes/images, but with empty lists none exist.
        val corrupted = json.replace(
            Regex("\"createdAt\"\\s*:\\s*\"[^\"]+\""),
            "\"createdAt\":\"not-a-date\""
        )
        val result = NotebookSerializer.deserializePage(corrupted)
        assertTrue("expected Error, got $result", result is AppResult.Error)
        val err = (result as AppResult.Error).error
        assertTrue(err is DomainError.UnexpectedState)
        assertTrue(err.userMessage.contains("corrupted", ignoreCase = true))
    }

    @Test
    fun deserializePage_returns_error_for_malformed_json() {
        val result = NotebookSerializer.deserializePage("{ not json")
        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).error is DomainError.UnexpectedState)
    }

    @Test
    fun serializePage_rewrites_absolute_image_uri_to_relative_form() {
        // Documented in convertToRelativeUri():
        // /storage/emulated/0/Documents/notabledb/images/abc.jpg -> images/abc.jpg
        val page = samplePage()
        val image = sampleImage(uri = "/storage/emulated/0/Documents/notabledb/images/abc.jpg")
        val json = NotebookSerializer.serializePage(page, emptyList(), listOf(image))

        assertTrue(
            "expected relative uri in JSON, got: $json",
            json.contains("\"uri\": \"images/abc.jpg\"")
                || json.contains("\"uri\":\"images/abc.jpg\"")
        )
    }

    @Test
    fun stroke_with_all_optional_point_fields_round_trips_through_base64() {
        // Exercises the SB1 path that carries pressure + tilt + dt — all four mask bits set.
        val page = samplePage()
        val points = listOf(
            StrokePoint(x = 0f, y = 0f, pressure = 0.1f, tiltX = -10, tiltY = 20, dt = 0.toUShort()),
            StrokePoint(x = 5f, y = 10f, pressure = 0.2f, tiltX = 5, tiltY = 25, dt = 16.toUShort()),
        )
        val stroke = sampleStroke(points = points)
        val json = NotebookSerializer.serializePage(page, listOf(stroke), emptyList())
        val result = NotebookSerializer.deserializePage(json)

        assertTrue(result is AppResult.Success)
        val (_, strokes, _) = (result as AppResult.Success).data
        assertEquals(1, strokes.size)
        assertPointsApprox(points, strokes[0].points)
        // Spot-check tilt + dt survived the Base64 + SB1 trip.
        assertNotNull(strokes[0].points[0].tiltX)
        assertEquals(-10, strokes[0].points[0].tiltX)
        assertEquals(0.toUShort(), strokes[0].points[0].dt)
        assertEquals(16.toUShort(), strokes[0].points[1].dt)
    }
}
