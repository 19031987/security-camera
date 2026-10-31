package com.securitycam.core.surveillance

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Algorithmic utility engine for day & night / low-light surveillance.
 *
 * Provides:
 * 1. Fast average frame luminance estimation.
 * 2. Low-light condition detection (average luminance < 40 / 255).
 * 3. Dynamic noise floor adaptation to counteract camera sensor ISO grain.
 * 4. High-performance O(1) gamma lookup table (LUT) digital luminance boosting (gamma ~ 0.55).
 * 5. Linear digital gain/offset contrast enhancement.
 * 6. Platform camera exposure and auto-torch guidance helpers.
 */
object LowLightEnhancer {

    /**
     * Luminance threshold below which a scene is classified as low-light.
     * At Y_bar < 40, camera sensors enter high-gain ISO amplification.
     */
    const val LOW_LIGHT_THRESHOLD: Float = 40.0f

    /**
     * Default gamma factor for digital shadow luminance expansion without mid-tone saturation.
     */
    const val DEFAULT_GAMMA: Float = 0.55f

    /**
     * Luminance threshold below which physical LED torch assist is recommended.
     */
    const val DEFAULT_AUTO_TORCH_THRESHOLD: Float = 20.0f

    /**
     * Maximum 8-bit unsigned pixel value.
     */
    const val MAX_PIXEL_VALUE: Int = 255

    /**
     * Precomputed 256-element Gamma Lookup Table (LUT) for gamma = 0.55.
     * LUT[i] = round(255 * (i / 255)^0.55)
     * Provides O(1) per-pixel digital luminance expansion without Math.pow() overhead.
     */
    val DEFAULT_GAMMA_LUT: IntArray = generateGammaLut(DEFAULT_GAMMA.toDouble())

    /**
     * Precomputed byte representation of DEFAULT_GAMMA_LUT for zero-conversion frame transformations.
     */
    val DEFAULT_GAMMA_BYTE_LUT: ByteArray = ByteArray(256) { i -> DEFAULT_GAMMA_LUT[i].toByte() }

    /**
     * Generates a 256-element gamma lookup table for a given gamma parameter.
     */
    fun generateGammaLut(gamma: Double): IntArray {
        require(gamma > 0.0) { "gamma must be positive, got $gamma" }
        return IntArray(256) { i ->
            val normalized = i.toDouble() / 255.0
            val boosted = (255.0 * normalized.pow(gamma)).roundToInt()
            boosted.coerceIn(0, 255)
        }
    }

    /**
     * Computes the average luminance (mean Y) of a LuminanceFrame.
     * Returns 0.0f if the frame is empty.
     */
    fun calculateAverageLuminance(frame: LuminanceFrame): Float {
        return calculateAverageLuminance(frame.data, frame.width * frame.height)
    }

    /**
     * Computes the average luminance (mean Y) across raw byte buffer data.
     * Sums pixel values as unsigned 8-bit integers using a 64-bit Long accumulator.
     */
    fun calculateAverageLuminance(data: ByteArray, totalPixels: Int = data.size): Float {
        if (totalPixels <= 0 || data.isEmpty()) return 0.0f
        val count = min(totalPixels, data.size)
        var sum = 0L
        for (i in 0 until count) {
            sum += (data[i].toInt() and 0xFF)
        }
        return sum.toFloat() / count.toFloat()
    }

    /**
     * Determines whether a frame is in a low-light state.
     */
    fun isLowLight(averageLuminance: Float, threshold: Float = LOW_LIGHT_THRESHOLD): Boolean {
        return averageLuminance < threshold
    }

    /**
     * Dynamically adjusts the motion detection pixel threshold to suppress dark camera sensor noise.
     *
     * In dark rooms, camera sensors apply aggressive analog and digital ISO gain, generating stochastic
     * salt-and-pepper noise grain. When lowLightCompensationEnabled is true and averageLuminance < 40,
     * the threshold is elevated:
     *   tau_effective = baseThreshold + max(0, round((40 - Y_bar) / 3))
     *
     * In complete darkness (Y_bar = 0), tau is elevated by +13.
     * In normal daylight (Y_bar >= 40), tau is unchanged.
     */
    fun computeDynamicThreshold(
        baseThreshold: Int,
        averageLuminance: Float,
        lowLightCompensationEnabled: Boolean = true
    ): Int {
        if (!lowLightCompensationEnabled || averageLuminance.isNaN() || averageLuminance.isInfinite() || averageLuminance >= LOW_LIGHT_THRESHOLD) {
            return baseThreshold
        }
        val delta = max(0, ((LOW_LIGHT_THRESHOLD - averageLuminance) / 3.0f).roundToInt())
        return baseThreshold + delta
    }

    /**
     * Applies the gamma LUT to a single 8-bit pixel value [0, 255].
     */
    fun applyGamma(pixelValue: Int, lut: IntArray = DEFAULT_GAMMA_LUT): Int {
        val clamped = pixelValue.coerceIn(0, 255)
        return lut[clamped]
    }

    /**
     * Applies the gamma LUT to an entire frame buffer for digital shadow boosting.
     * Modifies and returns destination array (allocates new array if not provided).
     */
    fun applyGammaToFrame(
        source: ByteArray,
        destination: ByteArray = ByteArray(source.size),
        lut: IntArray = DEFAULT_GAMMA_LUT
    ): ByteArray {
        val length = min(source.size, destination.size)
        for (i in 0 until length) {
            val y = source[i].toInt() and 0xFF
            destination[i] = lut[y].toByte()
        }
        return destination
    }

    /**
     * Applies linear digital gain and brightness offset:
     *   Y_boosted = clamp(round(Y * gain + offset), 0, 255)
     */
    fun enhanceLuminanceLinear(pixelValue: Int, gain: Float = 1.5f, offset: Int = 15): Int {
        val clamped = pixelValue.coerceIn(0, 255)
        val boosted = (clamped * gain + offset).roundToInt()
        return boosted.coerceIn(0, 255)
    }

    /**
     * Applies linear digital gain and brightness offset across a frame buffer.
     */
    fun applyLinearGainToFrame(
        source: ByteArray,
        destination: ByteArray = ByteArray(source.size),
        gain: Float = 1.5f,
        offset: Int = 15
    ): ByteArray {
        val length = min(source.size, destination.size)
        for (i in 0 until length) {
            val y = source[i].toInt() and 0xFF
            destination[i] = enhanceLuminanceLinear(y, gain, offset).toByte()
        }
        return destination
    }

    /**
     * Provides recommended Auto-Exposure (AE) compensation index for CameraX CameraControl.
     *
     * @param averageLuminance Current measured scene luminance.
     * @param maxEvIndex Maximum supported exposure compensation index from CameraInfo.exposureState.
     * @return Recommended EV step index (0 for daylight, up to maxEvIndex for low light).
     */
    fun getRecommendedExposureCompensation(averageLuminance: Float, maxEvIndex: Int): Int {
        if (maxEvIndex <= 0) return 0
        return when {
            averageLuminance >= LOW_LIGHT_THRESHOLD -> 0
            averageLuminance >= 25.0f -> min(maxEvIndex, 2)
            else -> maxEvIndex
        }
    }

    /**
     * Determines whether hardware LED torch assist should be automatically engaged.
     */
    fun shouldEngageTorch(
        averageLuminance: Float,
        autoTorchThreshold: Float = DEFAULT_AUTO_TORCH_THRESHOLD
    ): Boolean {
        return averageLuminance < autoTorchThreshold
    }
}
