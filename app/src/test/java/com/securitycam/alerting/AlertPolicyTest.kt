package com.securitycam.alerting

import com.securitycam.alerting.AlertCoordinatorTest.FakeEmailDispatcher
import com.securitycam.alerting.AlertCoordinatorTest.FakeEventRepository
import com.securitycam.alerting.AlertCoordinatorTest.FakeSettingsRepository
import com.securitycam.alerting.AlertCoordinatorTest.FakeSmsDispatcher
import com.securitycam.core.alerting.AlertPayload
import com.securitycam.core.alerting.AlertPolicy
import com.securitycam.core.alerting.AlertPolicy.Verdict
import com.securitycam.core.alerting.DefaultAlertCoordinator
import com.securitycam.core.config.CooldownManager
import com.securitycam.core.config.SecuritySettings
import com.securitycam.core.recognition.SceneLabel
import com.securitycam.core.storage.MotionEventEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class AlertPolicyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val defaults = SecuritySettings()

    @Test
    fun byDefaultPeopleAndAnimalsAlertButVehiclesAndOtherMovementDoNot() {
        assertEquals(Verdict.ALERT, AlertPolicy.decide(defaults, AlertPolicy.KIND_PERSON, false))
        assertEquals(Verdict.ALERT, AlertPolicy.decide(defaults, AlertPolicy.KIND_ANIMAL, false))
        assertEquals(Verdict.QUIET_NOT_SELECTED, AlertPolicy.decide(defaults, AlertPolicy.KIND_VEHICLE, false))
        assertEquals(Verdict.QUIET_NOT_SELECTED, AlertPolicy.decide(defaults, AlertPolicy.KIND_OTHER, false))
    }

    @Test
    fun eachKindFollowsItsOwnSwitch() {
        val reversed = defaults.copy(
            notifyPerson = false, notifyAnimal = false, notifyVehicle = true, notifyOther = true
        )
        assertEquals(Verdict.QUIET_NOT_SELECTED, AlertPolicy.decide(reversed, AlertPolicy.KIND_PERSON, false))
        assertEquals(Verdict.QUIET_NOT_SELECTED, AlertPolicy.decide(reversed, AlertPolicy.KIND_ANIMAL, false))
        assertEquals(Verdict.ALERT, AlertPolicy.decide(reversed, AlertPolicy.KIND_VEHICLE, false))
        assertEquals(Verdict.ALERT, AlertPolicy.decide(reversed, AlertPolicy.KIND_OTHER, false))
    }

    @Test
    fun ifTheModelCouldNotRunTheCameraStillAlerts() {
        assertEquals(Verdict.ALERT, AlertPolicy.decide(defaults, null, false))
        assertEquals(Verdict.ALERT, AlertPolicy.decide(defaults.copy(notifyPerson = false, notifyAnimal = false), null, false))
    }

    @Test
    fun inTheDarkUnidentifiedMovementAlertsBecauseTheModelMayNotSee() {
        assertEquals(Verdict.ALERT, AlertPolicy.decide(defaults, AlertPolicy.KIND_OTHER, true))
        // Something it did identify in the dark still follows its switch.
        assertEquals(Verdict.QUIET_NOT_SELECTED, AlertPolicy.decide(defaults, AlertPolicy.KIND_VEHICLE, true))
    }

    @Test
    fun homeModeIsAlwaysQuiet() {
        val home = defaults.copy(alertMode = AlertPolicy.MODE_HOME, notifyOther = true)
        for (kind in listOf(AlertPolicy.KIND_PERSON, AlertPolicy.KIND_ANIMAL, AlertPolicy.KIND_OTHER, null)) {
            assertEquals(Verdict.QUIET_HOME_MODE, AlertPolicy.decide(home, kind, true))
        }
    }

    @Test
    fun theModelsCategoryNamesMatchThePolicy() {
        assertEquals(AlertPolicy.KIND_PERSON, SceneLabel.Kind.PERSON.name)
        assertEquals(AlertPolicy.KIND_ANIMAL, SceneLabel.Kind.ANIMAL.name)
        assertEquals(AlertPolicy.KIND_VEHICLE, SceneLabel.Kind.VEHICLE.name)
        assertEquals(AlertPolicy.KIND_OTHER, SceneLabel.Kind.OTHER.name)
    }

    // Through the coordinator

    private val alerting = SecuritySettings(
        isArmed = true, smsEnabled = true, phoneNumbers = listOf("+14155550123"),
        emailEnabled = true, emailAddresses = listOf("owner@example.com")
    )

    private class Rig(settings: SecuritySettings, dispatcher: kotlinx.coroutines.test.TestDispatcher) {
        val events = FakeEventRepository()
        val sms = FakeSmsDispatcher()
        val email = FakeEmailDispatcher()
        val coordinator = DefaultAlertCoordinator(
            FakeSettingsRepository(settings), events, CooldownManager(30), sms, email, dispatcher
        )
    }

    private fun payload(id: String, at: Long, kind: String?, lowLight: Boolean = false) = AlertPayload(
        id, at, 0.8f, tempFolder.newFile("$id.jpg"), isLowLight = lowLight, detectedKind = kind
    )

    @Test
    fun aVehicleIsRecordedButNothingIsSent() = runTest(testDispatcher) {
        val rig = Rig(alerting, testDispatcher)

        val summary = rig.coordinator.onMotionDetected(payload("ev-car", 100_000L, AlertPolicy.KIND_VEHICLE))

        assertTrue(summary.isQuietByRules)
        assertTrue(summary.localEventSaved)
        assertEquals(0, rig.sms.sendCount)
        assertEquals(0, rig.email.sendCount)
        assertEquals(MotionEventEntity.STATUS_QUIET, rig.events.getEventById("ev-car")!!.smsStatus)
    }

    @Test
    fun aQuietEventDoesNotUseUpTheCooldownForAPerson() = runTest(testDispatcher) {
        val rig = Rig(alerting, testDispatcher)

        rig.coordinator.onMotionDetected(payload("ev-car", 100_000L, AlertPolicy.KIND_VEHICLE))
        val summary = rig.coordinator.onMotionDetected(payload("ev-person", 105_000L, AlertPolicy.KIND_PERSON))

        assertFalse(summary.isQuietByRules)
        assertFalse(summary.isSuppressedByCooldown)
        assertEquals(1, rig.sms.sendCount)
        assertEquals(1, rig.email.sendCount)
    }

    @Test
    fun homeModeRecordsAPersonWithoutSending() = runTest(testDispatcher) {
        val rig = Rig(alerting.copy(alertMode = AlertPolicy.MODE_HOME), testDispatcher)

        val summary = rig.coordinator.onMotionDetected(payload("ev-1", 100_000L, AlertPolicy.KIND_PERSON))

        assertTrue(summary.isQuietByRules)
        assertEquals(0, rig.sms.sendCount)
        assertEquals(1, rig.events.events.size)
    }

    @Test
    fun anEventWithNoCategoryAlertsAsBefore() = runTest(testDispatcher) {
        val rig = Rig(alerting, testDispatcher)

        val summary = rig.coordinator.onMotionDetected(payload("ev-1", 100_000L, null))

        assertFalse(summary.isQuietByRules)
        assertEquals(1, rig.sms.sendCount)
    }
}
