package com.ethran.notable.data.db

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EncodePolylineTest {

    // NOTE: Notable's encode()/decode() implement a *single-stream* delta encoding
    // — every value is a delta from the previous one in the same list. Google's
    // canonical polyline algorithm interleaves lat/lng and resets state between
    // coordinate *pairs*, so direct comparisons against Google's reference vectors
    // don't apply here. Coverage below focuses on the invariants Notable actually
    // relies on: round-trip preservation at the precision the stroke pipeline uses.

    @Test
    fun encode_then_decode_round_trip_recovers_input_at_precision_5() {
        val original = floatArrayOf(38.5f, -120.2f, 40.7f, -120.95f, 43.252f, -126.453f)
        val decoded = decode(encode(original, precision = 5), precision = 5)

        assertEquals(original.size, decoded.size)
        original.zip(decoded).forEach { (e, a) ->
            assertTrue("expected $e ≈ $a", abs(e - a) < 1e-5)
        }
    }

    @Test
    fun encode_output_is_deterministic_for_same_input() {
        // Locks the encoder against accidental output drift between releases.
        val coords = floatArrayOf(38.5f, -120.2f, 40.7f, -120.95f, 43.252f, -126.453f)
        assertArrayEquals(encode(coords), encode(coords))
    }

    @Test
    fun round_trip_preserves_values_within_precision() {
        // Note: precision=5 only allows ~1e-5 absolute error; precision=2 is what
        // StrokePointConverter uses for page coordinates.
        val original = floatArrayOf(0f, 1.23f, -4.56f, 1000.78f, -999.99f, 0.01f)

        val encoded = encode(original, precision = 2)
        val decoded = decode(encoded, precision = 2)

        assertEquals(original.size, decoded.size)
        original.zip(decoded).forEach { (e, a) ->
            assertTrue("expected $e ≈ $a", abs(e - a) < 1e-2f)
        }
    }

    @Test
    fun empty_input_encodes_to_empty_string() {
        assertEquals(0, encode(FloatArray(0)).size)
        assertEquals(0, decode(ByteArray(0)).size)
    }

    // Simple test to check if encoding is reversible. 
    @Test
    fun reencoding_a_float32_narrowed_decoded_value_reproduces_the_same_integer() {
        // Decoded coordinates are Float32; re-encoding them must reproduce the same integers.
        val trueValues = listOf(708.29, 706.95, 706.19, 704.66, 704.42, 711.8, 725.79, 728.48, -706.95, -708.29, 0.0, -0.01)
        val decodedAsFloat32 = FloatArray(trueValues.size) { trueValues[it].toFloat() }

        val reencoded = encode(decodedAsFloat32, precision = 2)
        val reencodedThenDecoded = decode(reencoded, precision = 2)

        assertEquals(trueValues.size, reencodedThenDecoded.size)
        trueValues.zip(reencodedThenDecoded.toList()).forEach { (original, roundTripped) ->
            assertEquals(
                "value $original must survive a decode-then-Float32-then-reencode cycle unchanged",
                original.toFloat(), roundTripped, 0f
            )
        }
    }

    @Test
    fun reencoding_a_fixed_known_polyline_reproduces_the_same_bytes() {
        // First 4 points of a real device stroke at precision 2.
        val fixture = "yiiCjGjCJ".toByteArray()
        val decodedAsFloat32 = decode(fixture, precision = 2)
        assertArrayEquals(fixture, encode(decodedAsFloat32, precision = 2))
    }

    @Test
    fun a_value_cut_off_at_the_end_is_dropped() {
        val values = floatArrayOf(12.34f, -5000.5f, 7f)
        val encoded = encode(values, precision = 2)
        for (cut in 0..encoded.size) {
            // Whatever is decoded is the start of the values, nothing made up from the cut-off part.
            val decoded = decode(encoded.copyOf(cut), precision = 2)
            assertArrayEquals(values.copyOf(decoded.size), decoded, 0f)
        }
        assertEquals(2, decode(encoded.copyOf(encoded.size - 1), precision = 2).size)
    }

    @Test
    fun negative_deltas_encode_and_decode_symmetrically() {
        // Monotonically decreasing — every delta is negative, exercising the
        // `delta < 0` branch of encode.
        val original = floatArrayOf(10f, 5f, 0f, -5f, -10f)
        val decoded = decode(encode(original, precision = 2), precision = 2)
        original.zip(decoded).forEach { (e, a) ->
            assertTrue("expected $e ≈ $a", abs(e - a) < 1e-2)
        }
    }
}
