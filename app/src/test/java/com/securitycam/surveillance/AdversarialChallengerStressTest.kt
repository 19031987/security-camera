package com.securitycam.surveillance

import com.securitycam.core.surveillance.AdaptiveBackgroundMotionDetector
import com.securitycam.core.surveillance.LowLightEnhancer
import com.securitycam.core.surveillance.LuminanceFrame
import com.securitycam.core.surveillance.MotionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Adversarial Challenger Stress Harness for Milestone 2.
 *
 * Rigorously probes:
 * 1. Boundary sensitivity parameters (S=1, 50, 51, 100) and out-of-range rejection.
 * 2. MotionConfig constructor domain validation and invariants.
 * 3. Degenerate, corrupted, truncated, and integer-overflowing frames.
 * 4. Micro-dimensions (1x1, 2x2, 3x3) and extreme aspect ratios (1x1000, 1000x1).
 * 5. Rapid frame resolution changes and dimensional transitions under continuous stream.
 * 6. Resolution transition during active motion triggers.
 * 7. LowLightEnhancer mathematical boundaries (NaN, Inf, negative luminance, large buffers).
 * 8. LowLightEnhancer buffer transformations under mismatched buffer sizes.
 * 9. Real-time performance invariants (mean latency < 3ms/frame on 160x120).
 * 10. Adversarial camouflage and high-frequency spatial patterns.
 * 11. Multi-threaded chaos harness (16 threads, concurrent process, config, reset).
 * 12. NormalizedRect mathematical integrity under extreme full-frame and 4-corner blobs.
 */
class AdversarialChallengerStressTest {

    private lateinit var detector: AdaptiveBackgroundMotionDetector

