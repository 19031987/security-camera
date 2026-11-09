package com.securitycam.remote

import com.securitycam.core.remote.AlertEventClient
import com.securitycam.core.remote.AlertEventClient.PollResult
import com.securitycam.core.remote.AlertEventHub
import com.securitycam.core.remote.FrameHub
import com.securitycam.core.remote.ViewerServer
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.concurrent.thread

/**
 * The receiver phone's client talking to the camera phone's real server over a local socket:
 * the whole path an alert travels between the two phones.
 */
class ReceiverLinkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val password = "correct-horse"
    private val hub = AlertEventHub()
    private var now = 1_000_000L
    private lateinit var image: File
    private lateinit var server: ViewerServer

    @Before
    fun setUp() {
        image = tempFolder.newFile("event.jpg").apply { writeBytes(byteArrayOf(10, 20, 30, 40)) }
        server = ViewerServer(
            frameHub = FrameHub(),
            passwordProvider = { password },
            clock = { now },
            alertEventHub = hub,
            eventImageProvider = { id -> if (id == "ev-1") image else null },
            eventWaitMs = 1_500
        )
        server.start(0)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun baseUrl(): HttpUrl = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(server.port).build()

    private fun client(pw: String = password) = AlertEventClient(baseUrl(), pw)

    @Test
    fun firstPollOnlyReportsWhereTheCameraIsUpTo() {
        hub.publish(AlertEventHub.TYPE_ALERT, "Old alert", "before the receiver connected", 1L, "old")

        val result = client().poll(-1) as PollResult.Success

        assertEquals(1L, result.latest)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun anAlertPublishedOnTheCameraReachesTheWaitingReceiver() {
        val start = (client().poll(-1) as PollResult.Success).latest
        val publisher = thread {
            Thread.sleep(200)
            hub.publish(AlertEventHub.TYPE_ALERT, "Someone is at your door", "Motion detected at 14:22.", 1234L, "ev-1")
        }

        val result = client().poll(start) as PollResult.Success
        publisher.join()

        assertEquals(1, result.events.size)
        val event = result.events[0]
        assertEquals(AlertEventHub.TYPE_ALERT, event.type)
        assertEquals("Someone is at your door", event.title)
        assertEquals("Motion detected at 14:22.", event.message)
        assertEquals(1234L, event.time)
        assertEquals("ev-1", event.eventId)
        assertEquals(event.id, result.latest)
    }

    @Test
    fun eventsMissedBetweenPollsAreDeliveredTogetherInOrder() {
        hub.publish(AlertEventHub.TYPE_ALERT, "First", "", 1L, null)
        hub.publish(AlertEventHub.TYPE_ARRIVAL, "आशा is home", "", 2L, null)

        val result = client().poll(0) as PollResult.Success

        assertEquals(listOf("First", "आशा is home"), result.events.map { it.title })
        assertEquals(AlertEventHub.TYPE_ARRIVAL, result.events[1].type)
        assertNull(result.events[1].eventId)
    }

    @Test
    fun aQuietPollEndsNormallyWithNoEvents() {
        val result = client().poll(0) as PollResult.Success
        assertTrue(result.events.isEmpty())
        assertEquals(0L, result.latest)
    }

    @Test
    fun aReceiverAheadOfARestartedCameraIsAnsweredAtOnceSoItCanResync() {
        val started = System.currentTimeMillis()
        val result = client().poll(500) as PollResult.Success

        assertTrue(System.currentTimeMillis() - started < 1_000)
        assertEquals(0L, result.latest)
    }

    @Test
    fun theScreenshotIsFetchedByEventId() {
        assertArrayEquals(byteArrayOf(10, 20, 30, 40), client().fetchImage("ev-1"))
    }

    @Test
    fun unknownOrHostileEventIdsGetNoImage() {
        assertNull(client().fetchImage("nope"))
        assertNull(client().fetchImage("../../etc/passwd"))
        assertNull(client().fetchImage(image.absolutePath))
        assertNull(client().fetchImage("x".repeat(200)))
    }

    @Test
    fun wrongPasswordIsRejectedForEventsAndImages() {
        assertEquals(PollResult.Rejected(lockedOut = false), client("wrong-password").poll(-1))
        assertNull(client("wrong-password").fetchImage("ev-1"))
    }

    @Test
    fun repeatedWrongPasswordsLockTheReceiverOutThenRecover() {
        repeat(ViewerServer.MAX_FAILURES) { client("wrong-password").poll(-1) }
        assertEquals(PollResult.Rejected(lockedOut = true), client().poll(-1))

        now += ViewerServer.LOCKOUT_MS + 1
        assertTrue(client().poll(-1) is PollResult.Success)
    }

    @Test
    fun anUnreachableCameraIsAFailureNotACrash() {
        val url = baseUrl()
        server.stop()
        assertTrue(AlertEventClient(url, password).poll(-1) is PollResult.Failed)
        assertNull(AlertEventClient(url, password).fetchImage("ev-1"))
    }

    @Test
    fun aServerWithoutAnEventHubHasNoEventsEndpoint() {
        val plain = ViewerServer(FrameHub(), { password })
        plain.start(0)
        try {
            val url = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(plain.port).build()
            assertTrue(AlertEventClient(url, password).poll(-1) is PollResult.Failed)
        } finally {
            plain.stop()
        }
    }

    @Test
    fun hubKeepsOnlyTheNewestEvents() {
        val small = AlertEventHub(capacity = 3)
        repeat(5) { small.publish(AlertEventHub.TYPE_ALERT, "e$it", "", it.toLong(), null) }

        assertEquals(5L, small.latestId)
        assertEquals(listOf("e2", "e3", "e4"), small.await(0, 0).map { it.title })
        assertEquals(listOf("e4"), small.await(4, 0).map { it.title })
        assertTrue(small.await(5, 0).isEmpty())
    }

    @Test
    fun addressesAreAcceptedInTheFormsPeopleType() {
        fun parse(s: String) = AlertEventClient.parseAddress(s)?.toString()
        assertEquals("http://192.168.1.20:8080/", parse("192.168.1.20"))
        assertEquals("http://192.168.1.20:8080/", parse(" 192.168.1.20:8080 "))
        assertEquals("http://192.168.1.20:9000/", parse("http://192.168.1.20:9000/"))
        assertEquals("http://camera.local:8080/", parse("camera.local"))
        assertEquals("https://cam.example.org:8443/", parse("https://cam.example.org:8443"))
        assertNotNull(parse("100.64.0.7:8080"))
    }

    @Test
    fun unusableAddressesAreRejected() {
        for (bad in listOf("", "   ", "ftp://192.168.1.20", "not an address", "http://")) {
            assertNull(bad, AlertEventClient.parseAddress(bad))
        }
        assertFalse(AlertEventClient.parseAddress("192.168.1.20")!!.toString().contains("@"))
    }
}
