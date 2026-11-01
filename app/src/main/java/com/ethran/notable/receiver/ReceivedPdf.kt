package com.ethran.notable.receiver

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The largest PDF the receiver takes. */
const val MAX_PDF_BYTES = 200L * 1024 * 1024

/** Leaves room for the temporary `.<name>.<random>.part` under the filesystem's 255-byte limit. */
private const val MAX_NAME_BYTES = 200

private val PDF_MAGIC = "%PDF-".encodeToByteArray()

/**
 * Whether [name] may be written in the receive folder: a plain `.pdf` file name, so a push can't
 * reach outside the folder, and can't collide with the temporary files.
 */
fun isAllowedName(name: String): Boolean =
    name.isNotEmpty() &&
        name.encodeToByteArray().size <= MAX_NAME_BYTES &&
        '/' !in name && '\u0000' !in name &&
        !name.startsWith('.') &&
        name.endsWith(".pdf", ignoreCase = true)

sealed interface WriteResult {
    data object Written : WriteResult
    data object NotPdf : WriteResult
    data object TooLarge : WriteResult

    /** The body ended before its announced length: the sender stopped, or the link broke. */
    data object Incomplete : WriteResult
    data object Unreadable : WriteResult
    data class Failed(val error: IOException) : WriteResult
}

/**
 * Writes [body], [length] bytes long, over [target] so that nothing ever reads half a file: into a
 * temporary file next to it, checked to be complete and a PDF of at least one page
 * ([countPages]), then renamed over [target]. On any failure [target] is left as it was.
 */
fun writeReceivedPdf(
    body: InputStream,
    length: Long,
    target: File,
    countPages: (File) -> Int,
    maxBytes: Long = MAX_PDF_BYTES,
): WriteResult {
    val dir = target.absoluteFile.parentFile
    var temp: File? = null
    try {
        dir.mkdirs()
        // Unique per push: two pushes of one name may arrive together.
        temp = File.createTempFile(".${target.name}.", ".part", dir)
        FileOutputStream(temp).use { out ->
            val magic = body.readUpTo(PDF_MAGIC.size)
            if (!magic.contentEquals(PDF_MAGIC)) return WriteResult.NotPdf
            out.write(magic)
            var total = magic.size.toLong()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = body.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return WriteResult.TooLarge
                out.write(buffer, 0, read)
            }
            // The server reports a cut-off body as an ordinary end; a PDF reader may still
            // repair it into a few pages.
            if (total != length) return WriteResult.Incomplete
            out.fd.sync()
        }
        if (countPages(temp) <= 0) return WriteResult.Unreadable
        Files.move(
            temp.toPath(), target.toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
        )
        return WriteResult.Written
    } catch (e: IOException) {
        return WriteResult.Failed(e)
    } finally {
        temp?.delete()
    }
}

/** Deletes temporary files a crash left behind in [dir]. */
fun deleteLeftoverParts(dir: File) {
    dir.listFiles { file -> file.name.startsWith(".") && file.name.endsWith(".part") }
        ?.forEach { it.delete() }
}

private fun InputStream.readUpTo(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var filled = 0
    while (filled < count) {
        val read = read(bytes, filled, count - filled)
        if (read < 0) break
        filled += read
    }
    return bytes.copyOf(filled)
}
