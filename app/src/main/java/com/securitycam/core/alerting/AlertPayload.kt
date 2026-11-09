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
    val cameraFacing: String = "BACK",
    val isLowLight: Boolean = false,
    /** Names of enrolled household members recognised in this event, if any. */
    val recognizedPeople: String? = null,
    /** True when the event is recorded but must not send an alert (a known person was recognised). */
    val suppressAlert: Boolean = false,
    /** What the on-device model saw moving, e.g. "Person" or "Animal (dog)"; null when unsure. */
    val detectedLabel: String? = null
)
