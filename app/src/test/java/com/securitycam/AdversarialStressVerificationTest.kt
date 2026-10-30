package com.securitycam

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.securitycam.core.alerting.AlertPayload
import com.securitycam.core.alerting.DispatchResult
import com.securitycam.core.storage.MotionEventEntity
import com.securitycam.core.surveillance.LuminanceFrame
import com.securitycam.core.surveillance.MotionConfig
import com.securitycam.core.surveillance.MotionResult
import com.securitycam.core.surveillance.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Empirical Adversarial Stress Test Suite for Milestone 1.
 * Conducts stress testing across domain models, boundaries, serialization,
 * and Robolectric environment integrity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdversarialStressVerificationTest {

    private lateinit var context: Context
    private val gson = Gson()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    // =========================================================================
    // 1. LuminanceFrame Boundary & Stress Tests
    // =========================================================================

    @Test
    fun testLuminanceFrame_zeroLengthDataBehavior() {
        val emptyBytes = ByteArray(0)
        val frame = LuminanceFrame(
            data = emptyBytes,
            width = 0,
            height = 0,
            timestampMs = 0L
        )
        assertEquals(0, frame.data.size)
        assertEquals(0, frame.width)
        assertEquals(0, frame.height)
        assertEquals(0L, frame.timestampMs)

        // Equal to another empty frame
        val anotherEmpty = LuminanceFrame(ByteArray(0), 0, 0, 0L)
        assertEquals(frame, anotherEmpty)
        assertEquals(frame.hashCode(), anotherEmpty.hashCode())
    }

    @Test
    fun testLuminanceFrame_mismatchedBufferSizeAndNegativeDimensions() {
        // Data carrier contract check: data size can diverge from width * height
        // at the raw model layer (validation is expected at detector layer in M2)
        val testBytes = ByteArray(10) { it.toByte() }
        val frame = LuminanceFrame(
            data = testBytes,
            width = 160,
            height = 120,
            timestampMs = -1L
        )
        assertEquals(10, frame.data.size)
        assertEquals(160, frame.width)
        assertEquals(120, frame.height)
        assertEquals(-1L, frame.timestampMs)

        // Negative dimensions data carrier behavior
        val negativeFrame = LuminanceFrame(
            data = testBytes,
            width = -1,
            height = -1,
            timestampMs = Long.MAX_VALUE
        )
        assertEquals(-1, negativeFrame.width)
        assertEquals(-1, negativeFrame.height)
        assertEquals(Long.MAX_VALUE, negativeFrame.timestampMs)
    }

    @Test
    fun testLuminanceFrame_contentEqualsVersusReferenceEquals() {
        val bytes1 = byteArrayOf(1, 2, 3, 4, 5)
        val bytes2 = byteArrayOf(1, 2, 3, 4, 5)
        val bytes3 = byteArrayOf(1, 2, 3, 4, 6)

        val frame1 = LuminanceFrame(bytes1, 5, 1, 100L)
        val frame2 = LuminanceFrame(bytes2, 5, 1, 100L)
        val frame3 = LuminanceFrame(bytes3, 5, 1, 100L)

        // Equal content must be equal even if distinct array references
        assertEquals(frame1, frame2)
        assertEquals(frame1.hashCode(), frame2.hashCode())

        // Different byte content must NOT be equal
        assertNotEquals(frame1, frame3)
    }

    @Test
    fun testLuminanceFrame_jsonSerializationRoundtrip() {
        val testBytes = ByteArray(19200) { (it % 128).toByte() }
        val original = LuminanceFrame(testBytes, 160, 120, 1700000000000L)
        val json = gson.toJson(original)
        val restored = gson.fromJson(json, LuminanceFrame::class.java)

        assertEquals(original.width, restored.width)
        assertEquals(original.height, restored.height)
        assertEquals(original.timestampMs, restored.timestampMs)
        assertEquals(original, restored)
    }

    // =========================================================================
    // 2. NormalizedRect Boundary & Invariant Stress Tests
    // =========================================================================

    @Test
    fun testNormalizedRect_extremeValidBoundaries() {
        // Point rectangle at origin
        val originPoint = NormalizedRect(0f, 0f, 0f, 0f)
        assertEquals(0f, originPoint.width, 0.00001f)
        assertEquals(0f, originPoint.height, 0.00001f)
        assertEquals(0f, originPoint.area, 0.00001f)

        // Full coverage rectangle
        val fullRect = NormalizedRect(0f, 0f, 1f, 1f)
        assertEquals(1f, fullRect.width, 0.00001f)
        assertEquals(1f, fullRect.height, 0.00001f)
        assertEquals(1f, fullRect.area, 0.00001f)

        // Point rectangle at bottom-right corner
        val cornerPoint = NormalizedRect(1f, 1f, 1f, 1f)
        assertEquals(0f, cornerPoint.width, 0.00001f)
        assertEquals(0f, cornerPoint.height, 0.00001f)
        assertEquals(0f, cornerPoint.area, 0.00001f)
    }

    @Test
    fun testNormalizedRect_rejectsNegativeCoordinates() {
        val negativeValues = listOf(-0.0001f, -1.0f, -100f)
        for (v in negativeValues) {
            try {
                NormalizedRect(left = v, top = 0f, right = 1f, bottom = 1f)
                fail("Expected IllegalArgumentException for left = $v")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("left must be in [0, 1]"))
            }

            try {
                NormalizedRect(left = 0f, top = v, right = 1f, bottom = 1f)
                fail("Expected IllegalArgumentException for top = $v")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("top must be in [0, 1]"))
            }
        }
    }

    @Test
    fun testNormalizedRect_rejectsCoordinatesExceedingUnity() {
        val excessiveValues = listOf(1.0001f, 1.5f, 100f)
        for (v in excessiveValues) {
            try {
                NormalizedRect(left = 0f, top = 0f, right = v, bottom = 1f)
                fail("Expected IllegalArgumentException for right = $v")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("right must be in [0, 1]"))
            }

            try {
                NormalizedRect(left = 0f, top = 0f, right = 1f, bottom = v)
                fail("Expected IllegalArgumentException for bottom = $v")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("bottom must be in [0, 1]"))
            }
        }
    }

    @Test
    fun testNormalizedRect_rejectsInvertedBounds() {
        // right < left
        try {
            NormalizedRect(left = 0.6f, top = 0.2f, right = 0.599f, bottom = 0.8f)
            fail("Expected IllegalArgumentException for right < left")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("must be >= left"))
        }

        // bottom < top
        try {
            NormalizedRect(left = 0.1f, top = 0.7f, right = 0.5f, bottom = 0.699f)
            fail("Expected IllegalArgumentException for bottom < top")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("must be >= top"))
        }
    }

    // =========================================================================
    // 3. MotionConfig Sensitivity & Parameter Stress Tests
    // =========================================================================

    @Test
    fun testMotionConfig_sensitivityBoundaries() {
        // Valid boundaries: 1, 50, 100
        val minConfig = MotionConfig(sensitivity = 1)
        assertEquals(1, minConfig.sensitivity)

        val midConfig = MotionConfig(sensitivity = 50)
        assertEquals(50, midConfig.sensitivity)

        val maxConfig = MotionConfig(sensitivity = 100)
        assertEquals(100, maxConfig.sensitivity)

        // Invalid: 0 (must throw)
        try {
            MotionConfig(sensitivity = 0)
            fail("Expected IllegalArgumentException for sensitivity = 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sensitivity must be in 1..100"))
        }

        // Invalid: 101 (must throw)
        try {
            MotionConfig(sensitivity = 101)
            fail("Expected IllegalArgumentException for sensitivity = 101")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sensitivity must be in 1..100"))
        }

        // Invalid: negative (-1)
        try {
            MotionConfig(sensitivity = -1)
            fail("Expected IllegalArgumentException for sensitivity = -1")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sensitivity must be in 1..100"))
        }

        // Invalid: Int extreme boundaries
        try {
            MotionConfig(sensitivity = Int.MIN_VALUE)
            fail("Expected IllegalArgumentException for sensitivity = Int.MIN_VALUE")
        } catch (_: IllegalArgumentException) {}

        try {
            MotionConfig(sensitivity = Int.MAX_VALUE)
            fail("Expected IllegalArgumentException for sensitivity = Int.MAX_VALUE")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun testMotionConfig_pixelThresholdBoundaries() {
        val valid = MotionConfig(pixelThreshold = 1)
        assertEquals(1, valid.pixelThreshold)

        try {
            MotionConfig(pixelThreshold = 0)
            fail("Expected IllegalArgumentException for pixelThreshold = 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("pixelThreshold must be positive"))
        }

        try {
            MotionConfig(pixelThreshold = -20)
            fail("Expected IllegalArgumentException for pixelThreshold = -20")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("pixelThreshold must be positive"))
        }
    }

    @Test
    fun testMotionConfig_minAreaRatioBoundaries() {
        val zeroArea = MotionConfig(minAreaRatio = 0f)
        assertEquals(0f, zeroArea.minAreaRatio, 0.00001f)

        val fullArea = MotionConfig(minAreaRatio = 1f)
        assertEquals(1f, fullArea.minAreaRatio, 0.00001f)

        try {
            MotionConfig(minAreaRatio = -0.0001f)
            fail("Expected IllegalArgumentException for minAreaRatio = -0.0001")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("minAreaRatio must be in [0, 1]"))
        }

        try {
            MotionConfig(minAreaRatio = 1.0001f)
            fail("Expected IllegalArgumentException for minAreaRatio = 1.0001")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("minAreaRatio must be in [0, 1]"))
        }
    }

    @Test
    fun testMotionConfig_consecutiveTriggerFramesBoundaries() {
        val singleFrame = MotionConfig(consecutiveTriggerFrames = 1)
        assertEquals(1, singleFrame.consecutiveTriggerFrames)

        try {
            MotionConfig(consecutiveTriggerFrames = 0)
            fail("Expected IllegalArgumentException for consecutiveTriggerFrames = 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("consecutiveTriggerFrames must be >= 1"))
        }

        try {
            MotionConfig(consecutiveTriggerFrames = -5)
            fail("Expected IllegalArgumentException for consecutiveTriggerFrames = -5")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("consecutiveTriggerFrames must be >= 1"))
        }
    }

    // =========================================================================
    // 4. MotionEventEntity Stress & Serialization Tests
    // =========================================================================

    @Test
    fun testMotionEventEntity_defaultValuesAndIdUniqueness() {
        val entity1 = MotionEventEntity(
            timestamp = 1000L,
            imagePath = "/path/1.jpg",
            motionScore = 0.5f,
            isLowLight = false
        )
        val entity2 = MotionEventEntity(
            timestamp = 1000L,
            imagePath = "/path/2.jpg",
            motionScore = 0.5f,
            isLowLight = false
        )

        // Default statuses
        assertEquals(MotionEventEntity.STATUS_DISABLED, entity1.smsStatus)
        assertEquals(MotionEventEntity.STATUS_DISABLED, entity1.emailStatus)

        // Unique auto-generated UUIDs
        assertNotNull(entity1.id)
        assertNotNull(entity2.id)
        assertNotEquals(entity1.id, entity2.id)
    }

    @Test
    fun testMotionEventEntity_statusConstants() {
        assertEquals("SENT", MotionEventEntity.STATUS_SENT)
        assertEquals("FAILED", MotionEventEntity.STATUS_FAILED)
        assertEquals("SUPPRESSED", MotionEventEntity.STATUS_SUPPRESSED)
        assertEquals("DISABLED", MotionEventEntity.STATUS_DISABLED)
    }

    @Test
    fun testMotionEventEntity_extremeValuesAndSerialization() {
        val extremeEntity = MotionEventEntity(
            id = "custom-id-xyz-999",
            timestamp = Long.MAX_VALUE,
            imagePath = "/storage/emulated/0/DCIM/Special%20Chars&Spaces/event#1.jpg",
            motionScore = 999.999f,
            isLowLight = true,
            smsStatus = MotionEventEntity.STATUS_SUPPRESSED,
            emailStatus = MotionEventEntity.STATUS_FAILED
        )

        val json = gson.toJson(extremeEntity)
        val deserialized = gson.fromJson(json, MotionEventEntity::class.java)

        assertEquals(extremeEntity, deserialized)
        assertEquals(Long.MAX_VALUE, deserialized.timestamp)
        assertEquals(999.999f, deserialized.motionScore, 0.001f)
        assertTrue(deserialized.isLowLight)
        assertEquals(MotionEventEntity.STATUS_SUPPRESSED, deserialized.smsStatus)
        assertEquals(MotionEventEntity.STATUS_FAILED, deserialized.emailStatus)
    }

    // =========================================================================
    // 5. Test Harness Rigidity & Negative Failure Verification
    // =========================================================================

    @Test
    fun testRobolectricHarness_negativeExpectationFailsProperly() {
        // Verifies that assertion failures are correctly thrown and not swallowed
        var caughtAssertionError = false
        try {
            assertEquals("intentionally_wrong_package", context.packageName)
        } catch (e: AssertionError) {
            caughtAssertionError = true
        }
        assertTrue("AssertionError must be thrown when expectation fails", caughtAssertionError)

        var caughtSdkAssertionError = false
        try {
            assertEquals(999, context.applicationInfo.targetSdkVersion)
        } catch (e: AssertionError) {
            caughtSdkAssertionError = true
        }
        assertTrue("AssertionError must be thrown when target SDK expectation fails", caughtSdkAssertionError)
    }
}
