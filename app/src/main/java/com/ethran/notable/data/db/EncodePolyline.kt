package com.ethran.notable.data.db

import kotlin.math.pow
import kotlin.math.round


/**
 * Encodes a polyline using Google's polyline algorithm
 * (See http://code.google.com/apis/maps/documentation/polylinealgorithm.html for more information).
 *
 * code derived from : https://gist.github.com/ghiermann/ed692322088bb39166a669a8ed3a6d14
 * which was derived from: https://gist.github.com/signed0/2031157
 *
 * @param (x,y)-Coordinates
 * @return polyline-string
 */
fun <T : Number> encode(coords: List<T>, precision: Int = 5): String {
    val result: MutableList<String> = mutableListOf()
    var prevValue = 0

    for (value in coords) {
        val iValue = (value.toDouble() * 10.0.pow(precision)).toInt()
        val delta = encodeValue(iValue - prevValue)
        prevValue = iValue
        result.add(delta)
    }

    return result.joinToString("")
}
private fun encodeValue(value: Int): String {
    // Step 2 & 4
    val actualValue = if (value < 0) (value shl 1).inv() else (value shl 1)

    // Step 5-8
    val chunks: List<Int> = splitIntoChunks(actualValue)

    // Step 9-10
    return chunks.map { (it + 63).toChar() }.joinToString("")
}

private fun splitIntoChunks(toEncode: Int): List<Int> {
    // Step 5-8
    val chunks = mutableListOf<Int>()
    var value = toEncode
    while(value >= 32) {
        chunks.add((value and 31) or (0x20))
        value = value shr 5
    }
    chunks.add(value)
    return chunks
}


/**
 * Decodes a polyline that has been encoded using Google's algorithm
 * (http://code.google.com/apis/maps/documentation/polylinealgorithm.html)
 *
 * code derived from : https://gist.github.com/ghiermann/ed692322088bb39166a669a8ed3a6d14
 * which was derived from: https://gist.github.com/signed0/2031157
 *
 * @param polyline-string
 * @return (long,lat)-Coordinates
 */
fun <T : Number> decode(polyline: String, precision: Int = 5, caster: (Double) -> T): List<T> {
    val valueChunks: MutableList<MutableList<Int>> = mutableListOf()
    valueChunks.add(mutableListOf())

    for (char in polyline.toCharArray()) {
        // convert each character to decimal from ascii
        var value = char.code - 63

        // values that have a chunk following have an extra 1 on the left
        val isLastOfChunk = (value and 0x20) == 0
        value = value and (0x1F)

        valueChunks.last().add(value)

        if (isLastOfChunk)
            valueChunks.add(mutableListOf())
    }

    valueChunks.removeAt(valueChunks.lastIndex)

    val deltas: MutableList<Double> = mutableListOf()
    for (coordinateChunk in valueChunks) {
        var coordinate = coordinateChunk.mapIndexed { i, chunk -> chunk shl (i * 5) }.reduce { i, j -> i or j }

        // there is a 1 on the right if the coordinate is negative
        if (coordinate and 0x1 > 0)
            coordinate = (coordinate).inv()

        coordinate = coordinate shr 1
        deltas.add(coordinate.toDouble() / 10.0.pow(precision))
    }

    val points: MutableList<T> = mutableListOf()
    var prevValue = 0.0

    for (delta in deltas) {
        prevValue += delta
        points.add(caster(roundToPrecision(prevValue, precision)))
    }
    return points
}

private fun roundToPrecision(value: Double, precision: Int): Double {
    val factor = 10.0.pow(precision)
    return round(value * factor) / factor
}
