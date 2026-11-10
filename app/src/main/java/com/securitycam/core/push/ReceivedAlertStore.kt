package com.securitycam.core.push

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/** One notification a family phone received from the camera. */
data class ReceivedAlert(
    val id: String = "",
    val type: String = "",
    val title: String = "",
    val message: String = "",
    val time: Long = 0L,
    /** File name of the small picture that came with it, inside the store's folder; null if none. */
    val pictureFile: String? = null
)

/**
 * The recent notifications on a family phone, newest first, with their small pictures.
 * Kept in the app's private storage; the oldest are dropped beyond [capacity].
 */
class ReceivedAlertStore(
    private val directory: File,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val gson: Gson = Gson()
) {
    private val lock = Any()
    private val indexFile = File(directory, INDEX_NAME)
    private val state = MutableStateFlow(load())

    val alerts: StateFlow<List<ReceivedAlert>> = state.asStateFlow()

    fun current(): List<ReceivedAlert> = state.value

    fun add(type: String, title: String, message: String, time: Long, pictureJpeg: ByteArray?): ReceivedAlert {
        synchronized(lock) {
            directory.mkdirs()
            val id = UUID.randomUUID().toString()
            var pictureName: String? = null
            if (pictureJpeg != null && pictureJpeg.isNotEmpty()) {
                pictureName = "$id$PICTURE_EXTENSION"
                File(directory, pictureName).writeBytes(pictureJpeg)
            }
            val alert = ReceivedAlert(id, type, title, message, time, pictureName)
            val all = listOf(alert) + state.value
            val kept = all.take(capacity)
            all.drop(capacity).forEach { deletePicture(it) }
            persist(kept)
            return alert
        }
    }

    /** The picture file for [alert], or null if it has none or the file has gone. */
    fun pictureOf(alert: ReceivedAlert): File? {
        val name = alert.pictureFile ?: return null
        // Stored names are generated here, but never trust a name that could leave the folder.
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        return File(directory, name).takeIf { it.isFile }
    }

    fun clear() {
        synchronized(lock) {
            state.value.forEach { deletePicture(it) }
            persist(emptyList())
        }
    }

    private fun deletePicture(alert: ReceivedAlert) {
        pictureOf(alert)?.delete()
    }

    private fun persist(next: List<ReceivedAlert>) {
        directory.mkdirs()
        val temp = File(directory, "$INDEX_NAME.tmp")
        temp.writeText(gson.toJson(next))
        if (!temp.renameTo(indexFile)) {
            temp.copyTo(indexFile, overwrite = true)
            temp.delete()
        }
        state.value = next
    }

    private fun load(): List<ReceivedAlert> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<ReceivedAlert>>() {}.type
            val stored: List<ReceivedAlert?> = gson.fromJson(indexFile.readText(), type) ?: emptyList()
            @Suppress("SENSELESS_COMPARISON")
            stored.filterNotNull().filter { it.id != null && it.title != null && it.id.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        const val DIRECTORY_NAME = "received_alerts"
        const val DEFAULT_CAPACITY = 50
        private const val INDEX_NAME = "alerts.json"
        private const val PICTURE_EXTENSION = ".jpg"
    }
}
