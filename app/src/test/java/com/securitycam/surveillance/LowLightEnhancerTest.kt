package com.securitycam.surveillance

import com.securitycam.core.surveillance.LuminanceFrame
import com.securitycam.core.surveillance.LowLightEnhancer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowLightEnhancerTest {

    @Test
    fun testAverageLuminanceCalculation() {
        // Uniform black
        val blackFrame = ByteArray(100) { 0 }
        assertEquals(0.0f, LowLightEnhancer.calculateAverageLuminance(blackFrame), 0.0001f)

        // Uniform white
        val whiteFrame = ByteArray(100) { 255.toByte() }
        assertEquals(255.0f, LowLightEnhancer.calculateAverageLuminance(whiteFrame), 0.0001f)

        // Uniform gray (128)
        val grayFrame = ByteArray(100) { 128.toByte() }
        assertEquals(128.0f, LowLightEnhancer.calculateAverageLuminance(grayFrame), 0.0001f)

        // Empty buffer safety
        val emptyFrame = ByteArray(0)
        assertEquals(0.0f, LowLightEnhancer.calculateAverageLuminance(emptyFrame), 0.0001f)

        // LuminanceFrame integration
        val lumFrame = LuminanceFrame(data = grayFrame, width = 10, height = 10, timestampMs = 1000L)
        assertEquals(128.0f, LowLightEnhancer.calculateAverageLuminance(lumFrame), 0.0001f)
    }

    @Test
    fun testLowLightConditionDetection() {
        assertTrue(LowLightEnhancer.isLowLight(0.0f))
        assertTrue(LowLightEnhancer.isLowLight(20.0f))
        assertTrue(LowLightEnhancer.isLowLight(39.9f))
        assertFalse(LowLightEnhancer.isLowLight(40.0f))
        assertFalse(LowLightEnhancer.isLowLight(120.0f))
    }

    @Test
    fun testDynamicNoiseFloorAdaptation() {
        val baseThreshold = 25

        // Daytime / bright scenes: no adjustment
        assertEquals(25, LowLightEnhancer.computeDynamicThreshold(baseThreshold, 150.0f, true))
        assertEquals(25, LowLightEnhancer.computeDynamicThreshold(baseThreshold, 40.0f, true))

        // Low-light compensation disabled: retains base threshold
        assertEquals(25, LowLightEnhancer.computeDynamicThreshold(baseThreshold, 10.0f, false))

        // Dynamic adjustment in low-light
        // Y_bar = 30 -> (40 - 30)/3 = 3.33 -> round 3 -> 25 + 3 = 28
        assertEquals(28, LowLightEnhancer.computeDynamicThreshold(baseThreshold, 30.0f, true))

        // Y_bar = 25 -> (40 - 25)/3 = 5.0 -> round 5 -> 25 + 5 = 30
        assertEquals(30, LowLightEnhancer.computeDynamicThreshold(baseThreshold, 25.0f, true))

        // Y_bar = 10 -> (40 - 10)/3 = 10.0 -> round 10 -> 25 + 10 = 35
        assertEquals(35, LowLightEnhancer.computeDynamicThreshold(baseThreshold, 10.0f, true))

        // Pitch black: Y_bar = 0 -> (40 - 0)/3 = 13.33 -> round 13 -> 25 + 13 = 38
        assertEquals(38, LowLightEnhancer.computeDynamicThreshold(baseThreshold, 0.0f, true))
    }

    @Test
    fun testGammaLookupTableIntegrity() {
        val lut = LowLightEnhancer.DEFAULT_GAMMA_LUT
        assertEquals(256, lut.size)
        assertEquals(0, lut[0])
        assertEquals(255, lut[255])

        // Verify strictly non-decreasing monotonicity
        for (i in 0 until 255) {
            assertTrue("LUT must be monotonic at $i", lut[i] <= lut[i + 1])
        }

        // Verify shadow detail expansion
        assertEquals(12, lut[1])
        assertEquals(43, lut[10])
        assertEquals(92, lut[40])
        assertEquals(152, lut[100])

        // Single pixel transform
        assertEquals(43, LowLightEnhancer.applyGamma(10))

        // Frame buffer transform
        val src = byteArrayOf(0, 10, 40, 100, 255.toByte())
        val dst = LowLightEnhancer.applyGammaToFrame(src)
        assertEquals(0, dst[0].toInt() and 0xFF)
        assertEquals(43, dst[1].toInt() and 0xFF)
        assertEquals(92, dst[2].toInt() and 0xFF)
        assertEquals(152, dst[3].toInt() and 0xFF)
        assertEquals(255, dst[4].toInt() and 0xFF)
    }

    @Test
    fun testExposureAndTorchGuidance() {
        // Exposure compensation advice
        assertEquals(0, LowLightEnhancer.getRecommendedExposureCompensation(100.0f, 6))
        assertEquals(2, LowLightEnhancer.getRecommendedExposureCompensation(30.0f, 6))
        assertEquals(6, LowLightEnhancer.getRecommendedExposureCompensation(15.0f, 6))

        // Torch advice
        assertFalse(LowLightEnhancer.shouldEngageTorch(30.0f))
        assertTrue(LowLightEnhancer.shouldEngageTorch(15.0f))
    }
}
