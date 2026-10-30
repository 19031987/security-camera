package com.securitycam.core.surveillance

/**
 * Configuration parameters for the adaptive background motion detection engine.
 */
data class MotionConfig(
    val sensitivity: Int = 50,
    val pixelThreshold: Int = 25,
    val minAreaRatio: Float = 0.01f,
    val consecutiveTriggerFrames: Int = 2,
    val lowLightCompensation: Boolean = true
) {
    init {
        require(sensitivity in 1..100) { "sensitivity must be in 1..100, got $sensitivity" }
        require(pixelThreshold > 0) { "pixelThreshold must be positive, got $pixelThreshold" }
        require(minAreaRatio in 0f..1f) { "minAreaRatio must be in [0, 1], got $minAreaRatio" }
        require(consecutiveTriggerFrames >= 1) { "consecutiveTriggerFrames must be >= 1, got $consecutiveTriggerFrames" }
    }
}
