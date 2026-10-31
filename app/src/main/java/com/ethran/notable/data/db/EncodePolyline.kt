package com.ethran.notable.data.db

import kotlin.math.pow
import kotlin.math.roundToInt

// A 32-bit value takes at most seven 5-bit characters.
private const val MAX_CHARS_PER_VALUE = 7

/**
 * Encodes a polyline using Google's polyline algorithm
 * (See http://code.google.com/apis/maps/documentation/polylinealgorithm.html for more information).
 * The values form a single stream: each one is stored as the difference from the one before.
 *
 * code derived from : https://gist.github.com/ghiermann/ed692322088bb39166a669a8ed3a6d14
 * which was derived from: https://gist.github.com/signed0/2031157
 *
 * @param values coordinates along one axis
 * @return polyline characters, as ASCII bytes
 */
fun encode(values: FloatArray, precision: Int = 5): ByteArray {
    val factor = 10.0.pow(precision)
    val result = ByteArray(values.size * MAX_CHARS_PER_VALUE)
    var length = 0
    var prevValue = 0

    for (value in values) {
        val iValue = (value.toDouble() * factor).roundToInt()
        val delta = iValue - prevValue
        prevValue = iValue

        // Step 2 & 4
        var chunks = if (delta < 0) (delta shl 1).inv() else (delta shl 1)

        // Step 5-10: five bits per character, low bits first; 0x20 marks that more follow
        while (chunks >= 32) {
            result[length++] = (((chunks and 31) or 0x20) + 63).toByte()
            chunks = chunks shr 5
        }
        result[length++] = (chunks + 63).toByte()
    }

    return result.copyOf(length)
}


/**
 * Decodes a polyline that has been encoded using Google's algorithm
 * (http://code.google.com/apis/maps/documentation/polylinealgorithm.html)
 *
 * code derived from : https://gist.github.com/ghiermann/ed692322088bb39166a669a8ed3a6d14
 * which was derived from: https://gist.github.com/signed0/2031157
 *
 * @param polyline polyline characters, as ASCII bytes
 * @return the values, rounded to [precision] decimals; a value cut off at the end is dropped
 */
fun decode(polyline: ByteArray, precision: Int = 5): FloatArray {
    val factor = 10.0.pow(precision)
    // Every value ends with a character that has no 0x20 bit.
    val points = FloatArray(polyline.count { ((it - 63) and 0x20) == 0 })
    var count = 0
    var prevValue = 0
    var coordinate = 0
    var shift = 0

    for (char in polyline) {
        // convert each character to decimal from ascii
        val value = char - 63
        coordinate = coordinate or ((value and 0x1F) shl shift)
        shift += 5

        // values that have a chunk following have an extra 1 on the left
        if ((value and 0x20) == 0) {
            // there is a 1 on the right if the coordinate is negative
            if (coordinate and 0x1 > 0)
                coordinate = coordinate.inv()
            coordinate = coordinate shr 1

            prevValue += coordinate
            points[count++] = (prevValue / factor).toFloat()
            coordinate = 0
            shift = 0
        }
    }
    return points
}
