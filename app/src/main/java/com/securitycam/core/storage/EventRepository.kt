package com.securitycam.core.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Domain repository abstraction for persisting and retrieving intrusion events.
 */
interface EventRepository {
    suspend fun insertEvent(event: MotionEventEntity): Long
    suspend fun getAllEvents(): List<MotionEventEntity>
    fun observeEvents(): Flow<List<MotionEventEntity>>
    suspend fun deleteEvent(id: String)
    suspend fun getEventById(id: String): MotionEventEntity? = null
    suspend fun getEventCount(): Int = 0
    fun observeEventCount(): Flow<Int> = emptyFlow()
    suspend fun deleteEventsOlderThan(cutoffTimestamp: Long): Int = 0
    suspend fun pruneOldestEvents(keepCount: Int): Int = 0
    suspend fun updateSmsStatus(id: String, status: String) {}
    suspend fun updateEmailStatus(id: String, status: String) {}
    suspend fun clearAllEvents() {}
    suspend fun updateClipPath(id: String, path: String?) {}

    /** Forgets a clip that has been deleted, on every event that pointed at it. */
    suspend fun clearClipPath(path: String) {}
    suspend fun countEventsWithClip(path: String): Int = 0
}
