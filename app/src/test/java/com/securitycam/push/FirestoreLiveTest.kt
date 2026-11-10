package com.securitycam.push

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.securitycam.core.push.FirestoreLive
import com.securitycam.core.push.FirestorePictures
import com.securitycam.core.push.LiveViewGate
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FirestoreLiveTest {

    private val code = "aBcdEFgh2345kmnpQRstuvwx"
    private lateinit var server: MockWebServer
    private lateinit var live: FirestoreLive

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        live = FirestoreLive(OkHttpClient(), server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun path(request: okhttp3.mockwebserver.RecordedRequest) =
        request.requestUrl!!.encodedPath.replace("%28", "(").replace("%29", ")")

    private fun fieldsOf(body: String): JsonObject = Gson().fromJson(body, JsonObject::class.java)["fields"].asJsonObject

    @Test
    fun theWatchNoteAFamilyPhoneWritesIsWhatTheCameraReads() {
        val nonce = FirestoreLive.newNonce()
        server.enqueue(MockResponse().setBody("{}"))

        assertTrue(live.writeRequest("my-project", "api-key", code, nonce))

        val write = server.takeRequest()
        assertEquals("PATCH", write.method)
        assertEquals("/v1/projects/my-project/databases/(default)/documents/cams/$code/live/request", path(write))
        assertEquals("api-key", write.requestUrl!!.queryParameter("key"))
        // Family phones don't sign in.
        assertNull(write.getHeader("Authorization"))
        val fields = fieldsOf(write.body.readUtf8())
        // Exactly the one field the database rules allow.
        assertEquals(setOf("nonce"), fields.keySet())

        server.enqueue(MockResponse().setBody("""{"fields":$fields}"""))
        assertEquals(nonce, live.readRequest("my-project", "token-1", code))
        val read = server.takeRequest()
        assertEquals("Bearer token-1", read.getHeader("Authorization"))
    }

    @Test
    fun thePictureTheCameraPublishesIsWhatAFamilyPhoneShows() {
        val jpeg = ByteArray(40_000) { (it * 3 % 256).toByte() }
        server.enqueue(MockResponse().setBody("{}"))

        assertTrue(live.writeFrame("my-project", "token-1", code, 42L, jpeg))

        val write = server.takeRequest()
        assertEquals("/v1/projects/my-project/databases/(default)/documents/cams/$code/live/frame", path(write))
        assertEquals("Bearer token-1", write.getHeader("Authorization"))
        val fields = fieldsOf(write.body.readUtf8())

        server.enqueue(MockResponse().setBody("""{"fields":$fields}"""))
        val frame = live.readFrame("my-project", "api-key", code)!!

        assertEquals(42L, frame.sequence)
        assertArrayEquals(jpeg, frame.jpeg)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun noteNoncesAreRandomAndOnlyPlainCharacters() {
        val a = FirestoreLive.newNonce()
        assertNotEquals(a, FirestoreLive.newNonce())
        assertTrue(FirestoreLive.isValidNonce(a))
        assertTrue(FirestoreLive.isValidNonce(FirestoreLive.NONCE_STOP))
        assertTrue(a.length <= 40)
        assertFalse(FirestoreLive.isValidNonce(""))
        assertFalse(FirestoreLive.isValidNonce("has space"))
        assertFalse(FirestoreLive.isValidNonce("x".repeat(41)))
    }

    @Test
    fun badInputsNeverReachTheNetwork() {
        assertFalse(live.writeRequest("p", "k", "bad/code", FirestoreLive.newNonce()))
        assertFalse(live.writeRequest("p", "k", code, "not a nonce!"))
        assertNull(live.readRequest("p", "t", "../x"))
        assertFalse(live.writeFrame("p", "t", code, 1L, ByteArray(0)))
        assertFalse(live.writeFrame("p", "t", code, 1L, ByteArray(FirestorePictures.MAX_PICTURE_BYTES + 1)))
        assertNull(live.readFrame("p", "k", "a b"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun missingRefusedOrMalformedDocumentsReadAsNothing() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(live.readRequest("p", "t", code))
        server.enqueue(MockResponse().setBody("""{"fields":{"nonce":{"stringValue":"NOT VALID !!"}}}"""))
        assertNull(live.readRequest("p", "t", code))

        server.enqueue(MockResponse().setResponseCode(403))
        assertNull(live.readFrame("p", "k", code))
        server.enqueue(MockResponse().setBody("""{"fields":{"seq":{"integerValue":"5"}}}"""))
        assertNull(live.readFrame("p", "k", code))
        server.enqueue(MockResponse().setBody("""{"fields":{"jpeg":{"bytesValue":"AQID"}}}"""))
        assertNull(live.readFrame("p", "k", code))
        server.enqueue(MockResponse().setBody("garbage"))
        assertNull(live.readFrame("p", "k", code))
    }

    @Test
    fun refusedWritesAndAnUnreachableDatabaseAreReportedNotThrown() {
        server.enqueue(MockResponse().setResponseCode(403))
        assertFalse(live.writeRequest("p", "k", code, FirestoreLive.newNonce()))
        server.enqueue(MockResponse().setResponseCode(500))
        assertFalse(live.writeFrame("p", "t", code, 1L, byteArrayOf(1)))

        server.shutdown()
        assertFalse(live.writeRequest("p", "k", code, FirestoreLive.newNonce()))
        assertNull(live.readRequest("p", "t", code))
        assertFalse(live.writeFrame("p", "t", code, 1L, byteArrayOf(1)))
        assertNull(live.readFrame("p", "k", code))
    }

    @Test
    fun theRulesCoverLiveViewAndNothingWider() {
        val rules = FirestorePictures.RULES
        assertTrue(rules.contains("match /cams/{code}/live/frame"))
        assertTrue(rules.contains("match /cams/{code}/live/request"))
        // The watch note may be created or updated, with only a short text field.
        assertTrue(rules.contains("allow create, update: if request.resource.data.keys().hasOnly(['nonce'])"))
        assertTrue(rules.contains("nonce.size() <= 40"))
        assertFalse(rules.contains("allow write"))
        assertFalse(rules.contains("allow read"))
        assertFalse(rules.contains("allow list"))
        assertFalse(rules.contains("allow delete"))
        assertFalse(rules.contains("{document=**}"))
    }

    // The camera's decision to stream

    private val gate get() = LiveViewGate(grantMs = 60_000)

    @Test
    fun aNoteAlreadyThereWhenTheCameraStartsDoesNotStartStreaming() {
        val g = gate
        g.onRequestSeen("leftover1", 1_000)
        assertFalse(g.isStreaming(1_000))
        // Seeing the same leftover again changes nothing.
        g.onRequestSeen("leftover1", 6_000)
        assertFalse(g.isStreaming(6_000))
    }

    @Test
    fun aNewNoteStartsStreamingForTheGrantPeriodOnTheCamerasOwnClock() {
        val g = gate
        g.onRequestSeen(null, 0)
        g.onRequestSeen("watch0001", 10_000)

        assertTrue(g.isStreaming(10_000))
        assertTrue(g.isStreaming(69_999))
        assertFalse(g.isStreaming(70_000))
    }

    @Test
    fun eachRenewalExtendsStreamingAndTheSameNoteDoesNot() {
        val g = gate
        g.onRequestSeen(null, 0)
        g.onRequestSeen("watch0001", 10_000)
        g.onRequestSeen("watch0001", 40_000)
        assertFalse(g.isStreaming(70_000))

        g.onRequestSeen("watch0002", 50_000)
        assertTrue(g.isStreaming(100_000))
        assertFalse(g.isStreaming(110_000))
    }

    @Test
    fun aStopNoteEndsStreamingAtOnceAndWatchingCanResume() {
        val g = gate
        g.onRequestSeen(null, 0)
        g.onRequestSeen("watch0001", 10_000)
        g.onRequestSeen(FirestoreLive.NONCE_STOP, 15_000)
        assertFalse(g.isStreaming(15_000))

        g.onRequestSeen("watch0002", 20_000)
        assertTrue(g.isStreaming(20_000))
    }

    @Test
    fun aFailedReadKeepsTheCurrentStateUntilTheGrantRunsOut() {
        val g = gate
        g.onRequestSeen(null, 0)
        g.onRequestSeen("watch0001", 10_000)
        g.onRequestSeen(null, 30_000)
        assertTrue(g.isStreaming(30_000))
        assertFalse(g.isStreaming(70_000))
    }

    @Test
    fun aWatcherWhoVanishesStopsCostingAnythingAfterTheGrant() {
        val g = gate
        g.onRequestSeen(null, 0)
        g.onRequestSeen("watch0001", 10_000)
        // No stop note, no renewals: the camera just keeps reading the same note.
        for (t in 15_000L..300_000L step 5_000L) g.onRequestSeen("watch0001", t)
        assertFalse(g.isStreaming(300_000))
    }
}
