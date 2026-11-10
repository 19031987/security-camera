package com.securitycam.core.push

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Remembers which pictures the camera has stored online, oldest first, so that old ones can be
 * removed and the database doesn't grow without limit.
 */
class PictureLedger(
    private val file: File,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val gson: Gson = Gson()
) {
    /** A stored picture and the link code it was stored under (the code can change). */
    data class Entry(val linkCode: String = "", val pictureId: String = "")

    private val lock = Any()

    /** Records a new picture and returns the entries that have aged out and should be deleted. */
    fun add(linkCode: String, pictureId: String): List<Entry> {
        synchronized(lock) {
            val all = load() + Entry(linkCode, pictureId)
            val overflow = (all.size - capacity).coerceAtLeast(0)
            save(all.drop(overflow))
            return all.take(overflow)
        }
    }

    /** Puts back entries whose deletion failed, so it is tried again next time. */
    fun restore(entries: List<Entry>) {
        if (entries.isEmpty()) return
        synchronized(lock) { save(entries + load()) }
    }

    fun entries(): List<Entry> = synchronized(lock) { load() }

    private fun load(): List<Entry> {
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<Entry>>() {}.type
            val stored: List<Entry?> = gson.fromJson(file.readText(), type) ?: emptyList()
            @Suppress("SENSELESS_COMPARISON")
            stored.filterNotNull().filter { it.linkCode != null && it.pictureId != null && it.pictureId.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(entries: List<Entry>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(gson.toJson(entries))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    companion object {
        const val FILE_NAME = "online_pictures.json"

        /** About 200 pictures of roughly 100 KB each: a small fraction of the free database allowance. */
        const val DEFAULT_CAPACITY = 200
    }
}
