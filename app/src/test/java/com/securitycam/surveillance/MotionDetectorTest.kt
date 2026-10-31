package com.securitycam.surveillance

import com.securitycam.core.surveillance.AdaptiveBackgroundMotionDetector
import com.securitycam.core.surveillance.LuminanceFrame
import com.securitycam.core.surveillance.MotionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Milestone 2 Vision & Motion Detection Engine Unit Test Suite.
 *
 * Verifies:
 * 1. Static background stability without false alarms.
 * 2. Moving target tracking and normalized bounding box extraction.
 * 3. Spatial morphological opening eradication of isolated single-pixel noise.
 * 4. Multi-target grid block bounding box clustering.
 * 5. Sensitivity curve discrimination (Low S=15 vs High S=85).
 * 6. Low-light dynamic noise floor adaptation and sensor grain suppression.
 * 7. Gradual environmental daylight adaptation (EMA background accumulator).
 * 8. Temporal hysteresis debouncing (transient glitch suppression).
 * 9. Engine state reset behavior.
 * 10. Adversarial edge cases (zero-size frames, coordinate bounds, selective ghosting).
 * 11. Multi-threaded concurrency and thread safety under simultaneous calls.
 */
class MotionDetectorTest {

    private lateinit var detector: AdaptiveBackgroundMotionDetector

    @Before
    fun setUp() {
        detector = AdaptiveBackgroundMotionDetector()
    }

    // =========================================================================
    // Synthetic Frame Generators
    // =========================================================================

    object SyntheticFrameGenerator {

        /**
         * Creates a uniform luminance frame where every pixel has value [luminance].
         */
        fun createUniformFrame(
            width: Int = 160,
            height: Int = 120,
            luminance: Int = 128,
            timestampMs: Long = 0L
        ): LuminanceFrame {
            require(width > 0 && height > 0) { "Dimensions must be positive" }
            require(luminance in 0..255) { "Luminance must be in 0..255, got $luminance" }
            val byteVal = (luminance and 0xFF).toByte()
            val data = ByteArray(width * height) { byteVal }
            return LuminanceFrame(data, width, height, timestampMs)
        }

