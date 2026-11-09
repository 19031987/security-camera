package com.securitycam.alerting

import com.securitycam.core.alerting.NtfyPushDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class NtfyPushDispatcherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcherThread = UnconfinedTestDispatcher()
    private val topic = "cam-0123456789abcdefABCDEF"
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Points the dispatcher at the local test server (which is plain http). */
    private fun dispatcher(topicName: String = topic): NtfyPushDispatcher {
        val base = server.url("/").toString().trimEnd('/')
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val local = original.url.newBuilder()
                    .scheme("http").host(server.hostName).port(server.port).build()
                chain.proceed(original.newBuilder().url(local).build())
            }
            .build()
        assertTrue(base.startsWith("http://"))
        return NtfyPushDispatcher({ NtfyPushDispatcher.Config("https://push.example", topicName) }, client, dispatcherThread)
    }

    @Test
    fun textOnlyNotificationIsPostedToTheTopic() = runTest(dispatcherThread) {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = dispatcher().sendPush("Security Camera test", "It works", null, urgent = false)

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/$topic", request.path)
        assertEquals("Security Camera test", request.getHeader("Title"))
        assertEquals("default", request.getHeader("Priority"))
        assertEquals("It works", request.body.readUtf8())
    }

    @Test
    fun alertWithScreenshotUploadsTheImageAndMarksItUrgent() = runTest(dispatcherThread) {
        server.enqueue(MockResponse().setResponseCode(200))
        val image = tempFolder.newFile("shot.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }

        val result = dispatcher().sendPush("Someone is at your door", "Motion detected", image, urgent = true)

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("urgent", request.getHeader("Priority"))
        assertEquals("Motion detected", request.getHeader("Message"))
        assertEquals("camera.jpg", request.getHeader("Filename"))
        assertTrue(byteArrayOf(1, 2, 3, 4, 5).contentEquals(request.body.readByteArray()))
    }

    @Test
    fun aMissingOrEmptyScreenshotFallsBackToText() = runTest(dispatcherThread) {
        server.enqueue(MockResponse().setResponseCode(200))
        val empty = tempFolder.newFile("empty.jpg")

        dispatcher().sendPush("Title", "Body", empty, urgent = true)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("Body", request.body.readUtf8())
    }

    @Test
    fun serverErrorIsReportedAsFailure() = runTest(dispatcherThread) {
        server.enqueue(MockResponse().setResponseCode(429))

        val result = dispatcher().sendPush("Title", "Body", null, urgent = true)

        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("429"))
    }

    @Test
    fun unreachableServerIsReportedAsFailureNotACrash() = runTest(dispatcherThread) {
        val d = dispatcher()
        server.shutdown()

        val result = d.sendPush("Title", "Body", null, urgent = true)

        assertFalse(result.isSuccess)
    }

    @Test
    fun nothingIsSentWithoutAValidTopic() = runTest(dispatcherThread) {
        val result = dispatcher(topicName = "short").sendPush("Title", "Body", null, urgent = true)

        assertFalse(result.isSuccess)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun plainHttpServersAreRefused() = runTest(dispatcherThread) {
        val d = NtfyPushDispatcher({ NtfyPushDispatcher.Config("http://push.example", topic) }, OkHttpClient(), dispatcherThread)

        val result = d.sendPush("Title", "Body", null, urgent = true)

        assertFalse(result.isSuccess)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun namesInOtherScriptsAreEncodedForTheHeader() = runTest(dispatcherThread) {
        server.enqueue(MockResponse().setResponseCode(200))

        dispatcher().sendPush("आशा is home", "Recognised", null, urgent = false)

        val title = server.takeRequest().getHeader("Title")!!
        assertTrue(title.startsWith("=?UTF-8?B?") && title.endsWith("?="))
        val decoded = String(Base64.getDecoder().decode(title.removePrefix("=?UTF-8?B?").removeSuffix("?=")))
        assertEquals("आशा is home", decoded)
    }

    @Test
    fun lineBreaksCannotInjectExtraHeaders() {
        assertEquals("Asha Priority: max", NtfyPushDispatcher.headerValue("Asha\r\nPriority: max"))
    }

    @Test
    fun topicRules() {
        assertTrue(NtfyPushDispatcher.isValidTopic("abcdefghijklmnop"))
        assertTrue(NtfyPushDispatcher.isValidTopic("cam-ABC_def-0123456789"))
        assertFalse(NtfyPushDispatcher.isValidTopic(""))
        assertFalse(NtfyPushDispatcher.isValidTopic("tooshort"))
        assertFalse(NtfyPushDispatcher.isValidTopic("has spaces in it 1234"))
        assertFalse(NtfyPushDispatcher.isValidTopic("slash/inside/topic/1234"))
        assertFalse(NtfyPushDispatcher.isValidTopic("a".repeat(65)))
    }

    @Test
    fun generatedTopicsAreValidAndDifferent() {
        val a = NtfyPushDispatcher.generateTopic()
        val b = NtfyPushDispatcher.generateTopic()
        assertTrue(NtfyPushDispatcher.isValidTopic(a))
        assertTrue(NtfyPushDispatcher.isValidTopic(b))
        assertNotEquals(a, b)
    }

    @Test
    fun serverAddressRules() {
        assertEquals("https://ntfy.sh", NtfyPushDispatcher.normalizeServerUrl(""))
        assertEquals("https://ntfy.sh", NtfyPushDispatcher.normalizeServerUrl(" https://ntfy.sh/ "))
        assertEquals("https://push.example.org", NtfyPushDispatcher.normalizeServerUrl("https://push.example.org"))
        assertNull(NtfyPushDispatcher.normalizeServerUrl("http://ntfy.sh"))
        assertNull(NtfyPushDispatcher.normalizeServerUrl("ntfy.sh"))
        assertNull(NtfyPushDispatcher.normalizeServerUrl("https://"))
    }
}
