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

/**
 * Compute mask strictly from the first point.
 * SB1 invariant: if a bit is set, ALL points must have that field non-null;
 * if a bit is clear, ALL points must have that field null.
 * Validation is performed separately to produce a clear error message.
 */
fun computeStrokeMask(points: List<StrokePoint>): Int {
    require(points.isNotEmpty()) { "Empty point list" }
    val p = points[0]
    var m = 0
    if (p.pressure != null) m = m or PRESSURE_MASK
    if (p.tiltX != null)    m = m or TILT_X_MASK
    if (p.tiltY != null)    m = m or TILT_Y_MASK
    if (p.dt != null)       m = m or DT_MASK
    return m
}

/**
 * Validates that presence/absence of each optional field is UNIFORM across the stroke,
 * matching the mask derived from the first point.
 */
private fun validateUniform(mask: Int, points: List<StrokePoint>) {
    if (mask.hasPressure()) {
        require(points.all { it.pressure != null }) { "Mask indicates pressure present for all points, but a null was found." }
    } else {
        require(points.all { it.pressure == null }) { "Mask indicates no pressure, but a non-null value was found." }
    }
    if (mask.hasTiltX()) {
        require(points.all { it.tiltX != null }) { "Mask indicates tiltX present for all points, but a null was found." }
    } else {
        require(points.all { it.tiltX == null }) { "Mask indicates no tiltX, but a non-null value was found." }
    }
    if (mask.hasTiltY()) {
        require(points.all { it.tiltY != null }) { "Mask indicates tiltY present for all points, but a null was found." }
    } else {
        require(points.all { it.tiltY == null }) { "Mask indicates no tiltY, but a non-null value was found." }
    }
    if (mask.hasDeltaTime()) {
        require(points.all { it.dt != null }) { "Mask indicates dt present for all points, but a null was found." }
    } else {
        require(points.all { it.dt == null }) { "Mask indicates no dt, but a non-null value was found." }
    }
}

/* ------------------ Encoding ------------------ */
/*
Header (little-endian):
  MAGIC0 (1 byte) = 'S'
  MAGIC1 (1 byte) = 'B'
  VERSION (1 byte) = 1
  MASK (1 byte)
  COUNT (4 bytes, Int)
Then sections in Structure-of-Arrays (SoA) order:
  x[count] float, y[count] float
  [if mask&PRESSURE] pressure[count] float
  [if mask&TILT_X]   tiltX[count] int
  [if mask&TILT_Y]   tiltY[count] int
  [if mask&DT]       dt[count] uint16
SB1: data must be uniform per stroke (no per-point nulls). Future versions may enable sparse/null-per-point with sentinels.
*/

private const val MAGIC0: Byte = 'S'.code.toByte()
private const val MAGIC1: Byte = 'B'.code.toByte()
private const val FORMAT_VERSION: Byte = 1
private const val HEADER_SIZE: Int = 1 + 1 + 1 + 1 + 4

// Reserved for future use if you ever support per-point null dt in a later version.
private const val DT_NULL_SENTINEL_INT = 0xFFFF  // 65535
private val DT_MAX_VALUE_INT = DT_NULL_SENTINEL_INT - 1  // 65534

@Suppress("KotlinConstantConditions")
fun encodeStrokePoints(
    points: List<StrokePoint>,
    mask: Int = computeStrokeMask(points)
): ByteArray {
    val count = points.size
    require(count > 0) { "Empty point list" }

    // Enforce SB1 invariant before writing (gives clear error instead of NPE).
    validateUniform(mask, points)

    val hasP = mask.hasPressure()
    val hasTX = mask.hasTiltX()
    val hasTY = mask.hasTiltY()
    val hasDT = mask.hasDeltaTime()

    // Size calculation
    var size = HEADER_SIZE + count * (4 + 4)
    if (hasP)  size += count * 4
    if (hasTX) size += count * 4
    if (hasTY) size += count * 4
    if (hasDT) size += count * 2
    require(size >= HEADER_SIZE) { "Invalid encoded size" }

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

    // Optional sections (safe to use !! after uniform validation)
    if (hasP) {
        for (p in points) buffer.putFloat(p.pressure!!)
    }
    if (hasTX) {
        for (p in points) buffer.putInt(p.tiltX!!)
    }
    if (hasTY) {
        for (p in points) buffer.putInt(p.tiltY!!)
    }
    if (hasDT) {
        for (p in points) {
            val v = p.dt!!.toInt().coerceIn(0, DT_MAX_VALUE_INT)
            buffer.putShort(v.toShort()) // lower 16 bits; sign doesn't matter
        }
    }

    return buffer.array()
}

/**
 * Returns the mask stored in the binary header without decoding the points.
 * Throws IllegalArgumentException if header is malformed or version unsupported.
 */
fun getStrokeMask(bytes: ByteArray): Int {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (buf.remaining() < HEADER_SIZE) {
        throw IllegalArgumentException("Buffer too small for header (need $HEADER_SIZE bytes)")
    }
    val m0 = buf.get()
    val m1 = buf.get()
    if (m0 != MAGIC0 || m1 != MAGIC1) {
        throw IllegalArgumentException("Bad magic, not a Stroke binary")
    }
    val version = buf.get()
    if (version > FORMAT_VERSION) {
        throw IllegalArgumentException("Unsupported version: $version")
    }
    return buf.get().toInt() and 0xFF
}

/* ------------------ Decoding ------------------ */

/**
 * SB1 decoding assumes uniform presence per mask. It still tolerates a future dt null sentinel:
 * if a decoded dt equals 0xFFFF, it returns null for that field.
 */
@Suppress("KotlinConstantConditions")
fun decodeStrokePoints(
    bytes: ByteArray,
    failOnTrailing: Boolean = false
): List<StrokePoint> {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (buffer.remaining() < HEADER_SIZE) {
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

    val hasP = mask.hasPressure()
    val hasTX = mask.hasTiltX()
    val hasTY = mask.hasTiltY()
    val hasDT = mask.hasDeltaTime()

    val pressures: FloatArray? = if (hasP) {
        if (buffer.remaining() < count * 4) throw IllegalArgumentException("Truncated pressure section")
        FloatArray(count) { buffer.float }
    } else null

    val tiltXs: IntArray? = if (hasTX) {
        if (buffer.remaining() < count * 4) throw IllegalArgumentException("Truncated tiltX section")
        IntArray(count) { buffer.int }
    } else null

    val tiltYs: IntArray? = if (hasTY) {
        if (buffer.remaining() < count * 4) throw IllegalArgumentException("Truncated tiltY section")
        IntArray(count) { buffer.int }
    } else null

    // Store raw signed shorts; convert to UShort at materialization time
    val dts: ShortArray? = if (hasDT) {
        if (buffer.remaining() < count * 2) throw IllegalArgumentException("Truncated dt section")
        ShortArray(count) { buffer.short }
    } else null

    val points = ArrayList<StrokePoint>(count)
    for (i in 0 until count) {
        val pressure = pressures?.get(i) // SB1 expects non-null if section present
        val tiltX = tiltXs?.get(i)
        val tiltY = tiltYs?.get(i)

        val dt = dts?.get(i)?.let { s ->
            val unsigned = s.toInt() and 0xFFFF
            if (unsigned == DT_NULL_SENTINEL_INT) null else unsigned.toUShort()
        }

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

    if (failOnTrailing && buffer.hasRemaining()) {
        throw IllegalArgumentException("Trailing bytes after decoding stroke")
    }

    return points
}