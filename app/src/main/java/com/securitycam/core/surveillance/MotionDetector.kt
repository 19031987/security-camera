package com.securitycam.core.surveillance

/**
 * Interface contract for real-time video stream motion detection.
 */
interface MotionDetector {
    fun processFrame(frame: LuminanceFrame): MotionResult
    fun configure(config: MotionConfig)
    fun reset()
}
