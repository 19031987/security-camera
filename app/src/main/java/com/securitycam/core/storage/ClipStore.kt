package com.securitycam.core.storage

import java.io.File

/**
 * Where event video clips live on the camera phone, and the rule that keeps them from filling
 * it: once the clips together exceed the limit, the oldest are deleted first.
 */
class ClipStore(
    private val directory: File,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
    private val freeSpace: () -> Long = { directory.usableSpace }
) {
    /** A new, not yet existing file for a clip that starts at [timestamp]. */
    fun newClipFile(timestamp: Long): File {
        directory.mkdirs()
        var file = File(directory, "clip_$timestamp$EXTENSION")
        var suffix = 1
        while (file.exists()) {
            file = File(directory, "clip_${timestamp}_$suffix$EXTENSION")
            suffix++
        }
        return file
    }

    /** False when the phone is nearly full; recording then would risk breaking everything else. */
    fun hasRoomToRecord(): Boolean {
        directory.mkdirs()
        return freeSpace() >= minFreeBytes
    }

    fun clips(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            ?: emptyList()

    fun totalBytes(): Long = clips().sumOf { it.length() }

    /**
     * Deletes the oldest clips until the total is within the limit, never touching [keep]
     * (the clip being recorded right now). Returns the files that were deleted.
     */
    fun prune(keep: File? = null): List<File> {
        val all = clips()
        var total = all.sumOf { it.length() }
        val deleted = ArrayList<File>()
        for (clip in all) {
            if (total <= maxTotalBytes) break
            if (keep != null && clip.absolutePath == keep.absolutePath) continue
            val size = clip.length()
            if (clip.delete()) {
                total -= size
                deleted.add(clip)
            }
        }
        return deleted
    }

    /** Deletes one clip, but only if it really is one of ours. */
    fun delete(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        val file = File(path)
        if (file.parentFile?.absolutePath != directory.absolutePath || !file.name.endsWith(EXTENSION)) return false
        return file.delete()
    }

    fun deleteAll() {
        clips().forEach { it.delete() }
    }

    companion object {
        const val DIRECTORY_NAME = "clips"
        const val EXTENSION = ".mp4"
        const val CLIP_LENGTH_MS = 15_000L
        const val DEFAULT_MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
        const val DEFAULT_MIN_FREE_BYTES = 300L * 1024 * 1024
    }
}
