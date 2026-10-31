package com.securitycam.ui

import com.securitycam.core.surveillance.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit test suite verifying BoundingBoxOverlayView coordinate projection math,
 * mirroring inversion logic, boundary clamping, and motion percentage formatting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BoundingBoxOverlayTest {

    @Test
    fun testNormalCoordinateTransformationBackCamera() {
        val box = NormalizedRect(left = 0.2f, top = 0.1f, right = 0.8f, bottom = 0.9f)
        val viewW = 1000f
        val viewH = 2000f

        val rect = BoundingBoxOverlayView.transformRect(
            box = box,
            viewWidth = viewW,
            viewHeight = viewH,
            isMirrored = false
        )

        assertEquals(200f, rect.left, 0.001f)
        assertEquals(200f, rect.top, 0.001f)
        assertEquals(800f, rect.right, 0.001f)
        assertEquals(1800f, rect.bottom, 0.001f)
        assertEquals(600f, rect.width(), 0.001f)
        assertEquals(1600f, rect.height(), 0.001f)
    }

    @Test
    fun testMirroredCoordinateTransformationFrontCamera() {
        val box = NormalizedRect(left = 0.1f, top = 0.3f, right = 0.4f, bottom = 0.7f)
        val viewW = 800f
        val viewH = 1200f

        val rect = BoundingBoxOverlayView.transformRect(
            box = box,
            viewWidth = viewW,
            viewHeight = viewH,
            isMirrored = true
        )

        // Mirrored X mapping:
        // left = (1.0 - 0.4) * 800 = 0.6 * 800 = 480
        // right = (1.0 - 0.1) * 800 = 0.9 * 800 = 720
        assertEquals(480f, rect.left, 0.001f)
        assertEquals(720f, rect.right, 0.001f)
        assertEquals(360f, rect.top, 0.001f)
        assertEquals(840f, rect.bottom, 0.001f)
        assertEquals(240f, rect.width(), 0.001f)
        assertEquals(480f, rect.height(), 0.001f)
    }

    @Test
    fun testBoundaryClampingEdgeCases() {
        val box = NormalizedRect(left = 0.0f, top = 0.0f, right = 1.0f, bottom = 1.0f)
        val viewW = 500f
        val viewH = 1000f

        val normalRect = BoundingBoxOverlayView.transformRect(box, viewW, viewH, false)
        assertEquals(0f, normalRect.left, 0.001f)
        assertEquals(0f, normalRect.top, 0.001f)
        assertEquals(500f, normalRect.right, 0.001f)
        assertEquals(1000f, normalRect.bottom, 0.001f)

        val mirroredRect = BoundingBoxOverlayView.transformRect(box, viewW, viewH, true)
        assertEquals(0f, mirroredRect.left, 0.001f)
        assertEquals(0f, mirroredRect.top, 0.001f)
        assertEquals(500f, mirroredRect.right, 0.001f)
        assertEquals(1000f, mirroredRect.bottom, 0.001f)
    }

    @Test
    fun testMotionLabelFormatting() {
        assertEquals("MOTION 0%", BoundingBoxOverlayView.formatMotionLabel(0.0f))
        assertEquals("MOTION 50%", BoundingBoxOverlayView.formatMotionLabel(0.50f))
        assertEquals("MOTION 88%", BoundingBoxOverlayView.formatMotionLabel(0.884f))
        assertEquals("MOTION 100%", BoundingBoxOverlayView.formatMotionLabel(1.0f))
        assertEquals("MOTION 100%", BoundingBoxOverlayView.formatMotionLabel(1.25f)) // Clamped
        assertEquals("MOTION 0%", BoundingBoxOverlayView.formatMotionLabel(-0.1f))  // Clamped
    }

    @Test
    fun testSubPixelPrecisionAndAreaPreservation() {
        val box = NormalizedRect(left = 0.333f, top = 0.25f, right = 0.666f, bottom = 0.75f)
        val viewW = 1080f
        val viewH = 1920f

        val normalRect = BoundingBoxOverlayView.transformRect(box, viewW, viewH, false)
        val mirroredRect = BoundingBoxOverlayView.transformRect(box, viewW, viewH, true)

        assertEquals(normalRect.width(), mirroredRect.width(), 0.001f)
        assertEquals(normalRect.height(), mirroredRect.height(), 0.001f)
        assertTrue(normalRect.left < normalRect.right)
        assertTrue(normalRect.top < normalRect.bottom)
    }
}
