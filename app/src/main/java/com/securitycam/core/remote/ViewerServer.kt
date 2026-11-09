package com.securitycam.core.remote

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * Small HTTP server that shows the live camera feed in a web browser.
 *
 *  - `/`              the viewer page
 *  - `/stream`        live video as motion-JPEG
 *  - `/snapshot.jpg`  a single current frame
 *  - `/status.json`   armed / night-vision state
 *
 * Every request must carry the password (HTTP Basic auth; the user name is ignored).
 * An address that keeps sending wrong passwords is locked out for a while.
 *
 * The connection is plain HTTP: on the home Wi-Fi the password and video are not encrypted.
 * Away from home it is meant to be reached through a private network (for example Tailscale),
 * which encrypts the traffic; it should not be exposed to the internet by port forwarding.
 */
class ViewerServer(
    private val frameHub: FrameHub,
    private val passwordProvider: () -> String,
    private val statusJsonProvider: () -> String = { "{}" },
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers: ExecutorService? = null
    private val connectionSlots = Semaphore(MAX_CONNECTIONS)
    private val failures = ConcurrentHashMap<String, FailureRecord>()

    private class FailureRecord(var count: Int, var lockedUntil: Long)

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /** The port actually bound, or -1 when stopped. */
    val port: Int get() = serverSocket?.takeIf { !it.isClosed }?.localPort ?: -1

    /** Starts listening. Pass port 0 to let the system choose a free port. */
    @Synchronized
    fun start(port: Int = DEFAULT_PORT) {
        if (isRunning) return
        require(isAcceptablePassword(passwordProvider())) {
            "A password of at least $MIN_PASSWORD_LENGTH characters is required"
        }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket
        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "viewer-connection").apply { isDaemon = true }
        }
        workers = pool
        acceptThread = Thread({ acceptLoop(socket, pool) }, "viewer-accept").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        workers?.shutdownNow()
        workers = null
        acceptThread = null
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            if (!connectionSlots.tryAcquire()) {
                // Too many open connections: refuse rather than queue without bound.
                closeQuietly(client)
                continue
            }
            try {
                pool.execute {
                    try {
                        handle(client)
                    } catch (_: Exception) {
                    } finally {
                        closeQuietly(client)
                        connectionSlots.release()
                    }
                }
            } catch (_: Exception) {
                closeQuietly(client)
                connectionSlots.release()
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val input = BufferedInputStream(client.getInputStream())
        val output = client.getOutputStream()
        val remote = client.inetAddress?.hostAddress ?: "unknown"

        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            respond(output, 400, "Bad Request", "text/plain", "Bad request".toByteArray())
            return
        }
        val method = parts[0]
        val path = parts[1].substringBefore('?')

        var authorization: String? = null
        var headerCount = 0
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            if (++headerCount > MAX_HEADERS) {
                respond(output, 400, "Bad Request", "text/plain", "Bad request".toByteArray())
                return
            }
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).equals("Authorization", ignoreCase = true)) {
                authorization = line.substring(colon + 1).trim()
            }
        }

        if (method != "GET") {
            respond(output, 405, "Method Not Allowed", "text/plain", "Method not allowed".toByteArray())
            return
        }

        val now = clock()
        if (isLockedOut(remote, now)) {
            respond(output, 429, "Too Many Requests", "text/plain", "Too many wrong passwords. Try again later.".toByteArray())
            return
        }
        if (!isAuthorized(authorization)) {
            // Only a supplied-but-wrong password counts; a browser's first request has none.
            if (authorization != null) recordFailure(remote, now)
            respond(
                output, 401, "Unauthorized", "text/plain", "Password required".toByteArray(),
                extraHeaders = "WWW-Authenticate: Basic realm=\"Security Camera\", charset=\"UTF-8\"\r\n"
            )
            return
        }
        failures.remove(remote)

        when (path) {
            "/", "/index.html" -> respond(output, 200, "OK", "text/html; charset=utf-8", VIEWER_PAGE.toByteArray())
            "/status.json" -> respond(output, 200, "OK", "application/json", statusJsonProvider().toByteArray())
            "/snapshot.jpg" -> serveSnapshot(output)
            "/stream" -> serveStream(client, output)
            else -> respond(output, 404, "Not Found", "text/plain", "Not found".toByteArray())
        }
    }

    private fun serveSnapshot(output: OutputStream) {
        frameHub.addViewer()
        try {
            // Only a frame captured after this request counts; an old one would mislead.
            val frame = frameHub.awaitFrame(0, SNAPSHOT_TIMEOUT_MS)
            if (frame == null) {
                respond(output, 503, "Service Unavailable", "text/plain", "Camera is not running".toByteArray())
            } else {
                respond(output, 200, "OK", "image/jpeg", frame.jpeg)
            }
        } finally {
            frameHub.removeViewer()
        }
    }

    private fun serveStream(client: Socket, output: OutputStream) {
        client.soTimeout = 0
        frameHub.addViewer()
        try {
            output.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
                    NO_CACHE_HEADERS +
                    "Connection: close\r\n\r\n").toByteArray()
            )
            var lastSequence = 0L
            var idleRounds = 0
            while (!client.isClosed && isRunning) {
                val frame = frameHub.awaitFrame(lastSequence, STREAM_WAIT_MS)
                if (frame == null) {
                    // Camera stalled or stopped. Give up after a while so the slot is freed.
                    if (++idleRounds >= STREAM_MAX_IDLE_ROUNDS) break
                    continue
                }
                idleRounds = 0
                lastSequence = frame.sequence
                output.write(
                    ("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.jpeg.size}\r\n\r\n").toByteArray()
                )
                output.write(frame.jpeg)
                output.write("\r\n".toByteArray())
                output.flush()
            }
        } finally {
            frameHub.removeViewer()
        }
    }

    private fun isAuthorized(authorization: String?): Boolean {
        val expected = passwordProvider()
        if (!isAcceptablePassword(expected)) return false
        if (authorization == null || !authorization.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = try {
            String(Base64.getDecoder().decode(authorization.substring(6).trim()), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val colon = decoded.indexOf(':')
        if (colon < 0) return false
        val supplied = decoded.substring(colon + 1)
        // Compare digests so the comparison takes the same time whatever was supplied.
        return MessageDigest.isEqual(sha256(supplied), sha256(expected))
    }

    private fun isLockedOut(remote: String, now: Long): Boolean {
        val record = failures[remote] ?: return false
        synchronized(record) {
            if (record.lockedUntil > now) return true
            if (record.lockedUntil != 0L) {
                failures.remove(remote)
            }
            return false
        }
    }

    private fun recordFailure(remote: String, now: Long) {
        if (failures.size > MAX_TRACKED_ADDRESSES) failures.clear()
        val record = failures.getOrPut(remote) { FailureRecord(0, 0L) }
        synchronized(record) {
            record.count++
            if (record.count >= MAX_FAILURES) {
                record.lockedUntil = now + LOCKOUT_MS
            }
        }
    }

    private fun respond(
        output: OutputStream,
        code: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        extraHeaders: String = ""
    ) {
        output.write(
            ("HTTP/1.1 $code $reason\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                NO_CACHE_HEADERS +
                "X-Content-Type-Options: nosniff\r\n" +
                extraHeaders +
                "Connection: close\r\n\r\n").toByteArray()
        )
        output.write(body)
        output.flush()
    }

    /** Reads one CRLF-terminated line, or null on end of stream / timeout / over-long line. */
    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        try {
            while (true) {
                val b = input.read()
                if (b == -1) return if (builder.isEmpty()) null else builder.toString()
                if (b == '\n'.code) break
                if (b != '\r'.code) builder.append(b.toChar())
                if (builder.length > MAX_LINE_LENGTH) return null
            }
        } catch (_: SocketTimeoutException) {
            return null
        }
        return builder.toString()
    }

    private fun sha256(text: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        const val MIN_PASSWORD_LENGTH = 8
        const val MAX_FAILURES = 5
        const val LOCKOUT_MS = 5 * 60 * 1000L

        private const val MAX_CONNECTIONS = 8
        private const val MAX_TRACKED_ADDRESSES = 256
        private const val MAX_HEADERS = 64
        private const val MAX_LINE_LENGTH = 4096
        private const val READ_TIMEOUT_MS = 10_000
        private const val SNAPSHOT_TIMEOUT_MS = 3_000L
        private const val STREAM_WAIT_MS = 5_000L
        private const val STREAM_MAX_IDLE_ROUNDS = 6
        private const val BOUNDARY = "securitycamframe"
        private const val NO_CACHE_HEADERS =
            "Cache-Control: no-store, no-cache, must-revalidate\r\nPragma: no-cache\r\n"

        fun isAcceptablePassword(password: String): Boolean = password.length >= MIN_PASSWORD_LENGTH

        internal val VIEWER_PAGE = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Security Camera</title>
            <style>
              html, body { margin: 0; height: 100%; background: #0A0E17; color: #F1F5F9;
                font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
              body { display: flex; flex-direction: column; }
              header { display: flex; align-items: center; gap: 12px; padding: 12px 16px; }
              h1 { font-size: 18px; font-weight: 600; margin: 0; flex: 1; }
              .pill { font-size: 13px; font-weight: 600; padding: 6px 12px; border-radius: 999px;
                background: #1B2538; color: #9FB0C7; }
              .pill.armed { background: #0C3524; color: #3DDC97; }
              .pill.disarmed { background: #3B2800; color: #FFB84D; }
              .pill.night { background: #3B2F73; color: #E6DEFF; }
              main { flex: 1; min-height: 0; display: flex; align-items: center; justify-content: center;
                background: #000; }
              img { max-width: 100%; max-height: 100%; display: block; }
              footer { padding: 10px 16px; font-size: 13px; color: #9FB0C7; }
              a { color: #4FD8EB; }
            </style>
            </head>
            <body>
            <header>
              <h1>Security Camera</h1>
              <span id="night" class="pill night" hidden>Night vision</span>
              <span id="armed" class="pill">Connecting…</span>
            </header>
            <main><img id="feed" src="/stream" alt="Live camera feed"></main>
            <footer><span id="note">Live view</span> · <a href="/snapshot.jpg" target="_blank" rel="noopener">Open snapshot</a></footer>
            <script>
              var feed = document.getElementById('feed');
              var note = document.getElementById('note');
              feed.onerror = function () {
                note.textContent = 'Feed interrupted. Reconnecting…';
                setTimeout(function () { feed.src = '/stream?t=' + Date.now(); }, 3000);
              };
              feed.onload = function () { note.textContent = 'Live view'; };
              function refresh() {
                fetch('/status.json', { cache: 'no-store' }).then(function (r) { return r.json(); }).then(function (s) {
                  var armed = document.getElementById('armed');
                  armed.textContent = s.armed ? 'Armed' : 'Disarmed';
                  armed.className = 'pill ' + (s.armed ? 'armed' : 'disarmed');
                  document.getElementById('night').hidden = !s.nightVision;
                }).catch(function () {});
              }
              refresh();
              setInterval(refresh, 5000);
            </script>
            </body>
            </html>
        """.trimIndent()
    }
}
