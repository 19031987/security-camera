package com.securitycam.core.surveillance

import kotlin.math.roundToInt

/**
 * Configuration parameters for the adaptive background motion detection engine.
 *
 * @property sensitivity User slider value from 1 (lowest sensitivity) to 100 (highest sensitivity).
 * @property pixelThreshold Luminance delta required for an individual pixel to be classified as changed.
 * @property minAreaRatio Fraction of total frame area required to be active to declare motion [0.0, 1.0].
 * @property consecutiveTriggerFrames Number of consecutive motion frames required before triggering alert.
 * @property lowLightCompensation When true, dynamically adapts noise floor threshold in low-light scenes.
 */
data class MotionConfig(
    val sensitivity: Int = DEFAULT_SENSITIVITY,
    val pixelThreshold: Int = DEFAULT_PIXEL_THRESHOLD,
    val minAreaRatio: Float = DEFAULT_MIN_AREA_RATIO,
    val consecutiveTriggerFrames: Int = DEFAULT_CONSECUTIVE_FRAMES,
    val lowLightCompensation: Boolean = true
) {
    init {
        require(sensitivity in MIN_SENSITIVITY..MAX_SENSITIVITY) {
            "sensitivity must be in 1..100, got $sensitivity"
        }
        require(pixelThreshold > 0) {
            "pixelThreshold must be positive, got $pixelThreshold"
        }
        require(minAreaRatio in 0f..1f) {
            "minAreaRatio must be in [0, 1], got $minAreaRatio"
        }
        require(consecutiveTriggerFrames >= 1) {
            "consecutiveTriggerFrames must be >= 1, got $consecutiveTriggerFrames"
        }
    }

    companion object {
        const val MIN_SENSITIVITY = 1
        const val MAX_SENSITIVITY = 100
        const val DEFAULT_SENSITIVITY = 50

        // Default legacy values for backwards compatibility with M1 tests
        const val DEFAULT_PIXEL_THRESHOLD = 25
        const val DEFAULT_MIN_AREA_RATIO = 0.01f
        const val DEFAULT_CONSECUTIVE_FRAMES = 2

        /**
         * Calculates the effective pixel threshold tau(S) from sensitivity slider S in [1, 100].
         * Formula: round(45 - ((S - 1) / 99) * (45 - 12))
         *
         * Maps S=1 -> 45 (rejects minor fluctuations),
         *      S=50 -> 29 (standard indoor motion),
         *      S=100 -> 12 (detects subtle distant movement).
         */
        fun calculatePixelThreshold(sliderValue: Int): Int {
            require(sliderValue in MIN_SENSITIVITY..MAX_SENSITIVITY) {
                "sensitivity must be in 1..100, got $sliderValue"
            }
            val normalized = (sliderValue - 1).toFloat() / 99f
            return (45f - normalized * (45f - 12f)).roundToInt()
        }

        /**
         * Calculates the minimum changed area ratio rho(S) from sensitivity slider S in [1, 100].
         * Formula: 0.001 + ((100 - S) / 100)^2 * 0.049
         *
         * Maps S=1 -> ~0.049 (4.9% of frame area),
         *      S=50 -> ~0.01325 (1.33% of frame area),
         *      S=100 -> 0.001 (0.1% of frame area, ~19 pixels on 160x120).
         */
        fun calculateMinAreaRatio(sliderValue: Int): Float {
            require(sliderValue in MIN_SENSITIVITY..MAX_SENSITIVITY) {
                "sensitivity must be in 1..100, got $sliderValue"
            }
            val factor = (100f - sliderValue.toFloat()) / 100f
            return 0.001f + (factor * factor) * 0.049f
        }

        /**
         * Calculates temporal debouncing consecutive frame requirement for sensitivity slider S in [1, 100].
         * Formula: 3 frames for S <= 50, 2 frames for S > 50.
         */
        fun calculateConsecutiveTriggerFrames(sliderValue: Int): Int {
            require(sliderValue in MIN_SENSITIVITY..MAX_SENSITIVITY) {
                "sensitivity must be in 1..100, got $sliderValue"
            }
            return if (sliderValue <= 50) 3 else 2
        }

        /**
         * Calibrates a MotionConfig instance from a single user sensitivity slider value [1, 100].
         */
        fun fromSensitivity(
            sliderValue: Int,
            lowLightCompensation: Boolean = true
        ): MotionConfig {
            require(sliderValue in MIN_SENSITIVITY..MAX_SENSITIVITY) {
                "sliderValue must be in 1..100, got $sliderValue"
            }
            return MotionConfig(
                sensitivity = sliderValue,
                pixelThreshold = calculatePixelThreshold(sliderValue),
                minAreaRatio = calculateMinAreaRatio(sliderValue),
                consecutiveTriggerFrames = calculateConsecutiveTriggerFrames(sliderValue),
                lowLightCompensation = lowLightCompensation
            )
        }
    }
}
