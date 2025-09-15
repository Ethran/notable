package com.ethran.notable.db

import android.util.Log
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.db.decode
import com.ethran.notable.data.db.encode
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sqrt

class EncodingTest {
    @Test
    fun simpleTest() {

        fun assertAlmostEqual(expected: List<Float>, actual: List<Float>, precision: Int) {
            val tolerance = 1.0 / 10.0.pow(precision)
            for (i in expected.indices) {
                if (kotlin.math.abs(expected[i] - actual[i]) > tolerance) {
                    throw AssertionError("Mismatch at $i: expected=${expected[i]} actual=${actual[i]}")
                }
            }
        }
        // Test data
        val floatList = listOf(12.34567f, 12.34667f, 12.40000f, 100.9f, 11.777777f)
        val intList = listOf(100, 150, 200, -19, -100)

        // Precisions to test
        val precisions = listOf(5, 1, 3, 6)

        for (precision in precisions) {
            println("=== Testing precision $precision ===")

            // Floats
            val encodedFloats = encode(floatList, precision)
            val decodedFloats = decode(encodedFloats, precision) { it.toFloat() }
            println("Floats original: $floatList")
            println("Floats encoded : $encodedFloats")
            println("Floats decoded : $decodedFloats")
            assertAlmostEqual(floatList, decodedFloats, precision)

            // Ints
            val encodedInts = encode(intList, precision)
            val decodedInts = decode(encodedInts, precision) { it.toInt() }
            println("Ints original: $intList")
            println("Ints encoded : $encodedInts")
            println("Ints decoded : $decodedInts")
            require(intList == decodedInts) { "Decoded ints do not match original" }
        }
    }
    fun analyzeStroke(points: List<StrokePoint>, tag: String = "StrokeAnalysis") {
        if (points.isEmpty()) {
            Log.d(tag, "No points to analyze")
            return
        }

        fun <T : Number> stats(values: List<T?>): String {
            val valid = values.filterNotNull().map { it.toDouble() }
            if (valid.isEmpty()) return "no data"

            val min = valid.minOrNull()!!
            val max = valid.maxOrNull()!!
            val mean = valid.average()
            val variance = valid.map { (it - mean).pow(2) }.average()
            val stdDev = sqrt(variance)

            return "min=$min, max=$max, mean=$mean, variance=$variance, stdDev=$stdDev, range=${max - min}"
        }

        Log.d(tag, "---- Stroke Analysis ----")
        Log.d(tag, "x: ${stats(points.map { it.x })}")
        Log.d(tag, "y: ${stats(points.map { it.y })}")
        Log.d(tag, "pressure: ${stats(points.map { it.pressure })}")
        Log.d(tag, "tiltX: ${stats(points.map { it.tiltX })}")
        Log.d(tag, "tiltY: ${stats(points.map { it.tiltY })}")
        Log.d(tag, "dt: ${stats(points.map { it.dt?.toInt() })}")
        Log.d(tag, "Total points: ${points.size}")
    }


    object CompressionStats {
        private var callCount = 0L
        private var totalRaw = 0L
        private var totalCompressed = 0L
        private var savedCases = 0L
        private var skippedCases = 0L

        // configurable interval
        private const val REPORT_INTERVAL = 1500

        @Synchronized
        fun record(rawSize: Int, compressedSize: Int?, usedCompression: Boolean) {
            callCount++
            totalRaw += rawSize

            if (usedCompression && compressedSize != null) {
                totalCompressed += compressedSize
                if (compressedSize < rawSize) savedCases++ else skippedCases++
            } else {
                totalCompressed += rawSize // treat uncompressed as "same as raw"
                skippedCases++
            }

            if (callCount % REPORT_INTERVAL == 0L) {
                report()
//            reset()
            }
        }

        private fun report() {
            val avgRaw = if (callCount > 0) totalRaw / callCount else 0
            val avgComp = if (callCount > 0) totalCompressed / callCount else 0
            val ratio = if (totalRaw > 0) totalCompressed.toDouble() / totalRaw else 1.0
            println(
                """
            [CompressionStats]
            Calls: $callCount
            Avg Raw Size: $avgRaw
            Avg Compressed Size: $avgComp
            Compression Ratio: ${"%.3f".format(ratio)}
            Successful Savings: $savedCases
            Skipped (no gain or too small): $skippedCases
            """.trimIndent()
            )
        }

        private fun reset() {
            callCount = 0
            totalRaw = 0
            totalCompressed = 0
            savedCases = 0
            skippedCases = 0
        }
    }


}


