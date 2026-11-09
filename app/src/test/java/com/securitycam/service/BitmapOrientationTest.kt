package com.securitycam.service

import android.graphics.Bitmap
import android.graphics.Color
import com.securitycam.core.surveillance.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BitmapOrientationTest {

    private val service = SurveillanceService()

    private fun createTestBitmap(width: Int = 320, height: Int = 240, color: Int = Color.BLUE): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap
    }

    @Test
    fun testOrientBitmap_0DegreesBackCamera_returnsOriginal() {
        val bitmap = createTestBitmap(320, 240)
        val oriented = service.orientBitmap(bitmap, rotationDegrees = 0, isFrontFacing = false)
        assertEquals(320, oriented.width)
        assertEquals(240, oriented.height)
    }

    @Test
    fun testOrientBitmap_90DegreesBackCamera_swapsDimensions() {
        val bitmap = createTestBitmap(320, 240)
        val oriented = service.orientBitmap(bitmap, rotationDegrees = 90, isFrontFacing = false)
        assertNotNull(oriented)
        assertEquals(240, oriented.width)
        assertEquals(320, oriented.height)
    }

    @Test
    fun testOrientBitmap_180DegreesBackCamera_retainsDimensions() {
        val bitmap = createTestBitmap(320, 240)
        val oriented = service.orientBitmap(bitmap, rotationDegrees = 180, isFrontFacing = false)
        assertNotNull(oriented)
        assertEquals(320, oriented.width)
        assertEquals(240, oriented.height)
    }

    @Test
    fun testOrientBitmap_270DegreesBackCamera_swapsDimensions() {
        val bitmap = createTestBitmap(320, 240)
        val oriented = service.orientBitmap(bitmap, rotationDegrees = 270, isFrontFacing = false)
        assertNotNull(oriented)
        assertEquals(240, oriented.width)
        assertEquals(320, oriented.height)
    }

    @Test
    fun testOrientBitmap_FrontCamera_mirrorsHorizontally() {
        val width = 320
        val height = 240
        val bitmap = createTestBitmap(width, height)

        val oriented = service.orientBitmap(bitmap, rotationDegrees = 0, isFrontFacing = true)
        assertNotNull(oriented)
        assertEquals("Width must match original dimensions", width, oriented.width)
        assertEquals("Height must match original dimensions", height, oriented.height)

        val orientedRotated = service.orientBitmap(createTestBitmap(width, height), rotationDegrees = 90, isFrontFacing = true)
        assertNotNull(orientedRotated)
        assertEquals("Width must be swapped on 90 degree front camera rotation", height, orientedRotated.width)
        assertEquals("Height must be swapped on 90 degree front camera rotation", width, orientedRotated.height)
    }

    @Test
    fun testOrientBoundingBoxes_90Degrees() {
        val box = NormalizedRect(left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f)
        val oriented = service.orientBoundingBoxes(listOf(box), rotationDegrees = 90, isFrontFacing = false)
        assertEquals(1, oriented.size)
        val res = oriented[0]
        assertTrue("Coordinates within [0..1]", res.left in 0f..1f && res.top in 0f..1f && res.right in 0f..1f && res.bottom in 0f..1f)
        assertTrue("Left <= Right", res.left <= res.right)
        assertTrue("Top <= Bottom", res.top <= res.bottom)
    }

    @Test
    fun testOrientBoundingBoxes_FrontCameraMirroring() {
        val box = NormalizedRect(left = 0.1f, top = 0.2f, right = 0.4f, bottom = 0.5f)
        val oriented = service.orientBoundingBoxes(listOf(box), rotationDegrees = 0, isFrontFacing = true)
        assertEquals(1, oriented.size)
        val res = oriented[0]
        // Mirrored horizontally: left becomes 1 - 0.4 = 0.6, right becomes 1 - 0.1 = 0.9
        assertEquals(0.6f, res.left, 0.01f)
        assertEquals(0.9f, res.right, 0.01f)
        assertEquals(0.2f, res.top, 0.01f)
        assertEquals(0.5f, res.bottom, 0.01f)
    }
}
