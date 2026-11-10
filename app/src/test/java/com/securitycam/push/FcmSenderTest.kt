package com.securitycam.push

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.securitycam.core.push.FcmSender
import com.securitycam.core.push.ServiceAccount
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class FcmSenderTest {

    private val topic = "cam_abcdefghijkmnpqrstuvwxyz"
    private lateinit var server: MockWebServer
    private lateinit var keys: KeyPair
    private var now = 1_800_000_000_000L
    private var account: ServiceAccount? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        account = ServiceAccount.parse(keyFile())
        assertNotNull(account)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** A key file shaped like the one the Firebase console produces. */
    private fun keyFile(type: String = "service_account"): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded)
        val pem = "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
        return Gson().toJson(
            mapOf(
                "type" to type,
                "project_id" to "securitycamera-test",
                "private_key" to pem,
                "client_email" to "firebase-adminsdk@securitycamera-test.iam.gserviceaccount.com",
                "token_uri" to server.url("/token").toString()
            )
        )
    }

    private fun sender() = FcmSender({ account }, OkHttpClient(), server.url("/").toString().trimEnd('/'), { now })

    private fun tokenResponse(token: String = "access-1", expiresIn: Int = 3600) =
        MockResponse().setBody("""{"access_token":"$token","expires_in":$expiresIn,"token_type":"Bearer"}""")

    @Test
    fun keyFileIsParsed() {
        val parsed = account!!
        assertEquals("securitycamera-test", parsed.projectId)
        assertEquals("firebase-adminsdk@securitycamera-test.iam.gserviceaccount.com", parsed.clientEmail)
        assertEquals(keys.private, parsed.privateKey)
    }

    @Test
    fun thingsThatAreNotKeyFilesAreRejected() {
        assertNull(ServiceAccount.parse(""))
        assertNull(ServiceAccount.parse("not json"))
        assertNull(ServiceAccount.parse("{}"))
        // The app config file (google-services.json) is not a key file.
        assertNull(ServiceAccount.parse("""{"project_info":{"project_id":"x"},"client":[]}"""))
        assertNull(ServiceAccount.parse(keyFile(type = "authorized_user")))
        assertNull(ServiceAccount.parse("""{"type":"service_account","project_id":"p","client_email":"e","private_key":"garbage"}"""))
    }

    @Test
    fun theTokenRequestIsSignedWithTheProjectKeyAndCarriesTheRightClaims() {
        server.enqueue(tokenResponse())
        server.enqueue(MockResponse().setBody("""{"name":"projects/x/messages/1"}"""))

        assertTrue(sender().sendToTopic(topic, mapOf("title" to "Hello")).isSuccess)

        val tokenRequest = server.takeRequest()
        assertEquals("/token", tokenRequest.path)
        val form = tokenRequest.body.readUtf8().split("&").associate {
            URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }
        assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form["grant_type"])

        val parts = form.getValue("assertion").split(".")
        assertEquals(3, parts.size)
        val decoder = Base64.getUrlDecoder()
        val header = Gson().fromJson(String(decoder.decode(parts[0])), JsonObject::class.java)
        assertEquals("RS256", header["alg"].asString)
        val claims = Gson().fromJson(String(decoder.decode(parts[1])), JsonObject::class.java)
        assertEquals("firebase-adminsdk@securitycamera-test.iam.gserviceaccount.com", claims["iss"].asString)
        assertEquals("https://www.googleapis.com/auth/firebase.messaging", claims["scope"].asString)
        assertEquals(server.url("/token").toString(), claims["aud"].asString)
        assertEquals(now / 1000, claims["iat"].asLong)
        assertEquals(now / 1000 + 3600, claims["exp"].asLong)

        // The signature must verify with the matching public key.
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(keys.public)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        }
        assertTrue(verifier.verify(decoder.decode(parts[2])))
    }

    @Test
    fun theMessageGoesToTheTopicAsHighPriorityData() {
        server.enqueue(tokenResponse("access-xyz"))
        server.enqueue(MockResponse().setBody("{}"))

        sender().sendToTopic(topic, mapOf("type" to "alert", "title" to "Person at your door"))

        server.takeRequest()
        val send = server.takeRequest()
        assertEquals("/v1/projects/securitycamera-test/messages:send", send.path)
        assertEquals("Bearer access-xyz", send.getHeader("Authorization"))
        val message = Gson().fromJson(send.body.readUtf8(), JsonObject::class.java)["message"].asJsonObject
        assertEquals(topic, message["topic"].asString)
        assertEquals("Person at your door", message["data"].asJsonObject["title"].asString)
        assertEquals("HIGH", message["android"].asJsonObject["priority"].asString)
        // Data only: the receiving app builds the notification, so it works with the app closed.
        assertFalse(message.has("notification"))
    }

    @Test
    fun theAccessTokenIsReusedUntilItIsAboutToExpire() {
        server.enqueue(tokenResponse("access-1", expiresIn = 3600))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(tokenResponse("access-2"))
        server.enqueue(MockResponse().setBody("{}"))
        val sender = sender()

        sender.sendToTopic(topic, mapOf("n" to "1"))
        now += 30 * 60 * 1000L
        sender.sendToTopic(topic, mapOf("n" to "2"))
        // Past the token's life (less the one-minute safety margin): a new one is fetched.
        now += 30 * 60 * 1000L
        sender.sendToTopic(topic, mapOf("n" to "3"))

        val paths = (1..5).map { server.takeRequest() }
        assertEquals(
            listOf("/token", "/v1/projects/securitycamera-test/messages:send",
                "/v1/projects/securitycamera-test/messages:send", "/token",
                "/v1/projects/securitycamera-test/messages:send"),
            paths.map { it.path }
        )
        assertEquals("Bearer access-2", paths[4].getHeader("Authorization"))
    }

    @Test
    fun aRejectedTokenIsDroppedSoTheNextSendFetchesAFreshOne() {
        server.enqueue(tokenResponse("stale"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Request had invalid authentication credentials."}}"""))
        server.enqueue(tokenResponse("fresh"))
        server.enqueue(MockResponse().setBody("{}"))
        val sender = sender()

        val first = sender.sendToTopic(topic, mapOf("n" to "1"))
        assertFalse(first.isSuccess)
        assertTrue(first.errorMessage!!.contains("401"))
        assertTrue(sender.sendToTopic(topic, mapOf("n" to "2")).isSuccess)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun googlesErrorMessageIsPassedOn() {
        server.enqueue(tokenResponse())
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"message":"Firebase Cloud Messaging API has not been used in project 1 before or it is disabled."}}""")
        )

        val result = sender().sendToTopic(topic, mapOf("n" to "1"))

        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("403"))
        assertTrue(result.errorMessage!!.contains("disabled"))
    }

    @Test
    fun aKeyGoogleDoesNotAcceptIsReported() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"Invalid JWT Signature."}"""))

        val result = sender().checkKey()

        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("Invalid JWT Signature"))
    }

    @Test
    fun checkKeySucceedsWithoutSendingAnyMessage() {
        server.enqueue(tokenResponse())
        assertTrue(sender().checkKey().isSuccess)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun nothingIsSentWithoutAKeyFileOrWithABadTopic() {
        assertFalse(sender().sendToTopic("cam_short", mapOf("n" to "1")).isSuccess)
        assertFalse(sender().sendToTopic("other_abcdefghijkmnpqrstuvwxyz", mapOf("n" to "1")).isSuccess)
        assertFalse(sender().sendToTopic("cam_abcdefghijkmnpqrst/../x", mapOf("n" to "1")).isSuccess)
        account = null
        assertFalse(sender().sendToTopic(topic, mapOf("n" to "1")).isSuccess)
        assertFalse(sender().checkKey().isSuccess)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anUnreachableServiceIsAFailureNotACrash() {
        val sender = sender()
        server.shutdown()
        assertFalse(sender.sendToTopic(topic, mapOf("n" to "1")).isSuccess)
    }
}
