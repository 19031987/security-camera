package com.securitycam.core.surveillance

/**
 * Downsampled grayscale luminance Y-plane representation of a camera frame.
 */
data class LuminanceFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val timestampMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LuminanceFrame
        if (!data.contentEquals(other.data)) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (timestampMs != other.timestampMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}