        /**
         * Creates a frame with a linear luminance gradient.
         */
        fun createGradientFrame(
            width: Int = 160,
            height: Int = 120,
            startY: Int = 50,
            endY: Int = 200,
            vertical: Boolean = false,
            timestampMs: Long = 0L
        ): LuminanceFrame {
            require(width > 0 && height > 0) { "Dimensions must be positive" }
            val data = ByteArray(width * height)
            val span = if (vertical) height else width
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pos = if (vertical) y else x
                    val ratio = if (span > 1) pos.toFloat() / (span - 1) else 0f
                    val lum = (startY + ratio * (endY - startY)).roundToInt().coerceIn(0, 255)
                    data[y * width + x] = (lum and 0xFF).toByte()
                }
            }
            return LuminanceFrame(data, width, height, timestampMs)
        }

        /**
         * Injects a rectangular target patch (x, y, w, h) of specified [targetLuminance] into [baseFrame].
         */
        fun injectMovingTarget(
            baseFrame: LuminanceFrame,
            x: Int,
            y: Int,
            targetWidth: Int,
            targetHeight: Int,
            targetLuminance: Int = 240,
            timestampMs: Long = baseFrame.timestampMs
        ): LuminanceFrame {
            val width = baseFrame.width
            val height = baseFrame.height
            val data = baseFrame.data.copyOf()
            val targetByte = (targetLuminance.coerceIn(0, 255) and 0xFF).toByte()

            val startX = x.coerceIn(0, width)
            val endX = (x + targetWidth).coerceIn(0, width)
            val startY = y.coerceIn(0, height)
            val endY = (y + targetHeight).coerceIn(0, height)

            for (row in startY until endY) {
                val rowOffset = row * width
                for (col in startX until endX) {
                    data[rowOffset + col] = targetByte
                }
            }
            return LuminanceFrame(data, width, height, timestampMs)
        }

        /**
         * Injects scattered isolated single-pixel spikes separated by at least 3 pixels.
         */
        fun injectIsolatedNoiseSpikes(
            baseFrame: LuminanceFrame,
            spikeCount: Int = 50,
            spikeLuminance: Int = 255,
            timestampMs: Long = baseFrame.timestampMs
        ): LuminanceFrame {
            val width = baseFrame.width
            val height = baseFrame.height
            val data = baseFrame.data.copyOf()
            val byteVal = (spikeLuminance and 0xFF).toByte()

            var placed = 0
            var y = 5
            while (y < height - 5 && placed < spikeCount) {
                var x = 5
                while (x < width - 5 && placed < spikeCount) {
                    data[y * width + x] = byteVal
                    placed++
                    x += 4 // Spaced 4 pixels apart horizontally
                }
                y += 4 // Spaced 4 pixels apart vertically
            }
            return LuminanceFrame(data, width, height, timestampMs)
        }

        /**
         * Generates a frame representing a dark room with Gaussian sensor shot noise.
         */
        fun createLowLightNoisyFrame(
            width: Int = 160,
            height: Int = 120,
            baseLuminance: Int = 25,
            noiseStdDev: Double = 5.0,
            seed: Long = 42L,
            timestampMs: Long = 0L
        ): LuminanceFrame {
            require(width > 0 && height > 0)
            val random = Random(seed)
            val data = ByteArray(width * height)
            for (i in data.indices) {
                val noise = random.nextGaussian() * noiseStdDev
                val noisyVal = (baseLuminance + noise).roundToInt().coerceIn(0, 255)
                data[i] = (noisyVal and 0xFF).toByte()
            }
            return LuminanceFrame(data, width, height, timestampMs)
        }

        /**
         * Generates a temporal sequence of frames where ambient illumination ramps smoothly
         * from [startLuminance] to [endLuminance] over [frameCount] frames.
         */
        fun generateIlluminationRampSequence(
            frameCount: Int = 100,
            width: Int = 160,
            height: Int = 120,
            startLuminance: Float = 50f,
            endLuminance: Float = 170f,
            startTimestampMs: Long = 0L,
            frameIntervalMs: Long = 100L
        ): List<LuminanceFrame> {
            require(frameCount > 1)
            val frames = ArrayList<LuminanceFrame>(frameCount)
            val delta = (endLuminance - startLuminance) / (frameCount - 1)
            for (i in 0 until frameCount) {
                val lum = (startLuminance + i * delta).roundToInt().coerceIn(0, 255)
                val ts = startTimestampMs + i * frameIntervalMs
                frames.add(createUniformFrame(width, height, lum, ts))
            }
            return frames
        }
    }

    // =========================================================================
    // Core Test Cases
    // =========================================================================

    @Test
    fun testStaticSceneProducesNoMotion() {
        val frame = SyntheticFrameGenerator.createUniformFrame(160, 120, 128)

        for (i in 0 until 50) {
            val result = detector.processFrame(frame)
            assertFalse("Static scene must not trigger motion at frame $i", result.isMotionDetected)
            assertEquals("Motion score must remain 0.0 on static scene", 0f, result.motionScore, 0.0001f)
            assertEquals("Active pixels must be 0", 0, result.activePixels)
            assertEquals(19200, result.totalPixels)
            assertEquals(128f, result.averageLuminance, 0.5f)
            assertFalse(result.isLowLight)
            assertTrue("Bounding boxes must be empty on static scene", result.boundingBoxes.isEmpty())
        }

        // Test with static gradient background (non-uniform room illumination)
        detector.reset()
        val gradientFrame = SyntheticFrameGenerator.createGradientFrame(160, 120, 40, 200)
        for (i in 0 until 20) {
            val result = detector.processFrame(gradientFrame)
            assertFalse("Static gradient must not trigger motion at frame $i", result.isMotionDetected)
            assertEquals(0, result.activePixels)
        }
    }

    @Test
    fun testMovingObjectTriggersMotion() {
        // 1. Warm up detector with 5 static frames (Y=100)
        val baseFrame = SyntheticFrameGenerator.createUniformFrame(160, 120, 100)
        for (i in 0 until 5) {
            val res = detector.processFrame(baseFrame)
            assertFalse(res.isMotionDetected)
        }

        // 2. Inject moving target: 24x24 white patch (area=576 px, ~3.0% of frame > 1% minAreaRatio)
        val targetWidth = 24
        val targetHeight = 24
        val targetLuminance = 245 // High contrast: |245 - 100| = 145 > 25

        // Frame 6 (Target appears at 20, 20): first trigger frame (debouncing requires 2)
        val frame6 = SyntheticFrameGenerator.injectMovingTarget(baseFrame, 20, 20, targetWidth, targetHeight, targetLuminance)
        val res6 = detector.processFrame(frame6)
        assertFalse("Single frame does not trigger motion due to 2-frame hysteresis debounce", res6.isMotionDetected)

        // Frame 7 (Target moves to 24, 22): 2nd consecutive trigger frame -> MOTION DETECTED!
        val frame7 = SyntheticFrameGenerator.injectMovingTarget(baseFrame, 24, 22, targetWidth, targetHeight, targetLuminance)
        val res7 = detector.processFrame(frame7)
        assertTrue("Second consecutive frame with moving target MUST trigger motion", res7.isMotionDetected)
        assertTrue("Motion score must exceed minAreaRatio", res7.motionScore >= 0.01f)
        assertTrue("Active pixels must reflect moving patch", res7.activePixels >= 400)

        // Verify bounding boxes
        assertFalse("Bounding boxes must not be empty upon motion trigger", res7.boundingBoxes.isEmpty())
        val box = res7.boundingBoxes.first()
        assertTrue("Bounding box left must be in [0, 1]", box.left in 0f..1f)
        assertTrue("Bounding box top must be in [0, 1]", box.top in 0f..1f)
        assertTrue("Bounding box right must be in [0, 1]", box.right in 0f..1f)
        assertTrue("Bounding box bottom must be in [0, 1]", box.bottom in 0f..1f)
        assertTrue("Right coordinate must be greater than left", box.right > box.left)
        assertTrue("Bottom coordinate must be greater than top", box.bottom > box.top)

        // Ensure box encloses the target position (x=24..48 -> 0.15..0.30, y=22..46 -> 0.183..0.383)
        assertTrue("Box left must align with target x", box.left <= (24f / 160f) + 0.05f)
        assertTrue("Box top must align with target y", box.top <= (22f / 120f) + 0.05f)
        assertTrue("Box right must extend past target x + width", box.right >= (48f / 160f) - 0.05f)
        assertTrue("Box bottom must extend past target y + height", box.bottom >= (46f / 120f) - 0.05f)
    }

    @Test
    fun testSpatialMorphologicalOpeningEradicatesIsolatedNoise() {
        val baseFrame = SyntheticFrameGenerator.createUniformFrame(160, 120, 100)
        for (i in 0 until 5) detector.processFrame(baseFrame)

        // Inject 50 isolated single-pixel noise spikes (spaced >= 4 px apart)
        val noisyFrame = SyntheticFrameGenerator.injectIsolatedNoiseSpikes(baseFrame, spikeCount = 50, spikeLuminance = 255)
        val res = detector.processFrame(noisyFrame)

        // Isolated 1-pixel spikes are completely eroded to 0 by 3x3 min structuring element
        assertEquals("Isolated noise spikes must be eradicated by morphological opening", 0, res.activePixels)
        assertEquals(0f, res.motionScore, 0.0001f)
        assertFalse("Isolated noise must not trigger motion", res.isMotionDetected)
        assertTrue("Bounding boxes must be empty for isolated noise", res.boundingBoxes.isEmpty())
    }

    @Test
    fun testGridBlockBoundingBoxClustering() {
        val baseFrame = SyntheticFrameGenerator.createUniformFrame(160, 120, 100)
        for (i in 0 until 5) detector.processFrame(baseFrame)

        // Inject two distinct moving blocks:
        // Target 1 in top-left: x=10..34, y=10..34 (24x24 px)
        // Target 2 in bottom-right: x=120..144, y=80..104 (24x24 px)
        val frameWithBoth1 = SyntheticFrameGenerator.injectMovingTarget(
            SyntheticFrameGenerator.injectMovingTarget(baseFrame, 10, 10, 24, 24, 240),
            120, 80, 24, 24, 240
        )
        val frameWithBoth2 = SyntheticFrameGenerator.injectMovingTarget(
            SyntheticFrameGenerator.injectMovingTarget(baseFrame, 12, 12, 24, 24, 240),
            122, 82, 24, 24, 240
        )

        detector.processFrame(frameWithBoth1)
        val res = detector.processFrame(frameWithBoth2)

        assertTrue(res.isMotionDetected)
        assertTrue("Must extract at least 2 distinct bounding boxes for separated targets", res.boundingBoxes.size >= 2)

        // Verify one box covers top-left and another covers bottom-right
        val topLeftBox = res.boundingBoxes.find { it.left < 0.4f && it.top < 0.4f }
        val bottomRightBox = res.boundingBoxes.find { it.right > 0.6f && it.bottom > 0.6f }
        assertTrue("Top-left bounding box must exist", topLeftBox != null)
        assertTrue("Bottom-right bounding box must exist", bottomRightBox != null)
    }

    @Test
    fun testLowSensitivityRejectsSmallMotion() {
        val lowSensConfig = MotionConfig.fromSensitivity(15)
        detector.configure(lowSensConfig)

        val baseFrame = SyntheticFrameGenerator.createUniformFrame(160, 120, 120)
        for (i in 0 until 5) {
            detector.processFrame(baseFrame)
        }

        // Scenario A: Subtle contrast disturbance (delta = 25 < tau = 40)
        val subtleFrame = SyntheticFrameGenerator.injectMovingTarget(baseFrame, 30, 30, 20, 20, 145)
        for (i in 0 until 5) {
            val res = detector.processFrame(subtleFrame)
            assertFalse("Low sensitivity must reject sub-threshold contrast delta (25 < 40)", res.isMotionDetected)
        }

        // Scenario B: High contrast but small area disturbance (6x6 = 36 px = 0.18% << rho = 3.64%)
        val smallAreaFrame = SyntheticFrameGenerator.injectMovingTarget(baseFrame, 50, 50, 6, 6, 255)
        for (i in 0 until 5) {
            val res = detector.processFrame(smallAreaFrame)
            assertFalse("Low sensitivity must reject sub-threshold area ratio (0.18% < 3.64%)", res.isMotionDetected)
        }
    }

    @Test
    fun testHighSensitivityDetectsSubtleMotion() {
        val highSensConfig = MotionConfig.fromSensitivity(85)
        detector.configure(highSensConfig)

        val baseFrame = SyntheticFrameGenerator.createUniformFrame(160, 120, 120)
        for (i in 0 until 5) {
            detector.processFrame(baseFrame)
        }

        // Inject subtle target: 12x12 px (144 px = 0.75% > 0.21%), subtle contrast: |142 - 120| = 22 > 17
        val subtleFrame1 = SyntheticFrameGenerator.injectMovingTarget(baseFrame, 40, 40, 12, 12, 142)
        val subtleFrame2 = SyntheticFrameGenerator.injectMovingTarget(baseFrame, 42, 41, 12, 12, 142)

        val res1 = detector.processFrame(subtleFrame1)
        assertFalse("First frame debounces", res1.isMotionDetected)

        val res2 = detector.processFrame(subtleFrame2)
        assertTrue("High sensitivity MUST detect subtle movement (area 0.75% > 0.21%, delta 22 > 17)", res2.isMotionDetected)
        assertTrue("Motion score must exceed high sensitivity threshold", res2.motionScore >= 0.0021f)

        // Cross-validation: The same subtle target must be REJECTED by low sensitivity (S=15)
        val lowDetector = AdaptiveBackgroundMotionDetector(MotionConfig.fromSensitivity(15))
        for (i in 0 until 5) lowDetector.processFrame(baseFrame)
        lowDetector.processFrame(subtleFrame1)
        val lowRes = lowDetector.processFrame(subtleFrame2)
        assertFalse("Low sensitivity must reject what high sensitivity detects", lowRes.isMotionDetected)
    }

    @Test
    fun testLowLightNoiseSuppression() {
        detector.configure(MotionConfig(lowLightCompensation = true, pixelThreshold = 25))

        // Feed 30 dark frames (base luminance = 25 < 40) with Gaussian shot noise (sigma = 5.0)
        for (i in 0 until 30) {
            val noisyFrame = SyntheticFrameGenerator.createLowLightNoisyFrame(
                width = 160,
                height = 120,
                baseLuminance = 25,
                noiseStdDev = 5.0,
                seed = 42L + i
            )
            val res = detector.processFrame(noisyFrame)
            assertTrue("Frame $i must be flagged as low light", res.isLowLight)
            assertTrue("Average luminance must be < 40 in dark scene", res.averageLuminance < 40f)
            assertFalse("Low-light sensor noise must NOT trigger false motion alarm at frame $i", res.isMotionDetected)
            assertTrue("Bounding boxes must be empty for noise", res.boundingBoxes.isEmpty())
        }
    }

    @Test
    fun testGradualSunlightAdaptation() {
        val rampFrames = SyntheticFrameGenerator.generateIlluminationRampSequence(
            frameCount = 300,
            width = 160,
            height = 120,
            startLuminance = 50f,
            endLuminance = 170f
        )

        for (i in rampFrames.indices) {
            val res = detector.processFrame(rampFrames[i])
            assertFalse("Gradual ambient illumination ramp must NOT trigger motion alarm at frame $i", res.isMotionDetected)
        }

        // Verify the background model learned the final bright midday luminance
        val finalRes = detector.processFrame(rampFrames.last())
        assertEquals("Average luminance must reach midday level", 170f, finalRes.averageLuminance, 1.0f)
        assertFalse("Midday scene must not be flagged as low light", finalRes.isLowLight)
    }

    @Test
    fun testHysteresisDebounce() {
        detector.configure(MotionConfig(consecutiveTriggerFrames = 2, minAreaRatio = 0.01f))

        val staticFrame = SyntheticFrameGenerator.createUniformFrame(160, 120, 100)
        for (i in 0 until 5) detector.processFrame(staticFrame)

        // 1. Inject a single anomalous spike frame (e.g. flashlight flicker or lightning: full white frame)
        val flashFrame = SyntheticFrameGenerator.createUniformFrame(160, 120, 255)
        val flashResult = detector.processFrame(flashFrame)
        assertFalse("Single anomalous flash frame MUST NOT trigger motion (hysteresis debounce)", flashResult.isMotionDetected)
        assertTrue("Raw motion score on flash frame should be high", flashResult.motionScore > 0.5f)

        // 2. Return immediately to normal static background
        val recoveryResult = detector.processFrame(staticFrame)
        assertFalse("Returning to static background must not trigger motion", recoveryResult.isMotionDetected)

        // 3. Now verify 2 consecutive genuine motion frames trigger as expected
        val targetFrame1 = SyntheticFrameGenerator.injectMovingTarget(staticFrame, 30, 30, 24, 24, 240)
        val targetFrame2 = SyntheticFrameGenerator.injectMovingTarget(staticFrame, 32, 31, 24, 24, 240)

        val trigger1 = detector.processFrame(targetFrame1)
        assertFalse("Frame 1 of genuine motion sets triggerCount to 1, still false", trigger1.isMotionDetected)

        val trigger2 = detector.processFrame(targetFrame2)
        assertTrue("Frame 2 of genuine motion satisfies consecutive requirement and MUST trigger", trigger2.isMotionDetected)
    }

    @Test
    fun testDetectorResetClearsBackgroundModel() {
        val baseFrame = SyntheticFrameGenerator.createUniformFrame(160, 120, 80)
        for (i in 0 until 5) detector.processFrame(baseFrame)

        // Put detector into triggered state
        val targetFrame1 = SyntheticFrameGenerator.injectMovingTarget(baseFrame, 20, 20, 30, 30, 250)
        val targetFrame2 = SyntheticFrameGenerator.injectMovingTarget(baseFrame, 22, 22, 30, 30, 250)
        detector.processFrame(targetFrame1)
        val triggeredRes = detector.processFrame(targetFrame2)
        assertTrue("Detector must be in triggered state prior to reset", triggeredRes.isMotionDetected)

        // Act: Reset detector state
        detector.reset()

        // Feed a frame from a completely different environment (e.g. bright outdoor scene Y=210)
        val newSceneFrame = SyntheticFrameGenerator.createUniformFrame(160, 120, 210)
        val postResetRes = detector.processFrame(newSceneFrame)

        // Assert: First frame after reset must re-initialize background model and produce zero motion
        assertFalse("First frame after reset must not trigger motion", postResetRes.isMotionDetected)
        assertEquals("Motion score after reset must be 0.0", 0f, postResetRes.motionScore, 0.0001f)
        assertEquals("Active pixels after reset must be 0", 0, postResetRes.activePixels)
        assertTrue("Bounding boxes after reset must be empty", postResetRes.boundingBoxes.isEmpty())
        assertEquals("Average luminance must reflect new scene", 210f, postResetRes.averageLuminance, 0.5f)

        // Second frame of new scene also produces no motion
        val secondPostResetRes = detector.processFrame(newSceneFrame)
        assertFalse("Subsequent static frame of new scene must not trigger motion", secondPostResetRes.isMotionDetected)
    }

    // =========================================================================
    // Adversarial & Hardening Tests
    // =========================================================================

    @Test
    fun testDegenerateAndZeroSizeFrames() {
        val zeroFrame = LuminanceFrame(ByteArray(0), 0, 0, 0L)
        val res = detector.processFrame(zeroFrame)
        assertFalse(res.isMotionDetected)
        assertEquals(0f, res.motionScore, 0.0001f)
        assertEquals(0, res.activePixels)
        assertEquals(0, res.totalPixels)
        assertTrue(res.boundingBoxes.isEmpty())

        // 1x1 micro frame
        val microFrame = LuminanceFrame(byteArrayOf(128.toByte()), 1, 1, 0L)
        val microRes = detector.processFrame(microFrame)
        assertFalse(microRes.isMotionDetected)
        assertEquals(1, microRes.totalPixels)
    }

    @Test
    fun testBoundingBoxCoordinateIntegrity() {
        val baseFrame = SyntheticFrameGenerator.createUniformFrame(160, 120, 100)
        for (i in 0 until 5) detector.processFrame(baseFrame)

        // Target near the bottom-right corner: x=135..155, y=95..115
        val cornerTarget1 = SyntheticFrameGenerator.injectMovingTarget(baseFrame, 135, 95, 20, 20, 240)
        val cornerTarget2 = SyntheticFrameGenerator.injectMovingTarget(baseFrame, 137, 96, 20, 20, 240)

        detector.processFrame(cornerTarget1)
        val res = detector.processFrame(cornerTarget2)

        assertTrue(res.isMotionDetected)
        assertFalse(res.boundingBoxes.isEmpty())

        for (box in res.boundingBoxes) {
            assertTrue("Left boundary must be >= 0.0", box.left >= 0f)
            assertTrue("Top boundary must be >= 0.0", box.top >= 0f)
            assertTrue("Right boundary must be <= 1.0", box.right <= 1f)
            assertTrue("Bottom boundary must be <= 1.0", box.bottom <= 1f)
            assertTrue("Width must be positive", box.width > 0f)
            assertTrue("Height must be positive", box.height > 0f)
            assertTrue("Area must be positive and <= 1.0", box.area in 0f..1f)
        }
    }

    @Test
    fun testDynamicReconfigurationDuringSurveillance() {
        val baseFrame = SyntheticFrameGenerator.createUniformFrame(160, 120, 100)
        for (i in 0 until 5) detector.processFrame(baseFrame)

        // Dynamic change from default (S=50) to very high sensitivity (S=95)
        val sensitiveConfig = MotionConfig.fromSensitivity(95)
        detector.configure(sensitiveConfig)

        // Inject subtle movement
        val subtle1 = SyntheticFrameGenerator.injectMovingTarget(baseFrame, 20, 20, 10, 10, 125)
        val subtle2 = SyntheticFrameGenerator.injectMovingTarget(baseFrame, 21, 21, 10, 10, 125)

        detector.processFrame(subtle1)
        val res = detector.processFrame(subtle2)
        assertTrue("Dynamic sensitivity reconfiguration must take effect immediately", res.isMotionDetected)
    }

    @Test
    fun testSelectiveUpdatePreventsImmediateGhosting() {
        val baseFrame = SyntheticFrameGenerator.createUniformFrame(160, 120, 100)
        for (i in 0 until 5) detector.processFrame(baseFrame)

        // Target enters scene and then STOPS moving (stationary intruder pause)
        val intruderFrame = SyntheticFrameGenerator.injectMovingTarget(baseFrame, 40, 40, 25, 25, 240)

        // Feed for 8 consecutive frames where target remains at the exact same location
        var detectedCount = 0
        for (i in 0 until 8) {
            val res = detector.processFrame(intruderFrame)
            if (res.isMotionDetected) detectedCount++
        }

        // Because foreground selective update uses slow alpha = 0.001, the stationary intruder
        // must NOT be absorbed within 8 frames (which would cause detection to vanish)
        assertTrue(
            "Stationary intruder must remain detected due to selective background update (detected in $detectedCount of 8 frames)",
            detectedCount >= 6
        )
    }

    @Test
    fun testThreadSafetyUnderConcurrentCalls() {
        val threadCount = 8
        val iterationsPerThread = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errorCount = AtomicInteger(0)

        val frameA = SyntheticFrameGenerator.createUniformFrame(160, 120, 100)
        val frameB = SyntheticFrameGenerator.injectMovingTarget(frameA, 30, 30, 24, 24, 240)

        for (t in 0 until threadCount) {
            executor.execute {
                try {
                    for (i in 0 until iterationsPerThread) {
                        when (i % 4) {
                            0 -> detector.processFrame(frameA)
                            1 -> detector.processFrame(frameB)
                            2 -> detector.configure(MotionConfig.fromSensitivity((i % 100) + 1))
                            3 -> if (i % 20 == 3) detector.reset()
                        }
                    }
                } catch (e: Throwable) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Threads must complete within timeout", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("No exceptions or race conditions should occur during concurrent operations", 0, errorCount.get())
    }
}
