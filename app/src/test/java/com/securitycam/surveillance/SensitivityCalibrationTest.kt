package com.securitycam.surveillance

import com.securitycam.core.surveillance.MotionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SensitivityCalibrationTest {

    @Test
    fun testDefaultConstructorPreservesBackwardsCompatibility() {
        val config = MotionConfig()
        assertEquals(50, config.sensitivity)
        assertEquals(25, config.pixelThreshold)
        assertEquals(0.01f, config.minAreaRatio, 0.0001f)
        assertEquals(2, config.consecutiveTriggerFrames)
        assertTrue(config.lowLightCompensation)
    }

    @Test
    fun testFromSensitivityBoundaryValues() {
        // Slider S = 1 (Minimum sensitivity)
        val configMin = MotionConfig.fromSensitivity(1)
        assertEquals(1, configMin.sensitivity)
        assertEquals(45, configMin.pixelThreshold)
        assertEquals(0.049025f, configMin.minAreaRatio, 0.0001f)
        assertEquals(3, configMin.consecutiveTriggerFrames)
        assertTrue(configMin.lowLightCompensation)

        // Slider S = 50 (Default midpoint)
        val configMid = MotionConfig.fromSensitivity(50)
        assertEquals(50, configMid.sensitivity)
        assertEquals(29, configMid.pixelThreshold)
        assertEquals(0.01325f, configMid.minAreaRatio, 0.0001f)
        assertEquals(3, configMid.consecutiveTriggerFrames)

        // Slider S = 51 (Debounce boundary)
        val config51 = MotionConfig.fromSensitivity(51)
        assertEquals(51, config51.sensitivity)
        assertEquals(28, config51.pixelThreshold)
        assertEquals(2, config51.consecutiveTriggerFrames)

        // Slider S = 100 (Maximum sensitivity)
        val configMax = MotionConfig.fromSensitivity(100, lowLightCompensation = false)
        assertEquals(100, configMax.sensitivity)
        assertEquals(12, configMax.pixelThreshold)
        assertEquals(0.001f, configMax.minAreaRatio, 0.0001f)
        assertEquals(2, configMax.consecutiveTriggerFrames)
        assertFalse(configMax.lowLightCompensation)
    }

    @Test
    fun testSensitivityMonotonicityAcrossFullRange() {
        for (s in 1..99) {
            val tau1 = MotionConfig.calculatePixelThreshold(s)
            val tau2 = MotionConfig.calculatePixelThreshold(s + 1)
            assertTrue("tau must be monotonically decreasing: tau($s)=$tau1, tau(${s+1})=$tau2", tau1 >= tau2)

            val rho1 = MotionConfig.calculateMinAreaRatio(s)
            val rho2 = MotionConfig.calculateMinAreaRatio(s + 1)
            assertTrue("rho must be strictly decreasing: rho($s)=$rho1, rho(${s+1})=$rho2", rho1 > rho2)

            val f1 = MotionConfig.calculateConsecutiveTriggerFrames(s)
            assertTrue("Frames must be 2 or 3", f1 in 2..3)
        }
    }

    @Test
    fun testFromSensitivityRejectsInvalidInputs() {
        val invalidValues = listOf(0, -1, 101, Int.MIN_VALUE, Int.MAX_VALUE)
        for (v in invalidValues) {
            try {
                MotionConfig.fromSensitivity(v)
                fail("Expected IllegalArgumentException for sliderValue = $v")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("sliderValue must be in 1..100"))
            }
        }
    }
}
