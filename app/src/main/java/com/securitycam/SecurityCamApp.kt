package com.securitycam

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * Main Application class for Android Security Camera.
 * Initializes notification channels, singleton accessors, and storage directories.
 */
class SecurityCamApp : Application() {

    companion object {
        const val TAG = "SecurityCamApp"

        // Notification Channels
        const val CHANNEL_SURVEILLANCE = "surveillance_channel"
        const val CHANNEL_SURVEILLANCE_ID = CHANNEL_SURVEILLANCE
        const val CHANNEL_ALERTS = "alert_channel"
        const val CHANNEL_ALERTS_ID = CHANNEL_ALERTS

        // Notification Identifiers
        const val NOTIFICATION_ID_SURVEILLANCE = 1001
        const val NOTIFICATION_ID_ALERT_BASE = 2000

        @Volatile
        private var instance: SecurityCamApp? = null

        fun getInstance(): SecurityCamApp {
            return checkNotNull(instance) { "SecurityCamApp is not initialized yet!" }
        }

        fun getAppContext(): Context {
            return getInstance().applicationContext
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        initNotificationChannels()
        initStorageDirectories()

        Log.i(TAG, "SecurityCamApp initialized successfully.")
    }

    /**
     * Initializes notification channels required for Android 8.0+ (API 26+).
     * Creates:
     * 1. Surveillance Channel: Low importance, non-intrusive ongoing notification.
     * 2. Alert Channel: High importance, sound, vibration, and red LED alert.
     */
    private fun initNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Ongoing Surveillance Background Channel
            val surveillanceChannel = NotificationChannel(
                CHANNEL_SURVEILLANCE,
                getString(R.string.channel_surveillance_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_surveillance_desc)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            // 2. High-Priority Motion Alerts Channel
            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.channel_alert_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_alert_desc)
                setShowBadge(true)
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }

            notificationManager.createNotificationChannels(listOf(surveillanceChannel, alertChannel))
            Log.i(TAG, "Notification channels registered: [$CHANNEL_SURVEILLANCE, $CHANNEL_ALERTS]")
        }
    }

    /**
     * Pre-creates required storage directories on application startup.
     */
    private fun initStorageDirectories() {
        try {
            // Internal storage
            val internalEventsDir = File(filesDir, "events")
            if (!internalEventsDir.exists()) internalEventsDir.mkdirs()

            // Internal cache for in-flight snapshots
            val cacheSnapshotsDir = File(cacheDir, "snapshots")
            if (!cacheSnapshotsDir.exists()) cacheSnapshotsDir.mkdirs()

            // External Pictures/events directory
            getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let { picturesDir ->
                val externalEventsDir = File(picturesDir, "events")
                if (!externalEventsDir.exists()) externalEventsDir.mkdirs()
            }

            Log.i(TAG, "Storage directories initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing storage directories: ${e.message}", e)
        }
    }
}
