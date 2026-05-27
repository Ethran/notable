package com.ethran.notable.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EncodePolylineTest {

    @Test
    fun encode_matches_google_reference_vector() {
        // Canonical example from Google's polyline algorithm documentation.
        // Coordinates 38.5, -120.2, 40.7, -120.95, 43.252, -126.453 → "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        val coords = listOf(38.5, -120.2, 40.7, -120.95, 43.252, -126.453)
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", encode(coords))
    }

    @Test
    fun decode_inverts_encode_for_the_reference_vector() {
        val encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        val decoded = decode(encoded, precision = 5) { it }
        val expected = listOf(38.5, -120.2, 40.7, -120.95, 43.252, -126.453)

        assertEquals(expected.size, decoded.size)
        expected.zip(decoded).forEach { (e, a) ->
            assertTrue("expected $e ≈ $a", abs(e - a) < 1e-5)
        }
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
