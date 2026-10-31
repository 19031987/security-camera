package com.securitycam.adversarial

import android.content.Context
import android.content.Intent
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.securitycam.core.config.RecipientValidator
import com.securitycam.core.surveillance.NormalizedRect
import com.securitycam.service.SurveillanceService
import com.securitycam.ui.BoundingBoxOverlayView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Empirical Adversarial Challenge Test Suite for Milestone 5:
 * 1. Coordinate transformations & mirroring edge cases in BoundingBoxOverlayView
 * 2. Invariant enforcement in NormalizedRect domain model
 * 3. RecipientValidator boundary conditions, edge cases, and fuzzing
 * 4. SurveillanceService lifecycle and rapid state transitions
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdversarialStressTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    // =========================================================================
    // 1. BOUNDING BOX OVERLAY & COORDINATE TRANSFORMATION ADVERSARIAL TESTS
    // =========================================================================

    @Test
    fun testZeroSizedRectangles() {
        val viewW = 1920f
        val viewH = 1080f

        // Point box (zero width, zero height)
        val pointBox = NormalizedRect(0.5f, 0.5f, 0.5f, 0.5f)
        val rectNormal = BoundingBoxOverlayView.transformRect(pointBox, viewW, viewH, isMirrored = false)
        val rectMirrored = BoundingBoxOverlayView.transformRect(pointBox, viewW, viewH, isMirrored = true)

        assertEquals(960f, rectNormal.left, 0.001f)
        assertEquals(960f, rectNormal.right, 0.001f)
        assertEquals(540f, rectNormal.top, 0.001f)
        assertEquals(540f, rectNormal.bottom, 0.001f)
        assertEquals(0f, rectNormal.width(), 0.001f)
        assertEquals(0f, rectNormal.height(), 0.001f)

        // Mirrored point at center should remain exactly at center
        assertEquals(960f, rectMirrored.left, 0.001f)
        assertEquals(960f, rectMirrored.right, 0.001f)
        assertEquals(540f, rectMirrored.top, 0.001f)
        assertEquals(540f, rectMirrored.bottom, 0.001f)
    }

    @Test
    fun testInvertedCoordinateBounds() {
        // Domain model must reject inverted horizontal coordinates
        try {
            NormalizedRect(left = 0.8f, top = 0.1f, right = 0.2f, bottom = 0.9f)
            fail("NormalizedRect must throw IllegalArgumentException when right < left")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("must be >= left") == true)
        }

        // Domain model must reject inverted vertical coordinates
        try {
            NormalizedRect(left = 0.2f, top = 0.9f, right = 0.8f, bottom = 0.1f)
            fail("NormalizedRect must throw IllegalArgumentException when bottom < top")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("must be >= top") == true)
        }

        // Verify BoundingBoxOverlayView.transformRect with valid edge rectangles
        val validEdgeBox = NormalizedRect(left = 0.1f, top = 0.2f, right = 0.8f, bottom = 0.9f)
        val transformed = BoundingBoxOverlayView.transformRect(validEdgeBox, 1000f, 1000f, isMirrored = false)
        assertEquals(100f, transformed.left, 0.001f)
        assertEquals(200f, transformed.top, 0.001f)
        assertEquals(800f, transformed.right, 0.001f)
        assertEquals(900f, transformed.bottom, 0.001f)
    }

    @Test
    fun testOutOfBoundsCoordinatesClamping() {
        // Negative left
        try {
            NormalizedRect(left = -0.1f, top = 0.0f, right = 0.8f, bottom = 0.8f)
            fail("NormalizedRect must throw IllegalArgumentException when left < 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("must be in [0, 1]") == true)
        }

        // Right > 1.0
        try {
            NormalizedRect(left = 0.0f, top = 0.0f, right = 1.5f, bottom = 0.8f)
            fail("NormalizedRect must throw IllegalArgumentException when right > 1")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("must be in [0, 1]") == true)
        }

        // Top < 0
        try {
            NormalizedRect(left = 0.0f, top = -0.5f, right = 0.8f, bottom = 0.8f)
            fail("NormalizedRect must throw IllegalArgumentException when top < 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("must be in [0, 1]") == true)
        }

        // Bottom > 1.0
        try {
            NormalizedRect(left = 0.0f, top = 0.0f, right = 0.8f, bottom = 2.0f)
            fail("NormalizedRect must throw IllegalArgumentException when bottom > 1")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("must be in [0, 1]") == true)
        }

        // Verify valid full boundary rects transform properly
        val fullBoundaryBox = NormalizedRect(left = 0.0f, top = 0.0f, right = 1.0f, bottom = 1.0f)
        val transformed = BoundingBoxOverlayView.transformRect(fullBoundaryBox, 1920f, 1080f, isMirrored = false)
        assertEquals(0f, transformed.left, 0.001f)
        assertEquals(0f, transformed.top, 0.001f)
        assertEquals(1920f, transformed.right, 0.001f)
        assertEquals(1080f, transformed.bottom, 0.001f)
    }

    @Test
    fun testBoundaryClampingExtremeLimits() {
        val viewW = 1000f
        val viewH = 1000f

        // Exact 0..1 bounding box at outer limit
        val fullBox = NormalizedRect(left = 0.0f, top = 0.0f, right = 1.0f, bottom = 1.0f)

        val rectNormal = BoundingBoxOverlayView.transformRect(fullBox, viewW, viewH, isMirrored = false)
        assertEquals(0f, rectNormal.left, 0.001f)
        assertEquals(0f, rectNormal.top, 0.001f)
        assertEquals(1000f, rectNormal.right, 0.001f)
        assertEquals(1000f, rectNormal.bottom, 0.001f)

        val rectMirrored = BoundingBoxOverlayView.transformRect(fullBox, viewW, viewH, isMirrored = true)
        assertEquals(0f, rectMirrored.left, 0.001f)
        assertEquals(0f, rectMirrored.top, 0.001f)
        assertEquals(1000f, rectMirrored.right, 0.001f)
        assertEquals(1000f, rectMirrored.bottom, 0.001f)
    }

    @Test
    fun testMirroringSymmetryInvariant() {
        val viewW = 1280f
        val viewH = 720f
        val rng = Random(42)

        // Fuzz 1,000 valid random boxes and verify:
        // 1. Width and height are exactly preserved under mirroring
        // 2. Distance from left edge in normal equals distance from right edge in mirrored:
        //    rectNormal.left == viewW - rectMirrored.right
        //    rectNormal.right == viewW - rectMirrored.left
        for (i in 0 until 1000) {
            val x1 = rng.nextFloat()
            val x2 = rng.nextFloat()
            val y1 = rng.nextFloat()
            val y2 = rng.nextFloat()

            val box = NormalizedRect(
                left = minOf(x1, x2),
                top = minOf(y1, y2),
                right = maxOf(x1, x2),
                bottom = maxOf(y1, y2)
            )

            val normal = BoundingBoxOverlayView.transformRect(box, viewW, viewH, isMirrored = false)
            val mirrored = BoundingBoxOverlayView.transformRect(box, viewW, viewH, isMirrored = true)

            assertEquals("Width must be preserved at iter $i", normal.width(), mirrored.width(), 0.01f)
            assertEquals("Height must be preserved at iter $i", normal.height(), mirrored.height(), 0.01f)
            assertEquals("Horizontal reflection symmetry left at iter $i", normal.left, viewW - mirrored.right, 0.01f)
            assertEquals("Horizontal reflection symmetry right at iter $i", normal.right, viewW - mirrored.left, 0.01f)
        }
    }

    @Test
    fun testZeroDimensionViewHandling() {
        val box = NormalizedRect(0.2f, 0.2f, 0.8f, 0.8f)
        // Zero dimensions should produce 0f coordinates without throwing
        val rect = BoundingBoxOverlayView.transformRect(box, 0f, 0f, false)
        assertEquals(0f, rect.left, 0.001f)
        assertEquals(0f, rect.right, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(0f, rect.bottom, 0.001f)
    }

    @Test
    fun testMotionScoreFormattingBounds() {
        // Boundary values
        assertEquals("MOTION 0%", BoundingBoxOverlayView.formatMotionLabel(0.000001f))
        assertEquals("MOTION 99%", BoundingBoxOverlayView.formatMotionLabel(0.9999f))
        assertEquals("MOTION 100%", BoundingBoxOverlayView.formatMotionLabel(1.0f))
        assertEquals("MOTION 100%", BoundingBoxOverlayView.formatMotionLabel(999.0f))
        assertEquals("MOTION 0%", BoundingBoxOverlayView.formatMotionLabel(-999.0f))
    }

    // =========================================================================
    // 2. RECIPIENT VALIDATOR ADVERSARIAL & BOUNDARY TESTS
    // =========================================================================

    @Test
    fun testE164LengthBoundaries() {
        // ITU-T E.164: + followed by 1 to 3 digit country code and subscriber number (total 7 to 15 digits)

        // 1-6 digits: MUST FAIL
        assertFalse("1 digit should fail", RecipientValidator.isValidE164("+1"))
        assertFalse("2 digits should fail", RecipientValidator.isValidE164("+12"))
        assertFalse("3 digits should fail", RecipientValidator.isValidE164("+123"))
        assertFalse("4 digits should fail", RecipientValidator.isValidE164("+1234"))
        assertFalse("5 digits should fail", RecipientValidator.isValidE164("+12345"))
        assertFalse("6 digits should fail", RecipientValidator.isValidE164("+123456"))

        // Exactly 7 digits (lower bound): MUST PASS
        assertTrue("7 digits (+1234567) must pass lower bound", RecipientValidator.isValidE164("+1234567"))

        // 8 to 14 digits: MUST PASS
        assertTrue("8 digits must pass", RecipientValidator.isValidE164("+12345678"))
        assertTrue("10 digits must pass", RecipientValidator.isValidE164("+1234567890"))
        assertTrue("12 digits must pass", RecipientValidator.isValidE164("+123456789012"))
        assertTrue("14 digits must pass", RecipientValidator.isValidE164("+12345678901234"))

        // Exactly 15 digits (upper bound): MUST PASS
        assertTrue("15 digits (+123456789012345) must pass upper bound", RecipientValidator.isValidE164("+123456789012345"))

        // 16+ digits: MUST FAIL
        assertFalse("16 digits should fail", RecipientValidator.isValidE164("+1234567890123456"))
        assertFalse("17 digits should fail", RecipientValidator.isValidE164("+12345678901234567"))
        assertFalse("20 digits should fail", RecipientValidator.isValidE164("+12345678901234567890"))
    }

    @Test
    fun testPhoneInjectionAndInvalidCharacters() {
        val dangerousInputs = listOf(
            "+12025550123\n+12025550124",
            "+12025550123\u0000",
            "+12025550123<script>alert(1)</script>",
            "+12025550123; DROP TABLE motion_events;--",
            "+12025550123' OR '1'='1",
            "++12025550123",
            "+ 12025550123",
            "+1202555012a",
            "+01202555012",  // Country code starting with 0
            "+",
            null,
            "",
            "        "
        )
        for (input in dangerousInputs) {
            assertFalse("Expected '$input' to be rejected by isValidE164", RecipientValidator.isValidE164(input))
        }
    }

    @Test
    fun testNormalizePhoneNumberEdgeCases() {
        assertEquals("", RecipientValidator.normalizePhoneNumber(null))
        assertEquals("", RecipientValidator.normalizePhoneNumber(""))
        assertEquals("", RecipientValidator.normalizePhoneNumber("     "))
        assertEquals("+12025550123", RecipientValidator.normalizePhoneNumber("0012025550123"))
        assertEquals("+12025550123", RecipientValidator.normalizePhoneNumber("  00 1 (202) 555-0123  "))
        assertEquals("+447911123456", RecipientValidator.normalizePhoneNumber("+44.7911.123.456"))
    }

    @Test
    fun testEmailValidationAdversarialCases() {
        val validEmails = listOf(
            "a@b.co",                        // Minimum 2-char TLD
            "user.name+tag@sub.domain.org",
            "test_123-abc@domain-name.com",
            "admin@x.io",
            "contact@company.solutions",      // Long TLD (9 chars)
            "security@longtld.international"  // 13-char TLD
        )
        for (email in validEmails) {
            assertTrue("Expected valid email: '$email'", RecipientValidator.isValidEmail(email))
        }

        val invalidEmails = listOf(
            "user@domain.c",                 // 1-char TLD (must be >= 2)
            "user@domain.toolongtopossiblyexist", // 20-char TLD (> 15 chars)
            "user..name@example.com",        // Consecutive dots in local part
            ".user@example.com",             // Leading dot
            "user.@example.com",             // Trailing dot
            "user@domain..com",              // Consecutive dots in domain
            "user@.domain.com",              // Leading dot in domain
            "user@domain_with_underscore.com", // Underscore in domain name
            "user@domain",                   // Missing TLD
            "user@127.0.0.1",                // IP address without square brackets/TLD
            "user name@example.com",         // Space in local part
            "user@exam ple.com",             // Space in domain
            "plainaddress",
            "@missinguser.com",
            "missingdomain@",
            "user@-domain.com",              // Leading hyphen in domain label
            "user@domain-.com",              // Trailing hyphen in domain label
            null,
            "",
            "   "
        )
        for (email in invalidEmails) {
            assertFalse("Expected invalid email: '$email'", RecipientValidator.isValidEmail(email))
        }
    }

    @Test
    fun testBatchParserUnderFuzzAndVolume() {
        // Generate 500 mixed entries
        val mixedTokens = mutableListOf<String>()
        for (i in 1..250) {
            mixedTokens.add("+1202555${String.format("%04d", i)}")
            mixedTokens.add("bad_phone_$i")
            mixedTokens.add("+000$i")
        }
        val rawInput = mixedTokens.joinToString(separator = ", ")

        val parsed = RecipientValidator.parseAndFilterPhones(rawInput)
        assertEquals(250, parsed.size)
        for (phone in parsed) {
            assertTrue(RecipientValidator.isValidE164(phone))
        }
    }

    // =========================================================================
    // 3. SURVEILLANCE SERVICE LIFECYCLE & STATE TRANSITION STRESS TESTS
    // =========================================================================

    @Test
    fun testRapidStartStopLifecycleTransitions() {
        val controller = Robolectric.buildService(SurveillanceService::class.java)
        controller.create().get()

        val startIntent = Intent(context, SurveillanceService::class.java).apply {
            action = SurveillanceService.ACTION_START
        }
        val stopIntent = Intent(context, SurveillanceService::class.java).apply {
            action = SurveillanceService.ACTION_STOP
        }

        // Rapid start -> stop cycling 10 times in sequence
        for (cycle in 1..10) {
            controller.withIntent(startIntent).startCommand(0, cycle * 2 - 1)
            assertTrue("Service must be running after START at cycle $cycle", SurveillanceService.liveState.value.isRunning)

            controller.withIntent(stopIntent).startCommand(0, cycle * 2)
            assertFalse("Service must be stopped after STOP at cycle $cycle", SurveillanceService.liveState.value.isRunning)
        }

        controller.destroy()
    }

    @Test
    fun testRapidArmDisarmToggleTransitions() {
        val controller = Robolectric.buildService(SurveillanceService::class.java)
        controller.create().get()

        val armIntent = Intent(context, SurveillanceService::class.java).apply {
            action = SurveillanceService.ACTION_ARM
        }
        val disarmIntent = Intent(context, SurveillanceService::class.java).apply {
            action = SurveillanceService.ACTION_DISARM
        }
        val toggleIntent = Intent(context, SurveillanceService::class.java).apply {
            action = SurveillanceService.ACTION_TOGGLE_ARM
        }

        // Start service
        val startIntent = Intent(context, SurveillanceService::class.java).apply {
            action = SurveillanceService.ACTION_START
        }
        controller.withIntent(startIntent).startCommand(0, 1)

        // 50 rapid toggle commands in succession
        var expectedArmed = SurveillanceService.liveState.value.isArmed
        for (i in 1..50) {
            controller.withIntent(toggleIntent).startCommand(0, i + 10)
            expectedArmed = !expectedArmed
            assertEquals("Armed state must strictly invert at toggle $i", expectedArmed, SurveillanceService.liveState.value.isArmed)
        }

        // Idempotent ARM calls
        for (i in 1..10) {
            controller.withIntent(armIntent).startCommand(0, i + 100)
            assertTrue("Multiple ACTION_ARM calls must remain armed", SurveillanceService.liveState.value.isArmed)
        }

        // Idempotent DISARM calls
        for (i in 1..10) {
            controller.withIntent(disarmIntent).startCommand(0, i + 200)
            assertFalse("Multiple ACTION_DISARM calls must remain disarmed", SurveillanceService.liveState.value.isArmed)
        }

        controller.destroy()
    }

    @Test
    fun testRapidCameraSwitching() {
        val controller = Robolectric.buildService(SurveillanceService::class.java)
        controller.create().get()

        val startIntent = Intent(context, SurveillanceService::class.java).apply {
            action = SurveillanceService.ACTION_START
        }
        controller.withIntent(startIntent).startCommand(0, 1)

        val switchIntent = Intent(context, SurveillanceService::class.java).apply {
            action = SurveillanceService.ACTION_SWITCH_CAMERA
        }

        var currentFacing = SurveillanceService.liveState.value.cameraFacing
        for (i in 1..20) {
            controller.withIntent(switchIntent).startCommand(0, i + 1)
            val expectedFacing = if (currentFacing == "FRONT") "BACK" else "FRONT"
            assertEquals("Camera facing must alternate at switch $i", expectedFacing, SurveillanceService.liveState.value.cameraFacing)
            currentFacing = expectedFacing
        }

        controller.destroy()
    }

    @Test
    fun testNullAndUnrecognizedIntentHandling() {
        val controller = Robolectric.buildService(SurveillanceService::class.java)
        controller.create().get()

        // 1. Null intent (simulating Android OS process restart)
        try {
            controller.withIntent(null).startCommand(0, 1)
            assertTrue("Service must default to startSurveillance on null intent", SurveillanceService.liveState.value.isRunning)
        } catch (e: Exception) {
            fail("Service should handle null intent gracefully, but threw: ${e.message}")
        }

        // 2. Unrecognized action string
        try {
            val rogueIntent = Intent(context, SurveillanceService::class.java).apply {
                action = "com.securitycam.service.UNKNOWN_ACTION_XYZ"
            }
            controller.withIntent(rogueIntent).startCommand(0, 2)
            // Should not crash, returns START_STICKY
        } catch (e: Exception) {
            fail("Service should safely ignore unknown intent actions, but threw: ${e.message}")
        }

        controller.destroy()
    }
}
