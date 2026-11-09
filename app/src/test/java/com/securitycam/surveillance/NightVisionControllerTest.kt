package com.securitycam.surveillance

import com.securitycam.core.surveillance.NightVisionController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightVisionControllerTest {

    private fun controller() = NightVisionController(enterBelow = 1f, exitAbove = 3f, framesToSwitch = 3)

    @Test
    fun staysOffInABrightScene() {
        val c = controller()
        repeat(10) { assertFalse(c.update(8f)) }
    }

    @Test
    fun turnsOnOnlyAfterEnoughConsecutiveDarkFrames() {
        val c = controller()
        assertFalse(c.update(0.2f))
        assertFalse(c.update(0.2f))
        assertTrue(c.update(0.2f))
    }

    @Test
    fun aBriefDarkMomentDoesNotSwitch() {
        val c = controller()
        c.update(0.2f)
        c.update(0.2f)
        c.update(8f) // streak broken
        assertFalse(c.update(0.2f))
        assertFalse(c.update(0.2f))
        assertTrue(c.update(0.2f))
    }

    @Test
    fun brightnessBetweenTheThresholdsKeepsTheCurrentMode() {
        val c = controller()
        repeat(5) { assertFalse(c.update(2f)) } // not dark enough to enter
        repeat(3) { c.update(0.2f) }
        assertTrue(c.isActive)
        repeat(10) { assertTrue(c.update(2f)) } // not bright enough to leave
    }

    @Test
    fun turnsOffAfterEnoughConsecutiveBrightFrames() {
        val c = controller()
        repeat(3) { c.update(0.2f) }
        assertTrue(c.update(5f))
        assertTrue(c.update(5f))
        assertFalse(c.update(5f))
    }

    @Test
    fun missingExposureDataKeepsTheCurrentMode() {
        val c = controller()
        repeat(10) { assertFalse(c.update(null)) }
        repeat(3) { c.update(0.2f) }
        repeat(10) { assertTrue(c.update(null)) }
    }

    @Test
    fun resetReturnsToDayMode() {
        val c = controller()
        repeat(3) { c.update(0.2f) }
        c.reset()
        assertFalse(c.isActive)
        assertFalse(c.update(0.2f))
    }

    @Test
    fun sceneBrightnessDividesLuminanceByExposure() {
        // ISO 400 at 1/40 s is an exposure of 10; luminance 120 gives 12.
        assertEquals(12f, NightVisionController.sceneBrightness(120f, 400, 25_000_000L)!!, 0.001f)
        // The same picture brightness reached with far more exposure means a much darker scene.
        val dark = NightVisionController.sceneBrightness(120f, 3200, 125_000_000L)!!
        assertEquals(0.3f, dark, 0.001f)
    }

    @Test
    fun sceneBrightnessIsNullWithoutUsableExposure() {
        assertNull(NightVisionController.sceneBrightness(100f, null, 1_000_000L))
        assertNull(NightVisionController.sceneBrightness(100f, 100, null))
        assertNull(NightVisionController.sceneBrightness(100f, 0, 1_000_000L))
        assertNull(NightVisionController.sceneBrightness(100f, 100, 0L))
    }

    @Test
    fun longerExposureInNightModeDoesNotLookLikeDaylight() {
        // Same dark scene before and after night mode quadruples the exposure time:
        // the picture gets brighter but the scene index stays put, so the mode can't oscillate.
        val before = NightVisionController.sceneBrightness(20f, 3200, 33_000_000L)!!
        val after = NightVisionController.sceneBrightness(80f, 3200, 132_000_000L)!!
        assertEquals(before, after, 0.001f)
    }
}
