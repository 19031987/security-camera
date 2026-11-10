package com.securitycam.push

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.securitycam.core.push.FcmPushNotifier
import com.securitycam.core.push.FcmSender
import com.securitycam.core.push.PushMessage
import com.securitycam.core.push.ServiceAccount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyPairGenerator
import java.util.Base64

/** The notifier asks for the full picture to be stored, and still notifies if that fails. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class FullPictureNotifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val code = "aBcdEFgh2345kmnpQRstuvwx"
    private lateinit var server: MockWebServer
    private lateinit var sender: FcmSender

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        val account = ServiceAccount.parse(
            Gson().toJson(
                mapOf(
                    "type" to "service_account", "project_id" to "p", "private_key" to pem,
                    "client_email" to "a@p.iam.gserviceaccount.com", "token_uri" to server.url("/token").toString()
                )
            )
        )
        sender = FcmSender({ account }, OkHttpClient(), server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun image(): File = tempFolder.newFile("shot.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private fun sentData(): JsonObject {
        server.takeRequest() // token
        val body = server.takeRequest().body.readUtf8()
        return Gson().fromJson(body, JsonObject::class.java)["message"].asJsonObject["data"].asJsonObject
    }

    @Test
    fun theStoredPicturesIdTravelsInTheNotification() = runTest(dispatcher) {
        server.enqueue(MockResponse().setBody("""{"access_token":"t","expires_in":3600}"""))
        server.enqueue(MockResponse().setBody("{}"))
        var uploaded: Pair<File, Long>? = null
        val file = image()
        val notifier = FcmPushNotifier(sender, { code }, dispatcher) { f, time ->
            uploaded = f to time
            "abcdefgh12345678"
        }

        val result = notifier.notify(PushMessage.TYPE_ALERT, "Person at your door", "m", 77L, file)

        assertTrue(result.isSuccess)
        assertEquals(file to 77L, uploaded)
        assertEquals("abcdefgh12345678", sentData()[PushMessage.KEY_PICTURE_ID].asString)
    }

    @Test
    fun ifStoringFailsOrThrowsTheNotificationStillGoesOut() = runTest(dispatcher) {
        for (uploader in listOf<(File, Long) -> String?>({ _, _ -> null }, { _, _ -> throw IllegalStateException("offline") })) {
            server.enqueue(MockResponse().setBody("""{"access_token":"t","expires_in":3600}"""))
            server.enqueue(MockResponse().setBody("{}"))
            val fresh = FcmSender(
                { ServiceAccount.parse(keyJson()) }, OkHttpClient(), server.url("/").toString().trimEnd('/')
            )

            val result = FcmPushNotifier(fresh, { code }, dispatcher, uploader)
                .notify(PushMessage.TYPE_ALERT, "Person at your door", "m", 1L, image0())

            assertTrue(result.isSuccess)
            assertFalse(sentData().has(PushMessage.KEY_PICTURE_ID))
        }
    }

    @Test
    fun aNotificationWithoutAPictureNeverAsksForStorage() = runTest(dispatcher) {
        server.enqueue(MockResponse().setBody("""{"access_token":"t","expires_in":3600}"""))
        server.enqueue(MockResponse().setBody("{}"))
        var asked = false
        val notifier = FcmPushNotifier(sender, { code }, dispatcher) { _, _ -> asked = true; "abcdefgh12345678" }

        notifier.notify(PushMessage.TYPE_TEST, "Test", "m", 1L, null)

        assertFalse(asked)
        assertFalse(sentData().has(PushMessage.KEY_PICTURE_ID))
    }

    private var counter = 0
    private fun image0(): File = tempFolder.newFile("shot${counter++}.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private fun keyJson(): String {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        return Gson().toJson(
            mapOf(
                "type" to "service_account", "project_id" to "p", "private_key" to pem,
                "client_email" to "a@p.iam.gserviceaccount.com", "token_uri" to server.url("/token").toString()
            )
        )
    }
}
