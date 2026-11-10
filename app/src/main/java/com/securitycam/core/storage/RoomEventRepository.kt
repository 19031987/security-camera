package com.securitycam.core.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Concrete implementation of [EventRepository] backed by Room SQLite DAO.
 * Executes all database operations safely off the main thread using [ioDispatcher].
 */
class RoomEventRepository(
    private val dao: MotionEventDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : EventRepository {

    override suspend fun insertEvent(event: MotionEventEntity): Long = withContext(ioDispatcher) {
        dao.insert(event)
    }

    override suspend fun getAllEvents(): List<MotionEventEntity> = withContext(ioDispatcher) {
        dao.getAllEvents()
    }

    override fun observeEvents(): Flow<List<MotionEventEntity>> {
        return dao.observeEvents()
    }

    override suspend fun deleteEvent(id: String) = withContext(ioDispatcher) {
        dao.deleteById(id)
        Unit
    }

    override suspend fun getEventById(id: String): MotionEventEntity? = withContext(ioDispatcher) {
        dao.getEventById(id)
    }

    override suspend fun getEventCount(): Int = withContext(ioDispatcher) {
        dao.getEventCount()
    }

    override fun observeEventCount(): Flow<Int> {
        return dao.observeEventCount()
    }

    override suspend fun deleteEventsOlderThan(cutoffTimestamp: Long): Int = withContext(ioDispatcher) {
        dao.deleteOlderThan(cutoffTimestamp)
    }

    override suspend fun pruneOldestEvents(keepCount: Int): Int = withContext(ioDispatcher) {
        val count = dao.getEventCount()
        if (count > keepCount) {
            dao.deleteOldest(count - keepCount)
        } else {
            0
        }
    }

    override suspend fun updateSmsStatus(id: String, status: String) = withContext(ioDispatcher) {
        dao.updateSmsStatus(id, status)
        Unit
    }

    override suspend fun updateEmailStatus(id: String, status: String) = withContext(ioDispatcher) {
        dao.updateEmailStatus(id, status)
        Unit
    }

    override suspend fun clearAllEvents() = withContext(ioDispatcher) {
        dao.deleteAll()
        Unit
    }

    override suspend fun updateClipPath(id: String, path: String?) = withContext(ioDispatcher) {
        dao.updateClipPath(id, path)
        Unit
    }

    override suspend fun clearClipPath(path: String) = withContext(ioDispatcher) {
        dao.clearClipPath(path)
        Unit
    }

    override suspend fun countEventsWithClip(path: String): Int = withContext(ioDispatcher) {
        dao.countEventsWithClip(path)
    }
}
