package com.securitycam.alerting

import com.securitycam.alerting.AlertCoordinatorTest.FakeEmailDispatcher
import com.securitycam.alerting.AlertCoordinatorTest.FakeEventRepository
import com.securitycam.alerting.AlertCoordinatorTest.FakeSettingsRepository
import com.securitycam.alerting.AlertCoordinatorTest.FakeSmsDispatcher
import com.securitycam.core.alerting.AlertPayload
import com.securitycam.core.alerting.CallMeBotWhatsAppDispatcher
import com.securitycam.core.alerting.DefaultAlertCoordinator
import com.securitycam.core.alerting.DispatchResult
import com.securitycam.core.alerting.WhatsAppContact
import com.securitycam.core.alerting.WhatsAppDispatcher
import com.securitycam.core.config.CooldownManager
import com.securitycam.core.config.SecuritySettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class WhatsAppDispatcherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer

    private val asha = WhatsAppContact("+14155550123", "123456")
    private val ravi = WhatsAppContact("+919876543210", "654321")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun dispatcher() = CallMeBotWhatsAppDispatcher(
        OkHttpClient(), server.url("/whatsapp.php").toString(), testDispatcher
    )

    @Test
    fun eachContactGetsARequestWithTheirNumberKeyAndTheText() = runTest(testDispatcher) {
        server.enqueue(MockResponse().setBody("Message queued. You will receive it in a few seconds."))
        server.enqueue(MockResponse().setBody("Message queued. You will receive it in a few seconds."))

        val result = dispatcher().send(listOf(asha, ravi), "Someone is at your door & more")

        assertTrue(result.isSuccess)
        assertEquals(2, result.deliveredCount)
        assertEquals(0, result.failedCount)

        val firstRequest = server.takeRequest()
        assertEquals("GET", firstRequest.method)
        val first = firstRequest.requestUrl!!
        assertEquals("+14155550123", first.queryParameter("phone"))
        assertEquals("123456", first.queryParameter("apikey"))
        // Special characters survive URL encoding.
        assertEquals("Someone is at your door & more", first.queryParameter("text"))

        val second = server.takeRequest().requestUrl!!
        assertEquals("+919876543210", second.queryParameter("phone"))
        assertEquals("654321", second.queryParameter("apikey"))
    }

    @Test
    fun oneFailingContactDoesNotStopTheOthers() = runTest(testDispatcher) {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("Message queued."))

        val result = dispatcher().send(listOf(asha, ravi), "Alert")

        assertTrue(result.isSuccess)
        assertEquals(1, result.deliveredCount)
        assertEquals(1, result.failedCount)
        assertTrue(result.errorMessage!!.contains("500"))
    }

    @Test
    fun aRejectedKeyIsAFailureEvenWithStatus200() = runTest(testDispatcher) {
        server.enqueue(MockResponse().setBody("APIKey is invalid."))

        val result = dispatcher().send(listOf(asha), "Alert")

        assertFalse(result.isSuccess)
        assertEquals(1, result.failedCount)
    }

    @Test
    fun errorMessagesDoNotExposeTheFullPhoneNumber() = runTest(testDispatcher) {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = dispatcher().send(listOf(asha), "Alert")

        assertFalse(result.errorMessage!!.contains("+14155550123"))
        assertTrue(result.errorMessage!!.contains("0123"))
    }

    @Test
    fun unreachableServiceIsAFailureNotACrash() = runTest(testDispatcher) {
        val d = dispatcher()
        server.shutdown()

        val result = d.send(listOf(asha), "Alert")

        assertFalse(result.isSuccess)
        assertEquals(1, result.failedCount)
    }

    @Test
    fun invalidContactsAreSkippedWithoutARequest() = runTest(testDispatcher) {
        val result = dispatcher().send(
            listOf(WhatsAppContact("12345", "123456"), WhatsAppContact("+14155550123", "")), "Alert"
        )

        assertFalse(result.isSuccess)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun contactRules() {
        assertTrue(CallMeBotWhatsAppDispatcher.isValidContact(asha))
        assertFalse(CallMeBotWhatsAppDispatcher.isValidContact(WhatsAppContact("4155550123", "123456")))
        assertFalse(CallMeBotWhatsAppDispatcher.isValidContact(WhatsAppContact("+14155550123", "12")))
        assertFalse(CallMeBotWhatsAppDispatcher.isValidContact(WhatsAppContact("+14155550123", "abc&x=1")))
        assertEquals("********0123", CallMeBotWhatsAppDispatcher.maskPhone("+14155550123"))
    }

    // Coordinator rules

    private class FakeWhatsApp : WhatsAppDispatcher {
        val sent = mutableListOf<Pair<List<WhatsAppContact>, String>>()
        override suspend fun send(contacts: List<WhatsAppContact>, message: String): DispatchResult {
            sent.add(contacts to message)
            return DispatchResult(isSuccess = true, deliveredCount = contacts.size, failedCount = 0)
        }
    }

    private val whatsappSettings = SecuritySettings(
        isArmed = true, whatsappEnabled = true, whatsappContacts = listOf(asha, ravi)
    )

    private fun coordinator(settings: SecuritySettings, whatsapp: FakeWhatsApp) = DefaultAlertCoordinator(
        FakeSettingsRepository(settings), FakeEventRepository(), CooldownManager(30),
        FakeSmsDispatcher(), FakeEmailDispatcher(), testDispatcher, null, null, whatsapp
    )

    @Test
    fun anAlertIsSentToAllWhatsAppContacts() = runTest(testDispatcher) {
        val whatsapp = FakeWhatsApp()
        val summary = coordinator(whatsappSettings, whatsapp).onMotionDetected(
            AlertPayload("ev-1", 100_000L, 0.8f, tempFolder.newFile("a.jpg"))
        )

        assertEquals(2, summary.whatsappResult!!.deliveredCount)
        assertEquals(listOf(asha, ravi), whatsapp.sent.single().first)
        assertTrue(whatsapp.sent.single().second.contains("someone is at your door"))
    }

    @Test
    fun noWhatsAppForARecognisedPersonWhenOffDisarmedOrInCooldown() = runTest(testDispatcher) {
        val known = FakeWhatsApp()
        coordinator(whatsappSettings, known).onMotionDetected(
            AlertPayload("ev-1", 100_000L, 0.8f, tempFolder.newFile("k.jpg"), recognizedPeople = "Asha", suppressAlert = true)
        )
        assertTrue(known.sent.isEmpty())

        val off = FakeWhatsApp()
        val summary = coordinator(whatsappSettings.copy(whatsappEnabled = false), off)
            .onMotionDetected(AlertPayload("ev-2", 100_000L, 0.8f, tempFolder.newFile("o.jpg")))
        assertTrue(off.sent.isEmpty())
        assertNull(summary.whatsappResult)

        val disarmed = FakeWhatsApp()
        coordinator(whatsappSettings.copy(isArmed = false), disarmed)
            .onMotionDetected(AlertPayload("ev-3", 100_000L, 0.8f, tempFolder.newFile("d.jpg")))
        assertTrue(disarmed.sent.isEmpty())

        val cooling = FakeWhatsApp()
        val c = coordinator(whatsappSettings, cooling)
        c.onMotionDetected(AlertPayload("ev-4", 100_000L, 0.8f, tempFolder.newFile("c1.jpg")))
        c.onMotionDetected(AlertPayload("ev-5", 110_000L, 0.8f, tempFolder.newFile("c2.jpg")))
        assertEquals(1, cooling.sent.size)
    }

    @Test
    fun testAlertIncludesWhatsApp() = runTest(testDispatcher) {
        val whatsapp = FakeWhatsApp()
        val summary = coordinator(whatsappSettings, whatsapp).sendTestAlert()

        assertTrue(summary.whatsappResult!!.isSuccess)
        assertTrue(whatsapp.sent.single().second.contains("test"))
    }
}
