# Image Filename Collision Issue

## Problem Statement

The current image handling system preserves original filenames (with sanitization) when users insert images, and uses UUID-based filenames for Xournal++ imports. This creates a collision problem during WebDAV synchronization across multiple devices.

### Collision Scenario

**Timeline:**
1. **Device A** has `photo.jpg` containing a cat picture
   - Syncs to `/Notable/notebooks/{notebookId}/images/photo.jpg` on WebDAV server
2. **Device B** has `photo.jpg` containing a dog picture
   - Attempts to sync to the same path
   - Current sync logic checks: `if (!webdavClient.exists(remotePath))` (SyncEngine.kt:560)
   - Sees `photo.jpg` already exists, skips upload
   - **Result: Device B's dog picture is never uploaded, effectively lost**

**Impact:**
- Data loss: Images with duplicate filenames from different devices are silently skipped
- No user notification of the collision
- Unpredictable behavior: Which device syncs first wins
- Affects user-inserted images from camera/gallery (commonly named `IMG_1234.jpg`, `photo.jpg`, etc.)

## Current Implementation

### File Locations

**User Image Insertion:**
- File: `/home/jtd/notable/app/src/main/java/com/ethran/notable/io/FileUtils.kt`
- Function: `copyImageToDatabase()` (line 134)
- Strategy: Preserves original filename with sanitization via `sanitizeFileName()`

**Xournal++ Import:**
- File: `/home/jtd/notable/app/src/main/java/com/ethran/notable/io/XoppFile.kt`
- Function: `decodeAndSave()` (line 419)
- Strategy: Generates `image_${UUID.randomUUID()}.png`

### Current Filename Generation

```kotlin
// FileUtils.kt - User insertions
fun copyImageToDatabase(context: Context, fileUri: Uri, subfolder: String? = null): File {
    val outputDir = ensureImagesFolder()
    return createFileFromContentUri(context, fileUri, outputDir)  // Uses original name
}

// XoppFile.kt - Xournal++ imports
private fun decodeAndSave(base64String: String): Uri? {
    val fileName = "image_${UUID.randomUUID()}.png"  // UUID-based
    val outputFile = File(outputDir, fileName)
    // ... save bitmap ...
}
```

### Sync Upload Logic

```kotlin
// SyncEngine.kt:554-568
private suspend fun uploadPage(page: Page, notebookId: String, webdavClient: WebDAVClient) {
    for (image in pageWithImages.images) {
        if (image.uri != null) {
            val localFile = File(image.uri)
            if (localFile.exists()) {
                val remotePath = "/Notable/notebooks/$notebookId/images/${localFile.name}"
                if (!webdavClient.exists(remotePath)) {  // ← COLLISION CHECK
                    webdavClient.putFile(remotePath, localFile, detectMimeType(localFile))
                }
            }
        }
    }
}
```

## Proposed Solution

### Content-Based Hashing (SHA-256)

Use SHA-256 hash of file content as the filename. This provides:

**Benefits:**
1. **Collision Prevention:** Different content = different hash = different filename
2. **Automatic Deduplication:** Same content = same hash = same filename
3. **Deterministic:** Same image always produces same filename across all devices
4. **Data Integrity:** Hash acts as content checksum

**Filename Format:**
- Pattern: `<sha256-hash>.<extension>`
- Example: `a3f5b2c8d1e4f7a9b0c3d6e9f2a5b8c1d4e7f0a3b6c9d2e5f8a1b4c7d0e3f6a9.jpg`
- Length: 64 hex characters + extension
- Extension preserved for MIME type detection

### Implementation Approach

#### 1. Create Hash Utility Function

**New file:** `/home/jtd/notable/app/src/main/java/com/ethran/notable/io/ImageHashUtils.kt`

```kotlin
package com.ethran.notable.io

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object ImageHashUtils {
    /**
     * Compute SHA-256 hash of file content.
     * @param file File to hash
     * @return Hex-encoded SHA-256 hash (64 characters)
     */
    fun computeFileHash(file: File): String {
        return file.inputStream().use { stream ->
            computeStreamHash(stream)
        }
    }

    /**
     * Compute SHA-256 hash of stream content.
     * @param stream InputStream to hash
     * @return Hex-encoded SHA-256 hash (64 characters)
     */
    fun computeStreamHash(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead: Int

        while (stream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Generate hash-based filename with original extension.
     * @param file Original file
     * @return Filename in format: <hash>.<ext>
     */
    fun generateHashFilename(file: File): String {
        val hash = computeFileHash(file)
        val extension = file.extension.lowercase()
        return if (extension.isNotEmpty()) {
            "$hash.$extension"
        } else {
            hash
        }
    }
}
```

#### 2. Update FileUtils.kt

**Location:** `/home/jtd/notable/app/src/main/java/com/ethran/notable/io/FileUtils.kt:134`

**Current code:**
```kotlin
fun copyImageToDatabase(context: Context, fileUri: Uri, subfolder: String? = null): File {
    var outputDir = ensureImagesFolder()
    if (subfolder != null) {
        outputDir = File(outputDir, subfolder)
        if (!outputDir.exists())
            outputDir.mkdirs()
    }
    return createFileFromContentUri(context, fileUri, outputDir)
}
```