    @Before
    fun setUp() {
        detector = AdaptiveBackgroundMotionDetector()
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun makeUniform(w: Int, h: Int, lum: Int, ts: Long = 0L): LuminanceFrame {
        val b = (lum.coerceIn(0, 255) and 0xFF).toByte()
        val data = ByteArray(w * h) { b }
        return LuminanceFrame(data, w, h, ts)
    }

    private fun injectPatch(base: LuminanceFrame, x: Int, y: Int, pw: Int, ph: Int, lum: Int): LuminanceFrame {
        val copy = base.data.copyOf()
        val b = (lum.coerceIn(0, 255) and 0xFF).toByte()
        val w = base.width
        val h = base.height
        val x0 = x.coerceIn(0, w)
        val x1 = (x + pw).coerceIn(0, w)
        val y0 = y.coerceIn(0, h)
        val y1 = (y + ph).coerceIn(0, h)
        for (r in y0 until y1) {
            val offset = r * w
            for (c in x0 until x1) {
                copy[offset + c] = b
            }
        }
        return LuminanceFrame(copy, w, h, base.timestampMs)
    }

    // =========================================================================
    // 1. Boundary Sensitivity Parameters & Out-of-Range Inputs
    // =========================================================================

    @Test
    fun testSensitivity_BoundaryValuesAndInvariants_S1_S50_S51_S100() {
        // S = 1 (Minimum)
        val cMin = MotionConfig.fromSensitivity(1)
        assertEquals(1, cMin.sensitivity)
        assertEquals(45, cMin.pixelThreshold)
        assertEquals(0.049025f, cMin.minAreaRatio, 0.0001f)
        assertEquals(3, cMin.consecutiveTriggerFrames)
        assertTrue(cMin.lowLightCompensation)

        // S = 50 (Default midpoint)
        val c50 = MotionConfig.fromSensitivity(50)
        assertEquals(50, c50.sensitivity)
        assertEquals(29, c50.pixelThreshold)
        assertEquals(0.01325f, c50.minAreaRatio, 0.0001f)
        assertEquals(3, c50.consecutiveTriggerFrames)

        // S = 51 (Consecutive trigger frame boundary transition)
        val c51 = MotionConfig.fromSensitivity(51)
        assertEquals(51, c51.sensitivity)
        assertEquals(28, c51.pixelThreshold)
        assertEquals(2, c51.consecutiveTriggerFrames)

        // S = 100 (Maximum)
        val cMax = MotionConfig.fromSensitivity(100, lowLightCompensation = false)
        assertEquals(100, cMax.sensitivity)
        assertEquals(12, cMax.pixelThreshold)
        assertEquals(0.001f, cMax.minAreaRatio, 0.0001f)
        assertEquals(2, cMax.consecutiveTriggerFrames)
        assertFalse(cMax.lowLightCompensation)

        // Empirical detection test: S=1 must reject sub-threshold contrast (delta=35 < 45)
        detector.configure(cMin)
        val base = makeUniform(160, 120, 100)
        for (i in 0 until 5) detector.processFrame(base)
        val delta35 = injectPatch(base, 20, 20, 30, 30, 135)
        for (i in 0 until 5) {
            val res = detector.processFrame(delta35)
            assertFalse("S=1 must reject delta 35 < tau 45", res.isMotionDetected)
        }

        // Empirical detection test: S=100 must detect subtle contrast (delta=15 > 12) with tiny area (0.15% > 0.1%)
        detector.reset()
        detector.configure(cMax)
        for (i in 0 until 5) detector.processFrame(base)
        val subtleTarget1 = injectPatch(base, 30, 30, 6, 5, 115) // 30 px = 0.156% of 19200
        val subtleTarget2 = injectPatch(base, 31, 31, 6, 5, 115)
        val r1 = detector.processFrame(subtleTarget1)
        assertFalse("First frame debounces", r1.isMotionDetected)
        val r2 = detector.processFrame(subtleTarget2)
        assertTrue("S=100 must detect delta 15 > 12 with area 0.156% > 0.1%", r2.isMotionDetected)
    }

    @Test
    fun testSensitivity_OutOfRangeInputsRejected() {
        val badValues = intArrayOf(
            -1000, -100, -1, 0, 101, 102, 500, Int.MIN_VALUE, Int.MAX_VALUE
        )
        for (bad in badValues) {
            try {
                MotionConfig.fromSensitivity(bad)
                fail("Expected IllegalArgumentException for fromSensitivity($bad)")
            } catch (e: IllegalArgumentException) {
                // Expected
            }

            try {
                MotionConfig.calculatePixelThreshold(bad)
                fail("Expected IllegalArgumentException for calculatePixelThreshold($bad)")
            } catch (e: IllegalArgumentException) {
                // Expected
            }

            try {
                MotionConfig.calculateMinAreaRatio(bad)
                fail("Expected IllegalArgumentException for calculateMinAreaRatio($bad)")
            } catch (e: IllegalArgumentException) {
                // Expected
            }

            try {
                MotionConfig.calculateConsecutiveTriggerFrames(bad)
                fail("Expected IllegalArgumentException for calculateConsecutiveTriggerFrames($bad)")
            } catch (e: IllegalArgumentException) {
                // Expected
            }
        }
    }

    @Test
    fun testMotionConfig_ConstructorValidationRejectsInvalidParameters() {
        // Sensitivity out of range
        try {
            MotionConfig(sensitivity = 0)
            fail("Expected exception for sensitivity 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sensitivity"))
        }

        try {
            MotionConfig(sensitivity = 101)
            fail("Expected exception for sensitivity 101")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sensitivity"))
        }

        // PixelThreshold <= 0
        try {
            MotionConfig(pixelThreshold = 0)
            fail("Expected exception for pixelThreshold 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("pixelThreshold"))
        }
        try {
            MotionConfig(pixelThreshold = -10)
            fail("Expected exception for pixelThreshold -10")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("pixelThreshold"))
        }

        // MinAreaRatio out of [0, 1]
        try {
            MotionConfig(minAreaRatio = -0.001f)
            fail("Expected exception for minAreaRatio < 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("minAreaRatio"))
        }
        try {
            MotionConfig(minAreaRatio = 1.001f)
            fail("Expected exception for minAreaRatio > 1")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("minAreaRatio"))
        }

        // ConsecutiveTriggerFrames < 1
        try {
            MotionConfig(consecutiveTriggerFrames = 0)
            fail("Expected exception for consecutiveTriggerFrames = 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("consecutiveTriggerFrames"))
        }
    }

    // =========================================================================
    // 2. Corrupted, Malformed, and Truncated Frame Handling
    // =========================================================================

    @Test
    fun testCorruptedAndMalformedFrames_HandledGracefullyWithoutCrashing() {
        // 1. Truncated data: width=160, height=120, but data.size = 19199 (1 byte missing)
        val truncatedFrame = LuminanceFrame(ByteArray(19199) { 100.toByte() }, 160, 120, 1000L)
        val resTruncated = detector.processFrame(truncatedFrame)
        assertFalse(resTruncated.isMotionDetected)
        assertEquals(0, resTruncated.activePixels)
        assertEquals(19200, resTruncated.totalPixels)

        // 2. Empty byte array
        val emptyFrame = LuminanceFrame(ByteArray(0), 160, 120, 1000L)
        val resEmpty = detector.processFrame(emptyFrame)
        assertFalse(resEmpty.isMotionDetected)

        // 3. Negative dimensions
        val negWFrame = LuminanceFrame(ByteArray(100), -10, 10, 1000L)
        val resNegW = detector.processFrame(negWFrame)
        assertFalse(resNegW.isMotionDetected)

        val negHFrame = LuminanceFrame(ByteArray(100), 10, -10, 1000L)
        val resNegH = detector.processFrame(negHFrame)
        assertFalse(resNegH.isMotionDetected)

        // 4. Zero dimensions
        val zeroWFrame = LuminanceFrame(ByteArray(0), 0, 100, 1000L)
        val resZeroW = detector.processFrame(zeroWFrame)
        assertFalse(resZeroW.isMotionDetected)

        // 5. Overflow dimensions: 100,000 x 100,000 (multiplication wraps around)
        val overflowFrame = LuminanceFrame(ByteArray(100), 100000, 100000, 1000L)
        val resOverflow = detector.processFrame(overflowFrame)
        assertFalse(resOverflow.isMotionDetected)

        // 6. Extra-large data: 160x120 with 38400 bytes (e.g. NV21 buffer with UV plane appended)
        val paddedData = ByteArray(38400) { 128.toByte() }
        val paddedFrame = LuminanceFrame(paddedData, 160, 120, 1000L)
        val resPadded = detector.processFrame(paddedFrame)
        assertFalse(resPadded.isMotionDetected)
        assertEquals(19200, resPadded.totalPixels)
        assertEquals(128f, resPadded.averageLuminance, 0.5f)

        // 7. Extreme timestamps
        detector.reset()
        val minTsFrame = LuminanceFrame(ByteArray(19200) { 100.toByte() }, 160, 120, Long.MIN_VALUE)
        val maxTsFrame = LuminanceFrame(ByteArray(19200) { 100.toByte() }, 160, 120, Long.MAX_VALUE)
        assertFalse(detector.processFrame(minTsFrame).isMotionDetected)
        assertFalse(detector.processFrame(maxTsFrame).isMotionDetected)
    }

    // =========================================================================
    // 3. Rapid Dimensional Transitions & Resolution Changes
    // =========================================================================

    @Test
    fun testRapidDimensionalTransitions_StressCycle() {
        // Resolutions: 160x120, 320x240, 64x48, 100x100, 80x60
        val resolutions = listOf(
            Pair(160, 120),
            Pair(320, 240),
            Pair(64, 48),
            Pair(100, 100),
            Pair(80, 60),
            Pair(160, 120)
        )

        for (cycle in 0 until 50) {
            for ((w, h) in resolutions) {
                val f1 = makeUniform(w, h, 100)
                val res1 = detector.processFrame(f1)
                // First frame of any resolution switch must be non-triggering background calibration
                assertFalse("First frame of resolution $w x $h must not trigger", res1.isMotionDetected)
                assertEquals(w * h, res1.totalPixels)

                // Second frame of same resolution: static scene, still no trigger
                val f2 = makeUniform(w, h, 100)
                val res2 = detector.processFrame(f2)
                assertFalse("Second static frame of $w x $h must not trigger", res2.isMotionDetected)
                assertEquals(0, res2.activePixels)
            }
        }
    }

    @Test
    fun testResolutionTransitionDuringActiveMotionTrigger() {
        val base160 = makeUniform(160, 120, 100)
        for (i in 0 until 5) detector.processFrame(base160)

        // Trigger active motion on 160x120
        val target1 = injectPatch(base160, 20, 20, 24, 24, 240)
        val target2 = injectPatch(base160, 22, 22, 24, 24, 240)
        detector.processFrame(target1)
        val triggered160 = detector.processFrame(target2)
        assertTrue("Detector must be in triggered state on 160x120", triggered160.isMotionDetected)
        assertFalse(triggered160.boundingBoxes.isEmpty())

        // Abruptly switch to 320x240 frame while in triggered state!
        val base320 = makeUniform(320, 240, 150)
        val switchRes = detector.processFrame(base320)

        // Must cleanly reset state, calibrate to 320x240, and produce 0 motion
        assertFalse("Abrupt resolution switch must suppress prior trigger and calibrate new resolution", switchRes.isMotionDetected)
        assertEquals(0, switchRes.activePixels)
        assertEquals(320 * 240, switchRes.totalPixels)
        assertTrue(switchRes.boundingBoxes.isEmpty())

        // Next frame on 320x240 is static, still no trigger
        val second320 = detector.processFrame(base320)
        assertFalse(second320.isMotionDetected)
    }

    // =========================================================================
    // 4. Micro-Frames and Extreme Aspect Ratios
    // =========================================================================

    @Test
    fun testMicroFramesAndExtremeAspectRatios() {
        // 1x1 micro frame
        val f1x1 = LuminanceFrame(byteArrayOf(128.toByte()), 1, 1, 0L)
        val r1x1 = detector.processFrame(f1x1)
        assertFalse(r1x1.isMotionDetected)
        assertEquals(1, r1x1.totalPixels)

        // 2x2 frame
        val f2x2 = makeUniform(2, 2, 100)
        detector.processFrame(f2x2)
        val r2x2 = detector.processFrame(f2x2)
        assertFalse(r2x2.isMotionDetected)
        assertEquals(4, r2x2.totalPixels)

        // 3x3 frame (minimum size where 3x3 morphology operates)
        val f3x3 = makeUniform(3, 3, 100)
        detector.processFrame(f3x3)
        val r3x3 = detector.processFrame(f3x3)
        assertFalse(r3x3.isMotionDetected)
        assertEquals(9, r3x3.totalPixels)

        // Extreme aspect ratios: 1 x 500
        val tallFrame = makeUniform(1, 500, 120)
        detector.processFrame(tallFrame)
        val rTall = detector.processFrame(tallFrame)
        assertFalse(rTall.isMotionDetected)
        assertEquals(500, rTall.totalPixels)

        // Extreme aspect ratios: 500 x 1
        val wideFrame = makeUniform(500, 1, 120)
        detector.processFrame(wideFrame)
        val rWide = detector.processFrame(wideFrame)
        assertFalse(rWide.isMotionDetected)
        assertEquals(500, rWide.totalPixels)
    }

    // =========================================================================
    // 5. LowLightEnhancer Boundaries & Adversarial Inputs
    // =========================================================================

    @Test
    fun testLowLightEnhancer_DynamicThresholdBoundaryAndAdversarialInputs() {
        val baseTau = 25

        // NaN average luminance -> should not crash, returns baseTau (0 delta)
        val nanTau = LowLightEnhancer.computeDynamicThreshold(baseTau, Float.NaN, true)
        assertEquals(baseTau, nanTau)

        // Negative infinity average luminance
        val negInfTau = LowLightEnhancer.computeDynamicThreshold(baseTau, Float.NEGATIVE_INFINITY, true)
        assertTrue(negInfTau >= baseTau)

        // Positive infinity average luminance
        val posInfTau = LowLightEnhancer.computeDynamicThreshold(baseTau, Float.POSITIVE_INFINITY, true)
        assertEquals(baseTau, posInfTau)

        // Negative luminance (-50.0f)
        val negLumTau = LowLightEnhancer.computeDynamicThreshold(baseTau, -50.0f, true)
        // (40 - (-50)) / 3 = 90 / 3 = 30 -> 25 + 30 = 55
        assertEquals(55, negLumTau)

        // Beyond white (300.0f)
        val highLumTau = LowLightEnhancer.computeDynamicThreshold(baseTau, 300.0f, true)
        assertEquals(baseTau, highLumTau)

        // 64-bit Long accumulator stress test: 5,000,000 pixels of 255 (sum = 1,275,000,000 > Int.MAX_VALUE / 2)
        val largeData = ByteArray(5_000_000) { 255.toByte() }
        val avgLarge = LowLightEnhancer.calculateAverageLuminance(largeData)
        assertEquals(255.0f, avgLarge, 0.001f)
    }

    @Test
    fun testLowLightEnhancer_GammaLutAndGainAdversarialCoercion() {
        // Gamma <= 0 or NaN must throw
        val badGammas = doubleArrayOf(0.0, -0.001, -1.0, Double.NaN)
        for (g in badGammas) {
            try {
                LowLightEnhancer.generateGammaLut(g)
                fail("Expected IllegalArgumentException for gamma $g")
            } catch (e: IllegalArgumentException) {
                // Expected
            }
        }

        // Linear gain with extreme and negative values: all output pixels must be in [0, 255]
        val extremes = intArrayOf(0, 1, 10, 50, 100, 200, 255)
        for (p in extremes) {
            val res1 = LowLightEnhancer.enhanceLuminanceLinear(p, gain = 100.0f, offset = 1000)
            assertEquals(255, res1)

            val res2 = LowLightEnhancer.enhanceLuminanceLinear(p, gain = -5.0f, offset = -500)
            assertEquals(0, res2)

            val res3 = LowLightEnhancer.enhanceLuminanceLinear(p, gain = 0.0f, offset = 128)
            assertEquals(128, res3)
        }

        // Buffer size mismatch in applyGammaToFrame
        val src = byteArrayOf(10, 20, 30, 40)
        val smallDst = ByteArray(2)
        val outSmall = LowLightEnhancer.applyGammaToFrame(src, smallDst)
        assertEquals(2, outSmall.size)

        val largeDst = ByteArray(10) { 99.toByte() }
        val outLarge = LowLightEnhancer.applyGammaToFrame(src, largeDst)
        assertEquals(10, outLarge.size)
        // First 4 transformed, remainder unchanged
        assertEquals(99.toByte(), outLarge[4])
    }

    // =========================================================================
    // 6. Performance Invariants & Latency Benchmark
    // =========================================================================

    @Test
    fun testPerformanceAndHotLoopLatencyInvariant() {
        val frame = makeUniform(160, 120, 128)
        // Warm up JIT
        for (i in 0 until 50) {
            detector.processFrame(frame)
        }

        val testIterations = 500
        val startNs = System.nanoTime()
        for (i in 0 until testIterations) {
            detector.processFrame(frame)
        }
        val elapsedNs = System.nanoTime() - startNs
        val elapsedMs = elapsedNs / 1_000_000.0
        val avgLatencyPerFrameMs = elapsedMs / testIterations

        println("EMPIRICAL BENCHMARK: $testIterations frames processed in ${elapsedMs}ms (average ${avgLatencyPerFrameMs}ms/frame)")

        // 30 FPS deadline is 33.3ms per frame. Vision engine hot loop must complete in < 3.0ms per frame.
        assertTrue(
            "Hot loop average latency must be < 3.0ms per frame on 160x120, was ${avgLatencyPerFrameMs}ms",
            avgLatencyPerFrameMs < 3.0
        )
    }

    // =========================================================================
    // 7. Adversarial Camouflage & Lightning Flicker Scenarios
    // =========================================================================

    @Test
    fun testAdversarialCamouflageTargetRejection() {
        val base = makeUniform(160, 120, 100)
        for (i in 0 until 5) detector.processFrame(base)

        // Inject camouflage target: patch of 105 luminance (delta = 5 << tau = 25)
        val camoFrame1 = injectPatch(base, 30, 30, 40, 40, 105)
        val camoFrame2 = injectPatch(base, 35, 35, 40, 40, 105)

        detector.processFrame(camoFrame1)
        val camoRes = detector.processFrame(camoFrame2)

        assertFalse("Camouflaged target with delta 5 < tau 25 must be rejected", camoRes.isMotionDetected)
        assertEquals(0, camoRes.activePixels)
    }

    @Test
    fun testLightningFlashRecovery() {
        val base = makeUniform(160, 120, 80)
        for (i in 0 until 5) detector.processFrame(base)

        // Lightning flash 1: full white frame
        val flash1 = makeUniform(160, 120, 255)
        val rFlash = detector.processFrame(flash1)
        assertFalse("Single flash frame must debounce without triggering motion alarm", rFlash.isMotionDetected)

        // Immediate return to dark room
        val rReturn1 = detector.processFrame(base)
        assertFalse("Return to normal room must not trigger", rReturn1.isMotionDetected)

        val rReturn2 = detector.processFrame(base)
        assertFalse("Subsequent frame must not trigger", rReturn2.isMotionDetected)
    }

    // =========================================================================
    // 8. Multi-Threaded Chaos Harness
    // =========================================================================

    @Test
    fun testConcurrentMultithreadedChaosHarness() {
        val threadCount = 16
        val iterationsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errorCount = AtomicInteger(0)

        val f1 = makeUniform(160, 120, 90)
        val f2 = injectPatch(f1, 20, 20, 30, 30, 230)
        val f3 = makeUniform(320, 240, 120)

        for (t in 0 until threadCount) {
            executor.execute {
                try {
                    for (i in 0 until iterationsPerThread) {
                        when (i % 6) {
                            0 -> detector.processFrame(f1)
                            1 -> detector.processFrame(f2)
                            2 -> detector.processFrame(f3)
                            3 -> detector.configure(MotionConfig.fromSensitivity((i % 100) + 1))
                            4 -> if (i % 25 == 0) detector.reset()
                            5 -> {
                                val dummy = detector.processFrame(f1)
                                assertNotNull(dummy)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    errorCount.incrementAndGet()
                    t.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Threads must complete within timeout", latch.await(15, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("Chaos harness must not trigger any concurrency exceptions", 0, errorCount.get())
    }

    // =========================================================================
    // 9. NormalizedRect Mathematical Integrity Under Extreme Blobs
    // =========================================================================

    @Test
    fun testNormalizedRectStrictCoordinateIntegrityUnderExtremeBlobs() {
        val base = makeUniform(160, 120, 100)
        for (i in 0 until 5) detector.processFrame(base)

        // 1. Extreme: 4 corners simultaneously active
        var fCorners = injectPatch(base, 0, 0, 16, 16, 240) // Top-left
        fCorners = injectPatch(fCorners, 144, 0, 16, 16, 240) // Top-right
        fCorners = injectPatch(fCorners, 0, 104, 16, 16, 240) // Bottom-left
        fCorners = injectPatch(fCorners, 144, 104, 16, 16, 240) // Bottom-right

        detector.processFrame(fCorners)
        val resCorners = detector.processFrame(fCorners)
        assertTrue(resCorners.isMotionDetected)
        assertTrue("Must detect at least 2 corner clusters", resCorners.boundingBoxes.isNotEmpty())

        for (box in resCorners.boundingBoxes) {
            assertTrue("left in [0, 1]: ${box.left}", box.left in 0f..1f)
            assertTrue("top in [0, 1]: ${box.top}", box.top in 0f..1f)
            assertTrue("right in [0, 1]: ${box.right}", box.right in 0f..1f)
            assertTrue("bottom in [0, 1]: ${box.bottom}", box.bottom in 0f..1f)
            assertTrue("right >= left: ${box.right} vs ${box.left}", box.right >= box.left)
            assertTrue("bottom >= top: ${box.bottom} vs ${box.top}", box.bottom >= box.top)
            assertTrue("area in [0, 1]: ${box.area}", box.area in 0f..1f)
        }

        // 2. Extreme: Full-frame motion (100% changed)
        detector.reset()
        for (i in 0 until 5) detector.processFrame(base)
        val fullWhite = makeUniform(160, 120, 240)
        detector.processFrame(fullWhite)
        val resFull = detector.processFrame(fullWhite)
        assertTrue(resFull.isMotionDetected)
        assertFalse(resFull.boundingBoxes.isEmpty())
        val fullBox = resFull.boundingBoxes.first()
        assertEquals(0f, fullBox.left, 0.05f)
        assertEquals(0f, fullBox.top, 0.05f)
        assertEquals(1f, fullBox.right, 0.05f)
        assertEquals(1f, fullBox.bottom, 0.05f)
    }
}
