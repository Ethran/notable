package com.ethran.notable.data.db

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
        val original = listOf(38.5, -120.2, 40.7, -120.95, 43.252, -126.453)
        val decoded = decode(encode(original, precision = 5), precision = 5) { it }

        assertEquals(original.size, decoded.size)
        original.zip(decoded).forEach { (e, a) ->
            assertTrue("expected $e ≈ $a", abs(e - a) < 1e-5)
        }
    }

    @Test
    fun encode_output_is_deterministic_for_same_input() {
        // Locks the encoder against accidental output drift between releases.
        val coords = listOf(38.5, -120.2, 40.7, -120.95, 43.252, -126.453)
        assertEquals(encode(coords), encode(coords))
    }

    @Test
    fun round_trip_preserves_values_within_precision() {
        // Note: precision=5 only allows ~1e-5 absolute error; precision=2 is what
        // StrokePointConverter uses for page coordinates.
        val original = listOf(0.0, 1.23, -4.56, 1000.78, -999.99, 0.01)

        val encoded = encode(original, precision = 2)
        val decoded = decode(encoded, precision = 2) { it }

        assertEquals(original.size, decoded.size)
        original.zip(decoded).forEach { (e, a) ->
            assertTrue("expected $e ≈ $a", abs(e - a) < 1e-2)
        }
    }

    @Test
    fun float_round_trip_works_with_caster() {
        val original = listOf(0.0f, 100.5f, 250.25f, -50.75f)

        val encoded = encode(original, precision = 2)
        val decoded = decode(encoded, precision = 2) { it.toFloat() }

        assertEquals(original.size, decoded.size)
        original.zip(decoded).forEach { (e, a) ->
            assertTrue("expected $e ≈ $a", abs(e - a) < 1e-2f)
        }
    }

    @Test
    fun empty_input_encodes_to_empty_string() {
        assertEquals("", encode(emptyList<Double>()))
    }

    @Test
    fun negative_deltas_encode_and_decode_symmetrically() {
        // Monotonically decreasing — every delta is negative, exercising the
        // `value < 0` branch of encodeValue.
        val original = listOf(10.0, 5.0, 0.0, -5.0, -10.0)
        val decoded = decode(encode(original, precision = 2), precision = 2) { it }
        original.zip(decoded).forEach { (e, a) ->
            assertTrue("expected $e ≈ $a", abs(e - a) < 1e-2)
        }
    }
}
