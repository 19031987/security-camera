package com.securitycam.core.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Main Room Database for the Android Security Camera.
 * Holds persistent motion intrusion event records and alert delivery history.
 */
@Database(
    entities = [MotionEventEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun motionEventDao(): MotionEventDao

    companion object {
        const val DATABASE_NAME = "security_cam_database"

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
