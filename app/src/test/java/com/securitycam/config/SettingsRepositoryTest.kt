package com.securitycam.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.securitycam.core.config.SecuritySettings
import com.securitycam.core.config.SharedPreferencesSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

    private lateinit var repository: SharedPreferencesSettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        repository = SharedPreferencesSettingsRepository(prefs)
    }

    @Test
    fun testDefaultSettings() {
        val settings = repository.getSettings()
        assertTrue(settings.phoneNumbers.isEmpty())
        assertTrue(settings.emailAddresses.isEmpty())
        assertFalse(settings.smsEnabled)
        assertFalse(settings.emailEnabled)
        assertEquals(50, settings.sensitivity)
        assertEquals(30, settings.cooldownSeconds)
        assertTrue(settings.isArmed)
        assertEquals("BACK", settings.cameraFacing)
        assertTrue(settings.lowLightCompensation)
        assertEquals("smtp.gmail.com", settings.smtpHost)
        assertEquals(587, settings.smtpPort)
    }

    @Test
    fun testUpdateAndRoundTripPersistence() {
        val custom = SecuritySettings(
            phoneNumbers = listOf("+12025550123", "+447911123456"),
            emailAddresses = listOf("alert@example.com", "security@domain.org"),
            smsEnabled = true,
            emailEnabled = true,
            webhookUrl = "https://webhook.example.com/alerts",
            smtpHost = "mail.mysecurity.com",
            smtpPort = 465,
            smtpUsername = "cam_user@mysecurity.com",
            smtpPassword = "super_secret_password",
            smtpSenderEmail = "no-reply@mysecurity.com",
            smtpSenderName = "Front Porch Cam",
            smtpUseTls = false,
            smtpUseSsl = true,
            smtpTimeoutMs = 20000,
            sensitivity = 85,
            cooldownSeconds = 60,
            isArmed = false,
            cameraFacing = "FRONT",
            lowLightCompensation = false
        )

        repository.updateSettings(custom)
        val loaded = repository.getSettings()

        assertEquals(custom.phoneNumbers, loaded.phoneNumbers)
        assertEquals(custom.emailAddresses, loaded.emailAddresses)
        assertEquals(custom.smsEnabled, loaded.smsEnabled)
        assertEquals(custom.emailEnabled, loaded.emailEnabled)
        assertEquals(custom.webhookUrl, loaded.webhookUrl)
        assertEquals(custom.smtpHost, loaded.smtpHost)
        assertEquals(custom.smtpPort, loaded.smtpPort)
        assertEquals(custom.smtpUsername, loaded.smtpUsername)
        assertEquals(custom.smtpPassword, loaded.smtpPassword)
        assertEquals(custom.smtpSenderEmail, loaded.smtpSenderEmail)
        assertEquals(custom.smtpSenderName, loaded.smtpSenderName)
        assertEquals(custom.smtpUseTls, loaded.smtpUseTls)
        assertEquals(custom.smtpUseSsl, loaded.smtpUseSsl)
        assertEquals(custom.smtpTimeoutMs, loaded.smtpTimeoutMs)
        assertEquals(custom.sensitivity, loaded.sensitivity)
        assertEquals(custom.cooldownSeconds, loaded.cooldownSeconds)
        assertEquals(custom.isArmed, loaded.isArmed)
        assertEquals(custom.cameraFacing, loaded.cameraFacing)
        assertEquals(custom.lowLightCompensation, loaded.lowLightCompensation)
    }

    @Test
    fun testIndividualFieldModifiers() {
        repository.setArmed(false)
        assertFalse(repository.isArmed())

        repository.setSensitivity(90)
        assertEquals(90, repository.getSensitivity())

        repository.setCooldownSeconds(45)
        assertEquals(45, repository.getCooldownSeconds())

        repository.setPhoneNumbers(listOf("+1 (202) 555-0123"))
        assertEquals(listOf("+12025550123"), repository.getPhoneNumbers())

        repository.setEmailAddresses(listOf(" valid@email.com "))
        assertEquals(listOf("valid@email.com"), repository.getEmailAddresses())
    }

    @Test
    fun testObserveSettingsFlow(): Unit = runBlocking {
        val initial = repository.observeSettings().first()
        assertTrue(initial.isArmed)

        repository.setArmed(false)
        val updated = repository.observeSettings().first()
        assertFalse(updated.isArmed)
    }

    @Test
    fun testResetToDefaults() {
        repository.setArmed(false)
        repository.setSensitivity(95)
        repository.resetToDefaults()

        val settings = repository.getSettings()
        assertTrue(settings.isArmed)
        assertEquals(50, settings.sensitivity)
    }
}
