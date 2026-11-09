package com.securitycam.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.securitycam.core.storage.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Upgrading from the version 1 database must keep existing events. Room checks the migrated
 * schema against the one it expects when the database is opened, so opening it here also
 * proves the migration produces exactly the version 2 schema.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppDatabase.resetInstance()
        context.deleteDatabase(AppDatabase.DATABASE_NAME)
    }

    @After
    fun tearDown() {
        AppDatabase.resetInstance()
        context.deleteDatabase(AppDatabase.DATABASE_NAME)
    }

    @Test
    fun version1EventsSurviveTheUpgrade() {
        // The schema exactly as Room created it for version 1.
        val path = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `motion_events` (`id` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                    "`imagePath` TEXT NOT NULL, `motionScore` REAL NOT NULL, `isLowLight` INTEGER NOT NULL, " +
                    "`smsStatus` TEXT NOT NULL, `emailStatus` TEXT NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_motion_events_timestamp` ON `motion_events` (`timestamp`)")
            db.execSQL(
                "INSERT INTO motion_events (id, timestamp, imagePath, motionScore, isLowLight, smsStatus, emailStatus) " +
                    "VALUES ('old-event', 1700000000000, '/data/old.jpg', 0.75, 1, 'SENT', 'FAILED')"
            )
            db.version = 1
        }

        val events = runBlocking { AppDatabase.getInstance(context).motionEventDao().getAllEvents() }

        assertEquals(1, events.size)
        val event = events[0]
        assertEquals("old-event", event.id)
        assertEquals(1700000000000L, event.timestamp)
        assertEquals("/data/old.jpg", event.imagePath)
        assertEquals(0.75f, event.motionScore, 0f)
        assertEquals(true, event.isLowLight)
        assertEquals("SENT", event.smsStatus)
        assertEquals("FAILED", event.emailStatus)
        assertNull(event.recognizedPerson)
        assertNull(event.detectedLabel)
    }

    @Test
    fun version2EventsSurviveTheUpgrade() {
        val path = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `motion_events` (`id` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                    "`imagePath` TEXT NOT NULL, `motionScore` REAL NOT NULL, `isLowLight` INTEGER NOT NULL, " +
                    "`smsStatus` TEXT NOT NULL, `emailStatus` TEXT NOT NULL, `recognizedPerson` TEXT, PRIMARY KEY(`id`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_motion_events_timestamp` ON `motion_events` (`timestamp`)")
            db.execSQL(
                "INSERT INTO motion_events (id, timestamp, imagePath, motionScore, isLowLight, smsStatus, emailStatus, recognizedPerson) " +
                    "VALUES ('v2-event', 1700000000000, '/data/v2.jpg', 0.5, 0, 'KNOWN_PERSON', 'KNOWN_PERSON', 'Asha')"
            )
            db.version = 2
        }

        val events = runBlocking { AppDatabase.getInstance(context).motionEventDao().getAllEvents() }

        assertEquals(1, events.size)
        assertEquals("v2-event", events[0].id)
        assertEquals("Asha", events[0].recognizedPerson)
        assertNull(events[0].detectedLabel)
    }
}
