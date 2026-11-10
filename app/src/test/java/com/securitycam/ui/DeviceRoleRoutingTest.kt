package com.securitycam.ui

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.securitycam.SecurityCamApp
import com.securitycam.core.config.SecuritySettings
import com.securitycam.service.BootCompletedReceiver
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

/** The same app runs on the camera phone and on the family's phones; the role decides what opens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceRoleRoutingTest {

    private val application get() = ApplicationProvider.getApplicationContext<Application>()
    private val repository get() = SecurityCamApp.getInstance().settingsRepository

    private fun setRole(role: String) {
        repository.updateSettings(repository.getSettings().copy(deviceRole = role))
    }

    private fun assertNoCameraServiceStarted() {
        var started = shadowOf(application).nextStartedService
        while (started != null) {
            assertFalse(started.component?.className == SurveillanceService::class.java.name)
            started = shadowOf(application).nextStartedService
        }
    }

    @Test
    fun aReceiverPhoneOpensTheReceiverScreenAndNeverStartsTheCamera() {
        setRole(SecuritySettings.ROLE_RECEIVER)
        shadowOf(application).grantPermissions(android.Manifest.permission.CAMERA)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        assertTrue(controller.get().isFinishing)
        assertEquals(
            ReceiverActivity::class.java.name,
            shadowOf(controller.get()).nextStartedActivity.component?.className
        )
        // Even with camera permission already granted, no camera service may be started.
        assertNoCameraServiceStarted()
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
        val next = shadowOf(controller.get()).nextStartedActivity
        assertTrue(next == null || next.component?.className != ReceiverActivity::class.java.name)
        assertNull(ShadowDialog.getLatestDialog())
    }

    @Test
    fun aReceiverPhoneDoesNotStartTheCameraAfterAReboot() {
        repository.setArmed(true)
        setRole(SecuritySettings.ROLE_RECEIVER)

        BootCompletedReceiver().onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(shadowOf(application).nextStartedService)
    }

    @Test
    fun theReceiverScreenOpensAndRejectsABadCode() {
        setRole(SecuritySettings.ROLE_RECEIVER)

        val activity = Robolectric.buildActivity(ReceiverActivity::class.java).setup().get()
        activity.findViewById<android.widget.EditText>(com.securitycam.R.id.etReceiverCode).setText("too short")
        activity.findViewById<android.view.View>(com.securitycam.R.id.btnReceiverLink).performClick()

        val field = activity.findViewById<com.google.android.material.textfield.TextInputLayout>(
            com.securitycam.R.id.tilReceiverCode
        )
        assertNotNull(field.error)
        assertEquals("", repository.getSettings().receiverLinkCode)
    }

    @Test
    fun theSettingsScreenOpensAndShowsALinkCodeForFamilyPhones() {
        setRole(SecuritySettings.ROLE_CAMERA)

        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

        assertFalse(activity.isFinishing)
        if (com.google.firebase.FirebaseApp.getApps(application).isNotEmpty()) {
            // A code is created on first use and is one the sender will accept.
            val code = repository.getSettings().pushLinkCode
            assertNotNull(com.securitycam.core.push.PushMessage.topicFor(code))
            // Without a key file loaded, notifications can't be switched on.
            assertFalse(repository.getSettings().pushEnabled)
        }
    }

    @Test
    fun theLiveViewScreenOpensAndExplainsWhenThePhoneIsNotLinked() {
        setRole(SecuritySettings.ROLE_RECEIVER)
        repository.updateSettings(repository.getSettings().copy(receiverLinkCode = ""))

        val activity = Robolectric.buildActivity(LiveViewActivity::class.java).setup().get()

        val status = activity.findViewById<android.widget.TextView>(com.securitycam.R.id.tvLiveStatus)
        assertEquals(activity.getString(com.securitycam.R.string.live_unavailable), status.text.toString())
    }
}
