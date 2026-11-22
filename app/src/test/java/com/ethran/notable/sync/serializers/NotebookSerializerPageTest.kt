package com.ethran.notable.sync.serializers

import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.MAX_PRESSURE_NORMALIZED
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.model.SheetSize
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        positionY: Int = 0,
        positionX: Int = 0,
        sheet: SheetSize? = null,
        parentFolderId: String? = null,
        createdAt: Date = Date(1_700_000_000_000),
        updatedAt: Date = Date(1_700_000_456_000),
    ) = Page(
        id = id,
        positionY = positionY,
        positionX = positionX,
        sheet = sheet,
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
    fun page_round_trip_preserves_the_sheet() {
        val page = samplePage(sheet = SheetSize(1860, 2632))
        val json = NotebookSerializer.serializePage(page, listOf(sampleStroke("s-1")), emptyList())

        val result = NotebookSerializer.deserializePage(json)
        assertTrue("expected Success, got $result", result is AppResult.Success)
        assertEquals(SheetSize(1860, 2632), (result as AppResult.Success).data.first.sheet)
    }

    // Older clients require the vertical position under its old key, "scroll".
    @Test
    fun page_file_keeps_the_vertical_position_under_the_scroll_key() {
        val json = NotebookSerializer.serializePage(samplePage(positionY = 300), emptyList(), emptyList())
        val fields = Json.parseToJsonElement(json).jsonObject

        assertEquals(300, fields.getValue("scroll").jsonPrimitive.int)
        assertFalse("positionY must not be written: $json", "positionY" in fields)
    }

    // "scroll" equals its default at the top of the page; it must still be written.
    @Test
    fun page_file_writes_scroll_at_the_top_of_the_page() {
        val json = NotebookSerializer.serializePage(samplePage(positionY = 0), emptyList(), emptyList())

        assertEquals(0, Json.parseToJsonElement(json).jsonObject.getValue("scroll").jsonPrimitive.int)
    }

    // A later app version may stop writing "scroll"; its page files must still parse here.
    @Test
    fun page_file_without_scroll_parses() {
        val noScroll = """{"version":1,"id":"page-1","notebookId":"nb-1","background":"blank",""" +
            """"backgroundType":"native","parentFolderId":null,""" +
            """"createdAt":"2023-11-14T22:13:20Z","updatedAt":"2023-11-14T22:20:56Z",""" +
            """"strokes":[],"images":[]}"""

        val result = NotebookSerializer.deserializePage(noScroll)
        assertTrue("expected Success, got $result", result is AppResult.Success)
    }

    // The view position is device-local; the horizontal one never goes on the wire.
    @Test
    fun page_file_never_writes_positionX() {
        val page = samplePage(positionX = 120, sheet = SheetSize(1860, 2632))
        val json = NotebookSerializer.serializePage(page, emptyList(), emptyList())

        assertFalse(
            "positionX must not be written: $json",
            "positionX" in Json.parseToJsonElement(json).jsonObject
        )
    }

    // An infinite page writes exactly the keys of the format before sheets, so older clients and
    // file diffs see no change. Also catches someone turning on encodeDefaults.
    @Test
    fun infinite_page_file_has_exactly_the_old_key_set() {
        val json = NotebookSerializer.serializePage(samplePage(positionY = 300), emptyList(), emptyList())

        assertEquals(
            setOf(
                "version", "id", "notebookId", "background", "backgroundType", "parentFolderId",
                "scroll", "createdAt", "updatedAt", "strokes", "images",
            ),
            Json.parseToJsonElement(json).jsonObject.keys,
        )
    }

    // A page file as the app wrote it before sheets. Kept as a literal: never regenerate it.
    @Test
    fun page_file_from_before_sheets_reads_as_an_infinite_page() {
        val old = """{"version":1,"id":"page-1","notebookId":"nb-1","background":"blank",""" +
            """"backgroundType":"native","parentFolderId":null,"scroll":300,""" +
            """"createdAt":"2023-11-14T22:13:20Z","updatedAt":"2023-11-14T22:20:56Z",""" +
            """"strokes":[],"images":[]}"""

        val result = NotebookSerializer.deserializePage(old)
        assertTrue("expected Success, got $result", result is AppResult.Success)
        val restored = (result as AppResult.Success).data.first
        assertEquals("page-1", restored.id)
        assertEquals("the view position is never read from the file", 0, restored.positionY)
        assertNull(restored.sheet)
    }

    // The page DTO of the app before sheets. An older client must still parse what we write;
    // this also pins "scroll" as written, since that DTO requires it.
    @Serializable
    private data class LegacyPageDto(
        val version: Int,
        val id: String,
        val notebookId: String?,
        val background: String,
        val backgroundType: String,
        val parentFolderId: String?,
        val scroll: Int,
        val createdAt: String,
        val updatedAt: String,
        val strokes: List<JsonElement>,
        val images: List<JsonElement>,
    )

    @Test
    fun older_client_parses_a_sheet_page_file() {
        val page = samplePage(positionY = 300, positionX = 120, sheet = SheetSize(1860, 2632))
        val json = NotebookSerializer.serializePage(
            page,
            listOf(sampleStroke("s-1")),
            listOf(sampleImage("i-1"))
        )

        val legacy = Json { ignoreUnknownKeys = true }.decodeFromString<LegacyPageDto>(json)
        assertEquals("page-1", legacy.id)
        assertEquals(300, legacy.scroll)
        assertEquals(1, legacy.strokes.size)
        assertEquals(1, legacy.images.size)
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
        assertEquals(page.sheet, restoredPage.sheet)
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
        // don't form a valid SB blob — decodeStrokePoints will throw, the
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
        // A deterministic parse failure must not be retried: identical bytes fail identically.
        assertEquals(false, err.recoverable)
    }

    @Test
    fun deserializePage_returns_error_for_malformed_json() {
        val result = NotebookSerializer.deserializePage("{ not json")
        assertTrue(result is AppResult.Error)
        val err = (result as AppResult.Error).error
        assertTrue(err is DomainError.UnexpectedState)
        // Truncated/garbled JSON is deterministic — non-recoverable, so it can't drive a retry loop.
        assertEquals(false, err.recoverable)
    }

    @Test
    fun deserializePage_truncated_json_is_non_recoverable() {
        // The exact production failure: a page cut off mid-stroke (torn upload). It must surface as
        // a non-recoverable error so the worker stops retrying the same poisoned bytes.
        val page = samplePage()
        val json = NotebookSerializer.serializePage(page, listOf(sampleStroke()), emptyList())
        val truncated = json.substring(0, json.length - 10)

        val result = NotebookSerializer.deserializePage(truncated)
        assertTrue("expected Error, got $result", result is AppResult.Error)
        val err = (result as AppResult.Error).error
        assertTrue(err is DomainError.UnexpectedState)
        assertEquals(false, err.recoverable)
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
        // Exercises the SB path that carries pressure + tilt + dt — all four mask bits set.
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
        // Spot-check tilt + dt survived the Base64 + SB trip.
        assertNotNull(strokes[0].points[0].tiltX)
        assertEquals(-10, strokes[0].points[0].tiltX)
        assertEquals(0.toUShort(), strokes[0].points[0].dt)
        assertEquals(16.toUShort(), strokes[0].points[1].dt)
    }
}
