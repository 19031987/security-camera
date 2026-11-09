package com.securitycam.service

import android.graphics.Bitmap
import com.securitycam.core.surveillance.LowLightEnhancer

/** Digital brightening for pictures taken in the dark. */
object BitmapEnhancer {

    /**
     * Lifts the shadows of a dark image with the gamma curve from [LowLightEnhancer].
     * It makes a dim scene readable; it cannot recover detail the sensor never captured.
     */
    fun brighten(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val lut = LowLightEnhancer.DEFAULT_GAMMA_LUT
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (p and -0x1000000) or
                (lut[(p shr 16) and 0xFF] shl 16) or
                (lut[(p shr 8) and 0xFF] shl 8) or
                lut[p and 0xFF]
        }

        val target = if (source.isMutable) source else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        target.setPixels(pixels, 0, width, 0, 0, width, height)
        return target
    }
}