**Proposed change:**
```kotlin
fun copyImageToDatabase(context: Context, fileUri: Uri, subfolder: String? = null): File {
    var outputDir = ensureImagesFolder()
    if (subfolder != null) {
        outputDir = File(outputDir, subfolder)
        if (!outputDir.exists())
            outputDir.mkdirs()
    }

    // Copy to temp file first to compute hash
    val tempFile = createFileFromContentUri(context, fileUri, context.cacheDir)

    try {
        // Generate hash-based filename
        val hashFilename = ImageHashUtils.generateHashFilename(tempFile)
        val finalFile = File(outputDir, hashFilename)

        // If file with same hash exists, reuse it (deduplication)
        if (finalFile.exists()) {
            tempFile.delete()
            return finalFile
        }

        // Move temp file to final location
        tempFile.copyTo(finalFile, overwrite = false)
        tempFile.delete()
        return finalFile
    } catch (e: Exception) {
        tempFile.delete()
        throw e
    }
}
```

#### 3. Update XoppFile.kt

**Location:** `/home/jtd/notable/app/src/main/java/com/ethran/notable/io/XoppFile.kt:419`

**Current code:**
```kotlin
private fun decodeAndSave(base64String: String): Uri? {
    return try {
        val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size) ?: return null

        val outputDir = ensureImagesFolder()
        val fileName = "image_${UUID.randomUUID()}.png"
        val outputFile = File(outputDir, fileName)

        FileOutputStream(outputFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }

        Uri.fromFile(outputFile)
    } catch (e: IOException) {
        log.e("Error decoding and saving image: ${e.message}")
        null
    }
}
```

**Proposed change:**
```kotlin
private fun decodeAndSave(base64String: String): Uri? {
    return try {
        val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size) ?: return null

        val outputDir = ensureImagesFolder()

        // Save to temp file first to compute hash
        val tempFile = File.createTempFile("temp_image_", ".png", context.cacheDir)
        FileOutputStream(tempFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }

        try {
            // Generate hash-based filename
            val hashFilename = ImageHashUtils.generateHashFilename(tempFile)
            val finalFile = File(outputDir, hashFilename)

            // If file with same hash exists, reuse it (deduplication)
            if (finalFile.exists()) {
                tempFile.delete()
                return Uri.fromFile(finalFile)
            }

            // Move temp file to final location
            tempFile.copyTo(finalFile, overwrite = false)
            tempFile.delete()
            Uri.fromFile(finalFile)
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    } catch (e: IOException) {
        log.e("Error decoding and saving image: ${e.message}")
        null
    }
}
```

## Technical Considerations

### 1. Performance Impact

**Hash Computation Cost:**
- SHA-256 is computationally efficient (~200-400 MB/s on mobile devices)
- For typical images (1-5 MB), hashing takes 5-25ms
- Occurs only during image insertion/import (not during page rendering)
- Acceptable overhead for correctness guarantee

**Memory Usage:**
- Uses 8KB buffer for streaming (minimal memory footprint)
- No need to load entire image into memory

### 2. Migration Strategy

**Existing Images:**
- No migration needed - existing images continue to work
- Old filenames coexist with new hash-based filenames
- New images use hash-based naming going forward
- Natural migration as users add/sync new images

**Backward Compatibility:**
- Database stores absolute paths - no schema changes needed
- JSON sync format unchanged (still uses relative paths)
- Only filename generation changes

### 3. Edge Cases

**Duplicate Detection:**
- If hash filename already exists, reuse existing file (deduplication)
- Saves storage space for identical images across notebooks

**Extension Handling:**
- Preserves original extension for MIME type detection
- Falls back to no extension if none provided

**Filename Length:**
- SHA-256 = 64 hex chars + extension (typically 67-70 chars total)
- Well within filesystem limits (255 chars on most systems)

### 4. Testing Considerations

**Test Scenarios:**
1. Insert same image from two devices → should deduplicate
2. Insert different images with same original name → should create separate files
3. Import Xournal++ with duplicate embedded images → should deduplicate
4. Sync collision scenario → should preserve both images
5. Large image files (10+ MB) → verify hash performance

## Security & Privacy

**Benefits:**
- Hash acts as content checksum (integrity verification)
- No filename-based information leakage (original names not exposed)

**Considerations:**
- SHA-256 is one-way (cannot reverse hash to get image)
- Identical images produce identical hashes (fingerprinting possible but limited to user's own images)

## Alternative Solutions Considered

### 1. UUID-Based Naming (Current for XOPP)
- **Pros:** Guaranteed unique, fast generation
- **Cons:** No deduplication, collision still possible if original name preserved elsewhere
- **Verdict:** Doesn't solve the core problem for user-inserted images

### 2. Filename + Timestamp
- **Pros:** Simple, preserves original name
- **Cons:** Still allows collisions if images inserted at same time, no deduplication
- **Verdict:** Unreliable

### 3. Filename + Device ID
- **Pros:** Prevents cross-device collisions
- **Cons:** No deduplication, complicates sync logic, device ID management issues
- **Verdict:** More complex, fewer benefits than hashing

## Recommendation

**Implement content-based hashing (SHA-256) for all image filename generation.**

This solution:
- ✅ Prevents all collision scenarios
- ✅ Provides automatic deduplication
- ✅ Maintains backward compatibility
- ✅ Has acceptable performance overhead
- ✅ Improves data integrity
- ✅ No sync logic changes required

## Files to Modify

1. **Create:** `/home/jtd/notable/app/src/main/java/com/ethran/notable/io/ImageHashUtils.kt` (new utility)
2. **Modify:** `/home/jtd/notable/app/src/main/java/com/ethran/notable/io/FileUtils.kt:134`
3. **Modify:** `/home/jtd/notable/app/src/main/java/com/ethran/notable/io/XoppFile.kt:419`

## Priority

**High** - This is a data loss issue that can silently discard user images during synchronization across multiple devices.
