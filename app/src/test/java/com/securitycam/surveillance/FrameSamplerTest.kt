package com.securitycam.surveillance

import com.securitycam.core.surveillance.FrameSampler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class FrameSamplerTest {

    /** A plane where each pixel's value encodes its position, with optional row padding. */
    private fun plane(width: Int, height: Int, rowStride: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(rowStride * height)
        for (row in 0 until height) {
            for (col in 0 until rowStride) {
                // Padding bytes get 99 so reading them by mistake is visible.
                buffer.put(row * rowStride + col, if (col < width) (row * 10 + col).toByte() else 99)
            }
        }
        return buffer
    }

    @Test
    fun aFrameAlreadyAtTargetSizeIsCopiedUnchanged() {
        val frame = FrameSampler.sampleLuminance(plane(4, 3, 4), 4, 3, 4, 7L, targetWidth = 4)
        assertEquals(4, frame.width)
        assertEquals(3, frame.height)
        assertEquals(7L, frame.timestampMs)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 10, 11, 12, 13, 20, 21, 22, 23), frame.data)
    }

    @Test
    fun rowPaddingIsSkipped() {
        val frame = FrameSampler.sampleLuminance(plane(4, 3, 6), 4, 3, 6, 0L, targetWidth = 4)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 10, 11, 12, 13, 20, 21, 22, 23), frame.data)
    }

    @Test
    fun aLargerFrameKeepsEveryNthPixelOfEveryNthRow() {
        // 8 x 4 reduced towards 4 wide: step 2 -> 4 x 2.
        val frame = FrameSampler.sampleLuminance(plane(8, 4, 8), 8, 4, 8, 0L, targetWidth = 4)
        assertEquals(4, frame.width)
        assertEquals(2, frame.height)
        assertArrayEquals(byteArrayOf(0, 2, 4, 6, 20, 22, 24, 26), frame.data)
    }

    @Test
    fun reductionWorksWithRowPadding() {
        val frame = FrameSampler.sampleLuminance(plane(8, 4, 12), 8, 4, 12, 0L, targetWidth = 4)
        assertArrayEquals(byteArrayOf(0, 2, 4, 6, 20, 22, 24, 26), frame.data)
    }

    @Test
    fun cameraSizedFrameBecomesDetectorSized() {
        val frame = FrameSampler.sampleLuminance(ByteBuffer.allocate(640 * 480), 640, 480, 640, 0L, targetWidth = 160)
        assertEquals(160, frame.width)
        assertEquals(120, frame.height)
    }

    @Test
    fun aFrameSmallerThanTheTargetIsNotEnlarged() {
        val frame = FrameSampler.sampleLuminance(plane(4, 2, 4), 4, 2, 4, 0L, targetWidth = 160)
        assertEquals(4, frame.width)
        assertEquals(2, frame.height)
    }

    @Test
    fun theBufferPositionIsLeftAlone() {
        val buffer = plane(8, 4, 8)
        buffer.position(3)
        FrameSampler.sampleLuminance(buffer, 8, 4, 8, 0L, targetWidth = 4)
        assertEquals(3, buffer.position())
    }
}
