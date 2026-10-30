package com.securitycam.core.surveillance

/**
 * Outcome of processing a single LuminanceFrame through the motion engine.
 */
data class MotionResult(
    val isMotionDetected: Boolean,
    val motionScore: Float,
    val activePixels: Int,
    val totalPixels: Int,
    val averageLuminance: Float,
    val isLowLight: Boolean,
    val boundingBoxes: List<NormalizedRect> = emptyList()
)
