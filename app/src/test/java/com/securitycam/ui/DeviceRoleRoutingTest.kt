package com.securitycam.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.securitycam.SecurityCamApp
import com.securitycam.core.config.SecuritySettings
import com.securitycam.service.SurveillanceService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** The same app runs on the camera phone and on the owner's phone; the role decides what opens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceRoleRoutingTest {

    private val repository get() = SecurityCamApp.getInstance().settingsRepository

    private fun setRole(role: String) {
        repository.updateSettings(repository.getSettings().copy(deviceRole = role))
    }

    @Test
    fun aReceiverPhoneOpensTheReceiverScreenAndNeverStartsTheCamera() {
        setRole(SecuritySettings.ROLE_RECEIVER)
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).grantPermissions(android.Manifest.permission.CAMERA)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        assertTrue(controller.get().isFinishing)
        assertEquals(
            ReceiverActivity::class.java.name,
            shadowOf(controller.get()).nextStartedActivity.component?.className
        )
        // Even with camera permission already granted, no camera service may be started.
        var started = shadowOf(application).nextStartedService
        while (started != null) {
            assertFalse(started.component?.className == SurveillanceService::class.java.name)
            started = shadowOf(application).nextStartedService
        }
    }

    @Test
    fun firstLaunchAsksHowThePhoneIsUsedBeforeAnythingElse() {
        setRole("")

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        assertFalse(controller.get().isFinishing)
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
    }

    @Test
    fun aCameraPhoneOpensTheLiveScreenWithoutAsking() {
        setRole(SecuritySettings.ROLE_CAMERA)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        assertFalse(controller.get().isFinishing)
        // The only thing it may open is the system permission prompt, never the receiver screen.
        val next = shadowOf(controller.get()).nextStartedActivity
        assertTrue(next == null || next.component?.className != ReceiverActivity::class.java.name)
        assertNull(ShadowDialog.getLatestDialog())
    }
}
