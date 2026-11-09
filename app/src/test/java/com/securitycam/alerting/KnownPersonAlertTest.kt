package com.securitycam.alerting

import com.securitycam.alerting.AlertCoordinatorTest.FakeEmailDispatcher
import com.securitycam.alerting.AlertCoordinatorTest.FakeEventRepository
import com.securitycam.alerting.AlertCoordinatorTest.FakeSettingsRepository
import com.securitycam.alerting.AlertCoordinatorTest.FakeSmsDispatcher
import com.securitycam.core.alerting.AlertPayload
import com.securitycam.core.alerting.DefaultAlertCoordinator
import com.securitycam.core.config.CooldownManager
import com.securitycam.core.config.SecuritySettings
import com.securitycam.core.storage.MotionEventEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class KnownPersonAlertTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    private val alertingSettings = SecuritySettings(
        isArmed = true,
        smsEnabled = true,
        emailEnabled = true,
        phoneNumbers = listOf("+14155550123"),
        emailAddresses = listOf("owner@example.com")
    )

    @Test
    fun recognisedPersonIsRecordedButNothingIsSent() = runTest(testDispatcher) {
        val eventRepo = FakeEventRepository()
        val cooldown = CooldownManager(30)
        val sms = FakeSmsDispatcher()
        val email = FakeEmailDispatcher()
        val coordinator = DefaultAlertCoordinator(
            FakeSettingsRepository(alertingSettings), eventRepo, cooldown, sms, email, testDispatcher
        )

        val summary = coordinator.onMotionDetected(
            AlertPayload(
                "ev-known", 100_000L, 0.8f, tempFolder.newFile("known.jpg"),
                isLowLight = true, recognizedPeople = "Asha", suppressAlert = true
            )
        )

        assertTrue(summary.isSuppressedByKnownPerson)
        assertFalse(summary.isSuppressedByCooldown)
        assertTrue(summary.localEventSaved)
        assertNull(summary.smsResult)
        assertNull(summary.emailResult)
        assertEquals(0, sms.sendCount)
        assertEquals(0, email.sendCount)

        val saved = eventRepo.getEventById("ev-known")!!
        assertEquals("Asha", saved.recognizedPerson)
        assertTrue(saved.isLowLight)
        assertEquals(MotionEventEntity.STATUS_KNOWN_PERSON, saved.smsStatus)
        assertEquals(MotionEventEntity.STATUS_KNOWN_PERSON, saved.emailStatus)
    }

    @Test
    fun aSuppressedEventDoesNotUseUpTheCooldownForARealAlert() = runTest(testDispatcher) {
        val eventRepo = FakeEventRepository()
        val sms = FakeSmsDispatcher()
        val email = FakeEmailDispatcher()
        val coordinator = DefaultAlertCoordinator(
            FakeSettingsRepository(alertingSettings), eventRepo, CooldownManager(30), sms, email, testDispatcher
        )

        coordinator.onMotionDetected(
            AlertPayload(
                "ev-known", 100_000L, 0.8f, tempFolder.newFile("known.jpg"),
                recognizedPeople = "Asha", suppressAlert = true
            )
        )
        // A stranger five seconds later must still alert.
        val summary = coordinator.onMotionDetected(
            AlertPayload("ev-stranger", 105_000L, 0.9f, tempFolder.newFile("stranger.jpg"))
        )

        assertFalse(summary.isSuppressedByKnownPerson)
        assertFalse(summary.isSuppressedByCooldown)
        assertEquals(1, sms.sendCount)
        assertEquals(1, email.sendCount)
        assertNull(eventRepo.getEventById("ev-stranger")!!.recognizedPerson)
    }

    @Test
    fun ordinaryEventsAreUnaffected() = runTest(testDispatcher) {
        val eventRepo = FakeEventRepository()
        val sms = FakeSmsDispatcher()
        val email = FakeEmailDispatcher()
        val coordinator = DefaultAlertCoordinator(
            FakeSettingsRepository(alertingSettings), eventRepo, CooldownManager(30), sms, email, testDispatcher
        )

        val summary = coordinator.onMotionDetected(
            AlertPayload("ev-plain", 100_000L, 0.8f, tempFolder.newFile("plain.jpg"))
        )

        assertFalse(summary.isSuppressedByKnownPerson)
        assertEquals(1, sms.sendCount)
        assertEquals(1, email.sendCount)
        assertEquals(MotionEventEntity.STATUS_SENT, eventRepo.getEventById("ev-plain")!!.smsStatus)
    }
}
