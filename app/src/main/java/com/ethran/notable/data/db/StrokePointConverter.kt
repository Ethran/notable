package com.ethran.notable.data.db

import android.util.Log
import com.ethran.notable.TAG
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import net.jpountz.lz4.LZ4Factory
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
    if (p.tiltX != null) m = m or TILT_X_MASK
    if (p.tiltY != null) m = m or TILT_Y_MASK
    if (p.dt != null) m = m or DT_MASK
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

/* ------------------ SB1 Encoding ------------------ *//*
Header (little-endian):
     MAGIC0 (1 byte) = 'S'
     MAGIC1 (1 byte) = 'B'
     VERSION (1 byte) = 1
     MASK (1 byte)
     COUNT (4 bytes, Int)
     COMPRESSION (1 byte) = 0 (no), 1 (LZ4)
   Body (compressed or uncompressed):
     [if compression] uncompressed size (4 bytes, Int)
     X_SIZE (4 bytes, Int)
     X_DATA [X_SIZE]
     Y_SIZE (4 bytes, Int)
     Y_DATA [Y_SIZE]
     [if mask&PRESSURE] pressure[count] int16
     [if mask&TILT_X]   tiltX[count] int8
     [if mask&TILT_Y]   tiltY[count] int8
     [if mask&DT]       dt[count] uint16
--------
Notes:
- ENCODE_SIZE is always present for each channel, regardless of encoding.
- COUNT is the logical number of points for the stroke (for metadata or array allocation).
- All arrays must be uniform per stroke (no per-point nulls).
- Optional: In future, add ENCODE_TYPE, FLAGS, or META fields per channel for more flexibility.
*/

private const val MAGIC0: Byte = 'S'.code.toByte()
private const val MAGIC1: Byte = 'B'.code.toByte()
private const val FORMAT_VERSION: Byte = 1
private const val HEADER_SIZE: Int = 1 + 1 + 1 + 1 + 1 + 4

// Compression flag values
private const val COMPRESSION_NONE: Byte = 0
private const val COMPRESSION_LZ4: Byte = 1
private const val MIN_BYTES_FOR_COMPRESSION: Int = 512

// Reserved for future use if you ever support per-point null dt in a later version.
private const val DT_NULL_SENTINEL_INT = 0xFFFF  // 65535
private const val DT_MAX_VALUE_INT = DT_NULL_SENTINEL_INT - 1  // 65534
private const val MIN_SAVING_RATIO: Double = 0.75

// Encoding precision constants
private const val ENCODING_PRECISION_XY = 2


/* ------------------ LZ4 Helpers ------------------ */

private val lz4Factory = LZ4Factory.fastestInstance()
private val compressor = lz4Factory.highCompressor() // slower, but smaller size

private fun lz4CompressBlock(raw: ByteArray): ByteArray {
    val maxCompressedLength = compressor.maxCompressedLength(raw.size)
    val compressed = ByteArray(maxCompressedLength)
    val compressedSize = compressor.compress(raw, 0, raw.size, compressed, 0, maxCompressedLength)
    return compressed.copyOf(compressedSize) // trim unused tail
}

private val decompressor = lz4Factory.fastDecompressor()

// Thread-local reusable compression buffer to avoid reallocating for each stroke.
private val TL_DECOMP_BUFFER = object : ThreadLocal<ByteArray>() {
    override fun initialValue(): ByteArray = ByteArray(0)
}

private fun lz4DecompressBlock(
    compressed: ByteArray,
    offset: Int,
    length: Int,
    originalSize: Int
): ByteArray {
    var restored = TL_DECOMP_BUFFER.get()
    if (restored!!.size < originalSize) {
        restored = ByteArray(originalSize)
        TL_DECOMP_BUFFER.set(restored)
    }
    decompressor.decompress(compressed, offset, restored, 0, originalSize)
    return restored
}


