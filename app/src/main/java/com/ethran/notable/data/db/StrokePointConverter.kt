package com.ethran.notable.data.db

import java.nio.ByteBuffer
import java.nio.ByteOrder


/* ------------------ Mask Bits & Helpers ------------------ */

private const val PRESSURE_BIT = 0
private const val TILT_X_BIT = 1
private const val TILT_Y_BIT = 2
private const val DT_BIT = 3

private const val PRESSURE_MASK = 1 shl PRESSURE_BIT
private const val TILT_X_MASK = 1 shl TILT_X_BIT
private const val TILT_Y_MASK = 1 shl TILT_Y_BIT
private const val DT_MASK = 1 shl DT_BIT

fun Int.hasPressure() = (this and PRESSURE_MASK) != 0
fun Int.hasTiltX() = (this and TILT_X_MASK) != 0
fun Int.hasTiltY() = (this and TILT_Y_MASK) != 0
fun Int.hasDeltaTime() = (this and DT_MASK) != 0

fun computeStrokeMask(points: List<StrokePoint>): Int {
    var m = 0
    for (p in points) {
        if (p.pressure != null) m = m or PRESSURE_MASK
        if (p.tiltX != null) m = m or TILT_X_MASK
        if (p.tiltY != null) m = m or TILT_Y_MASK
        if (p.dt != null) m = m or DT_MASK
        if (m == (PRESSURE_MASK or TILT_X_MASK or TILT_Y_MASK or DT_MASK)) break
    }
    return m
}

/* ------------------ Encoding ------------------ */
// version and mask:
private const val MAGIC0: Byte = 'S'.code.toByte()
private const val MAGIC1: Byte = 'B'.code.toByte()
private const val FORMAT_VERSION: Byte = 1
private const val DT_NULL_SENTINEL = 0xFFFF // 65535 reserved for null (limit dt to 0..65534)


/**
 * Encode list of points into a compact SoA binary layout with a single stroke-level mask.
 * Any point-level null for a field where the mask bit is set is written as a sentinel.
 */
fun encodeStrokePoints(
    points: List<StrokePoint>,
    mask: Int = computeStrokeMask(points)
): ByteArray {
    val count = points.size

    // Precompute arrays for optional fields to minimize branching in size calc
    val pressureArray: FloatArray? = if (mask.hasPressure()) FloatArray(count) else null
    val tiltXArray: IntArray? = if (mask.hasTiltX()) IntArray(count) else null
    val tiltYArray: IntArray? = if (mask.hasTiltY()) IntArray(count) else null
    val dtArray: IntArray? =
        if (mask.hasDeltaTime()) IntArray(count) else null // store unsigned 16 or sentinel

    if (pressureArray != null) {
        for (i in 0 until count) {
            pressureArray[i] = points[i].pressure ?: Float.NaN
        }
    }
    if (tiltXArray != null) {
        for (i in 0 until count) {
            tiltXArray[i] = points[i].tiltX ?: Int.MIN_VALUE
        }
    }
    if (tiltYArray != null) {
        for (i in 0 until count) {
            tiltYArray[i] = points[i].tiltY ?: Int.MIN_VALUE
        }
    }
    if (dtArray != null) {
        for (i in 0 until count) {
            val dt = points[i].dt
            dtArray[i] = dt?.toInt()?.coerceIn(0, DT_NULL_SENTINEL - 1) ?: DT_NULL_SENTINEL
        }
    }

    // Size calculation:
    // header (2 + 1 + 1 + 4) + mandatory coords (count * 8)
    var size = 2 + 1 + 1 + 4 + count * (4 + 4)
    if (pressureArray != null) size += count * 4
    if (tiltXArray != null) size += count * 4
    if (tiltYArray != null) size += count * 4
    if (dtArray != null) size += count * 2

    val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put(MAGIC0)
    buffer.put(MAGIC1)
    buffer.put(FORMAT_VERSION)
    buffer.put(mask.toByte())
    buffer.putInt(count)

    // Mandatory coordinates
    for (p in points) {
        buffer.putFloat(p.x)
        buffer.putFloat(p.y)
    }

    // Optional sections
    if (pressureArray != null) {
        for (v in pressureArray) buffer.putFloat(v)
    }
    if (tiltXArray != null) {
        for (v in tiltXArray) buffer.putInt(v)
    }
    if (tiltYArray != null) {
        for (v in tiltYArray) buffer.putInt(v)
    }
    if (dtArray != null) {
        for (v in dtArray) buffer.putShort(v.toShort())
    }

    return buffer.array()
}


fun decodeStrokePoints(bytes: ByteArray): List<StrokePoint> {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (buffer.remaining() < 2 + 1 + 1 + 4) {
        throw IllegalArgumentException("Buffer too small for header")
    }
    val m0 = buffer.get()
    val m1 = buffer.get()
    if (m0 != MAGIC0 || m1 != MAGIC1) {
        throw IllegalArgumentException("Bad magic, not a Stroke binary")
    }
    val version = buffer.get()
    if (version > FORMAT_VERSION) {
        throw IllegalArgumentException("Unsupported version: $version")
    }
    val mask = buffer.get().toInt() and 0xFF
    val count = buffer.int
    if (count < 0) throw IllegalArgumentException("Negative point count")

    if (buffer.remaining() < count * 8) {
        throw IllegalArgumentException("Truncated coordinates section")
    }
    val xs = FloatArray(count)
    val ys = FloatArray(count)
    for (i in 0 until count) {
        xs[i] = buffer.float
        ys[i] = buffer.float
    }

    val pressures: FloatArray? = if (mask.hasPressure()) {
        if (buffer.remaining() < count * 4) throw IllegalArgumentException("Truncated pressure section")
        FloatArray(count) { buffer.float }
    } else null

    val tiltXs: IntArray? = if (mask.hasTiltX()) {
        if (buffer.remaining() < count * 4) throw IllegalArgumentException("Truncated tiltX section")
        IntArray(count) { buffer.int }
    } else null

    val tiltYs: IntArray? = if (mask.hasTiltY()) {
        if (buffer.remaining() < count * 4) throw IllegalArgumentException("Truncated tiltY section")
        IntArray(count) { buffer.int }
    } else null

    val dts: IntArray? = if (mask.hasDeltaTime()) {
        if (buffer.remaining() < count * 2) throw IllegalArgumentException("Truncated dt section")
        IntArray(count) { buffer.short.toInt() and 0xFFFF }
    } else null

    val points = ArrayList<StrokePoint>(count)
    for (i in 0 until count) {
        val pressure = pressures?.get(i)?.let { if (it.isNaN()) null else it }
        val tiltX = tiltXs?.get(i)?.let { if (it == Int.MIN_VALUE) null else it }
        val tiltY = tiltYs?.get(i)?.let { if (it == Int.MIN_VALUE) null else it }
        val dt = dts?.get(i)?.let { if (it == DT_NULL_SENTINEL) null else it.toUShort() }

        points.add(
            StrokePoint(
                x = xs[i],
                y = ys[i],
                pressure = pressure,
                tiltX = tiltX,
                tiltY = tiltY,
                dt = dt,
                legacyTimestamp = null,
                legacySize = null
            )
        )
    }

    return points
}
