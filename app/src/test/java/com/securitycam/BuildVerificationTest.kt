package com.securitycam

import android.app.NotificationManager
import android.content.Context
import android.os.Build
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
 * Milestone 1 Build and Architecture Verification Suite.
 * Validates:
 * 1. Headless JUnit 4 and Robolectric test harness execution.
 * 2. Application context, package name, minSdk (29), and targetSdk (34).
 * 3. Dual notification channels initialization (surveillance and alerts).
 * 4. FileProvider authority configuration.
 * 5. Core domain model instantiation, constraint enforcement, and JSON serialization.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BuildVerificationTest {

    private lateinit var context: Context
    private val gson = Gson()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    @Test
    fun testRobolectricAndJUnitHarnessRuns() {
        assertNotNull("Robolectric must provide a non-null Application context", context)
        assertNotNull("Robolectric must provide access to PackageManager", context.packageManager)
        assertTrue(
            "Application context must be an instance of SecurityCamApp",
            context is SecurityCamApp
        )
    }

    @Test
    fun testPackageNameMatchesSpecification() {
        val expectedPackage = "com.securitycam"
        val actualPackage = context.packageName
        assertEquals(
            "Application package name must strictly match specification",
            expectedPackage,
            actualPackage
        )
    }

    @Test
    fun testSdkVersionsMatchSpecification() {
        val appInfo = context.applicationInfo
        assertEquals(
            "Target SDK version must be 34 (Android 14)",
            34,
            appInfo.targetSdkVersion
        )
        assertEquals(
            "Minimum SDK version must be 29 (Android 10)",
            29,
            appInfo.minSdkVersion
        )
    }

    @Test
    fun testNotificationChannelsConfigured() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull("NotificationManager must be available", notificationManager)

        val surveillanceChannel = notificationManager.getNotificationChannel(SecurityCamApp.CHANNEL_SURVEILLANCE_ID)
        assertNotNull("Surveillance notification channel must be created", surveillanceChannel)
        assertEquals(
            "Surveillance channel must have LOW importance for non-intrusive ongoing monitoring",
            NotificationManager.IMPORTANCE_LOW,
            surveillanceChannel.importance
        )

        val alertChannel = notificationManager.getNotificationChannel(SecurityCamApp.CHANNEL_ALERTS_ID)
        assertNotNull("Alert notification channel must be created", alertChannel)
        assertEquals(
            "Alert channel must have HIGH importance for urgent intrusion alerts",
            NotificationManager.IMPORTANCE_HIGH,
            alertChannel.importance
        )
    }

    @Test
    fun testFileProviderAuthorityConfigured() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PROVIDERS
        )
        val expectedAuthority = "${context.packageName}.fileprovider"
        val fileProvider = packageInfo.providers?.find { it.authority == expectedAuthority }

        assertNotNull(
            "FileProvider with authority '$expectedAuthority' must be registered in the manifest",
            fileProvider
        )
        assertEquals(
            "FileProvider must use androidx.core.content.FileProvider",
            "androidx.core.content.FileProvider",
            fileProvider?.name
        )
        assertFalse("FileProvider must not be exported", fileProvider!!.exported)
        assertTrue("FileProvider must grant URI permissions", fileProvider.grantUriPermissions)
    }

    @Test
    fun testVisionDomainModelsInstantiationAndSerialization() {
        // 1. LuminanceFrame
        val testBytes = ByteArray(160 * 120) { (it % 256).toByte() }
        val frame = LuminanceFrame(
            data = testBytes,
            width = 160,
            height = 120,
            timestampMs = 1700000000000L
        )
        assertEquals(160, frame.width)
        assertEquals(120, frame.height)
        assertEquals(1700000000000L, frame.timestampMs)
        assertEquals(19200, frame.data.size)

        // Verify array equality via overridden equals
        val frameClone = LuminanceFrame(
            data = testBytes.copyOf(),
            width = 160,
            height = 120,
            timestampMs = 1700000000000L
        )
        assertEquals("LuminanceFrame equals must compare byte array content", frame, frameClone)

        // 2. NormalizedRect & constraints
        val validRect = NormalizedRect(
            left = 0.1f,
            top = 0.2f,
            right = 0.6f,
            bottom = 0.8f
        )
        assertEquals(0.1f, validRect.left, 0.0001f)
        assertEquals(0.2f, validRect.top, 0.0001f)
        assertEquals(0.6f, validRect.right, 0.0001f)
        assertEquals(0.8f, validRect.bottom, 0.0001f)
        assertEquals(0.5f, validRect.width, 0.0001f)
        assertEquals(0.6f, validRect.height, 0.0001f)
        assertEquals(0.3f, validRect.area, 0.0001f)

        // Constraint check: invalid boundary must throw IllegalArgumentException
        try {
            NormalizedRect(left = -0.1f, top = 0.2f, right = 0.6f, bottom = 0.8f)
            fail("NormalizedRect should throw IllegalArgumentException for negative coordinates")
        } catch (_: IllegalArgumentException) {}

        try {
            NormalizedRect(left = 0.7f, top = 0.2f, right = 0.4f, bottom = 0.8f)
            fail("NormalizedRect should throw IllegalArgumentException when right < left")
        } catch (_: IllegalArgumentException) {}

        // JSON roundtrip
        val rectJson = gson.toJson(validRect)
        val deserializedRect = gson.fromJson(rectJson, NormalizedRect::class.java)
        assertEquals(validRect, deserializedRect)

        // 3. MotionConfig
        val defaultConfig = MotionConfig()
        assertEquals(50, defaultConfig.sensitivity)
        assertEquals(25, defaultConfig.pixelThreshold)
        assertEquals(0.01f, defaultConfig.minAreaRatio, 0.0001f)
        assertEquals(2, defaultConfig.consecutiveTriggerFrames)
        assertTrue(defaultConfig.lowLightCompensation)

        val customConfig = MotionConfig(
            sensitivity = 85,
            pixelThreshold = 18,
            minAreaRatio = 0.005f,
            consecutiveTriggerFrames = 3,
            lowLightCompensation = false
        )
        val configJson = gson.toJson(customConfig)
        val deserializedConfig = gson.fromJson(configJson, MotionConfig::class.java)
        assertEquals(customConfig, deserializedConfig)

        // 4. MotionResult
        val motionResult = MotionResult(
            isMotionDetected = true,
            motionScore = 0.88f,
            activePixels = 2400,
            totalPixels = 19200,
            averageLuminance = 115.4f,
            isLowLight = false,
            boundingBoxes = listOf(validRect)
        )
        assertTrue(motionResult.isMotionDetected)
        assertEquals(0.88f, motionResult.motionScore, 0.0001f)
        assertEquals(1, motionResult.boundingBoxes.size)

        val resultJson = gson.toJson(motionResult)
        val deserializedResult = gson.fromJson(resultJson, MotionResult::class.java)
        assertEquals(motionResult.isMotionDetected, deserializedResult.isMotionDetected)
        assertEquals(motionResult.motionScore, deserializedResult.motionScore, 0.0001f)
        assertEquals(motionResult.activePixels, deserializedResult.activePixels)
        assertEquals(motionResult.boundingBoxes.size, deserializedResult.boundingBoxes.size)
    }

    @Test
    fun testStorageDomainModelInstantiationAndSerialization() {
        val eventId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val event = MotionEventEntity(
            id = eventId,
            timestamp = timestamp,
            imagePath = "/data/user/0/com.securitycam/files/events/event_$eventId.jpg",
            motionScore = 0.76f,
            isLowLight = false,
            smsStatus = MotionEventEntity.STATUS_SENT,
            emailStatus = MotionEventEntity.STATUS_SENT
        )

        assertEquals(eventId, event.id)
        assertEquals(timestamp, event.timestamp)
        assertEquals("SENT", event.smsStatus)
        assertEquals("SENT", event.emailStatus)

        val eventJson = gson.toJson(event)
        val deserializedEvent = gson.fromJson(eventJson, MotionEventEntity::class.java)
        assertEquals(event, deserializedEvent)
    }

    @Test
    fun testAlertingDomainModelsInstantiationAndSerialization() {
        val dummyFile = File(context.cacheDir, "test_snapshot.jpg")
        val alertPayload = AlertPayload(
            eventId = "alert-test-001",
            timestamp = 1700000005000L,
            motionScore = 0.94f,
            screenshotFile = dummyFile,
            cameraFacing = "BACK"
        )
        assertEquals("alert-test-001", alertPayload.eventId)
        assertEquals(1700000005000L, alertPayload.timestamp)
        assertEquals("BACK", alertPayload.cameraFacing)
        assertEquals(dummyFile.absolutePath, alertPayload.screenshotFile.absolutePath)

        val dispatchSuccess = DispatchResult(
            isSuccess = true,
            deliveredCount = 3,
            failedCount = 0,
            errorMessage = null
        )
        assertTrue(dispatchSuccess.isSuccess)
        assertEquals(3, dispatchSuccess.deliveredCount)
        assertEquals(0, dispatchSuccess.failedCount)

        val dispatchFailure = DispatchResult(
            isSuccess = false,
            deliveredCount = 1,
            failedCount = 2,
            errorMessage = "SMTP 535 Authentication Credentials Invalid"
        )
        assertFalse(dispatchFailure.isSuccess)
        assertEquals(1, dispatchFailure.deliveredCount)
        assertEquals(2, dispatchFailure.failedCount)
        assertEquals("SMTP 535 Authentication Credentials Invalid", dispatchFailure.errorMessage)

        val dispatchJson = gson.toJson(dispatchSuccess)
        val deserializedDispatch = gson.fromJson(dispatchJson, DispatchResult::class.java)
        assertEquals(dispatchSuccess, deserializedDispatch)
    }
}
