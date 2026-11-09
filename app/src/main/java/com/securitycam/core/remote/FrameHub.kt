package com.securitycam.core.remote

import java.util.concurrent.atomic.AtomicInteger

/**
 * Hands the newest camera frame (as JPEG bytes) from the camera thread to whoever is watching.
 * The camera only spends time encoding frames while [hasViewers] is true.
 */
class FrameHub {

    class Frame(val sequence: Long, val jpeg: ByteArray)

    private val viewers = AtomicInteger(0)
    private val lock = Object()
    private var latest: Frame? = null
    private var sequence = 0L

    val hasViewers: Boolean get() = viewers.get() > 0

    fun addViewer() {
        viewers.incrementAndGet()
    }

    fun removeViewer() {
        viewers.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    fun publish(jpeg: ByteArray) {
        synchronized(lock) {
            sequence++
            latest = Frame(sequence, jpeg)
            lock.notifyAll()
        }
    }

    /**
     * Waits for a frame newer than [afterSequence]. Returns null if none arrives in [timeoutMs].
     * Pass 0 to wait for the next frame published after this call starts, so a frame left over
     * from an earlier viewer is never returned as current.
     */
    fun awaitFrame(afterSequence: Long, timeoutMs: Long): Frame? {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            val threshold = if (afterSequence == 0L) sequence else afterSequence
            while (true) {
                val current = latest
                if (current != null && current.sequence > threshold) return current
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return null
                lock.wait(remaining)
            }
        }
    }
}
