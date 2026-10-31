package com.securitycam.alerting

import com.securitycam.core.alerting.AndroidSmsDispatcher
import com.securitycam.core.alerting.SmsTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmsDispatcherTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    class FakeSmsTransport(
        private val supported: Boolean = true,
        private val permitted: Boolean = true,
        private val failDestinations: Set<String> = emptySet()
    ) : SmsTransport {
        val sentHistory = mutableListOf<Pair<String, String>>()

        override fun isTelephonySupported(): Boolean = supported
        override fun hasSmsPermission(): Boolean = permitted

        override fun sendMultipart(destination: String, text: String): Boolean {
            if (failDestinations.contains(destination)) return false
            sentHistory.add(destination to text)
            return true
        }
    }

    @Test
    fun testSuccessfulSmsDispatch() = runTest(testDispatcher) {
        val fakeTransport = FakeSmsTransport()
        val dispatcher = AndroidSmsDispatcher(
            transport = fakeTransport,
            ioDispatcher = testDispatcher
        )

        val phones = listOf("+12025550123", "+447911123456")
        val result = dispatcher.sendSms(phones, "Intruder detected!")

        assertTrue(result.isSuccess)
        assertEquals(2, result.deliveredCount)
        assertEquals(0, result.failedCount)
        assertEquals(2, fakeTransport.sentHistory.size)
    }

    @Test
    fun testPartialSmsFailureTracking() = runTest(testDispatcher) {
        val fakeTransport = FakeSmsTransport(failDestinations = setOf("+447911123456"))
        val dispatcher = AndroidSmsDispatcher(
            transport = fakeTransport,
            ioDispatcher = testDispatcher
        )

        val phones = listOf("+12025550123", "+447911123456")
        val result = dispatcher.sendSms(phones, "Intruder detected!")

        assertFalse(result.isSuccess)
        assertEquals(1, result.deliveredCount)
        assertEquals(1, result.failedCount)
        assertTrue(result.errorMessage!!.contains("+447911123456"))
    }

    @Test
    fun testEmptyPhoneListHandledGracefully() = runTest(testDispatcher) {
        val fakeTransport = FakeSmsTransport()
        val dispatcher = AndroidSmsDispatcher(transport = fakeTransport, ioDispatcher = testDispatcher)

        val result = dispatcher.sendSms(emptyList(), "Test")
        assertFalse(result.isSuccess)
        assertEquals(0, result.deliveredCount)
    }

    @Test
    fun testTelephonyUnavailableWithoutWebhookFailsCleanly() = runTest(testDispatcher) {
        val fakeTransport = FakeSmsTransport(supported = false)
        val dispatcher = AndroidSmsDispatcher(
            transport = fakeTransport,
            webhookUrlProvider = { null },
            ioDispatcher = testDispatcher
        )

        val result = dispatcher.sendSms(listOf("+12025550123"), "Test")
        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("Telephony unavailable"))
    }
}
