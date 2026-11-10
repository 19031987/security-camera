package com.securitycam.core.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Main Room Database for the Android Security Camera.
 * Holds persistent motion intrusion event records and alert delivery history.
 */
@Database(
    entities = [MotionEventEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun motionEventDao(): MotionEventDao

    companion object {
        const val DATABASE_NAME = "security_cam_database"

        /** Version 2 adds the name of the recognised household member to each event. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE motion_events ADD COLUMN recognizedPerson TEXT")
            }
        }

        /** Version 3 adds what the on-device model saw moving. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE motion_events ADD COLUMN detectedLabel TEXT")
            }
        }

        /** Version 4 adds the path of the event's video clip. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE motion_events ADD COLUMN clipPath TEXT")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Returns singleton database instance with destructive migration fallback.
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
        }

        /**
         * Creates an in-memory database for isolated, fast unit testing.
         */
        fun createInMemory(context: Context): AppDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                AppDatabase::class.java
            )
            .allowMainThreadQueries()
            .build()
        }

        /**
         * Closes and resets singleton instance.
         */
        fun resetInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
