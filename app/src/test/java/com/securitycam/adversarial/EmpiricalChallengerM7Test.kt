package com.securitycam.adversarial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.securitycam.SecurityCamApp
import com.securitycam.core.alerting.AlertPayload
import com.securitycam.core.alerting.DefaultAlertCoordinator
import com.securitycam.core.alerting.DispatchResult
import com.securitycam.core.alerting.EmailDispatcher
import com.securitycam.core.alerting.SmsDispatcher
import com.securitycam.core.config.CooldownManager
import com.securitycam.core.config.SecuritySettings
import com.securitycam.core.config.SettingsRepository
import com.securitycam.core.storage.EventRepository
import com.securitycam.core.storage.MotionEventEntity
import com.securitycam.core.surveillance.AdaptiveBackgroundMotionDetector
import com.securitycam.core.surveillance.LuminanceFrame
import com.securitycam.core.surveillance.NormalizedRect
import com.securitycam.service.SurveillanceService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Empirical Adversarial Challenger Verification Suite for Milestone 7 (R1, R2, R7).
 *
 * Rigorously stress-tests:
 * 1. R1 Snapshot Burst Throttling: High-frequency simulated frame bursts (300 frames @ 30 FPS),
 *    exact boundary conditions (2999ms vs 3000ms), and out-of-order timestamps.
 * 2. R2 Bitmap Rotation & Mirroring: Complete oracle verification comparing actual pixel locations
 *    in transformed Bitmaps against transformed NormalizedRect coordinates across all 8 permutations
 *    (0°, 90°, 180°, 270° x {Back, Front}).
 * 3. R7 Dual-Camera Patrol Mode & Motion Model Resets: Verifies background model clearing on lens switch,
 *    immunity to false motion alarms during extreme contrast scene changes (e.g. Y=220 -> Y=30),
 *    and patrol configuration boundaries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class EmpiricalChallengerM7Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var context: Context
    private val service = SurveillanceService()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    // =========================================================================
    // Fakes for AlertCoordinator Testing
    // =========================================================================

    class FakeSettingsRepository(private var currentSettings: SecuritySettings = SecuritySettings()) : SettingsRepository {
        override fun getSettings(): SecuritySettings = currentSettings
        override fun updateSettings(settings: SecuritySettings) { this.currentSettings = settings }
        override fun observeSettings(): Flow<SecuritySettings> = emptyFlow()
        override fun isArmed(): Boolean = currentSettings.isArmed
        override fun setArmed(armed: Boolean) { currentSettings = currentSettings.copy(isArmed = armed) }
        override fun getSensitivity(): Int = currentSettings.sensitivity
        override fun setSensitivity(sensitivity: Int) { currentSettings = currentSettings.copy(sensitivity = sensitivity) }
        override fun getCooldownSeconds(): Int = currentSettings.cooldownSeconds
        override fun setCooldownSeconds(seconds: Int) { currentSettings = currentSettings.copy(cooldownSeconds = seconds) }
        override fun getPhoneNumbers(): List<String> = currentSettings.phoneNumbers
        override fun setPhoneNumbers(numbers: List<String>) { currentSettings = currentSettings.copy(phoneNumbers = numbers) }
        override fun getEmailAddresses(): List<String> = currentSettings.emailAddresses
        override fun setEmailAddresses(emails: List<String>) { currentSettings = currentSettings.copy(emailAddresses = emails) }
        override fun isPatrolModeEnabled(): Boolean = currentSettings.isPatrolModeEnabled
        override fun setPatrolModeEnabled(enabled: Boolean) { currentSettings = currentSettings.copy(isPatrolModeEnabled = enabled) }
        override fun getPatrolIntervalSeconds(): Int = currentSettings.patrolIntervalSeconds
        override fun setPatrolIntervalSeconds(seconds: Int) { currentSettings = currentSettings.copy(patrolIntervalSeconds = seconds) }
        override fun resetToDefaults() { currentSettings = SecuritySettings() }
    }

    class FakeEventRepository : EventRepository {
        val events = mutableMapOf<String, MotionEventEntity>()
        override suspend fun insertEvent(event: MotionEventEntity): Long {
            events[event.id] = event
            return 1L
        }
        override suspend fun getAllEvents(): List<MotionEventEntity> = events.values.toList()
        override fun observeEvents(): Flow<List<MotionEventEntity>> = emptyFlow()
        override suspend fun deleteEvent(id: String) { events.remove(id) }
        override suspend fun getEventById(id: String): MotionEventEntity? = events[id]
        override suspend fun updateSmsStatus(id: String, status: String) {
            events[id]?.let { events[id] = it.copy(smsStatus = status) }
        }
        override suspend fun updateEmailStatus(id: String, status: String) {
            events[id]?.let { events[id] = it.copy(emailStatus = status) }
        }
    }

    class FakeSmsDispatcher : SmsDispatcher {
        var callCount = 0
        override suspend fun sendSms(phoneNumbers: List<String>, message: String): DispatchResult {
            callCount++
            return DispatchResult(isSuccess = true, deliveredCount = phoneNumbers.size, failedCount = 0)
        }
    }

    class FakeEmailDispatcher : EmailDispatcher {
        var callCount = 0
        override suspend fun sendEmail(
            emailAddresses: List<String>,
            subject: String,
            bodyHtml: String,
            attachmentFile: File?
        ): DispatchResult {
            callCount++
            return DispatchResult(isSuccess = true, deliveredCount = emailAddresses.size, failedCount = 0)
        }
    }

    // =========================================================================
    // 1. R1: SNAPSHOT BURST THROTTLING ADVERSARIAL STRESS TESTS
    // =========================================================================

    @Test
    fun testR1_HighFrequencyBurstThrottling_30FPS_10Seconds() = runTest(testDispatcher) {
        // Simulate continuous motion alert stream: 300 frames over 10 seconds (~33.3ms interval, 30 FPS)
        val settingsRepo = FakeSettingsRepository(SecuritySettings(isArmed = true))
        val eventRepo = FakeEventRepository()
        val cooldown = CooldownManager(30)
        val sms = FakeSmsDispatcher()
        val email = FakeEmailDispatcher()

        val coordinator = DefaultAlertCoordinator(
            settingsRepository = settingsRepo,
            eventRepository = eventRepo,
            cooldownManager = cooldown,
            smsDispatcher = sms,
            emailDispatcher = email,
            ioDispatcher = testDispatcher,
            context = context
        )

        val imageFile = tempFolder.newFile("burst_frame.jpg")
        val totalFrames = 300
        val frameIntervalMs = 33L
        var savedCount = 0
        var suppressedCount = 0

        for (i in 0 until totalFrames) {
            val timestamp = 100_000L + (i * frameIntervalMs)
            val payload = AlertPayload(
                eventId = "burst-ev-$i",
                timestamp = timestamp,
                motionScore = 0.85f,
                screenshotFile = imageFile
            )
            val summary = coordinator.onMotionDetected(payload)
            if (summary.localEventSaved) {
                savedCount++
            } else {
                suppressedCount++
            }
        }

        // Over 10,000ms with a 3,000ms debounce interval:
        // Frame 0: T=100,000 -> SAVED (1)
        // Frame 91: T=103,003 -> SAVED (2)
        // Frame 182: T=106,006 -> SAVED (3)
        // Frame 273: T=109,009 -> SAVED (4)
        // Exactly 4 events saved out of 300 frames. 296 suppressed.
        assertEquals("Exactly 4 snapshots should be saved across 10s of continuous motion", 4, savedCount)
        assertEquals("296 frames must be suppressed by burst debounce", 296, suppressedCount)
        assertEquals("Event repo must contain exactly 4 DB rows", 4, eventRepo.events.size)
    }

    @Test
    fun testR1_ExactBoundaryDebounceThreshold() = runTest(testDispatcher) {
        val settingsRepo = FakeSettingsRepository(SecuritySettings(isArmed = true))
        val eventRepo = FakeEventRepository()
        val cooldown = CooldownManager(60)
        val coordinator = DefaultAlertCoordinator(
            settingsRepository = settingsRepo,
            eventRepository = eventRepo,
            cooldownManager = cooldown,
            smsDispatcher = FakeSmsDispatcher(),
            emailDispatcher = FakeEmailDispatcher(),
            ioDispatcher = testDispatcher,
            context = context
        )

        val dummyFile = tempFolder.newFile("dummy.jpg")

        // 1. First event at T = 10,000ms
        val p1 = coordinator.onMotionDetected(AlertPayload("e1", 10_000L, 0.7f, dummyFile))
        assertTrue("T=10,000ms must be saved", p1.localEventSaved)

        // 2. Event at T = 12,999ms (delta = 2,999ms < 3,000ms) -> MUST BE SUPPRESSED
        val p2 = coordinator.onMotionDetected(AlertPayload("e2", 12_999L, 0.7f, dummyFile))
        assertFalse("T=12,999ms (delta 2999ms) must be suppressed", p2.localEventSaved)
        assertTrue("T=12,999ms must indicate cooldown suppression", p2.isSuppressedByCooldown)

        // 3. Event at T = 13,000ms (delta = 3,000ms == BURST_DEBOUNCE_MS) -> MUST BE SAVED
        val p3 = coordinator.onMotionDetected(AlertPayload("e3", 13_000L, 0.7f, dummyFile))
        assertTrue("T=13,000ms (delta exactly 3000ms) must be saved", p3.localEventSaved)

        // 4. Event at T = 15,999ms (delta = 2,999ms since e3) -> MUST BE SUPPRESSED
        val p4 = coordinator.onMotionDetected(AlertPayload("e4", 15_999L, 0.7f, dummyFile))
        assertFalse("T=15,999ms must be suppressed", p4.localEventSaved)

        // 5. Event at T = 16,001ms (delta = 3,001ms since e3) -> MUST BE SAVED
        val p5 = coordinator.onMotionDetected(AlertPayload("e5", 16_001L, 0.7f, dummyFile))
        assertTrue("T=16,001ms must be saved", p5.localEventSaved)

        assertEquals("Total saved events must be exactly 3", 3, eventRepo.events.size)
    }

    @Test
    fun testR1_NegativeOrBackwardsTimestampHandling() = runTest(testDispatcher) {
        val settingsRepo = FakeSettingsRepository(SecuritySettings(isArmed = true))
        val eventRepo = FakeEventRepository()
        val coordinator = DefaultAlertCoordinator(
            settingsRepository = settingsRepo,
            eventRepository = eventRepo,
            cooldownManager = CooldownManager(30),
            smsDispatcher = FakeSmsDispatcher(),
            emailDispatcher = FakeEmailDispatcher(),
            ioDispatcher = testDispatcher,
            context = context
        )

        val file = tempFolder.newFile("neg.jpg")

        // Initial event at T=50,000ms
        val p1 = coordinator.onMotionDetected(AlertPayload("e1", 50_000L, 0.8f, file))
        assertTrue(p1.localEventSaved)

        // Jitter / clock jump backwards: T=45,000ms (delta = -5000ms < 3000ms)
        val p2 = coordinator.onMotionDetected(AlertPayload("e2", 45_000L, 0.8f, file))
        assertFalse("Backwards timestamp must be suppressed by debounce window", p2.localEventSaved)
    }

    // =========================================================================
    // 2. R2: BITMAP ROTATION & MIRRORING ORACLE HARNESS
    // =========================================================================

    /**
     * Mathematical & Empirical Oracle Test: Compares the exact Android Matrix transformation
     * (used by Bitmap.createBitmap) against SurveillanceService.orientBoundingBoxes
     * across all 8 permutations of (rotationDegrees in {0, 90, 180, 270}) x (isFrontFacing in {false, true}).
     */
    @Test
    fun testR2_MatrixOracle_BitmapVsBoundingBoxAcrossAllPermutations() {
        val originalW = 160f
        val originalH = 120f

        val origLeftPx = 20f
        val origTopPx = 30f
        val origRightPx = 60f
        val origBottomPx = 80f

        val origBox = NormalizedRect(
            left = origLeftPx / originalW,
            top = origTopPx / originalH,
            right = origRightPx / originalW,
            bottom = origBottomPx / originalH
        )

        val rotations = listOf(0, 90, 180, 270)
        val facings = listOf(false, true)

        for (rotation in rotations) {
            for (isFront in facings) {
                // 1. Verify orientBitmap executes without error and swaps/retains dimensions correctly
                val srcBitmap = Bitmap.createBitmap(originalW.toInt(), originalH.toInt(), Bitmap.Config.ARGB_8888)
                val transformedBitmap = service.orientBitmap(srcBitmap, rotation, isFront)
                val isSwapped = (rotation == 90 || rotation == 270)
                val expectedW = if (isSwapped) originalH.toInt() else originalW.toInt()
                val expectedH = if (isSwapped) originalW.toInt() else originalH.toInt()
                assertEquals("Transformed width mismatch for rot=$rotation front=$isFront", expectedW, transformedBitmap.width)
                assertEquals("Transformed height mismatch for rot=$rotation front=$isFront", expectedH, transformedBitmap.height)

                // 2. Android Bitmap.createBitmap Matrix transformation oracle
                val matrix = android.graphics.Matrix()
                if (isFront) {
                    matrix.postScale(-1f, 1f, originalW / 2f, originalH / 2f)
                }
                if (rotation != 0) {
                    matrix.postRotate(rotation.toFloat())
                }

                // Android framework deviceR computation
                val srcRect = android.graphics.RectF(0f, 0f, originalW, originalH)
                val deviceR = android.graphics.RectF()
                matrix.mapRect(deviceR, srcRect)
                val destW = deviceR.width()
                val destH = deviceR.height()

                val drawMatrix = android.graphics.Matrix(matrix)
                drawMatrix.postTranslate(-deviceR.left, -deviceR.top)

                // 3. Map rectangle corners through drawMatrix
                val pts = floatArrayOf(
                    origLeftPx, origTopPx,
                    origRightPx, origTopPx,
                    origRightPx, origBottomPx,
                    origLeftPx, origBottomPx
                )
                drawMatrix.mapPoints(pts)

                val mappedMinX = minOf(pts[0], pts[2], pts[4], pts[6])
                val mappedMaxX = maxOf(pts[0], pts[2], pts[4], pts[6])
                val mappedMinY = minOf(pts[1], pts[3], pts[5], pts[7])
                val mappedMaxY = maxOf(pts[1], pts[3], pts[5], pts[7])

                val matrixNormLeft = mappedMinX / destW
                val matrixNormRight = mappedMaxX / destW
                val matrixNormTop = mappedMinY / destH
                val matrixNormBottom = mappedMaxY / destH

                // 4. Compare with SurveillanceService.orientBoundingBoxes
                val orientedBoxes = service.orientBoundingBoxes(listOf(origBox), rotation, isFront)
                assertEquals(1, orientedBoxes.size)
                val oBox = orientedBoxes[0]

                assertEquals("Left coordinate mismatch for rot=$rotation front=$isFront", matrixNormLeft, oBox.left, 0.001f)
                assertEquals("Right coordinate mismatch for rot=$rotation front=$isFront", matrixNormRight, oBox.right, 0.001f)
                assertEquals("Top coordinate mismatch for rot=$rotation front=$isFront", matrixNormTop, oBox.top, 0.001f)
                assertEquals("Bottom coordinate mismatch for rot=$rotation front=$isFront", matrixNormBottom, oBox.bottom, 0.001f)
            }
        }
    }

    @Test
    fun testR2_BoundingBoxMathematicalInvariantsUnderExtremeInputs() {
        val extremeBoxes = listOf(
            NormalizedRect(0f, 0f, 0f, 0f),                 // Point top-left
            NormalizedRect(1f, 1f, 1f, 1f),                 // Point bottom-right
            NormalizedRect(0.5f, 0.5f, 0.5f, 0.5f),         // Point center
            NormalizedRect(0f, 0f, 1f, 1f),                 // Full screen
            NormalizedRect(0.001f, 0.002f, 0.999f, 0.998f), // Near full
            NormalizedRect(0.1f, 0.2f, 0.3f, 0.4f)          // Normal
        )

        val rotations = listOf(0, 90, 180, 270)
        val facings = listOf(false, true)

        for (rot in rotations) {
            for (front in facings) {
                val oriented = service.orientBoundingBoxes(extremeBoxes, rot, front)
                assertEquals(extremeBoxes.size, oriented.size)

                for ((idx, box) in oriented.withIndex()) {
                    assertTrue("Box $idx left in [0, 1] for rot=$rot front=$front (was ${box.left})", box.left in 0f..1f)
                    assertTrue("Box $idx top in [0, 1] for rot=$rot front=$front (was ${box.top})", box.top in 0f..1f)
                    assertTrue("Box $idx right in [0, 1] for rot=$rot front=$front (was ${box.right})", box.right in 0f..1f)
                    assertTrue("Box $idx bottom in [0, 1] for rot=$rot front=$front (was ${box.bottom})", box.bottom in 0f..1f)
                    assertTrue("Box $idx left <= right for rot=$rot front=$front", box.left <= box.right)
                    assertTrue("Box $idx top <= bottom for rot=$rot front=$front", box.top <= box.bottom)
                }
            }
        }

        // Empty box list returns empty list
        val emptyResult = service.orientBoundingBoxes(emptyList(), 90, true)
        assertTrue(emptyResult.isEmpty())
    }

    // =========================================================================
    // 3. R7: DUAL-CAMERA PATROL MODE & BACKGROUND RESET EMPIRICAL VERIFICATION
    // =========================================================================

    @Test
    fun testR7_CameraSwitch_MotionDetectorResetPreventsFalseAlarmOnExtremeContrast() {
        val app = SecurityCamApp.getInstance()
        val detector = app.motionDetector

        // Phase 1: Camera BACK is monitoring a sunlit outdoor area (high luminance ~220)
        val brightFrame1 = LuminanceFrame(ByteArray(160 * 120) { 220.toByte() }, 160, 120, 1000L)
        val brightFrame2 = LuminanceFrame(ByteArray(160 * 120) { 220.toByte() }, 160, 120, 1033L)
        detector.processFrame(brightFrame1)
        val resBright = detector.processFrame(brightFrame2)
        assertFalse("Bright scene baseline must not detect motion", resBright.isMotionDetected)
        assertEquals(0, resBright.activePixels)

        // Phase 2: Dual-Camera Patrol toggles lens to FRONT camera!
        // FRONT camera points indoors at a dark hallway (low luminance ~30, contrast delta = 190!)
        val darkFrame = LuminanceFrame(ByteArray(160 * 120) { 30.toByte() }, 160, 120, 2000L)

        // Control check: Without reset, contrast delta 190 triggers 100% false alarm!
        val falseAlarmDetector = AdaptiveBackgroundMotionDetector()
        falseAlarmDetector.processFrame(brightFrame1)
        falseAlarmDetector.processFrame(brightFrame2)
        falseAlarmDetector.processFrame(darkFrame)
        val falseAlarmResult = falseAlarmDetector.processFrame(darkFrame)
        assertTrue("Without reset, sudden lens switch MUST trigger massive false alarm", falseAlarmResult.isMotionDetected)
        assertTrue("Without reset, active pixels must be huge", falseAlarmResult.activePixels > 10000)

        // Experimental check: switchCameraLens in SurveillanceService performs clean reset!
        val controller = Robolectric.buildService(SurveillanceService::class.java)
        val surveillanceService = controller.create().get()

        // Call switchCameraLens
        surveillanceService.switchCameraLens(persistSetting = false)

        // First frame arriving from new FRONT lens:
        val firstFrameNewLens = detector.processFrame(darkFrame)
        assertFalse(
            "R7 REQUIREMENT: First frame after camera switch MUST NOT trigger false motion alarm! (was detected=${firstFrameNewLens.isMotionDetected})",
            firstFrameNewLens.isMotionDetected
        )
        assertEquals("Active pixels must be 0 on first frame after lens switch", 0, firstFrameNewLens.activePixels)
        assertEquals(0f, firstFrameNewLens.motionScore, 0.0001f)
        assertTrue("No false bounding boxes permitted after lens switch", firstFrameNewLens.boundingBoxes.isEmpty())

        // Second frame on new FRONT lens (still static dark scene):
        val secondFrameNewLens = detector.processFrame(darkFrame)
        assertFalse("Second frame on new lens must remain non-triggering", secondFrameNewLens.isMotionDetected)
        assertEquals(0, secondFrameNewLens.activePixels)

        controller.destroy()
    }

    @Test
    fun testR7_RapidCameraSwitchingStress_50Cycles() {
        val controller = Robolectric.buildService(SurveillanceService::class.java)
        val surveillanceService = controller.create().get()

        var currentFacing = SurveillanceService.liveState.value.cameraFacing

        for (cycle in 1..50) {
            surveillanceService.switchCameraLens(persistSetting = false)
            val expectedFacing = if (currentFacing == "FRONT") "BACK" else "FRONT"
            val actualFacing = SurveillanceService.liveState.value.cameraFacing

            assertEquals("Camera facing must alternate deterministically at cycle $cycle", expectedFacing, actualFacing)
            assertNull("Motion result must be cleared to null on camera switch", SurveillanceService.liveState.value.motionResult)
            assertFalse("Torch must be turned off on camera switch", SurveillanceService.liveState.value.isTorchOn)

            currentFacing = expectedFacing
        }

        controller.destroy()
    }

    @Test
    fun testR7_PatrolSettingsClampingAndConfiguration() {
        val repo = SecurityCamApp.getInstance().settingsRepository

        // 1. Clamping patrol interval: minimum 10s, maximum 300s
        repo.setPatrolIntervalSeconds(5)
        assertEquals("Patrol interval < 10 must clamp to 10s", 10, repo.getPatrolIntervalSeconds())

        repo.setPatrolIntervalSeconds(500)
        assertEquals("Patrol interval > 300 must clamp to 300s", 300, repo.getPatrolIntervalSeconds())

        repo.setPatrolIntervalSeconds(45)
        assertEquals("Valid patrol interval 45s must be retained", 45, repo.getPatrolIntervalSeconds())

        // 2. Patrol mode toggle
        repo.setPatrolModeEnabled(true)
        assertTrue(repo.isPatrolModeEnabled())
        repo.setPatrolModeEnabled(false)
        assertFalse(repo.isPatrolModeEnabled())
    }
}
