package com.securitycam.storage

import com.securitycam.core.storage.ClipStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClipStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var free = 10L * 1024 * 1024 * 1024

    private fun dir() = File(tempFolder.root, "clips")

    private fun store(maxBytes: Long = 1000) = ClipStore(dir(), maxBytes, minFreeBytes = 500, freeSpace = { free })

    /** Creates a clip of [size] bytes whose age is set by [modified]. */
    private fun clip(store: ClipStore, timestamp: Long, size: Int, modified: Long = timestamp): File =
        store.newClipFile(timestamp).apply {
            writeBytes(ByteArray(size))
            setLastModified(modified)
        }

    @Test
    fun newClipFilesAreMp4InTheClipFolderAndNeverCollide() {
        val store = store()
        val first = store.newClipFile(1_700_000_000_000L)
        first.writeBytes(byteArrayOf(1))
        val second = store.newClipFile(1_700_000_000_000L)

        assertEquals(dir().absolutePath, first.parentFile!!.absolutePath)
        assertTrue(first.name.endsWith(".mp4"))
        assertNotEquals(first.absolutePath, second.absolutePath)
    }

    @Test
    fun nothingIsDeletedWhileUnderTheLimit() {
        val store = store(maxBytes = 1000)
        clip(store, 1_000_000L, 400)
        clip(store, 2_000_000L, 400)

        assertTrue(store.prune().isEmpty())
        assertEquals(800L, store.totalBytes())
    }

    @Test
    fun theOldestClipsGoFirstUntilTheTotalFits() {
        val store = store(maxBytes = 1000)
        val oldest = clip(store, 1_000_000L, 400)
        val middle = clip(store, 2_000_000L, 400)
        val newest = clip(store, 3_000_000L, 400)

        val deleted = store.prune()

        assertEquals(listOf(oldest.absolutePath), deleted.map { it.absolutePath })
        assertFalse(oldest.exists())
        assertTrue(middle.exists() && newest.exists())
        assertEquals(800L, store.totalBytes())
    }

    @Test
    fun severalClipsAreDeletedIfOneIsNotEnough() {
        val store = store(maxBytes = 500)
        val a = clip(store, 1_000_000L, 400)
        val b = clip(store, 2_000_000L, 400)
        val c = clip(store, 3_000_000L, 400)

        store.prune()

        assertFalse(a.exists() || b.exists())
        assertTrue(c.exists())
    }

    @Test
    fun theClipBeingRecordedIsNeverDeletedEvenIfItIsTheOldest() {
        val store = store(maxBytes = 500)
        val recording = clip(store, 1_000_000L, 400)
        val later = clip(store, 2_000_000L, 400)

        val deleted = store.prune(keep = recording)

        assertTrue(recording.exists())
        assertEquals(listOf(later.absolutePath), deleted.map { it.absolutePath })
    }

    @Test
    fun otherFilesInTheFolderAreLeftAlone() {
        val store = store(maxBytes = 100)
        dir().mkdirs()
        val stray = File(dir(), "notes.txt").apply { writeBytes(ByteArray(5000)) }
        clip(store, 1_000_000L, 400)

        store.prune()
        store.deleteAll()

        assertTrue(stray.exists())
        assertTrue(store.clips().isEmpty())
    }

    @Test
    fun recordingIsRefusedWhenThePhoneIsNearlyFull() {
        val store = store()
        assertTrue(store.hasRoomToRecord())
        free = 499
        assertFalse(store.hasRoomToRecord())
    }

    @Test
    fun deleteOnlyRemovesFilesThatAreOurClips() {
        val store = store()
        val mine = clip(store, 1_000_000L, 10)
        val outside = tempFolder.newFile("outside.mp4")
        dir().mkdirs()
        val notClip = File(dir(), "keep.txt").apply { writeBytes(byteArrayOf(1)) }

        assertTrue(store.delete(mine.absolutePath))
        assertFalse(store.delete(outside.absolutePath))
        assertFalse(store.delete(File(dir(), "../outside.mp4").path))
        assertFalse(store.delete(notClip.absolutePath))
        assertFalse(store.delete(null))
        assertFalse(store.delete(""))

        assertFalse(mine.exists())
        assertTrue(outside.exists() && notClip.exists())
    }

    @Test
    fun theDefaultLimitIsTwoGigabytesAndClipsAreFifteenSeconds() {
        assertEquals(2L * 1024 * 1024 * 1024, ClipStore.DEFAULT_MAX_TOTAL_BYTES)
        assertEquals(15_000L, ClipStore.CLIP_LENGTH_MS)
    }
}
