package com.securitycam.push

import com.securitycam.core.push.ReceivedAlert
import com.securitycam.core.push.ReceivedAlertStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReceivedAlertStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun dir() = File(tempFolder.root, "received")

    private fun store(capacity: Int = 50) = ReceivedAlertStore(dir(), capacity)

    @Test
    fun startsEmpty() {
        assertTrue(store().current().isEmpty())
    }

    @Test
    fun newestAlertsComeFirst() {
        val store = store()
        store.add("alert", "First", "", 1L, null)
        store.add("arrival", "Second", "msg", 2L, null)

        assertEquals(listOf("Second", "First"), store.current().map { it.title })
        assertEquals("arrival", store.current()[0].type)
        assertEquals("msg", store.current()[0].message)
    }

    @Test
    fun thePictureIsSavedAndCanBeReadBack() {
        val store = store()
        val jpeg = byteArrayOf(1, 2, 3, 4)

        val withPicture = store.add("alert", "Person at your door", "", 1L, jpeg)
        val without = store.add("test", "Test", "", 2L, null)

        assertArrayEquals(jpeg, store.pictureOf(withPicture)!!.readBytes())
        assertNull(store.pictureOf(without))
        assertNull(store.pictureOf(store.add("alert", "Empty picture", "", 3L, ByteArray(0))))
    }

    @Test
    fun alertsAndPicturesSurviveARestart() {
        val first = store()
        first.add("alert", "Kept", "", 5L, byteArrayOf(9, 9))

        val reopened = store()

        assertEquals("Kept", reopened.current().single().title)
        assertNotNull(reopened.pictureOf(reopened.current().single()))
    }

    @Test
    fun beyondTheLimitTheOldestAreDroppedWithTheirPictures() {
        val store = store(capacity = 3)
        val oldest = store.add("alert", "1", "", 1L, byteArrayOf(1))
        val oldestPicture = store.pictureOf(oldest)!!
        store.add("alert", "2", "", 2L, byteArrayOf(2))
        store.add("alert", "3", "", 3L, byteArrayOf(3))
        store.add("alert", "4", "", 4L, byteArrayOf(4))

        assertEquals(listOf("4", "3", "2"), store.current().map { it.title })
        assertFalse(oldestPicture.exists())
        assertEquals(3, dir().listFiles { f -> f.name.endsWith(".jpg") }!!.size)
    }

    @Test
    fun clearRemovesEverything() {
        val store = store()
        val alert = store.add("alert", "1", "", 1L, byteArrayOf(1))
        val picture = store.pictureOf(alert)!!

        store.clear()

        assertTrue(store.current().isEmpty())
        assertFalse(picture.exists())
        assertTrue(store().current().isEmpty())
    }

    @Test
    fun aCorruptListStartsEmptyRatherThanCrashing() {
        dir().mkdirs()
        File(dir(), "alerts.json").writeText("{broken")
        assertTrue(store().current().isEmpty())
    }

    @Test
    fun aPictureNameThatPointsOutsideTheFolderIsRefused() {
        val store = store()
        val outside = tempFolder.newFile("secret.jpg").apply { writeBytes(byteArrayOf(7)) }
        dir().mkdirs()

        assertNull(store.pictureOf(ReceivedAlert("x", "alert", "t", "", 1L, "../secret.jpg")))
        assertNull(store.pictureOf(ReceivedAlert("x", "alert", "t", "", 1L, outside.absolutePath)))
        assertTrue(outside.exists())
    }
}
