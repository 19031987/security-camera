package com.securitycam.core.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for motion intrusion events recorded by the security camera.
 */
@Dao
interface MotionEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: MotionEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<MotionEventEntity>): List<Long>

    @Query("SELECT * FROM motion_events ORDER BY timestamp DESC")
    suspend fun getAllEvents(): List<MotionEventEntity>

    @Query("SELECT * FROM motion_events ORDER BY timestamp DESC")
    fun observeEvents(): Flow<List<MotionEventEntity>>

    @Query("SELECT * FROM motion_events WHERE id = :id LIMIT 1")
    suspend fun getEventById(id: String): MotionEventEntity?

    @Query("SELECT COUNT(*) FROM motion_events")
    suspend fun getEventCount(): Int

    @Query("SELECT COUNT(*) FROM motion_events")
    fun observeEventCount(): Flow<Int>

    @Query("DELETE FROM motion_events WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Delete
    suspend fun delete(event: MotionEventEntity): Int

    @Query("DELETE FROM motion_events WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOlderThan(cutoffTimestamp: Long): Int

    @Query("DELETE FROM motion_events WHERE id IN (SELECT id FROM motion_events ORDER BY timestamp ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int): Int

    @Query("DELETE FROM motion_events")
    suspend fun deleteAll(): Int

    @Query("UPDATE motion_events SET smsStatus = :status WHERE id = :id")
    suspend fun updateSmsStatus(id: String, status: String): Int

    @Query("UPDATE motion_events SET emailStatus = :status WHERE id = :id")
    suspend fun updateEmailStatus(id: String, status: String): Int

    @Query("UPDATE motion_events SET clipPath = :path WHERE id = :id")
    suspend fun updateClipPath(id: String, path: String?): Int

    @Query("UPDATE motion_events SET clipPath = NULL WHERE clipPath = :path")
    suspend fun clearClipPath(path: String): Int

    @Query("SELECT COUNT(*) FROM motion_events WHERE clipPath = :path")
    suspend fun countEventsWithClip(path: String): Int
}
