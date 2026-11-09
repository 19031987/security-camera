package com.securitycam.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.securitycam.core.alerting.WhatsAppContact
import com.securitycam.core.config.SharedPreferencesSettingsRepository
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
class WhatsAppSettingsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SharedPreferencesSettingsRepository.create(context).resetToDefaults()
    }

    @Test
    fun newSettingsDefaultToOff() {
        val settings = SharedPreferencesSettingsRepository.create(context).getSettings()
        assertFalse(settings.whatsappEnabled)
        assertTrue(settings.whatsappContacts.isEmpty())
        assertFalse(settings.pushEnabled)
        assertEquals("", settings.pushTopic)
        assertFalse(settings.remoteViewEnabled)
        assertFalse(settings.knownPeopleEnabled)
        assertTrue(settings.nightVisionEnabled)
    }

    @Test
    fun contactsAndSecretsSurviveAReloadAndStayOutOfPlainPreferences() {
        val repo = SharedPreferencesSettingsRepository.create(context)
        val contacts = listOf(WhatsAppContact("+14155550123", "123456"), WhatsAppContact("+919876543210", "654321"))
        repo.updateSettings(
            repo.getSettings().copy(
                whatsappEnabled = true,
                whatsappContacts = contacts,
                pushEnabled = true,
                pushTopic = "cam-0123456789abcdefABCDEF",
                remoteViewPassword = "correct-horse"
            )
        )

        val reloaded = SharedPreferencesSettingsRepository.create(context).getSettings()
        assertTrue(reloaded.whatsappEnabled)
        assertEquals(contacts, reloaded.whatsappContacts)
        assertEquals("cam-0123456789abcdefABCDEF", reloaded.pushTopic)
        assertEquals("correct-horse", reloaded.remoteViewPassword)

        // Keys, topic and password must not be written to the ordinary (backed-up) preferences.
        val plain = context.getSharedPreferences(
            SharedPreferencesSettingsRepository.PREF_FILE_NAME, Context.MODE_PRIVATE
        ).all.toString()
        for (secret in listOf("123456", "654321", "cam-0123456789abcdefABCDEF", "correct-horse")) {
            assertFalse("secret found in plain preferences", plain.contains(secret))
        }
    }
}
