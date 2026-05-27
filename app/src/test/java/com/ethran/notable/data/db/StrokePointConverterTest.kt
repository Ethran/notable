package com.ethran.notable.data.db

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

class StrokePointConverterTest {

    private fun assertPointsApproxEqual(expected: List<StrokePoint>, actual: List<StrokePoint>) {
        assertEquals("count mismatch", expected.size, actual.size)
        expected.zip(actual).forEachIndexed { i, (e, a) ->
            // Precision 2 in encoding => ~1e-2 absolute tolerance on x/y.
            assertTrue("x[$i]: expected ${e.x} ≈ ${a.x}", abs(e.x - a.x) < 1e-2f)
            assertTrue("y[$i]: expected ${e.y} ≈ ${a.y}", abs(e.y - a.y) < 1e-2f)
            assertEquals("pressure[$i]", e.pressure, a.pressure)
            assertEquals("tiltX[$i]", e.tiltX, a.tiltX)
            assertEquals("tiltY[$i]", e.tiltY, a.tiltY)
            assertEquals("dt[$i]", e.dt, a.dt)
        }
    }

    @Test
    fun roundTrip_bare_points_preserves_coordinates() {
        val points = listOf(
            StrokePoint(x = 0f, y = 0f),
            StrokePoint(x = 10.25f, y = 20.5f),
            StrokePoint(x = 100.75f, y = 50.0f),
        )
        val mask = computeStrokeMask(points)
        assertEquals(0, mask)

        val encoded = encodeStrokePoints(points)
        val decoded = decodeStrokePoints(encoded)
        assertPointsApproxEqual(points, decoded)
    }

    @Test
    fun roundTrip_with_all_optional_fields_present() {
        val points = listOf(
            StrokePoint(x = 1f, y = 2f, pressure = 100f, tiltX = -10, tiltY = 20, dt = 0.toUShort()),
            StrokePoint(x = 3f, y = 4f, pressure = 200f, tiltX = -5, tiltY = 25, dt = 12.toUShort()),
            StrokePoint(x = 5f, y = 6f, pressure = 4096f, tiltX = 0, tiltY = 30, dt = 100.toUShort()),
        )
        val encoded = encodeStrokePoints(points)
        val decoded = decodeStrokePoints(encoded)
        assertPointsApproxEqual(points, decoded)
    }

    @Test
    fun roundTrip_pressure_only_mask() {
        val points = listOf(
            StrokePoint(x = 0f, y = 0f, pressure = 50f),
            StrokePoint(x = 1f, y = 1f, pressure = 75f),
        )
        val encoded = encodeStrokePoints(points)
        val decoded = decodeStrokePoints(encoded)
        assertPointsApproxEqual(points, decoded)
        decoded.forEach {
            assertNotNull(it.pressure)
            assertNull(it.tiltX)
            assertNull(it.tiltY)
            assertNull(it.dt)
        }
    }

    @Test
    fun header_mask_matches_computed_mask() {
        val points = listOf(
            StrokePoint(x = 0f, y = 0f, pressure = 100f, tiltY = 5),
        )
        val expectedMask = computeStrokeMask(points)
        val encoded = encodeStrokePoints(points)
        assertEquals(expectedMask, getStrokeMask(encoded))
    }

    @Test
    fun small_stroke_is_stored_uncompressed() {
        // Body well under MIN_BYTES_FOR_COMPRESSION (512) → compression flag must be NONE.
        val points = listOf(StrokePoint(x = 0f, y = 0f), StrokePoint(x = 1f, y = 1f))
        val encoded = encodeStrokePoints(points)

        // Header layout: MAGIC0, MAGIC1, VERSION, MASK, COUNT(4), COMPRESSION(1) at index 8.
        val compressionFlag = encoded[8]
        assertEquals("expected COMPRESSION_NONE for tiny payload", 0.toByte(), compressionFlag)
    }

    @Test
    fun large_stroke_round_trips_through_compression() {
        // Many points → raw body well over the 512-byte threshold; values are
        // highly compressible (monotonic), so the encoder should pick LZ4 and
        // the decoder must still reconstruct the points exactly.
        val points = (0 until 500).map {
            StrokePoint(
                x = it.toFloat(),
                y = (it * 2).toFloat(),
                pressure = (1000 + (it % 100)).toFloat(),
                tiltX = (it % 21) - 10,
                tiltY = (it % 41) - 20,
                dt = (it % 50).toUShort(),
            )
        }
        val encoded = encodeStrokePoints(points)
        val decoded = decodeStrokePoints(encoded)
        assertPointsApproxEqual(points, decoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_rejects_buffer_too_small_for_header() {
        decodeStrokePoints(ByteArray(4))
    }

    @Test
    fun decode_rejects_bad_magic() {
        val encoded = encodeStrokePoints(
            listOf(StrokePoint(x = 0f, y = 0f), StrokePoint(x = 1f, y = 1f))
        )
        encoded[0] = 'X'.code.toByte()
        try {
            decodeStrokePoints(encoded)
            fail("expected IllegalArgumentException for bad magic")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Bad magic"))
        }
    }

    @Test
    fun decode_rejects_future_version() {
        val encoded = encodeStrokePoints(
            listOf(StrokePoint(x = 0f, y = 0f), StrokePoint(x = 1f, y = 1f))
        )
        encoded[2] = 99.toByte() // version byte
        try {
            decodeStrokePoints(encoded)
            fail("expected IllegalArgumentException for unsupported version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Unsupported version"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun getStrokeMask_rejects_truncated_header() {
        getStrokeMask(ByteArray(3))
    }

    @Test
    fun getStrokeMask_rejects_bad_magic() {
        val bytes = ByteArray(16)
        // MAGIC bytes are wrong (all zero), should fail before reading further.
        try {
            getStrokeMask(bytes)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Bad magic"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun encode_rejects_empty_points() {
        encodeStrokePoints(emptyList())
    }

    @Test
    fun encode_rejects_non_uniform_pressure() {
        // First point has pressure (mask bit set), second is null → validateUniform must fail.
        val points = listOf(
            StrokePoint(x = 0f, y = 0f, pressure = 100f),
            StrokePoint(x = 1f, y = 1f, pressure = null),
        )
        try {
            encodeStrokePoints(points)
            fail("expected IllegalArgumentException for non-uniform pressure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("pressure"))
        }
    }

    @Test
    fun encode_rejects_non_uniform_when_first_lacks_field() {
        // First point lacks pressure (mask bit clear), but a later point has it.
        val points = listOf(
            StrokePoint(x = 0f, y = 0f, pressure = null),
            StrokePoint(x = 1f, y = 1f, pressure = 100f),
        )
        try {
            encodeStrokePoints(points)
            fail("expected IllegalArgumentException for non-uniform pressure (extra)")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("pressure"))
        }
    }

    @Test
    fun encode_then_decode_preserves_byte_layout_for_header() {
        // Sanity-check the documented header layout: magic 'S','B', version 1, then mask byte.
        val points = listOf(
            StrokePoint(x = 0f, y = 0f, pressure = 100f),
            StrokePoint(x = 1f, y = 1f, pressure = 200f),
        )
        val encoded = encodeStrokePoints(points)
        assertArrayEquals(
            byteArrayOf('S'.code.toByte(), 'B'.code.toByte(), 1.toByte()),
            encoded.copyOfRange(0, 3)
        )
        // Mask byte: pressure bit (bit 0) only.
        assertEquals(0b0001.toByte(), encoded[3])
    }
}
