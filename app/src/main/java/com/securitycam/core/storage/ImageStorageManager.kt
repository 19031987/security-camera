package com.securitycam.core.storage

import android.graphics.Bitmap
import com.securitycam.core.surveillance.NormalizedRect
import java.io.File

/**
 * Manages persistent storage, canvas watermarking, and disk quota pruning of motion snapshot images.
 */
interface ImageStorageManager {

    /**
     * Overlays timestamp text banner and detected bounding boxes onto [bitmap],
     * compresses to JPEG, writes atomically to an app-scoped file, and returns the stored File.
     */
    suspend fun saveScreenshot(bitmap: Bitmap, timestamp: Long, boxes: List<NormalizedRect>): File

    /**
     * Writes raw JPEG byte array directly to disk and returns the stored File.
     */
    suspend fun saveJpegBytes(jpegData: ByteArray, timestamp: Long): File

    /**
     * Returns the app-scoped storage directory for motion event images (`context.filesDir/events/`).
     */
    fun getEventDirectory(): File

    /**
     * Prunes storage based on maximum allowed disk quota in bytes and maximum event file count.
     * Deletes oldest files first (FIFO). Returns count of deleted files.
     */
    suspend fun pruneStorage(maxBytes: Long = DEFAULT_MAX_STORAGE_BYTES, maxFiles: Int = DEFAULT_MAX_FILES): Int

    /**
     * Calculates total bytes consumed by all stored event files.
     */
    suspend fun getStorageUsedBytes(): Long

    /**
     * Returns total count of image files currently stored in the events directory.
     */
    suspend fun getEventFileCount(): Int

    /**
     * Deletes a specific screenshot file. Returns true if file was deleted.
     */
    suspend fun deleteImage(file: File): Boolean

    companion object {
        const val DEFAULT_MAX_STORAGE_BYTES = 500L * 1024L * 1024L // 500 MB
        const val DEFAULT_MAX_FILES = 1000 // 1000 files
        const val DEFAULT_JPEG_QUALITY = 85
    }
}
