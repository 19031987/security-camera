package com.securitycam.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.securitycam.core.storage.AppDatabase
import com.securitycam.core.storage.MotionEventDao
import com.securitycam.core.storage.MotionEventEntity
import com.securitycam.core.storage.RoomEventRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomDatabaseStorageTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: MotionEventDao
    private lateinit var repository: RoomEventRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = AppDatabase.createInMemory(context)
        dao = database.motionEventDao()
        repository = RoomEventRepository(dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testInsertSingleEventAndQueryById(): Unit = runBlocking {
        val eventId = UUID.randomUUID().toString()
        val event = MotionEventEntity(
            id = eventId,
            timestamp = 1700000000000L,
            imagePath = "/data/user/0/com.securitycam/files/events/event_1.jpg",
            motionScore = 0.85f,
            isLowLight = false,
            smsStatus = MotionEventEntity.STATUS_DISABLED,
            emailStatus = MotionEventEntity.STATUS_SENT
        )

        val rowId = repository.insertEvent(event)
        assertTrue("SQLite rowid must be > 0", rowId > 0)

        val retrieved = repository.getEventById(eventId)
        assertNotNull("Retrieved event must not be null", retrieved)
        assertEquals(event.id, retrieved!!.id)
        assertEquals(event.timestamp, retrieved.timestamp)
        assertEquals(event.imagePath, retrieved.imagePath)
        assertEquals(event.motionScore, retrieved.motionScore, 0.0001f)
        assertEquals(event.isLowLight, retrieved.isLowLight)
        assertEquals(event.smsStatus, retrieved.smsStatus)
        assertEquals(event.emailStatus, retrieved.emailStatus)
    }

    @Test
    fun testInsertDuplicateIdReplaces(): Unit = runBlocking {
        val id = "duplicate-test-id"
        val original = MotionEventEntity(id = id, timestamp = 1000L, imagePath = "p1", motionScore = 0.5f, isLowLight = false)
        repository.insertEvent(original)

        val updated = MotionEventEntity(id = id, timestamp = 2000L, imagePath = "p2", motionScore = 0.95f, isLowLight = true)
        repository.insertEvent(updated)

        val retrieved = repository.getEventById(id)
        assertNotNull(retrieved)
        assertEquals(2000L, retrieved!!.timestamp)
        assertEquals("p2", retrieved.imagePath)
        assertEquals(0.95f, retrieved.motionScore, 0.0001f)
        assertTrue(retrieved.isLowLight)
        assertEquals(1, repository.getEventCount())
    }

    @Test
    fun testInsertAllAndOrdering(): Unit = runBlocking {
        val events = listOf(
            MotionEventEntity(id = "e1", timestamp = 1000L, imagePath = "p1", motionScore = 0.1f, isLowLight = false),
            MotionEventEntity(id = "e2", timestamp = 3000L, imagePath = "p2", motionScore = 0.3f, isLowLight = false),
            MotionEventEntity(id = "e3", timestamp = 2000L, imagePath = "p3", motionScore = 0.2f, isLowLight = false)
        )
        dao.insertAll(events)

        val retrieved = repository.getAllEvents()
        assertEquals(3, retrieved.size)
        // Ordered timestamp DESC: e2 (3000), e3 (2000), e1 (1000)
        assertEquals("e2", retrieved[0].id)
        assertEquals("e3", retrieved[1].id)
        assertEquals("e1", retrieved[2].id)
    }

    @Test
    fun testObserveEventsFlow(): Unit = runBlocking {
        val initial = repository.observeEvents().first()
        assertTrue(initial.isEmpty())

        val event = MotionEventEntity(id = "flow-test", timestamp = 5000L, imagePath = "p", motionScore = 0.7f, isLowLight = false)
        repository.insertEvent(event)

        val updated = repository.observeEvents().first()
        assertEquals(1, updated.size)
        assertEquals("flow-test", updated[0].id)
    }

    @Test
    fun testObserveEventCountFlow(): Unit = runBlocking {
        assertEquals(0, repository.observeEventCount().first())

        repository.insertEvent(MotionEventEntity(id = "c1", timestamp = 10L, imagePath = "p", motionScore = 0.5f, isLowLight = false))
        assertEquals(1, repository.observeEventCount().first())

        repository.insertEvent(MotionEventEntity(id = "c2", timestamp = 20L, imagePath = "p", motionScore = 0.5f, isLowLight = false))
        assertEquals(2, repository.observeEventCount().first())
    }

    @Test
    fun testDeleteById(): Unit = runBlocking {
        repository.insertEvent(MotionEventEntity(id = "del-1", timestamp = 10L, imagePath = "p", motionScore = 0.5f, isLowLight = false))
        repository.insertEvent(MotionEventEntity(id = "del-2", timestamp = 20L, imagePath = "p", motionScore = 0.5f, isLowLight = false))

        repository.deleteEvent("del-1")
        assertNull(repository.getEventById("del-1"))
        assertNotNull(repository.getEventById("del-2"))
        assertEquals(1, repository.getEventCount())
    }

    @Test
    fun testDeleteOlderThan(): Unit = runBlocking {
        repository.insertEvent(MotionEventEntity(id = "old1", timestamp = 100L, imagePath = "p", motionScore = 0.5f, isLowLight = false))
        repository.insertEvent(MotionEventEntity(id = "old2", timestamp = 200L, imagePath = "p", motionScore = 0.5f, isLowLight = false))
        repository.insertEvent(MotionEventEntity(id = "new1", timestamp = 500L, imagePath = "p", motionScore = 0.5f, isLowLight = false))

        val deleted = repository.deleteEventsOlderThan(300L)
        assertEquals(2, deleted)
        assertEquals(1, repository.getEventCount())
        assertNotNull(repository.getEventById("new1"))
    }

    @Test
    fun testPruneOldestEvents(): Unit = runBlocking {
        for (i in 1..10) {
            repository.insertEvent(MotionEventEntity(id = "ev-$i", timestamp = i * 100L, imagePath = "p", motionScore = 0.5f, isLowLight = false))
        }
        assertEquals(10, repository.getEventCount())

        val pruned = repository.pruneOldestEvents(keepCount = 6)
        assertEquals(4, pruned)
        assertEquals(6, repository.getEventCount())

        val remaining = repository.getAllEvents()
        assertEquals(6, remaining.size)
        // Kept events 5..10 (newest)
        assertEquals("ev-10", remaining.first().id)
        assertEquals("ev-5", remaining.last().id)
    }

    @Test
    fun testUpdateSmsAndEmailStatus(): Unit = runBlocking {
        val id = "status-update-test"
        repository.insertEvent(MotionEventEntity(id = id, timestamp = 100L, imagePath = "p", motionScore = 0.5f, isLowLight = false))

        repository.updateSmsStatus(id, MotionEventEntity.STATUS_SENT)
        repository.updateEmailStatus(id, MotionEventEntity.STATUS_FAILED)

        val updated = repository.getEventById(id)
        assertNotNull(updated)
        assertEquals(MotionEventEntity.STATUS_SENT, updated!!.smsStatus)
        assertEquals(MotionEventEntity.STATUS_FAILED, updated.emailStatus)
    }

    @Test
    fun testEmptyDatabaseBehavior(): Unit = runBlocking {
        assertEquals(0, repository.getEventCount())
        assertTrue(repository.getAllEvents().isEmpty())
        assertNull(repository.getEventById("non-existent-id"))
    }

    @Test
    fun testClearAllEvents(): Unit = runBlocking {
        for (i in 1..5) {
            repository.insertEvent(MotionEventEntity(id = "clear-$i", timestamp = i * 100L, imagePath = "p", motionScore = 0.5f, isLowLight = false))
        }
        assertEquals(5, repository.getEventCount())

        repository.clearAllEvents()
        assertEquals(0, repository.getEventCount())
        assertTrue(repository.getAllEvents().isEmpty())
    }
}
