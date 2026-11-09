package com.securitycam.core.recognition

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * Enrolled household members, kept as a JSON file in the app's private storage.
 * Only face embeddings (lists of numbers) are stored, never the photos they came from.
 * The file is outside the backup rules, so it does not leave the device.
 */
class KnownPeopleStore(
    private val file: File,
    private val gson: Gson = Gson()
) {
    private class StoredPerson(val id: String = "", val name: String = "", val embeddings: List<List<Float>> = emptyList())

    private val lock = Any()
    private val state = MutableStateFlow(load())

    val people: StateFlow<List<KnownPerson>> = state.asStateFlow()

    fun current(): List<KnownPerson> = state.value

    /** Adds a face sample to the person called [name] (case-insensitive), creating them if new. */
    fun addSample(name: String, embedding: FloatArray): KnownPerson {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "A name is required" }
        synchronized(lock) {
            val existing = state.value.firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
            val updated = if (existing != null) {
                existing.copy(embeddings = (existing.embeddings + embedding).takeLast(MAX_SAMPLES_PER_PERSON))
            } else {
                KnownPerson(UUID.randomUUID().toString(), cleanName, listOf(embedding))
            }
            val next = state.value.filter { it.id != updated.id } + updated
            persist(next)
            return updated
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            persist(state.value.filter { it.id != id })
        }
    }

    private fun persist(next: List<KnownPerson>) {
        val stored = next.map { person ->
            StoredPerson(person.id, person.name, person.embeddings.map { it.toList() })
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(gson.toJson(stored))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        state.value = next
    }

    private fun load(): List<KnownPerson> {
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<StoredPerson>>() {}.type
            val stored: List<StoredPerson> = gson.fromJson(file.readText(), type) ?: emptyList()
            stored.filter { it.id.isNotEmpty() && it.name.isNotEmpty() && it.embeddings.isNotEmpty() }
                .map { KnownPerson(it.id, it.name, it.embeddings.map { e -> e.toFloatArray() }) }
        } catch (_: Exception) {
            // A corrupt file means nobody is recognised, which fails safe: alerts are sent.
            emptyList()
        }
    }

    companion object {
        const val FILE_NAME = "known_people.json"
        const val MAX_SAMPLES_PER_PERSON = 10
    }
}
