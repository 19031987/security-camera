package com.securitycam.alerting

import com.securitycam.alerting.AlertCoordinatorTest.FakeEmailDispatcher
import com.securitycam.alerting.AlertCoordinatorTest.FakeEventRepository
import com.securitycam.alerting.AlertCoordinatorTest.FakeSettingsRepository
import com.securitycam.alerting.AlertCoordinatorTest.FakeSmsDispatcher
import com.securitycam.core.alerting.AlertPayload
import com.securitycam.core.alerting.DefaultAlertCoordinator
import com.securitycam.core.config.CooldownManager
import com.securitycam.core.config.SecuritySettings
import com.securitycam.core.remote.AlertEventHub
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which events the camera publishes for receiver phones. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class AlertEventPublishTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    private fun coordinator(hub: AlertEventHub, settings: SecuritySettings = SecuritySettings(isArmed = true)) =
        DefaultAlertCoordinator(
            FakeSettingsRepository(settings), FakeEventRepository(), CooldownManager(30),
            FakeSmsDispatcher(), FakeEmailDispatcher(), testDispatcher, null, hub
        )

    private fun known(id: String, at: Long, names: String = "Asha") = AlertPayload(
        id, at, 0.8f, tempFolder.newFile("$id.jpg"), recognizedPeople = names, suppressAlert = true
    )

    @Test
    fun unrecognisedMotionIsPublishedAsAnAlertEvenWithSmsAndEmailOff() = runTest(testDispatcher) {
        val hub = AlertEventHub()
        coordinator(hub).onMotionDetected(AlertPayload("ev-1", 100_000L, 0.8f, tempFolder.newFile("a.jpg")))

        val event = hub.await(0, 0).single()
        assertEquals(AlertEventHub.TYPE_ALERT, event.type)
        assertEquals("Someone is at your door", event.title)
        assertEquals("ev-1", event.eventId)
        assertEquals(100_000L, event.time)
    }

    @Test
    fun nothingIsPublishedWhileDisarmedOrInCooldown() = runTest(testDispatcher) {
        val disarmed = AlertEventHub()
        coordinator(disarmed, SecuritySettings(isArmed = false))
            .onMotionDetected(AlertPayload("ev-1", 100_000L, 0.8f, tempFolder.newFile("d.jpg")))
        assertTrue(disarmed.await(0, 0).isEmpty())

        val hub = AlertEventHub()
        val c = coordinator(hub)
        c.onMotionDetected(AlertPayload("ev-2", 100_000L, 0.8f, tempFolder.newFile("c1.jpg")))
        c.onMotionDetected(AlertPayload("ev-3", 110_000L, 0.8f, tempFolder.newFile("c2.jpg")))
        assertEquals(1, hub.await(0, 0).size)
    }

    @Test
    fun aRecognisedPersonIsPublishedAsAnArrivalNamingThem() = runTest(testDispatcher) {
        val hub = AlertEventHub()
        coordinator(hub).onMotionDetected(known("ev-1", 100_000L))

        val event = hub.await(0, 0).single()
        assertEquals(AlertEventHub.TYPE_ARRIVAL, event.type)
        assertEquals("Asha is home", event.title)
    }

    @Test
    fun severalRecognisedPeopleReadNaturally() = runTest(testDispatcher) {
        val hub = AlertEventHub()
        coordinator(hub).onMotionDetected(known("ev-1", 100_000L, names = "Asha, Ravi"))
        assertEquals("Asha, Ravi are home", hub.await(0, 0).single().title)
    }

    @Test
    fun theSamePersonMovingAroundIsAnnouncedOnceThenAgainLater() = runTest(testDispatcher) {
        val hub = AlertEventHub()
        val c = coordinator(hub)

        c.onMotionDetected(known("ev-1", 100_000L))
        c.onMotionDetected(known("ev-2", 110_000L))
        c.onMotionDetected(known("ev-3", 400_000L))
        assertEquals(1, hub.await(0, 0).size)

        c.onMotionDetected(known("ev-4", 410_000L, names = "Ravi"))
        assertEquals(listOf("Asha is home", "Ravi is home"), hub.await(0, 0).map { it.title })

        c.onMotionDetected(known("ev-5", 410_000L + DefaultAlertCoordinator.ARRIVAL_ANNOUNCE_INTERVAL_MS + 5_000L))
        assertEquals(3, hub.await(0, 0).size)
    }

    @Test
    fun anArrivalDoesNotBlockAStrangerAlertRightAfter() = runTest(testDispatcher) {
        val hub = AlertEventHub()
        val c = coordinator(hub)

        c.onMotionDetected(known("ev-1", 100_000L))
        c.onMotionDetected(AlertPayload("ev-2", 105_000L, 0.9f, tempFolder.newFile("s.jpg")))

        assertEquals(
            listOf(AlertEventHub.TYPE_ARRIVAL, AlertEventHub.TYPE_ALERT),
            hub.await(0, 0).map { it.type }
        )
    }
}
