package com.securitycam.alerting

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
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.securitycam.SecurityCamApp
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class AlertCoordinatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    class FakeSettingsRepository(initialSettings: SecuritySettings = SecuritySettings()) : SettingsRepository {
        private var currentSettings: SecuritySettings = initialSettings

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
        var sendCount = 0
        override suspend fun sendSms(phoneNumbers: List<String>, message: String): DispatchResult {
            sendCount++
            return DispatchResult(isSuccess = true, deliveredCount = phoneNumbers.size, failedCount = 0)
        }
    }

    class FakeEmailDispatcher : EmailDispatcher {
        var sendCount = 0
        override suspend fun sendEmail(
            emailAddresses: List<String>,
            subject: String,
            bodyHtml: String,
            attachmentFile: File?
        ): DispatchResult {
            sendCount++
            return DispatchResult(isSuccess = true, deliveredCount = emailAddresses.size, failedCount = 0)
        }
    }

    @Test
    fun testDisarmedMotionSavesLocalEventWithoutDispatching() = runTest(testDispatcher) {
        val settingsRepo = FakeSettingsRepository(SecuritySettings(isArmed = false))
        val eventRepo = FakeEventRepository()
        val cooldown = CooldownManager(30)
        val sms = FakeSmsDispatcher()
        val email = FakeEmailDispatcher()

        val coordinator = DefaultAlertCoordinator(settingsRepo, eventRepo, cooldown, sms, email, testDispatcher)

        val imageFile = tempFolder.newFile("motion.jpg")
        val payload = AlertPayload("ev-disarmed", 1000L, 0.8f, imageFile)

        val summary = coordinator.onMotionDetected(payload)

        assertFalse(summary.isArmed)
        assertFalse(summary.isSuppressedByCooldown)
        assertTrue(summary.localEventSaved)
        assertNull(summary.smsResult)
        assertNull(summary.emailResult)
        assertEquals(0, sms.sendCount)
        assertEquals(0, email.sendCount)

        // Verify local DB entity
        val saved = eventRepo.getEventById("ev-disarmed")
        assertNotNull(saved)
        assertEquals(MotionEventEntity.STATUS_DISABLED, saved!!.smsStatus)
        assertEquals(MotionEventEntity.STATUS_DISABLED, saved.emailStatus)
    }

    @Test
    fun testArmedDuringActiveCooldownSuppressesAlert() = runTest(testDispatcher) {
        val settingsRepo = FakeSettingsRepository(SecuritySettings(isArmed = true))
        val eventRepo = FakeEventRepository()
        val cooldown = CooldownManager(30)
        cooldown.recordAlertDispatched(1000L) // Entered cooldown at T=1000

        val sms = FakeSmsDispatcher()
        val email = FakeEmailDispatcher()

        val coordinator = DefaultAlertCoordinator(settingsRepo, eventRepo, cooldown, sms, email, testDispatcher)

        val imageFile = tempFolder.newFile("motion2.jpg")
        val payload = AlertPayload("ev-cooldown", 5000L, 0.9f, imageFile) // At T=5000 (< 31000)

        val summary = coordinator.onMotionDetected(payload)

        assertTrue(summary.isArmed)
        assertTrue(summary.isSuppressedByCooldown)
        assertTrue(summary.localEventSaved)
        assertEquals(0, sms.sendCount)
        assertEquals(0, email.sendCount)

        val saved = eventRepo.getEventById("ev-cooldown")
        assertNotNull(saved)
        assertEquals(MotionEventEntity.STATUS_SUPPRESSED, saved!!.smsStatus)
        assertEquals(MotionEventEntity.STATUS_SUPPRESSED, saved.emailStatus)
    }

    @Test
    fun testArmedAndReadyDispatchesSmsAndEmail() = runTest(testDispatcher) {
        val settingsRepo = FakeSettingsRepository(
            SecuritySettings(
                isArmed = true,
                smsEnabled = true,
                emailEnabled = true,
                phoneNumbers = listOf("+12025550123"),
                emailAddresses = listOf("user@example.com")
            )
        )
        val eventRepo = FakeEventRepository()
        val cooldown = CooldownManager(30)
        val sms = FakeSmsDispatcher()
        val email = FakeEmailDispatcher()

        val coordinator = DefaultAlertCoordinator(settingsRepo, eventRepo, cooldown, sms, email, testDispatcher)

        val imageFile = tempFolder.newFile("motion3.jpg")
        val payload = AlertPayload("ev-dispatch", 10_000L, 0.75f, imageFile)

        val summary = coordinator.onMotionDetected(payload)

        assertTrue(summary.isArmed)
        assertFalse(summary.isSuppressedByCooldown)
        assertTrue(summary.localEventSaved)
        assertNotNull(summary.smsResult)
        assertNotNull(summary.emailResult)
        assertEquals(1, sms.sendCount)
        assertEquals(1, email.sendCount)

        val saved = eventRepo.getEventById("ev-dispatch")
        assertNotNull(saved)
        assertEquals(MotionEventEntity.STATUS_SENT, saved!!.smsStatus)
        assertEquals(MotionEventEntity.STATUS_SENT, saved.emailStatus)

        // Verify cooldown was triggered
        assertFalse(cooldown.shouldAlert(15_000L))
    }

    @Test
    fun testSendTestAlertBypassesArmAndCooldown() = runTest(testDispatcher) {
        val settingsRepo = FakeSettingsRepository(
            SecuritySettings(
                isArmed = false, // Disarmed
                phoneNumbers = listOf("+12025550123"),
                emailAddresses = listOf("test@test.com")
            )
        )
        val eventRepo = FakeEventRepository()
        val cooldown = CooldownManager(30)
        cooldown.recordAlertDispatched(1000L) // In active cooldown

        val sms = FakeSmsDispatcher()
        val email = FakeEmailDispatcher()

        val coordinator = DefaultAlertCoordinator(settingsRepo, eventRepo, cooldown, sms, email, testDispatcher)

        val summary = coordinator.sendTestAlert()

        assertNotNull(summary.smsResult)
        assertNotNull(summary.emailResult)
        assertEquals(1, sms.sendCount)
        assertEquals(1, email.sendCount)
        assertFalse(summary.localEventSaved)
    }

    @Test
    fun testBurstDebounceSuppressesRapidConsecutiveAlerts() = runTest(testDispatcher) {
        val settingsRepo = FakeSettingsRepository(SecuritySettings(isArmed = true))
        val eventRepo = FakeEventRepository()
        val cooldown = CooldownManager(30)
        val sms = FakeSmsDispatcher()
        val email = FakeEmailDispatcher()

        val coordinator = DefaultAlertCoordinator(settingsRepo, eventRepo, cooldown, sms, email, testDispatcher)

        val imageFile1 = tempFolder.newFile("frame1.jpg")
        val imageFile2 = tempFolder.newFile("frame2.jpg")
        val imageFile3 = tempFolder.newFile("frame3.jpg")

        // First frame at T=10,000ms
        val payload1 = AlertPayload("ev-burst-1", 10_000L, 0.8f, imageFile1)
        val summary1 = coordinator.onMotionDetected(payload1)
        assertTrue(summary1.localEventSaved)
        assertFalse(summary1.isSuppressedByCooldown)
        assertEquals(1, eventRepo.events.size)

        // Rapid second frame at T=11,500ms (1500ms later < BURST_DEBOUNCE_MS of 3000ms)
        val payload2 = AlertPayload("ev-burst-2", 11_500L, 0.85f, imageFile2)
        val summary2 = coordinator.onMotionDetected(payload2)
        assertFalse("Rapid burst frame must not save duplicate local event", summary2.localEventSaved)
        assertTrue("Rapid burst frame must be suppressed by burst debounce", summary2.isSuppressedByCooldown)
        assertEquals("Event repo must still have only 1 event", 1, eventRepo.events.size)

        // Third frame at T=13,500ms (3500ms after first frame, cooldown active for SMS/Email but local event saved)
        val payload3 = AlertPayload("ev-burst-3", 13_500L, 0.9f, imageFile3)
        val summary3 = coordinator.onMotionDetected(payload3)
        assertTrue("Frame after debounce window must save local event", summary3.localEventSaved)
        assertEquals(2, eventRepo.events.size)
    }

    @Test
    fun testLocalAlertNotification_postedOnChannelAlerts() = runTest(testDispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()

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

        val imageFile = tempFolder.newFile("alert_screen.jpg")
        val payload = AlertPayload("ev-notif-1", 20_000L, 0.88f, imageFile, cameraFacing = "BACK")

        coordinator.onMotionDetected(payload)

        val notifications = shadowOf(notificationManager).allNotifications
        assertEquals("Exactly 1 local alert notification must be posted", 1, notifications.size)
        val notif = notifications[0]
        assertEquals(SecurityCamApp.CHANNEL_ALERTS, notif.channelId)
        assertEquals(NotificationCompat.PRIORITY_HIGH, notif.priority)
    }
}
