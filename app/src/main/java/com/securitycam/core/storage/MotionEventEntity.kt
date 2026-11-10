package com.securitycam.core.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Room database entity recording a verified motion detection intrusion event.
 * Indexed by timestamp for high-performance gallery listing and FIFO pruning.
 */
@Entity(
    tableName = "motion_events",
    indices = [Index(value = ["timestamp"])]
)
data class MotionEventEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val timestamp: Long,
    val imagePath: String,
    val motionScore: Float,
    val isLowLight: Boolean,
    val smsStatus: String = STATUS_DISABLED,
    val emailStatus: String = STATUS_DISABLED,
    /** Names of enrolled household members recognised in this event; null when nobody was. */
    val recognizedPerson: String? = null,
    /** What the on-device model saw moving, e.g. "Person"; null when it couldn't tell. */
    val detectedLabel: String? = null
) {
    companion object {
        const val STATUS_SENT = "SENT"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_SUPPRESSED = "SUPPRESSED"
        const val STATUS_DISABLED = "DISABLED"

        /** No alert was sent because an enrolled household member was recognised. */
        const val STATUS_KNOWN_PERSON = "KNOWN_PERSON"

        /** No alert was sent because of the alert rules: Home mode, or a kind not selected. */
        const val STATUS_QUIET = "QUIET"
    }
}
