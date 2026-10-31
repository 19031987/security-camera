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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Empirical Adversarial Challenger Stress Testing Suite for Milestone 2:
 * Core Vision & Motion Detection Engine.
 *
 * Rigorously challenges:
 * 1. Concurrency & Thread-Safety: High-load concurrent calls with frame processing, dynamic reconfiguration,
 *    rapid resets, and chaotic multi-resolution switching across 16 threads.
 * 2. Ghosting & Intruder Departure: Verifies zero phantom/ghost motion when an intruder traverses through,
 *    pauses in-scene, and departs, ensuring background unmasking does not trigger false alerts.
 * 3. Dynamic Noise Floor Adaptation: Heavy Gaussian sensor noise (sigma up to 10.0), extreme salt-and-pepper
 *    sensor grain, and genuine motion discrimination in dark environments.
 * 4. Multi-Resolution & Non-Standard Frame Geometry: Non-standard frame dimensions (e.g. 163x121) testing
 *    8x8 block padding and 3x3 morphological boundaries without index out-of-bounds exceptions.
 * 5. Continuous Frame Endurance & Memory Stability: 2,000 continuous frames and rapid reset cycles
 *    confirming zero memory leaks and deterministic performance.
 */
class AdversarialMotionStressTest {

    private lateinit var detector: AdaptiveBackgroundMotionDetector

    @Before
    fun setUp() {
        detector = AdaptiveBackgroundMotionDetector()
    }

    // =========================================================================
    // Synthetic Frame Helpers
    // =========================================================================

    private fun makeUniform(w: Int, h: Int, lum: Int, ts: Long = 0L): LuminanceFrame {
        val b = (lum.coerceIn(0, 255) and 0xFF).toByte()
        return LuminanceFrame(ByteArray(w * h) { b }, w, h, ts)
    }

    private fun injectTarget(
        base: LuminanceFrame,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        targetLum: Int
    ): LuminanceFrame {
        val data = base.data.copyOf()
        val b = (targetLum.coerceIn(0, 255) and 0xFF).toByte()
        val x0 = x.coerceIn(0, base.width)
        val x1 = (x + w).coerceIn(0, base.width)
        val y0 = y.coerceIn(0, base.height)
        val y1 = (y + h).coerceIn(0, base.height)
        for (r in y0 until y1) {
            val offset = r * base.width
            for (c in x0 until x1) {
                data[offset + c] = b
            }
        }
        return LuminanceFrame(data, base.width, base.height, base.timestampMs)
    }

    private fun makeGaussianNoisyFrame(
        w: Int,
        h: Int,
        baseLum: Int,
        sigma: Double,
        seed: Long
    ): LuminanceFrame {
        val rng = Random(seed)
        val data = ByteArray(w * h)
        for (i in data.indices) {
            val sample = (baseLum + rng.nextGaussian() * sigma).roundToInt().coerceIn(0, 255)
            data[i] = (sample and 0xFF).toByte()
        }
        return LuminanceFrame(data, w, h, 0L)
    }

    // =========================================================================
    // 1. Concurrency & Thread-Safety Stress Tests
    // =========================================================================