private fun compress(rawBody: ByteArray): Pair<Byte, ByteArray> {
    return if (rawBody.size >= MIN_BYTES_FOR_COMPRESSION) {
        val compressed = lz4CompressBlock(rawBody)
        if (compressed.size.toDouble() <= rawBody.size * MIN_SAVING_RATIO) {
            CompressionStats.record(rawBody.size, compressed.size, true)
            COMPRESSION_LZ4 to compressed
        } else {
            CompressionStats.record(rawBody.size, compressed.size, false)
            COMPRESSION_NONE to rawBody
        }
    } else {
        COMPRESSION_NONE to rawBody
    }
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


/* ------------------ Encoding ------------------ */

private val TL_ENCODING_BUFFER = object : ThreadLocal<ByteBuffer>() {
    override fun initialValue(): ByteBuffer =
        ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
}

fun getReusableBuffer(rawBodySize: Int): ByteBuffer {
    var bodyBuffer = TL_ENCODING_BUFFER.get()
    // Resize the buffer if it's smaller than needed
    if (bodyBuffer!!.capacity() < rawBodySize) {
        // Allocate a new buffer and replace the old one in the ThreadLocal
        bodyBuffer = ByteBuffer.allocate(rawBodySize).order(ByteOrder.LITTLE_ENDIAN)
        TL_ENCODING_BUFFER.set(bodyBuffer)
    }
    // Reset the buffer before reuse
    bodyBuffer.clear()
    return bodyBuffer
}


@Suppress("KotlinConstantConditions")
fun encodeStrokePoints(
    points: List<StrokePoint>, mask: Int = computeStrokeMask(points)
): ByteArray {
    if (points.first().y > 1000000f) {
        Log.e(TAG, "Page is too large!")
        SnackState.globalSnackFlow.tryEmit(
            SnackConf(
                id = "oversize", text = "Page is too large!", duration = 4000
            )
        )
        throw IllegalArgumentException("Page is too large!")
    }
    val count = points.size
    require(count > 0) { "Empty point list" }
    // Enforce SB1 invariant before writing (gives clear error instead of NPE).
    validateUniform(mask, points)

    // encode mandatory data, using Polyline
    val encodedX =
        encode(points.map { it.x }, precision = ENCODING_PRECISION_XY).toByteArray(Charsets.UTF_8)
    val encodedY =
        encode(points.map { it.y }, precision = ENCODING_PRECISION_XY).toByteArray(Charsets.UTF_8)

    val hasP = mask.hasPressure()
    val hasTX = mask.hasTiltX()
    val hasTY = mask.hasTiltY()
    val hasDT = mask.hasDeltaTime()

    // Build raw (uncompressed) body first to optionally compress
    val rawBodySize =
        4 + encodedX.size + 4 + encodedY.size + (if (hasP) count * 2 else 0) + (if (hasTX) count * 1 else 0) + (if (hasTY) count * 1 else 0) + (if (hasDT) count * 2 else 0)

    val bodyBuffer = getReusableBuffer(rawBodySize)
    // Body layout
    bodyBuffer.putInt(encodedX.size)
    bodyBuffer.put(encodedX)
    bodyBuffer.putInt(encodedY.size)
    bodyBuffer.put(encodedY)

    if (hasP) {
        for (p in points) bodyBuffer.putShort(p.pressure!!.toInt().toShort())
    }
    if (hasTX) {
        for (p in points) bodyBuffer.put(p.tiltX!!.toByte())
    }
    if (hasTY) {
        for (p in points) bodyBuffer.put(p.tiltY!!.toByte())
    }
    if (hasDT) {
        for (p in points) {
            val v = p.dt!!.toInt().coerceIn(0, DT_MAX_VALUE_INT)
            bodyBuffer.putShort(v.toShort())
        }
    }
    bodyBuffer.flip() // Ready for reading
    val rawBody = ByteArray(bodyBuffer.remaining()) // Extract only valid data
    bodyBuffer.get(rawBody) // Copy from position to limit into new array
    require(rawBodySize == rawBody.size) { "Body size mismatch: expected $rawBodySize, got ${rawBody.size}" }

    val (compressionFlag, finalBody) = compress(rawBody)
    // Allocate final buffer: header + body
    val totalSize =
        HEADER_SIZE + finalBody.size + (if (compressionFlag == COMPRESSION_LZ4) 4 else 0)
    val out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
    // Header
    out.put(MAGIC0)
    out.put(MAGIC1)
    out.put(FORMAT_VERSION)
    out.put(mask.toByte())
    out.putInt(count)
    out.put(compressionFlag)

    if (compressionFlag == COMPRESSION_LZ4)
        out.putInt(rawBodySize) // needed for decompression

    // Body (compressed or raw)
    out.put(finalBody)

    return out.array()
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
fun decodeStrokePoints(bytes: ByteArray): List<StrokePoint> {
    if (bytes.size < HEADER_SIZE + 8) {
        throw IllegalArgumentException("Buffer too small for SB1 header (need $HEADER_SIZE bytes)")
    }
//    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val header = ByteBuffer.wrap(bytes, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)


    val m0 = header.get()
    val m1 = header.get()
    if (m0 != MAGIC0 || m1 != MAGIC1) {
        throw IllegalArgumentException("Bad magic, not a Stroke binary")
    }
    val version = header.get()
    if (version > FORMAT_VERSION) {
        throw IllegalArgumentException("Unsupported version: $version")
    }
    val mask = header.get().toInt() and 0xFF
    val count = header.int
    if (count < 0) throw IllegalArgumentException("Negative point count")
    val compressionFlag = header.get()


    val buffer: ByteBuffer = when (compressionFlag) {
        COMPRESSION_NONE -> {
            ByteBuffer.wrap(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
        }
        COMPRESSION_LZ4 -> {
            require(bytes.size > HEADER_SIZE + 4) { "Truncated (missing raw size)" }
            val rawSize = ByteBuffer.wrap(bytes, HEADER_SIZE, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int
            require(rawSize > 0) { "Invalid raw size $rawSize" }
            val compressedDataOffset = HEADER_SIZE + 4
            val compressedLen = bytes.size - compressedDataOffset
            require(compressedLen > 0) { "No compressed data" }
            val decompressed = lz4DecompressBlock(
                compressed = bytes,
                offset = compressedDataOffset,
                length = compressedLen,
                originalSize = rawSize
            )
            ByteBuffer.wrap(decompressed).order(ByteOrder.LITTLE_ENDIAN)
        }

        else -> error("Unknown compression flag $compressionFlag")
    }


    // decode mandatory coordinates
    val xs = getDecodedListFloat(buffer, ENCODING_PRECISION_XY)
    val ys = getDecodedListFloat(buffer, ENCODING_PRECISION_XY)
    require(xs.size == count && ys.size == count) { "Point count mismatch, xs=${xs.size} ys=${ys.size} count=$count, $ys" }

    val pressures: ShortArray? = if (mask.hasPressure()) {
        if (buffer.remaining() < count * 2) throw IllegalArgumentException("Truncated pressure section")
        ShortArray(count) { buffer.short }
    } else null

    val tiltXs: ByteArray? = if (mask.hasTiltX()) {
        if (buffer.remaining() < count * 1) throw IllegalArgumentException("Truncated tiltX section")
        ByteArray(count) { buffer.get() }
    } else null

    val tiltYs: ByteArray? = if (mask.hasTiltY()) {
        if (buffer.remaining() < count * 1) throw IllegalArgumentException("Truncated tiltY section")
        ByteArray(count) { buffer.get() }
    } else null

    val dts: ShortArray? = if (mask.hasDeltaTime()) {
        if (buffer.remaining() < count * 2) throw IllegalArgumentException("Truncated dt section")
        ShortArray(count) { buffer.short }
    } else null

    val points = List(count) { i ->
        StrokePoint(
            x = xs[i],
            y = ys[i],
            pressure = pressures?.getOrNull(i)?.toFloat(),
            tiltX = tiltXs?.getOrNull(i)?.toInt(),
            tiltY = tiltYs?.getOrNull(i)?.toInt(),
            dt = dts?.getOrNull(i)?.toUShort(),
            legacyTimestamp = null,
            legacySize = null
        )
    }
    if (buffer.hasRemaining() && compressionFlag == COMPRESSION_NONE) {
        throw IllegalArgumentException("Trailing bytes after decoding stroke")
    }
    return points

}


fun getDecodedListFloat(buffer: ByteBuffer, precision: Int): List<Float> {
    val size = buffer.int
    val bytes = ByteArray(size)
    if (buffer.remaining() < size) {
        throw IllegalArgumentException("Truncated coordinates section")
    }
    buffer.get(bytes)
    return decode(String(bytes, Charsets.UTF_8), precision) { it.toFloat() }
}