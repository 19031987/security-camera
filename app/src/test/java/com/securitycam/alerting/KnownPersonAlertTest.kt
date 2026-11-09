package com.securitycam.alerting

import com.securitycam.alerting.AlertCoordinatorTest.FakeEmailDispatcher
import com.securitycam.alerting.AlertCoordinatorTest.FakeEventRepository
import com.securitycam.alerting.AlertCoordinatorTest.FakeSettingsRepository
import com.securitycam.alerting.AlertCoordinatorTest.FakeSmsDispatcher
import com.securitycam.core.alerting.AlertPayload
import com.securitycam.core.alerting.DefaultAlertCoordinator
import com.securitycam.core.alerting.DispatchResult
import com.securitycam.core.alerting.PushDispatcher
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
import java.io.File

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

    private class FakePushDispatcher : PushDispatcher {
        data class Sent(val title: String, val message: String, val hasImage: Boolean, val urgent: Boolean)

        val sent = mutableListOf<Sent>()

        override suspend fun sendPush(title: String, message: String, imageFile: File?, urgent: Boolean): DispatchResult {
            sent.add(Sent(title, message, imageFile != null, urgent))
            return DispatchResult(isSuccess = true, deliveredCount = 1, failedCount = 0)
        }
    }

    private val pushSettings = SecuritySettings(
        isArmed = true,
        pushEnabled = true,
        pushTopic = "cam-0123456789abcdefABCDEF"
    )

    private fun coordinatorWithPush(
        settings: SecuritySettings,
        push: FakePushDispatcher
    ) = DefaultAlertCoordinator(
        FakeSettingsRepository(settings), FakeEventRepository(), CooldownManager(30),
        FakeSmsDispatcher(), FakeEmailDispatcher(), testDispatcher, null, push
    )

    @Test
    fun unrecognisedMotionSendsAnUrgentPushWithThePicture() = runTest(testDispatcher) {
        val push = FakePushDispatcher()
        val summary = coordinatorWithPush(pushSettings, push).onMotionDetected(
            AlertPayload("ev-1", 100_000L, 0.8f, tempFolder.newFile("a.jpg"))
        )

        assertTrue(summary.pushResult!!.isSuccess)
        assertEquals(1, push.sent.size)
        assertEquals("Someone is at your door", push.sent[0].title)
        assertTrue(push.sent[0].urgent)
        assertTrue(push.sent[0].hasImage)
    }

    @Test
    fun aRecognisedPersonSendsAQuietArrivalPushNamingThem() = runTest(testDispatcher) {
        val push = FakePushDispatcher()
        coordinatorWithPush(pushSettings, push).onMotionDetected(
            AlertPayload("ev-1", 100_000L, 0.8f, tempFolder.newFile("a.jpg"), recognizedPeople = "Asha", suppressAlert = true)
        )

        assertEquals(1, push.sent.size)
        assertEquals("Asha is home", push.sent[0].title)
        assertFalse(push.sent[0].urgent)
    }

    @Test
    fun theSamePersonMovingAroundIsAnnouncedOnlyOnce() = runTest(testDispatcher) {
        val push = FakePushDispatcher()
        val coordinator = coordinatorWithPush(pushSettings, push)
        fun payload(id: String, at: Long) = AlertPayload(
            id, at, 0.8f, tempFolder.newFile("$id.jpg"), recognizedPeople = "Asha", suppressAlert = true
        )

        coordinator.onMotionDetected(payload("ev-1", 100_000L))
        coordinator.onMotionDetected(payload("ev-2", 110_000L))
        coordinator.onMotionDetected(payload("ev-3", 400_000L))
        assertEquals(1, push.sent.size)

        // Someone else arriving is announced straight away.
        coordinator.onMotionDetected(
            AlertPayload("ev-4", 410_000L, 0.8f, tempFolder.newFile("ev-4.jpg"), recognizedPeople = "Ravi", suppressAlert = true)
        )
        assertEquals(listOf("Asha is home", "Ravi is home"), push.sent.map { it.title })

        // And the first person again once the quiet interval has passed.
        coordinator.onMotionDetected(
            payload("ev-5", 410_000L + DefaultAlertCoordinator.KNOWN_PERSON_PUSH_INTERVAL_MS + 5_000L)
        )
        assertEquals(3, push.sent.size)
    }

    @Test
    fun arrivalPushesCanBeSwitchedOff() = runTest(testDispatcher) {
        val push = FakePushDispatcher()
        coordinatorWithPush(pushSettings.copy(pushNotifyKnownPeople = false), push).onMotionDetected(
            AlertPayload("ev-1", 100_000L, 0.8f, tempFolder.newFile("a.jpg"), recognizedPeople = "Asha", suppressAlert = true)
        )
        assertTrue(push.sent.isEmpty())
    }

    @Test
    fun noPushWhenSwitchedOffDisarmedOrWithoutATopic() = runTest(testDispatcher) {
        val file = tempFolder.newFile("a.jpg")
        for (settings in listOf(
            pushSettings.copy(pushEnabled = false),
            pushSettings.copy(isArmed = false),
            pushSettings.copy(pushTopic = "")
        )) {
            val push = FakePushDispatcher()
            val summary = coordinatorWithPush(settings, push).onMotionDetected(AlertPayload("ev", 100_000L, 0.8f, file))
            assertTrue(push.sent.isEmpty())
            assertNull(summary.pushResult)
        }
    }

    @Test
    fun pushesRespectTheAlertCooldown() = runTest(testDispatcher) {
        val push = FakePushDispatcher()
        val coordinator = coordinatorWithPush(pushSettings, push)

        coordinator.onMotionDetected(AlertPayload("ev-1", 100_000L, 0.8f, tempFolder.newFile("1.jpg")))
        coordinator.onMotionDetected(AlertPayload("ev-2", 110_000L, 0.8f, tempFolder.newFile("2.jpg")))

        assertEquals(1, push.sent.size)
    }

    @Test
    fun testAlertIncludesAPush() = runTest(testDispatcher) {
        val push = FakePushDispatcher()
        val summary = coordinatorWithPush(pushSettings, push).sendTestAlert()

        assertTrue(summary.pushResult!!.isSuccess)
        assertEquals("Security Camera test", push.sent.single().title)
        assertFalse(push.sent.single().urgent)
    }
}
