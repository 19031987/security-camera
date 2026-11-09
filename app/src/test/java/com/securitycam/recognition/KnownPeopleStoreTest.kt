package com.securitycam.recognition

import com.securitycam.core.recognition.KnownPeopleStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KnownPeopleStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun file() = File(tempFolder.root, "people/known_people.json")

    @Test
    fun startsEmptyWhenThereIsNoFile() {
        assertTrue(KnownPeopleStore(file()).current().isEmpty())
    }

    @Test
    fun samplesForTheSameNameAreGroupedIgnoringCase() {
        val store = KnownPeopleStore(file())
        store.addSample("Asha", floatArrayOf(1f, 0f))
        store.addSample(" asha ", floatArrayOf(0f, 1f))
        store.addSample("Ravi", floatArrayOf(0.5f, 0.5f))

        val people = store.current()
        assertEquals(2, people.size)
        assertEquals(2, people.first { it.name == "Asha" }.embeddings.size)
    }

    @Test
    fun peopleSurviveAReload() {
        val file = file()
        KnownPeopleStore(file).addSample("Asha", floatArrayOf(0.25f, -0.5f, 1f))

        val reloaded = KnownPeopleStore(file).current()
        assertEquals(1, reloaded.size)
        assertEquals("Asha", reloaded[0].name)
        assertArrayEquals(floatArrayOf(0.25f, -0.5f, 1f), reloaded[0].embeddings[0], 0f)
    }

    @Test
    fun removingAPersonPersists() {
        val file = file()
        val store = KnownPeopleStore(file)
        val asha = store.addSample("Asha", floatArrayOf(1f))
        store.addSample("Ravi", floatArrayOf(2f))
        store.remove(asha.id)

        assertEquals(listOf("Ravi"), KnownPeopleStore(file).current().map { it.name })
    }

    @Test
    fun onlyTheNewestSamplesAreKept() {
        val store = KnownPeopleStore(file())
        repeat(KnownPeopleStore.MAX_SAMPLES_PER_PERSON + 3) { store.addSample("Asha", floatArrayOf(it.toFloat())) }

        val samples = store.current()[0].embeddings
        assertEquals(KnownPeopleStore.MAX_SAMPLES_PER_PERSON, samples.size)
        assertEquals(3f, samples.first()[0], 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBlankNameIsRejected() {
        KnownPeopleStore(file()).addSample("   ", floatArrayOf(1f))
    }

    @Test
    fun aCorruptFileMeansNobodyIsRecognised() {
        val file = file()
        file.parentFile!!.mkdirs()
        file.writeText("{not json")
        assertTrue(KnownPeopleStore(file).current().isEmpty())
    }

    @Test
    fun noTemporaryFileIsLeftBehind() {
        val file = file()
        KnownPeopleStore(file).addSample("Asha", floatArrayOf(1f))
        assertTrue(file.exists())
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
    }
}
