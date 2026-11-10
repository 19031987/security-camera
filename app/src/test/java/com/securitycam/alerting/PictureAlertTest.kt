package com.securitycam.alerting

import com.securitycam.alerting.AlertCoordinatorTest.FakeEmailDispatcher
import com.securitycam.alerting.AlertCoordinatorTest.FakeEventRepository
import com.securitycam.alerting.AlertCoordinatorTest.FakeSettingsRepository
import com.securitycam.core.alerting.AlertPayload
import com.securitycam.core.alerting.CloudUploader
import com.securitycam.core.alerting.DefaultAlertCoordinator
import com.securitycam.core.alerting.DispatchResult
import com.securitycam.core.alerting.PictureMessageDispatcher
import com.securitycam.core.alerting.SmsDispatcher
import com.securitycam.core.config.CooldownManager
import com.securitycam.core.config.SecuritySettings
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

/** Picture messages, the text fallback, Drive uploads and what the alert text says. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class PictureAlertTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    private class RecordingSms : SmsDispatcher {
        val texts = mutableListOf<String>()
        override suspend fun sendSms(phoneNumbers: List<String>, message: String): DispatchResult {
            texts.add(message)
            return DispatchResult(isSuccess = true, deliveredCount = phoneNumbers.size, failedCount = 0)
        }
    }

    private class FakePicture(private val succeed: Boolean = true, private val partial: Boolean = false) :
        PictureMessageDispatcher {
        val sent = mutableListOf<Triple<List<String>, String, File>>()
        override suspend fun send(phoneNumbers: List<String>, text: String, image: File): DispatchResult {
            sent.add(Triple(phoneNumbers, text, image))
            return when {
                partial -> DispatchResult(true, 1, phoneNumbers.size - 1, "one failed")
                succeed -> DispatchResult(true, phoneNumbers.size, 0)
                else -> DispatchResult(false, 0, phoneNumbers.size, "The mobile network refused the picture message")
            }
        }
    }

    private class FakeDrive(private val succeed: Boolean = true) : CloudUploader {
        val uploads = mutableListOf<Pair<File, String>>()
        override suspend fun upload(file: File, name: String): DispatchResult {
            uploads.add(file to name)
            return DispatchResult(succeed, if (succeed) 1 else 0, if (succeed) 0 else 1, if (succeed) null else "offline")
        }
    }

    private val smsSettings = SecuritySettings(
        isArmed = true, smsEnabled = true, phoneNumbers = listOf("+14155550123", "+919876543210")
    )

    private fun image(name: String = "shot.jpg"): File =
        tempFolder.newFile(name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private fun coordinator(
        settings: SecuritySettings,
        sms: RecordingSms = RecordingSms(),
        picture: PictureMessageDispatcher? = null,
        drive: CloudUploader? = null,
        events: FakeEventRepository = FakeEventRepository()
    ) = DefaultAlertCoordinator(
        FakeSettingsRepository(settings), events, CooldownManager(30),
        sms, FakeEmailDispatcher(), testDispatcher, null, picture, drive
    )

    @Test
    fun thePictureMessageGoesToEveryNumberAndNoPlainTextIsSent() = runTest(testDispatcher) {
        val sms = RecordingSms()
        val picture = FakePicture()
        val file = image()

        val summary = coordinator(smsSettings, sms, picture).onMotionDetected(
            AlertPayload("ev-1", 100_000L, 0.8f, file, detectedLabel = "Person")
        )

        assertTrue(summary.smsSentAsPicture)
        assertTrue(summary.smsResult!!.isSuccess)
        assertEquals(listOf("+14155550123", "+919876543210"), picture.sent.single().first)
        assertTrue(picture.sent.single().second.contains("Person detected"))
        assertEquals(file, picture.sent.single().third)
        assertTrue(sms.texts.isEmpty())
    }

    @Test
    fun ifThePictureMessageFailsTheTextStillGoesOut() = runTest(testDispatcher) {
        val sms = RecordingSms()

        val summary = coordinator(smsSettings, sms, FakePicture(succeed = false)).onMotionDetected(
            AlertPayload("ev-1", 100_000L, 0.8f, image(), detectedLabel = "Animal (dog)")
        )

        assertFalse(summary.smsSentAsPicture)
        assertTrue(summary.smsResult!!.isSuccess)
        assertEquals(1, sms.texts.size)
        assertTrue(sms.texts[0].contains("Animal (dog) detected"))
    }

    @Test
    fun ifOnlySomeNumbersGotThePictureEveryoneGetsTheText() = runTest(testDispatcher) {
        val sms = RecordingSms()

        val summary = coordinator(smsSettings, sms, FakePicture(partial = true)).onMotionDetected(
            AlertPayload("ev-1", 100_000L, 0.8f, image())
        )

        assertFalse(summary.smsSentAsPicture)
        assertEquals(1, sms.texts.size)
    }

    @Test
    fun pictureMessagesCanBeSwitchedOff() = runTest(testDispatcher) {
        val sms = RecordingSms()
        val picture = FakePicture()

        coordinator(smsSettings.copy(smsSendPicture = false), sms, picture).onMotionDetected(
            AlertPayload("ev-1", 100_000L, 0.8f, image())
        )

        assertTrue(picture.sent.isEmpty())
        assertEquals(1, sms.texts.size)
    }

    @Test
    fun anEmptyPictureFileSendsTextOnly() = runTest(testDispatcher) {
        val sms = RecordingSms()
        val picture = FakePicture()

        coordinator(smsSettings, sms, picture).onMotionDetected(
            AlertPayload("ev-1", 100_000L, 0.8f, tempFolder.newFile("empty.jpg"))
        )

        assertTrue(picture.sent.isEmpty())
        assertEquals(1, sms.texts.size)
    }

    @Test
    fun withoutALabelTheTextSaysMotionAsBefore() = runTest(testDispatcher) {
        val sms = RecordingSms()

        coordinator(smsSettings.copy(smsSendPicture = false), sms).onMotionDetected(
            AlertPayload("ev-1", 100_000L, 0.8f, image())
        )

        assertTrue(sms.texts[0].startsWith("[SECURITY CAM ALERT] Motion detected at "))
    }

    @Test
    fun theLabelIsStoredWithTheEvent() = runTest(testDispatcher) {
        val events = FakeEventRepository()

        coordinator(smsSettings, events = events).onMotionDetected(
            AlertPayload("ev-1", 100_000L, 0.8f, image(), detectedLabel = "Vehicle (car)")
        )

        assertEquals("Vehicle (car)", events.getEventById("ev-1")!!.detectedLabel)
    }

    @Test
    fun noPictureMessageWhenSmsIsOffDisarmedInCooldownOrForAKnownPerson() = runTest(testDispatcher) {
        val off = FakePicture()
        coordinator(smsSettings.copy(smsEnabled = false), picture = off)
            .onMotionDetected(AlertPayload("a", 100_000L, 0.8f, image("a.jpg")))
        assertTrue(off.sent.isEmpty())

        val disarmed = FakePicture()
        coordinator(smsSettings.copy(isArmed = false), picture = disarmed)
            .onMotionDetected(AlertPayload("b", 100_000L, 0.8f, image("b.jpg")))
        assertTrue(disarmed.sent.isEmpty())

        val cooling = FakePicture()
        val c = coordinator(smsSettings, picture = cooling)
        c.onMotionDetected(AlertPayload("c1", 100_000L, 0.8f, image("c1.jpg")))
        c.onMotionDetected(AlertPayload("c2", 110_000L, 0.8f, image("c2.jpg")))
        assertEquals(1, cooling.sent.size)

        val known = FakePicture()
        coordinator(smsSettings, picture = known).onMotionDetected(
            AlertPayload("d", 100_000L, 0.8f, image("d.jpg"), recognizedPeople = "Asha", suppressAlert = true)
        )
        assertTrue(known.sent.isEmpty())
    }

    @Test
    fun theAlertPictureIsUploadedToDriveWithADatedName() = runTest(testDispatcher) {
        val drive = FakeDrive()
        val file = image()

        val summary = coordinator(SecuritySettings(isArmed = true, driveEnabled = true), drive = drive)
            .onMotionDetected(AlertPayload("ev-1", 100_000L, 0.8f, file, detectedLabel = "Animal (dog)"))

        assertTrue(summary.driveResult!!.isSuccess)
        assertEquals(file, drive.uploads.single().first)
        val name = drive.uploads.single().second
        assertTrue(name, Regex("alert_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}_animal-dog\\.jpg").matches(name))
    }

    @Test
    fun driveUploadHappensEvenWithSmsAndEmailOff() = runTest(testDispatcher) {
        val drive = FakeDrive()
        coordinator(SecuritySettings(isArmed = true, driveEnabled = true), drive = drive)
            .onMotionDetected(AlertPayload("ev-1", 100_000L, 0.8f, image()))
        assertEquals(1, drive.uploads.size)
        assertTrue(drive.uploads[0].second.endsWith("_motion.jpg"))
    }

    @Test
    fun noDriveUploadWhenOffDisarmedOrInCooldown() = runTest(testDispatcher) {
        val off = FakeDrive()
        val summary = coordinator(SecuritySettings(isArmed = true, driveEnabled = false), drive = off)
            .onMotionDetected(AlertPayload("a", 100_000L, 0.8f, image("a.jpg")))
        assertTrue(off.uploads.isEmpty())
        assertNull(summary.driveResult)

        val disarmed = FakeDrive()
        coordinator(SecuritySettings(isArmed = false, driveEnabled = true), drive = disarmed)
            .onMotionDetected(AlertPayload("b", 100_000L, 0.8f, image("b.jpg")))
        assertTrue(disarmed.uploads.isEmpty())

        val cooling = FakeDrive()
        val c = coordinator(SecuritySettings(isArmed = true, driveEnabled = true), drive = cooling)
        c.onMotionDetected(AlertPayload("c1", 100_000L, 0.8f, image("c1.jpg")))
        c.onMotionDetected(AlertPayload("c2", 110_000L, 0.8f, image("c2.jpg")))
        assertEquals(1, cooling.uploads.size)
    }

    @Test
    fun aFailedDriveUploadDoesNotStopTheSmsAlert() = runTest(testDispatcher) {
        val sms = RecordingSms()
        val summary = coordinator(
            smsSettings.copy(driveEnabled = true, smsSendPicture = false), sms, drive = FakeDrive(succeed = false)
        ).onMotionDetected(AlertPayload("ev-1", 100_000L, 0.8f, image()))

        assertFalse(summary.driveResult!!.isSuccess)
        assertTrue(summary.smsResult!!.isSuccess)
        assertEquals(1, sms.texts.size)
    }
}
