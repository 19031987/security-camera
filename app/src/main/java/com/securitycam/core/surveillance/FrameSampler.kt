package com.securitycam.core.surveillance

import java.nio.ByteBuffer

/**
 * Reduces a camera luminance (Y) plane to roughly [targetWidth] pixels across by keeping every
 * n-th pixel of every n-th row. Motion detection was tuned on small frames, so this lets the
 * camera capture at a higher resolution without changing how detection behaves.
 */
object FrameSampler {

    fun sampleLuminance(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        timestampMs: Long,
        targetWidth: Int
    ): LuminanceFrame {
        val step = (width / targetWidth).coerceAtLeast(1)
        val outWidth = width / step
        val outHeight = height / step
        val out = ByteArray(outWidth * outHeight)
        // Absolute reads only: the buffer's position is left untouched for later consumers.
        for (row in 0 until outHeight) {
            val rowStart = row * step * rowStride
            val outStart = row * outWidth
            for (col in 0 until outWidth) {
                out[outStart + col] = buffer.get(rowStart + col * step)
            }
        }
        return LuminanceFrame(out, outWidth, outHeight, timestampMs)
    }
}
