package com.securitycam.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.securitycam.core.storage.FileImageStorageManager
import com.securitycam.core.surveillance.NormalizedRect
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileImageStorageTest {

    private lateinit var context: Context
    private lateinit var storageManager: FileImageStorageManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        storageManager = FileImageStorageManager(
            context = context,
            maxStorageBytes = 500L * 1024L * 1024L,
            maxFileCount = 1000,
            jpegQuality = 85
        )
        clearDirectory(storageManager.getEventDirectory())
    }

    @After
    fun tearDown() {
        clearDirectory(storageManager.getEventDirectory())
    }

    private fun clearDirectory(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun createTestBitmap(width: Int = 320, height: Int = 240, color: Int = Color.BLUE): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap
    }

    @Test
    fun testEventDirectoryCreatedUnderFilesDir() {
        val dir = storageManager.getEventDirectory()
        assertTrue("Event directory must exist", dir.exists())
        assertTrue("Event directory must be a directory", dir.isDirectory)
        assertEquals("events", dir.name)
        assertEquals(context.filesDir.absolutePath, dir.parentFile?.absolutePath)
    }

    @Test
    fun testSaveScreenshotCreatesValidJpeg(): Unit = runBlocking {
        val bitmap = createTestBitmap()
        val timestamp = 1700000000000L
        val boxes = listOf(NormalizedRect(0.2f, 0.2f, 0.6f, 0.6f))

        val savedFile = storageManager.saveScreenshot(bitmap, timestamp, boxes)

        assertNotNull("Saved file must not be null", savedFile)
        assertTrue("Saved file must exist on disk", savedFile.exists())
        assertTrue("Saved file size must be > 0", savedFile.length() > 0)
        assertTrue("Filename must contain event timestamp", savedFile.name.contains("event_1700000000000"))
        assertTrue("Filename must end with .jpg", savedFile.name.endsWith(".jpg"))
    }

    @Test
    fun testJpegMagicBytesHeaderAndFooter(): Unit = runBlocking {
        val bitmap = createTestBitmap()
        val file = storageManager.saveScreenshot(bitmap, System.currentTimeMillis(), emptyList())

        val bytes = file.readBytes()
        assertTrue("JPEG file must have at least 4 bytes", bytes.size >= 4)

        // SOI (Start Of Image) magic bytes: 0xFF, 0xD8
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])

        // EOI (End Of Image) magic bytes: 0xFF, 0xD9
        val len = bytes.size
        assertEquals(0xFF.toByte(), bytes[len - 2])
        assertEquals(0xD9.toByte(), bytes[len - 1])
    }

    @Test
    fun testSavedImageDecodableWithMatchingDimensions(): Unit = runBlocking {
        val width = 640
        val height = 480
        val bitmap = createTestBitmap(width, height)
        val file = storageManager.saveScreenshot(bitmap, System.currentTimeMillis(), emptyList())

        val decodedBitmap = BitmapFactory.decodeFile(file.absolutePath)
        assertNotNull("Saved JPEG must decode successfully via BitmapFactory", decodedBitmap)
        assertEquals("Width must match original", width, decodedBitmap.width)
        assertEquals("Height must match original", height, decodedBitmap.height)
    }

    @Test
    fun testWatermarkBannerModifiesPixels(): Unit = runBlocking {
        val width = 200
        val height = 200
        // Create solid pure white bitmap
        val pureWhite = createTestBitmap(width, height, Color.WHITE)
        val file = storageManager.saveScreenshot(pureWhite, 1700000000000L, emptyList())

        val decoded = BitmapFactory.decodeFile(file.absolutePath)
        assertNotNull(decoded)

        // Bottom banner region (around y = height - 10) must be darkened (non-white) due to translucent banner
        val bannerPixel = decoded.getPixel(width / 2, height - 10)
        val red = Color.red(bannerPixel)
        val green = Color.green(bannerPixel)
        val blue = Color.blue(bannerPixel)

        // On pure white (255, 255, 255), a 160-alpha black banner darkens color significantly (< 200)
        assertTrue("Banner must darken background pixels (R=$red, G=$green, B=$blue)", red < 200)
    }

    @Test
    fun testBoundingBoxDrawnOnImage(): Unit = runBlocking {
        val width = 200
        val height = 200
        val solidBlue = createTestBitmap(width, height, Color.BLUE)
        val box = NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f)
        val file = storageManager.saveScreenshot(solidBlue, 1700000000000L, listOf(box))

        val decoded = BitmapFactory.decodeFile(file.absolutePath)
        assertNotNull(decoded)

        // Top edge of box at y = 0.1 * 200 = 20
        var foundRed = false
        for (x in 20..180) {
            val pixel = decoded.getPixel(x, 20)
            if (Color.red(pixel) > 180 && Color.blue(pixel) < 100) {
                foundRed = true
                break
            }
        }
        assertTrue("Red bounding box border must be drawn on bitmap", foundRed)
    }

    @Test
    fun testSaveJpegBytesDirectly(): Unit = runBlocking {
        val bitmap = createTestBitmap(100, 100)
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        val rawJpeg = baos.toByteArray()

        val timestamp = 1700000005000L
        val savedFile = storageManager.saveJpegBytes(rawJpeg, timestamp)

        assertTrue(savedFile.exists())
        assertEquals(rawJpeg.size.toLong(), savedFile.length())

        val header = ByteArray(2)
        FileInputStream(savedFile).use { it.read(header) }
        assertEquals(0xFF.toByte(), header[0])
        assertEquals(0xD8.toByte(), header[1])
    }

    @Test
    fun testFifoPruningByCount(): Unit = runBlocking {
        val manager = FileImageStorageManager(context, maxStorageBytes = 100L * 1024 * 1024, maxFileCount = 3)
        val bitmap = createTestBitmap(50, 50)

        // Save 5 screenshots spaced out by timestamp
        for (i in 1..5) {
            manager.saveScreenshot(bitmap, timestamp = 1000L * i, boxes = emptyList())
            Thread.sleep(10) // Ensure distinct lastModified
        }

        // Must auto-prune to 3 files
        val count = manager.getEventFileCount()
        assertEquals("File count must not exceed maxFileCount (3)", 3, count)
    }

    @Test
    fun testFifoPruningByDiskQuota(): Unit = runBlocking {
        val bitmap = createTestBitmap(300, 300)
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, baos)
        val singleFileSize = baos.size().toLong()

        // Set quota sufficient for only ~2 files
        val quota = singleFileSize * 2 + 500
        val manager = FileImageStorageManager(context, maxStorageBytes = quota, maxFileCount = 100)

        for (i in 1..4) {
            manager.saveScreenshot(bitmap, timestamp = 1000L * i, boxes = emptyList())
            Thread.sleep(10)
        }

        val used = manager.getStorageUsedBytes()
        assertTrue("Used storage ($used) must be <= quota ($quota)", used <= quota)
    }

    @Test
    fun testDeleteImageFile(): Unit = runBlocking {
        val file = storageManager.saveScreenshot(createTestBitmap(50, 50), System.currentTimeMillis(), emptyList())
        assertTrue(file.exists())

        val deleted = storageManager.deleteImage(file)
        assertTrue(deleted)
        assertFalse(file.exists())

        val deleteAgain = storageManager.deleteImage(file)
        assertFalse("Deleting non-existent file must return false", deleteAgain)
    }

    @Test
    fun testBoundaryNormalizedRects(): Unit = runBlocking {
        val bitmap = createTestBitmap(100, 100)
        val fullScreenBox = NormalizedRect(0f, 0f, 1f, 1f)
        val tinyBox = NormalizedRect(0.5f, 0.5f, 0.51f, 0.51f)

        // Should not throw exception
        val file = storageManager.saveScreenshot(bitmap, System.currentTimeMillis(), listOf(fullScreenBox, tinyBox))
        assertTrue(file.exists())
    }

    @Test
    fun testConcurrentFileSaving(): Unit = runBlocking {
        val bitmap = createTestBitmap(80, 80)
        val jobs = (1..6).map { idx ->
            async {
                storageManager.saveScreenshot(bitmap, timestamp = 1700000000000L + idx, boxes = emptyList())
            }
        }
        val files = jobs.awaitAll()
        assertEquals(6, files.size)
        files.forEach { file ->
            assertTrue("Concurrent file ${file.name} must exist and be valid", file.exists() && file.length() > 0)
        }
    }
}
