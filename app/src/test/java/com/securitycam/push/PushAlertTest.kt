package com.securitycam.push

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.securitycam.SecurityCamApp
import com.securitycam.alerting.AlertCoordinatorTest.FakeEmailDispatcher
import com.securitycam.alerting.AlertCoordinatorTest.FakeEventRepository
import com.securitycam.alerting.AlertCoordinatorTest.FakeSettingsRepository
import com.securitycam.alerting.AlertCoordinatorTest.FakeSmsDispatcher
import com.securitycam.core.alerting.AlertPayload
import com.securitycam.core.alerting.AlertPolicy
import com.securitycam.core.alerting.DefaultAlertCoordinator
import com.securitycam.core.alerting.DispatchResult
import com.securitycam.core.config.CooldownManager
import com.securitycam.core.config.SecuritySettings
import com.securitycam.core.config.SharedPreferencesSettingsRepository
import com.securitycam.core.push.FcmPushNotifier
import com.securitycam.core.push.PushMessage
import com.securitycam.core.push.PushNotifier
import com.securitycam.service.AlertMessagingService
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class PushAlertTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    private class FakePush : PushNotifier {
        data class Sent(val type: String, val title: String, val message: String, val hasImage: Boolean)

        val sent = mutableListOf<Sent>()
        override suspend fun notify(type: String, title: String, message: String, time: Long, image: File?): DispatchResult {
            sent.add(Sent(type, title, message, image != null))
            return DispatchResult(isSuccess = true, deliveredCount = 1, failedCount = 0)
        }
    }

    private val pushSettings = SecuritySettings(isArmed = true, pushEnabled = true)

    private fun coordinator(settings: SecuritySettings, push: FakePush) = DefaultAlertCoordinator(
        FakeSettingsRepository(settings), FakeEventRepository(), CooldownManager(30),
        FakeSmsDispatcher(), FakeEmailDispatcher(), testDispatcher, null, null, null, push
    )

    private fun file(name: String) = tempFolder.newFile(name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private fun known(id: String, at: Long, names: String = "Asha") = AlertPayload(
        id, at, 0.8f, file("$id.jpg"), recognizedPeople = names, suppressAlert = true
    )

    // What the camera sends

    @Test
    fun aPersonSendsAnAlertPushNamingWhatWasSeen() = runTest(testDispatcher) {
        val push = FakePush()
        val summary = coordinator(pushSettings, push).onMotionDetected(
            AlertPayload("ev-1", 100_000L, 0.8f, file("a.jpg"), detectedLabel = "Person", detectedKind = AlertPolicy.KIND_PERSON)
        )

        assertTrue(summary.pushResult!!.isSuccess)
        assertEquals(FakePush.Sent(PushMessage.TYPE_ALERT, "Person at your door", push.sent[0].message, true), push.sent.single())
        assertTrue(push.sent[0].message.startsWith("Detected at "))
    }

    @Test
    fun anAnimalIsNamedAndUnlabelledMotionReadsAsMovement() = runTest(testDispatcher) {
        val animal = FakePush()
        coordinator(pushSettings, animal).onMotionDetected(
            AlertPayload("ev-1", 100_000L, 0.8f, file("a.jpg"), detectedLabel = "Animal (dog)", detectedKind = AlertPolicy.KIND_ANIMAL)
        )
        assertEquals("Animal (dog) at your door", animal.sent.single().title)

        val unknown = FakePush()
        coordinator(pushSettings, unknown).onMotionDetected(AlertPayload("ev-2", 100_000L, 0.8f, file("b.jpg")))
        assertEquals("Movement at your door", unknown.sent.single().title)
    }

    @Test
    fun noPushWhenOffDisarmedInHomeModeInCooldownOrForAnUnselectedKind() = runTest(testDispatcher) {
        fun person(id: String, at: Long = 100_000L) = AlertPayload(
            id, at, 0.8f, file("$id.jpg"), detectedLabel = "Person", detectedKind = AlertPolicy.KIND_PERSON
        )
        for ((name, settings) in listOf(
            "off" to pushSettings.copy(pushEnabled = false),
            "disarmed" to pushSettings.copy(isArmed = false),
            "home" to pushSettings.copy(alertMode = AlertPolicy.MODE_HOME)
        )) {
            val push = FakePush()
            val summary = coordinator(settings, push).onMotionDetected(person(name))
            assertTrue(name, push.sent.isEmpty())
            assertNull(name, summary.pushResult)
        }

        val cooling = FakePush()
        val c = coordinator(pushSettings, cooling)
        c.onMotionDetected(person("c1", 100_000L))
        c.onMotionDetected(person("c2", 110_000L))
        assertEquals(1, cooling.sent.size)

        val vehicle = FakePush()
        coordinator(pushSettings, vehicle).onMotionDetected(
            AlertPayload("v", 100_000L, 0.8f, file("v.jpg"), detectedLabel = "Vehicle (car)", detectedKind = AlertPolicy.KIND_VEHICLE)
        )
        assertTrue(vehicle.sent.isEmpty())
    }

    @Test
    fun aRecognisedPersonSendsOneQuietArrivalThenStaysSilentForAWhile() = runTest(testDispatcher) {
        val push = FakePush()
        val c = coordinator(pushSettings, push)

        c.onMotionDetected(known("ev-1", 100_000L))
        c.onMotionDetected(known("ev-2", 110_000L))
        c.onMotionDetected(known("ev-3", 400_000L))
        assertEquals(listOf("Asha is home"), push.sent.map { it.title })
        assertEquals(PushMessage.TYPE_ARRIVAL, push.sent[0].type)

        c.onMotionDetected(known("ev-4", 410_000L, names = "Asha, Ravi"))
        assertEquals("Asha, Ravi are home", push.sent[1].title)

        c.onMotionDetected(known("ev-5", 410_000L + DefaultAlertCoordinator.ARRIVAL_PUSH_INTERVAL_MS + 5_000L, names = "Asha, Ravi"))
        assertEquals(3, push.sent.size)
    }

    @Test
    fun arrivalsAreNotAnnouncedWhenSwitchedOffOrInHomeMode() = runTest(testDispatcher) {
        val off = FakePush()
        coordinator(pushSettings.copy(pushNotifyArrivals = false), off).onMotionDetected(known("a", 100_000L))
        assertTrue(off.sent.isEmpty())

        val home = FakePush()
        coordinator(pushSettings.copy(alertMode = AlertPolicy.MODE_HOME), home).onMotionDetected(known("b", 100_000L))
        assertTrue(home.sent.isEmpty())
    }

    @Test
    fun anArrivalDoesNotDelayAStrangerAlert() = runTest(testDispatcher) {
        val push = FakePush()
        val c = coordinator(pushSettings, push)

        c.onMotionDetected(known("ev-1", 100_000L))
        c.onMotionDetected(
            AlertPayload("ev-2", 105_000L, 0.9f, file("s.jpg"), detectedLabel = "Person", detectedKind = AlertPolicy.KIND_PERSON)
        )

        assertEquals(listOf(PushMessage.TYPE_ARRIVAL, PushMessage.TYPE_ALERT), push.sent.map { it.type })
    }

    @Test
    fun theTestAlertSendsAQuietTestPush() = runTest(testDispatcher) {
        val push = FakePush()
        val summary = coordinator(pushSettings, push).sendTestAlert()

        assertTrue(summary.pushResult!!.isSuccess)
        assertEquals(PushMessage.TYPE_TEST, push.sent.single().type)
        assertFalse(push.sent.single().hasImage)
    }

    @Test
    fun theThumbnailOfARealPictureFitsInAPushMessage() {
        // A busy 640 x 480 picture, which compresses badly: the worst case for size.
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(640 * 480) { i -> (0xFF000000.toInt()) or ((i * 2654435761L).toInt() and 0xFFFFFF) }
        bitmap.setPixels(pixels, 0, 640, 0, 0, 640, 480)
        val file = tempFolder.newFile("busy.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }

        val thumbnail = FcmPushNotifier.makeThumbnail(file)

        // Either it fits, or it is left out; it is never sent oversized.
        assertTrue(thumbnail == null || thumbnail.size <= PushMessage.MAX_THUMBNAIL_BYTES)
        assertNull(FcmPushNotifier.makeThumbnail(tempFolder.newFile("empty.jpg")))
        assertNull(FcmPushNotifier.makeThumbnail(File(tempFolder.root, "missing.jpg")))
    }

    // What a receiver phone shows

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun notifications() =
        shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications

    @Test
    fun aReceivedAlertBecomesANotificationAndIsRemembered() {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val data = PushMessage.build(PushMessage.TYPE_ALERT, "Person at your door", "Detected at 14:22.", 1234L, null)

        assertTrue(AlertMessagingService.show(context, data))

        val notification = notifications().single()
        assertEquals("Person at your door", shadowOf(notification).contentTitle)
        assertEquals("Detected at 14:22.", shadowOf(notification).contentText)
        assertEquals(SecurityCamApp.CHANNEL_ALERTS, notification.channelId)
        assertEquals("Person at your door", AlertMessagingService.lastAlert.value!!.title)

        // Survives the app being restarted.
        AlertMessagingService.loadLastAlert(context)
        assertEquals(1234L, AlertMessagingService.lastAlert.value!!.time)
    }

    @Test
    fun arrivalsUseTheQuietChannelAndSeveralAlertsDoNotReplaceEachOther() {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        AlertMessagingService.show(context, PushMessage.build(PushMessage.TYPE_ARRIVAL, "Asha is home", "", 1L, null))
        AlertMessagingService.show(context, PushMessage.build(PushMessage.TYPE_ALERT, "Person at your door", "", 2L, null))

        val all = notifications()
        assertEquals(2, all.size)
        assertNotNull(all.firstOrNull { it.channelId == AlertMessagingService.CHANNEL_ARRIVALS })
    }

    @Test
    fun receivedAlertsAreListedNewestFirstWithTheirPictures() {
        val store = SecurityCamApp.getInstance().receivedAlerts
        store.clear()
        val picture = byteArrayOf(5, 6, 7)

        AlertMessagingService.show(context, PushMessage.build(PushMessage.TYPE_ALERT, "Person at your door", "Detected.", 10L, picture))
        AlertMessagingService.show(context, PushMessage.build(PushMessage.TYPE_ARRIVAL, "Asha is home", "", 20L, null))

        val listed = store.current()
        assertEquals(listOf("Asha is home", "Person at your door"), listed.map { it.title })
        assertEquals(10L, listed[1].time)
        assertTrue(picture.contentEquals(store.pictureOf(listed[1])!!.readBytes()))
        assertNull(store.pictureOf(listed[0]))
    }

    @Test
    fun anAlertIsListedEvenWhenNotificationsAreNotAllowed() {
        val store = SecurityCamApp.getInstance().receivedAlerts
        store.clear()
        shadowOf(context as Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val shown = AlertMessagingService.show(context, PushMessage.build(PushMessage.TYPE_ALERT, "Person at your door", "", 1L, null))

        assertFalse(shown)
        assertEquals("Person at your door", store.current().single().title)
    }

    @Test
    fun aMessageWithNoTitleIsDropped() {
        assertFalse(AlertMessagingService.show(context, mapOf(PushMessage.KEY_MESSAGE to "x")))
        assertTrue(notifications().isEmpty())
    }

    // Secrets

    @Test
    fun keyFileAndLinkCodesAreKeptOutOfPlainPreferences() {
        val repo = SharedPreferencesSettingsRepository.create(context)
        repo.updateSettings(
            repo.getSettings().copy(
                pushEnabled = true,
                pushLinkCode = "LINKCODEabcdefghijkmnpqr",
                pushServiceAccountJson = """{"private_key":"SECRETKEYMATERIAL"}""",
                receiverLinkCode = "RECEIVERcodeabcdefghijkm"
            )
        )

        val reloaded = SharedPreferencesSettingsRepository.create(context).getSettings()
        assertEquals("LINKCODEabcdefghijkmnpqr", reloaded.pushLinkCode)
        assertTrue(reloaded.pushServiceAccountJson.contains("SECRETKEYMATERIAL"))

        val plain = context.getSharedPreferences(
            SharedPreferencesSettingsRepository.PREF_FILE_NAME, Context.MODE_PRIVATE
        ).all.toString()
        for (secret in listOf("LINKCODEabcdefghijkmnpqr", "SECRETKEYMATERIAL", "RECEIVERcodeabcdefghijkm")) {
            assertFalse("secret found in plain preferences", plain.contains(secret))
        }
    }
}
