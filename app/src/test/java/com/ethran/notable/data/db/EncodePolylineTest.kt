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
    fun reencoding_a_float32_narrowed_decoded_value_reproduces_the_same_integer() {
        // Real-world regression: `StrokePointConverter` decodes coordinates into `Float`
        // (StrokePoint.x/y), and Room's own type converter later re-encodes that same
        // `Stroke` when persisting a downloaded page -- so `encode` routinely runs again
        // on a value that has already been narrowed to Float32 by a prior decode. At
        // precision 2, widening a Float32 back to Double can land the scaled value just
        // above OR just below the original integer (which side depends on the value --
        // e.g. below for 708.29 -> 708.28997802734375, above for 706.95 ->
        // 706.95001220703125). `.toInt()` truncates toward zero, so any value that
        // landed just below its integer loses a whole hundredth on re-encode even though
        // nothing was edited. `.roundToInt()` recovers the original integer in both
        // directions. Includes negative coordinates and both narrowing directions.
        val trueValues = listOf(708.29, 706.95, 706.19, 704.66, 704.42, 711.8, 725.79, 728.48, -706.95, -708.29, 0.0, -0.01)
        val decodedAsFloat32 = trueValues.map { it.toFloat() }

        val reencoded = encode(decodedAsFloat32, precision = 2)
        val reencodedThenDecoded = decode(reencoded, precision = 2) { it }

        assertEquals(trueValues.size, reencodedThenDecoded.size)
        trueValues.zip(reencodedThenDecoded).forEach { (original, roundTripped) ->
            assertEquals(
                "value $original must survive a decode-then-Float32-then-reencode cycle unchanged",
                original, roundTripped, 1e-9
            )
        }
    }

    @Test
    fun reencoding_a_fixed_known_polyline_reproduces_the_same_bytes() {
        // Same regression as above, but against a fixed, independently-sourced encoded
        // fixture (not one derived from calling `encode` under test) -- checks the
        // actual byte-level round trip a real Notable sync cycle performs: an already-
        // encoded polyline gets decoded to Float32 (as `StrokePoint.x/y` are), then
        // re-encoded when Room persists the resulting `Stroke`. This fixture is the
        // first 4 points (708.29, 706.95, 706.25, 706.19) of a real 26-point stroke
        // captured on a real device during this bug's investigation, at precision 2.
        val fixture = "yiiCjGjCJ"
        val decodedAsFloat32 = decode(fixture, precision = 2) { it.toFloat() }
        assertEquals(fixture, encode(decodedAsFloat32, precision = 2))
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
