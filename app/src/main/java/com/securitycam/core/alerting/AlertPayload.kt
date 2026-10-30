package com.securitycam.core.alerting

import java.io.File

/**
 * Comprehensive alert dispatch payload containing event metadata and screenshot attachment.
 */
data class AlertPayload(
    val eventId: String,
    val timestamp: Long,
    val motionScore: Float,
    val screenshotFile: File,
    val cameraFacing: String = "BACK"
)
