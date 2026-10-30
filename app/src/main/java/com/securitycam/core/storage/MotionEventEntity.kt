package com.securitycam.core.storage

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Room database entity recording a verified motion detection intrusion event.
 */
@Entity(tableName = "motion_events")
data class MotionEventEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val timestamp: Long,
    val imagePath: String,
    val motionScore: Float,
    val isLowLight: Boolean,
    val smsStatus: String = STATUS_DISABLED,
    val emailStatus: String = STATUS_DISABLED
) {
    companion object {
        const val STATUS_SENT = "SENT"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_SUPPRESSED = "SUPPRESSED"
        const val STATUS_DISABLED = "DISABLED"
    }
}
