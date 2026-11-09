package com.securitycam.service

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.securitycam.SecurityCamApp
import com.securitycam.core.config.SharedPreferencesSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootCompletedReceiverTest {

    private lateinit var context: Context
    private lateinit var receiver: BootCompletedReceiver

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        receiver = BootCompletedReceiver()
    }

    @Test
    fun testBootCompleted_whenArmed_startsSurveillanceService() {
        // Ensure settings repo is armed
        val repo = SharedPreferencesSettingsRepository.create(context)
        repo.setArmed(true)

        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)

        val app = context as Application
        val startedServiceIntent = shadowOf(app).nextStartedService
        assertNotNull("SurveillanceService must be started when armed", startedServiceIntent)
        assertEquals(SurveillanceService::class.java.name, startedServiceIntent.component?.className)
        assertEquals(SurveillanceService.ACTION_START, startedServiceIntent.action)
    }

    @Test
    fun testBootCompleted_whenDisarmed_doesNotStartService() {
        // Ensure settings repo is disarmed
        val repo = SharedPreferencesSettingsRepository.create(context)
        repo.setArmed(false)

        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)

        val app = context as Application
        val startedServiceIntent = shadowOf(app).nextStartedService
        assertNull("SurveillanceService must not be started when disarmed", startedServiceIntent)
    }

    @Test
    fun testUnrelatedAction_doesNotStartService() {
        val repo = SharedPreferencesSettingsRepository.create(context)
        repo.setArmed(true)

        val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        receiver.onReceive(context, intent)

        val app = context as Application
        val startedServiceIntent = shadowOf(app).nextStartedService
        assertNull("SurveillanceService must not be started on unrelated broadcast", startedServiceIntent)
    }

    @Test
    fun testBootCompleted_onReceiverPhone_startsReceiverNotTheCamera() {
        val repo = SharedPreferencesSettingsRepository.create(context)
        repo.setArmed(true)
        repo.updateSettings(
            repo.getSettings().copy(deviceRole = "RECEIVER", receiverEnabled = true)
        )

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val started = shadowOf(context as Application).nextStartedService
        assertNotNull("ReceiverService must be started on a receiver phone", started)
        assertEquals("com.securitycam.service.ReceiverService", started.component?.className)
    }

    @Test
    fun testBootCompleted_onDisconnectedReceiverPhone_startsNothing() {
        val repo = SharedPreferencesSettingsRepository.create(context)
        repo.setArmed(true)
        repo.updateSettings(
            repo.getSettings().copy(deviceRole = "RECEIVER", receiverEnabled = false)
        )

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(shadowOf(context as Application).nextStartedService)
    }
}
