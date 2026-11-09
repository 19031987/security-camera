package com.securitycam.remote

import com.securitycam.core.remote.FrameHub
import com.securitycam.core.remote.ViewerServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread

class ViewerServerTest {

    private val password = "correct-horse"
    private val hub = FrameHub()
    private var now = 1_000_000L
    private var server = ViewerServer(hub, { password }, { "{\"armed\":true}" }, { now })

    @After
    fun tearDown() {
        server.stop()
    }

    private class Response(val status: Int, val headers: String, val body: ByteArray)

    private fun auth(pw: String) = "Authorization: Basic " +
        Base64.getEncoder().encodeToString("viewer:$pw".toByteArray())

    /** Sends one request and reads until the server closes the connection. */
    private fun request(path: String, authHeader: String? = null, method: String = "GET"): Response {
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 5_000
            val head = StringBuilder("$method $path HTTP/1.1\r\nHost: localhost\r\n")
            if (authHeader != null) head.append(authHeader).append("\r\n")
            head.append("\r\n")
            socket.getOutputStream().write(head.toString().toByteArray())
            socket.getOutputStream().flush()
            val all = socket.getInputStream().readBytes()
            val text = String(all, Charsets.ISO_8859_1)
            val split = text.indexOf("\r\n\r\n")
            val headers = text.substring(0, split)
            return Response(headers.substring(9, 12).toInt(), headers, all.copyOfRange(split + 4, all.size))
        }
    }

    @Test
    fun refusesToStartWithoutALongEnoughPassword() {
        val weak = ViewerServer(hub, { "short" })
        try {
            weak.start(0)
            throw AssertionError("start should have failed")
        } catch (expected: IllegalArgumentException) {
        }
        assertFalse(weak.isRunning)
    }

    @Test
    fun requestWithoutPasswordIsChallenged() {
        server.start(0)
        val response = request("/")
        assertEquals(401, response.status)
        assertTrue(response.headers.contains("WWW-Authenticate: Basic"))
        assertFalse(String(response.body).contains("<img"))
    }

    @Test
    fun wrongPasswordIsRejectedOnEveryPath() {
        server.start(0)
        for (path in listOf("/", "/stream", "/snapshot.jpg", "/status.json")) {
            assertEquals(path, 401, request(path, auth("wrong-password")).status)
        }
    }

    @Test
    fun correctPasswordGetsTheViewerPage() {
        server.start(0)
        val response = request("/", auth(password))
        assertEquals(200, response.status)
        assertTrue(String(response.body).contains("/stream"))
        assertTrue(response.headers.contains("Cache-Control: no-store"))
    }

    @Test
    fun statusComesFromTheProvider() {
        server.start(0)
        assertEquals("{\"armed\":true}", String(request("/status.json", auth(password)).body))
    }

    @Test
    fun unknownPathIs404AndPostIsRefused() {
        server.start(0)
        assertEquals(404, request("/etc/passwd", auth(password)).status)
        assertEquals(405, request("/", auth(password), method = "POST").status)
    }

    @Test
    fun repeatedWrongPasswordsLockTheAddressOutEvenForTheRightPassword() {
        server.start(0)
        repeat(ViewerServer.MAX_FAILURES) {
            assertEquals(401, request("/", auth("wrong-password")).status)
        }
        assertEquals(429, request("/", auth(password)).status)

        // The lockout ends by itself.
        now += ViewerServer.LOCKOUT_MS + 1
        assertEquals(200, request("/", auth(password)).status)
    }

    @Test
    fun requestsWithNoPasswordAtAllDoNotCountTowardsLockout() {
        server.start(0)
        repeat(ViewerServer.MAX_FAILURES + 2) { assertEquals(401, request("/").status) }
        assertEquals(200, request("/", auth(password)).status)
    }

    @Test
    fun aSuccessfulLoginClearsEarlierFailures() {
        server.start(0)
        repeat(ViewerServer.MAX_FAILURES - 1) { request("/", auth("wrong-password")) }
        assertEquals(200, request("/", auth(password)).status)
        repeat(ViewerServer.MAX_FAILURES - 1) { request("/", auth("wrong-password")) }
        assertEquals(200, request("/", auth(password)).status)
    }

    @Test
    fun snapshotReturnsAFrameCapturedAfterTheRequest() {
        server.start(0)
        hub.publish(byteArrayOf(9, 9, 9)) // stale frame from before the request
        val publisher = thread {
            // Publish once the request has registered as a viewer.
            val deadline = System.currentTimeMillis() + 3_000
            while (!hub.hasViewers && System.currentTimeMillis() < deadline) Thread.sleep(5)
            Thread.sleep(50)
            hub.publish(byteArrayOf(1, 2, 3, 4))
        }
        val response = request("/snapshot.jpg", auth(password))
        publisher.join()
        assertEquals(200, response.status)
        assertTrue(response.headers.contains("Content-Type: image/jpeg"))
        assertTrue(byteArrayOf(1, 2, 3, 4).contentEquals(response.body))
        assertFalse(hub.hasViewers)
    }

    @Test
    fun streamSendsFramesAndStopsCountingTheViewerWhenClosed() {
        server.start(0)
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET /stream HTTP/1.1\r\n${auth(password)}\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()

            val deadline = System.currentTimeMillis() + 3_000
            while (!hub.hasViewers && System.currentTimeMillis() < deadline) Thread.sleep(5)
            assertTrue(hub.hasViewers)
            Thread.sleep(50)
            hub.publish("FRAME-ONE".toByteArray())

            val input = socket.getInputStream()
            val received = StringBuilder()
            val buffer = ByteArray(1024)
            while (!received.contains("FRAME-ONE")) {
                val read = input.read(buffer)
                if (read < 0) break
                received.append(String(buffer, 0, read, Charsets.ISO_8859_1))
            }
            assertTrue(received.contains("multipart/x-mixed-replace"))
            assertTrue(received.contains("Content-Length: 9"))
            assertTrue(received.contains("FRAME-ONE"))
        }
        // After the browser goes away the next write fails and the viewer is released.
        val deadline = System.currentTimeMillis() + 5_000
        while (hub.hasViewers && System.currentTimeMillis() < deadline) {
            hub.publish("MORE".toByteArray())
            Thread.sleep(20)
        }
        assertFalse(hub.hasViewers)
    }

    @Test
    fun stopClosesThePort() {
        server.start(0)
        val port = server.port
        assertTrue(server.isRunning)
        server.stop()
        assertFalse(server.isRunning)
        assertEquals(-1, server.port)
        try {
            Socket("127.0.0.1", port).close()
            throw AssertionError("port should be closed")
        } catch (expected: java.io.IOException) {
        }
    }

    @Test
    fun frameHubOnlyReturnsFramesNewerThanAsked() {
        val h = FrameHub()
        assertNull(h.awaitFrame(0, 20))
        h.publish(byteArrayOf(1))
        // Asking for "the next frame" does not return the one already there.
        assertNull(h.awaitFrame(0, 20))
        val waiter = thread { Thread.sleep(30); h.publish(byteArrayOf(2)) }
        val frame = h.awaitFrame(0, 2_000)
        waiter.join()
        assertNotNull(frame)
        assertEquals(2, frame!!.jpeg[0].toInt())
        assertNull(h.awaitFrame(frame.sequence, 20))
    }

    @Test
    fun viewerCountNeverGoesNegative() {
        val h = FrameHub()
        h.removeViewer()
        assertFalse(h.hasViewers)
        h.addViewer()
        assertTrue(h.hasViewers)
    }
}