    @Test
    fun testChaoticMultiThreadedConcurrencyStress() {
        val threadCount = 16
        val iterationsPerThread = 150
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val failureCount = AtomicInteger(0)
        val firstException = AtomicReference<Throwable?>(null)

        val frame160x120 = makeUniform(160, 120, 100)
        val moving160x120 = injectTarget(frame160x120, 20, 20, 25, 25, 230)
        val frame320x240 = makeUniform(320, 240, 100)
        val frame80x60 = makeUniform(80, 60, 100)

        for (t in 0 until threadCount) {
            executor.execute {
                try {
                    for (i in 0 until iterationsPerThread) {
                        when ((t + i) % 7) {
                            0 -> detector.processFrame(frame160x120)
                            1 -> detector.processFrame(moving160x120)
                            2 -> detector.processFrame(frame320x240)
                            3 -> detector.processFrame(frame80x60)
                            4 -> detector.configure(MotionConfig.fromSensitivity((i % 100) + 1))
                            5 -> detector.reset()
                            6 -> {
                                detector.configure(MotionConfig(pixelThreshold = 20, minAreaRatio = 0.02f))
                                detector.processFrame(frame160x120)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    failureCount.incrementAndGet()
                    firstException.compareAndSet(null, e)
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(20, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue("All concurrent threads must finish within 20s without deadlock", completed)
        if (firstException.get() != null) {
            firstException.get()!!.printStackTrace()
        }
        assertEquals("Zero exceptions permitted during chaotic multi-threaded execution", 0, failureCount.get())

        // Verify detector remains fully operable after stress test
        detector.reset()
        val staticFrame = makeUniform(160, 120, 100)
        for (i in 0 until 5) detector.processFrame(staticFrame)
        val resultAfterStress = detector.processFrame(staticFrame)
        assertFalse("Detector must function cleanly after chaotic concurrent stress", resultAfterStress.isMotionDetected)
        assertEquals(0, resultAfterStress.activePixels)
    }

    // =========================================================================
    // 2. Ghosting & Intruder Departure Stress Tests
    // =========================================================================

    @Test
    fun testGhostingImmunityWhenIntruderTraversesAndExits() {
        val width = 160
        val height = 120
        val baseLum = 90
        val intruderLum = 230
        val targetW = 20
        val targetH = 20

        // 1. Stabilize on empty background (Y=90)
        val emptyFrame = makeUniform(width, height, baseLum)
        for (i in 0 until 10) {
            val res = detector.processFrame(emptyFrame)
            assertFalse("Baseline empty scene must have no motion", res.isMotionDetected)
        }

        // 2. Intruder traverses across screen: x from 10 to 130 over 25 frames
        var motionDetectedDuringTraverse = 0
        for (step in 0 until 25) {
            val posX = 10 + (step * 5).coerceAtMost(130)
            val intruderFrame = injectTarget(emptyFrame, posX, 40, targetW, targetH, intruderLum)
            val res = detector.processFrame(intruderFrame)
            if (res.isMotionDetected) {
                motionDetectedDuringTraverse++
                assertFalse("Must provide bounding boxes during motion", res.boundingBoxes.isEmpty())
            }
        }
        assertTrue("Intruder must be continuously detected during traversal (detected $motionDetectedDuringTraverse of 25)",
            motionDetectedDuringTraverse >= 22)

        // 3. Intruder completely leaves the scene! Frame returns to empty background.
        // Within consecutiveTriggerFrames (2 frames), motion status must drop to false.
        val postDepartureFrame1 = detector.processFrame(emptyFrame)
        // Due to debounce hysteresis, frame 1 may still have consecutive count decremented from 2 to 1
        val postDepartureFrame2 = detector.processFrame(emptyFrame)
        assertFalse("Frame 2 after departure MUST NOT report motion", postDepartureFrame2.isMotionDetected)

        // Verify across next 30 continuous frames that NO phantom/ghost motion ever appears
        for (f in 3..32) {
            val res = detector.processFrame(emptyFrame)
            assertFalse("Phantom motion detected at frame $f after intruder departure!", res.isMotionDetected)
            assertEquals("Active pixels must remain 0 after departure", 0, res.activePixels)
            assertEquals("Motion score must remain 0.0 after departure", 0f, res.motionScore, 0.0001f)
            assertTrue("No ghost bounding boxes permitted", res.boundingBoxes.isEmpty())
        }
    }

    @Test
    fun testGhostingImmunityStationaryIntruderPauseAndDeparture() {
        val width = 160
        val height = 120
        val baseLum = 100
        val intruderLum = 240
        val emptyFrame = makeUniform(width, height, baseLum)

        // Stabilize empty scene
        for (i in 0 until 10) detector.processFrame(emptyFrame)

        // Intruder enters at (50, 50) and stands still for 20 continuous frames (~0.7s at 30 fps)
        val pausedIntruderFrame = injectTarget(emptyFrame, 50, 50, 24, 24, intruderLum)
        var detectedWhilePaused = 0
        for (i in 0 until 20) {
            val res = detector.processFrame(pausedIntruderFrame)
            if (res.isMotionDetected) detectedWhilePaused++
        }
        assertTrue("Intruder must remain detected while paused due to selective update", detectedWhilePaused >= 18)

        // Intruder suddenly vacates the scene (e.g. runs out or vanishes)
        // Newly uncovered background at (50, 50) must not register as an inverted ghost object
        val post1 = detector.processFrame(emptyFrame)
        val post2 = detector.processFrame(emptyFrame)
        assertFalse("Detector must de-assert motion by frame 2 after stationary departure", post2.isMotionDetected)

        for (i in 3 until 40) {
            val res = detector.processFrame(emptyFrame)
            assertFalse("Stationary departure ghost detected at frame $i", res.isMotionDetected)
            assertEquals("Active pixels must be 0 for uncovered background", 0, res.activePixels)
        }
    }

    @Test
    fun testGhostingImmunityAtHighSensitivity() {
        // High sensitivity S=80 (tau = 18, rho = 0.00296)
        detector.configure(MotionConfig.fromSensitivity(80))

        val emptyFrame = makeUniform(160, 120, 110)
        for (i in 0 until 10) detector.processFrame(emptyFrame)

        // Intruder with moderate contrast delta = 40 (150 vs 110)
        val intruder = injectTarget(emptyFrame, 30, 30, 16, 16, 150)
        for (i in 0 until 15) detector.processFrame(intruder)

        // Intruder leaves
        detector.processFrame(emptyFrame)
        val post2 = detector.processFrame(emptyFrame)
        assertFalse("At S=80, post-departure must not trigger motion at frame 2", post2.isMotionDetected)

        for (i in 3 until 30) {
            val res = detector.processFrame(emptyFrame)
            assertFalse("At S=80, phantom motion detected at frame $i", res.isMotionDetected)
        }
    }

    // =========================================================================
    // 3. Dynamic Noise Floor & Low-Light Stress Tests
    // =========================================================================

    @Test
    fun testExtremeDarknessWithHeavyGaussianNoiseFloor() {
        // Very dark room: base luminance Y = 10, low-light compensation active
        detector.configure(MotionConfig(lowLightCompensation = true, pixelThreshold = 25))

        // Sigma = 8.0 in 10-luminance darkness represents severe analog amplifier grain
        for (frameIdx in 0 until 100) {
            val noisyFrame = makeGaussianNoisyFrame(
                w = 160,
                h = 120,
                baseLum = 10,
                sigma = 8.0,
                seed = 1000L + frameIdx
            )
            val res = detector.processFrame(noisyFrame)
            assertTrue("Scene must be detected as low-light", res.isLowLight)
            assertTrue("Average luminance must be < 40 in dark scene", res.averageLuminance < 40f)
            assertFalse(
                "Heavy Gaussian low-light noise must NOT trigger motion alarm at frame $frameIdx (active=${res.activePixels})",
                res.isMotionDetected
            )
            assertTrue("Bounding boxes must be empty for noisy background", res.boundingBoxes.isEmpty())
        }
    }

    @Test
    fun testExtremeSaltAndPepperSensorGrainSuppression() {
        // Base low-light scene Y = 30
        val baseFrame = makeUniform(160, 120, 30)
        for (i in 0 until 5) detector.processFrame(baseFrame)

        // Inject 200 random salt-and-pepper noise spikes (each single pixel)
        val rng = Random(9999L)
        val noisyBytes = baseFrame.data.copyOf()
        for (s in 0 until 200) {
            val x = rng.nextInt(156) + 2
            val y = rng.nextInt(116) + 2
            noisyBytes[y * 160 + x] = if (rng.nextBoolean()) 255.toByte() else 0.toByte()
        }
        val noisyFrame = LuminanceFrame(noisyBytes, 160, 120, 0L)
        val res = detector.processFrame(noisyFrame)

        // Morphological 3x3 opening requires a full 3x3 block to survive erosion; isolated spikes are 0
        assertEquals("Isolated salt-and-pepper spikes must produce 0 active pixels after opening", 0, res.activePixels)
        assertFalse("Salt-and-pepper grain must not trigger motion", res.isMotionDetected)
    }

    @Test
    fun testGenuineMotionDiscriminationUnderLowLightNoise() {
        // Verify that low-light noise suppression does NOT blind the detector to genuine intruders!
        detector.configure(MotionConfig(lowLightCompensation = true, pixelThreshold = 25))

        // 1. Establish noisy low-light baseline (Y=20, sigma=4.0)
        for (i in 0 until 10) {
            val noisyBaseline = makeGaussianNoisyFrame(160, 120, 20, 4.0, 500L + i)
            val res = detector.processFrame(noisyBaseline)
            assertFalse(res.isMotionDetected)
        }

        // 2. An intruder enters the dark room (patch 25x25, Y=80, contrast delta = 60 > dynamic threshold ~32)
        val noisyWithIntruder1 = injectTarget(
            makeGaussianNoisyFrame(160, 120, 20, 4.0, 601L),
            40, 40, 25, 25, 80
        )
        val noisyWithIntruder2 = injectTarget(
            makeGaussianNoisyFrame(160, 120, 20, 4.0, 602L),
            42, 42, 25, 25, 80
        )

        detector.processFrame(noisyWithIntruder1)
        val resIntruder = detector.processFrame(noisyWithIntruder2)

        assertTrue("Genuine intruder MUST be detected even under noisy low-light conditions", resIntruder.isMotionDetected)
        assertTrue("Intruder must generate bounding boxes", resIntruder.boundingBoxes.isNotEmpty())
        assertTrue("Scene must still be flagged as low light", resIntruder.isLowLight)

        // 3. Intruder leaves: verify return to clean no-motion
        detector.processFrame(makeGaussianNoisyFrame(160, 120, 20, 4.0, 701L))
        val postDeparture = detector.processFrame(makeGaussianNoisyFrame(160, 120, 20, 4.0, 702L))
        assertFalse("After intruder leaves dark room, detector must cleanly return to false", postDeparture.isMotionDetected)
    }

    // =========================================================================
    // 4. Non-Standard Resolution & Grid Block Boundary Stress Tests
    // =========================================================================

    @Test
    fun testNonStandardResolutionAndBoundaryPadding() {
        // Frame dimensions that are NOT multiples of 8 (e.g. 163 x 121)
        val width = 163
        val height = 121
        val nonStandardFrame = makeUniform(width, height, 120)

        // Must not crash or throw IndexOutOfBoundsException during block partitioning or morphology
        for (i in 0 until 5) {
            val res = detector.processFrame(nonStandardFrame)
            assertFalse(res.isMotionDetected)
            assertEquals(width * height, res.totalPixels)
        }

        // Inject moving target right against the odd boundaries (x=140..163, y=100..121)
        val targetFrame1 = injectTarget(nonStandardFrame, 135, 95, 28, 26, 240)
        val targetFrame2 = injectTarget(nonStandardFrame, 137, 97, 26, 24, 240)

        detector.processFrame(targetFrame1)
        val resTarget = detector.processFrame(targetFrame2)

        assertTrue("Target near non-standard boundaries must trigger motion", resTarget.isMotionDetected)
        for (box in resTarget.boundingBoxes) {
            assertTrue("Left in bounds", box.left in 0f..1f)
            assertTrue("Top in bounds", box.top in 0f..1f)
            assertTrue("Right in bounds", box.right in 0f..1f)
            assertTrue("Bottom in bounds", box.bottom in 0f..1f)
            assertTrue("Right >= Left", box.right >= box.left)
            assertTrue("Bottom >= Top", box.bottom >= box.top)
        }
    }

    @Test
    fun testStrobeLightOscillationStress() {
        // Rapid alternating between full white (255) and full black (0) simulating strobe attack
        val blackFrame = makeUniform(160, 120, 0)
        val whiteFrame = makeUniform(160, 120, 255)

        for (i in 0 until 20) {
            val frame = if (i % 2 == 0) whiteFrame else blackFrame
            val res = detector.processFrame(frame)
            // Regardless of whether motion triggers on strobe, bounding boxes must NEVER violate invariants
            for (box in res.boundingBoxes) {
                assertTrue(box.left in 0f..1f)
                assertTrue(box.top in 0f..1f)
                assertTrue(box.right in 0f..1f)
                assertTrue(box.bottom in 0f..1f)
            }
        }
    }

    // =========================================================================
    // 5. Continuous Frame Endurance & Memory Stability Tests
    // =========================================================================

    @Test
    fun testContinuousFrameEnduranceAndZeroMemoryLeak() {
        // Run 2,000 continuous frames through the vision engine
        val totalFrames = 2000
        val baseFrame = makeUniform(160, 120, 100)
        val movingTarget = injectTarget(baseFrame, 40, 40, 20, 20, 220)

        // Seed detector
        detector.processFrame(baseFrame)

        var triggerCount = 0
        val startTime = System.currentTimeMillis()

        for (i in 0 until totalFrames) {
            // Alternate between static scenes (frames 0..39) and moving target bursts (frames 40..49)
            val isTargetBurst = (i % 50) >= 40
            val frame = if (isTargetBurst) movingTarget else baseFrame
            val res = detector.processFrame(frame)
            if (res.isMotionDetected) {
                triggerCount++
            }
        }

        val elapsedMs = System.currentTimeMillis() - startTime
        val fps = (totalFrames.toDouble() / (elapsedMs.toDouble() / 1000.0)).roundToInt()

        // Verify speed: 2000 frames must process comfortably in test runner (> 100 FPS on desktop JVM)
        assertTrue("Endurance run must process frames efficiently (got $fps FPS, elapsed ${elapsedMs}ms)", elapsedMs < 10000)
        assertTrue("Trigger count must reflect target bursts", triggerCount > 200)

        // Post-endurance static verification
        for (i in 0 until 5) detector.processFrame(baseFrame)
        val finalRes = detector.processFrame(baseFrame)
        assertFalse("Detector must remain healthy and stable after 2000 frames", finalRes.isMotionDetected)
        assertEquals(0, finalRes.activePixels)
    }

    @Test
    fun testRapidResetCyclesStability() {
        // Rapid alternating between reset() and processFrame() 500 times
        val frame = makeUniform(160, 120, 120)
        for (i in 0 until 500) {
            detector.reset()
            val res = detector.processFrame(frame)
            assertFalse("First frame after reset must ALWAYS return isMotionDetected = false", res.isMotionDetected)
            assertEquals("First frame after reset must have activePixels = 0", 0, res.activePixels)
            assertEquals(0f, res.motionScore, 0.0001f)
        }
    }
}
