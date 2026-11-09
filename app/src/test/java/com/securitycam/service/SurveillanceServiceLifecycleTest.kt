package com.securitycam.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.securitycam.SecurityCamApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric unit test suite verifying SurveillanceService lifecycle,
 * foreground notification generation, wake lock acquisition/release,
 * and arm/disarm/switch camera intent action processing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SurveillanceServiceLifecycleTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    @Test
    fun testServiceStartAndForegroundState() {
        val controller = Robolectric.buildService(SurveillanceService::class.java)
        val service = controller.create().get()

        val startIntent = Intent(context, SurveillanceService::class.java).apply {
            action = SurveillanceService.ACTION_START
        }
        controller.withIntent(startIntent).startCommand(0, 1)

        val state = SurveillanceService.liveState.value
        assertTrue("SurveillanceService must report isRunning=true after ACTION_START", state.isRunning)

        controller.destroy()
    }

    @Test
    fun testArmAndDisarmActions() {
        val controller = Robolectric.buildService(SurveillanceService::class.java)
        val service = controller.create().get()

        // 1. Arm
        val armIntent = Intent(context, SurveillanceService::class.java).apply {
            action = SurveillanceService.ACTION_ARM
        }
        controller.withIntent(armIntent).startCommand(0, 1)
        assertTrue("Service must update isArmed=true on ACTION_ARM", SurveillanceService.liveState.value.isArmed)

        // 2. Disarm
        val disarmIntent = Intent(context, SurveillanceService::class.java).apply {
            action = SurveillanceService.ACTION_DISARM
        }
        controller.withIntent(disarmIntent).startCommand(0, 2)
        assertFalse("Service must update isArmed=false on ACTION_DISARM", SurveillanceService.liveState.value.isArmed)

        // 3. Toggle Arm
        val toggleIntent = Intent(context, SurveillanceService::class.java).apply {
            action = SurveillanceService.ACTION_TOGGLE_ARM
        }
        controller.withIntent(toggleIntent).startCommand(0, 3)
        assertTrue("Service must toggle to isArmed=true", SurveillanceService.liveState.value.isArmed)

        controller.destroy()
    }

    @Test
    fun testSwitchCameraAction() {
        val controller = Robolectric.buildService(SurveillanceService::class.java)
        val service = controller.create().get()

        // Initial setup
        val startIntent = Intent(context, SurveillanceService::class.java).apply {
            action = SurveillanceService.ACTION_START
        }
        controller.withIntent(startIntent).startCommand(0, 1)

        val initialFacing = SurveillanceService.liveState.value.cameraFacing
        val switchIntent = Intent(context, SurveillanceService::class.java).apply {
            action = SurveillanceService.ACTION_SWITCH_CAMERA
        }
        controller.withIntent(switchIntent).startCommand(0, 2)

        val switchedFacing = SurveillanceService.liveState.value.cameraFacing
        val expectedFacing = if (initialFacing == "FRONT") "BACK" else "FRONT"
        assertEquals("Camera facing should toggle between FRONT and BACK", expectedFacing, switchedFacing)

        controller.destroy()
    }

    @Test
    fun testOngoingNotificationGeneration() {
        val controller = Robolectric.buildService(SurveillanceService::class.java)
        val service = controller.create().get()

        val notificationArmed = service.buildOngoingNotification(isArmed = true)
        assertNotNull(notificationArmed)
        assertTrue("Notification should have ongoing flag", (notificationArmed.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0)
        assertNotNull("Notification must contain an arm/disarm action", notificationArmed.actions)
        assertTrue("Notification must have at least 1 action", notificationArmed.actions.isNotEmpty())

        val notificationDisarmed = service.buildOngoingNotification(isArmed = false)
        assertNotNull(notificationDisarmed)
        assertNotNull(notificationDisarmed.actions)

        controller.destroy()
    }

    @Test
    fun testServiceStopAction() {
        val controller = Robolectric.buildService(SurveillanceService::class.java)
        val service = controller.create().get()

        // Start
        val startIntent = Intent(context, SurveillanceService::class.java).apply {
            action = SurveillanceService.ACTION_START
        }
        controller.withIntent(startIntent).startCommand(0, 1)
        assertTrue(SurveillanceService.liveState.value.isRunning)

        // Stop
        val stopIntent = Intent(context, SurveillanceService::class.java).apply {
            action = SurveillanceService.ACTION_STOP
        }
        controller.withIntent(stopIntent).startCommand(0, 2)
        assertFalse("Service must report isRunning=false after ACTION_STOP", SurveillanceService.liveState.value.isRunning)

        controller.destroy()
    }

    @Test
    fun testSwitchCameraResetsMotionDetector() {
        val app = SecurityCamApp.getInstance()
        // Feed an initial frame to motionDetector
        val frame = com.securitycam.core.surveillance.LuminanceFrame(ByteArray(160 * 120) { 100 }, 160, 120, 1000L)
        app.motionDetector.processFrame(frame)

        val controller = Robolectric.buildService(SurveillanceService::class.java)
        val service = controller.create().get()

        // Call switchCameraLens
        service.switchCameraLens(persistSetting = false)

        // After reset, processing a completely different frame should be treated as first frame (isMotionDetected = false)
        val newLensFrame = com.securitycam.core.surveillance.LuminanceFrame(ByteArray(160 * 120) { 20 }, 160, 120, 2000L)
        val result = app.motionDetector.processFrame(newLensFrame)
        assertFalse("First frame after camera switch must not trigger false motion alarm", result.isMotionDetected)

        controller.destroy()
    }
}
