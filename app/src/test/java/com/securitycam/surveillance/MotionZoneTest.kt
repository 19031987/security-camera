package com.securitycam.surveillance

import com.securitycam.core.surveillance.MotionZone
import com.securitycam.core.surveillance.NormalizedRect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionZoneTest {

    /** The lower-left quarter of the picture, e.g. a doorstep. */
    private val doorstep = MotionZone(left = 0f, top = 0.5f, right = 0.5f, bottom = 1f)

    private fun box(l: Float, t: Float, r: Float, b: Float) = NormalizedRect(l, t, r, b)

    @Test
    fun theDefaultZoneIsTheWholePictureAndCountsEverything() {
        assertTrue(MotionZone.WHOLE_PICTURE.isWholePicture)
        assertTrue(MotionZone.WHOLE_PICTURE.contains(listOf(box(0.9f, 0f, 1f, 0.1f))))
        assertTrue(MotionZone(0.005f, 0f, 1f, 0.995f).isWholePicture)
        assertFalse(doorstep.isWholePicture)
    }

    @Test
    fun movementInsideTheZoneCounts() {
        assertTrue(doorstep.contains(listOf(box(0.1f, 0.6f, 0.3f, 0.9f))))
    }

    @Test
    fun movementEntirelyOutsideIsIgnored() {
        assertFalse(doorstep.contains(listOf(box(0.6f, 0.1f, 0.9f, 0.4f))))
        assertFalse(doorstep.contains(listOf(box(0.6f, 0.6f, 0.9f, 0.9f), box(0.1f, 0.05f, 0.3f, 0.3f))))
    }

    @Test
    fun oneBoxInsideIsEnoughAmongSeveral() {
        assertTrue(doorstep.contains(listOf(box(0.6f, 0.1f, 0.9f, 0.4f), box(0.2f, 0.7f, 0.3f, 0.8f))))
    }

    @Test
    fun someoneSteppingAcrossTheEdgeCounts() {
        // Centre is outside, but 30% of the box is inside.
        assertTrue(doorstep.contains(listOf(box(0.35f, 0.6f, 0.85f, 0.9f))))
    }

    @Test
    fun aBoxThatOnlyClipsTheEdgeIsIgnored() {
        // Only 10% of the box is inside.
        assertFalse(doorstep.contains(listOf(box(0.45f, 0.6f, 0.95f, 0.9f))))
    }

    @Test
    fun aLargeBoxWhoseCentreIsInsideCounts() {
        assertTrue(doorstep.contains(listOf(box(0f, 0.3f, 0.8f, 1f))))
    }

    @Test
    fun motionWithNoBoxesCountsRatherThanBeingSilentlyDropped() {
        assertTrue(doorstep.contains(emptyList()))
    }

    @Test
    fun aCorruptZoneBehavesAsTheWholePicture() {
        val inverted = MotionZone(0.8f, 0.8f, 0.2f, 0.2f)
        val tiny = MotionZone(0.5f, 0.5f, 0.52f, 0.52f)
        val outOfRange = MotionZone(-1f, 0f, 2f, 1f)
        for (zone in listOf(inverted, tiny, outOfRange)) {
            assertFalse(zone.isValid)
            assertTrue(zone.contains(listOf(box(0.9f, 0f, 1f, 0.1f))))
        }
    }
}
