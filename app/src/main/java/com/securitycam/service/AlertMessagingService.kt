package com.securitycam.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.securitycam.R
import com.securitycam.SecurityCamApp
import com.securitycam.core.config.SecuritySettings
import com.securitycam.core.push.PushMessage
import com.securitycam.ui.ReceiverActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runs on a receiver phone. Google wakes it when the camera phone sends a push message, and it
 * turns that message into a notification, with the small picture if one came with it.
 */
class AlertMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val settings = try {
            SecurityCamApp.getInstance().settingsRepository.getSettings()
        } catch (e: Exception) {
            return
        }
        // Only a linked receiver phone shows these; the camera phone ignores them.
        if (settings.deviceRole != SecuritySettings.ROLE_RECEIVER) return
        val expectedTopic = PushMessage.topicFor(settings.receiverLinkCode) ?: return
        if (message.from != "/topics/$expectedTopic") return

        // If the camera stored a full-size picture, fetch it now so the notification can show it.
        val pictureId = message.data[PushMessage.KEY_PICTURE_ID]
        val fullPicture = if (pictureId != null) fetchFullPicture(this, settings.receiverLinkCode, pictureId) else null

        show(this, message.data, fullPicture)
    }

    companion object {
        private const val TAG = "AlertMessagingService"
        const val CHANNEL_ARRIVALS = "arrival_channel"
        private const val NOTIFICATION_ID_FIRST = 3000
        private const val NOTIFICATION_ID_COUNT = 20
        private const val PREFS = "receiver_state"
        private const val KEY_LAST_TITLE = "last_title"
        private const val KEY_LAST_TIME = "last_time"
        private const val KEY_NEXT_ID = "next_id"

        /** Title and time of the newest notification, for the receiver screen. */
        data class LastAlert(val title: String, val time: Long)

        private val _lastAlert = MutableStateFlow<LastAlert?>(null)
        val lastAlert: StateFlow<LastAlert?> = _lastAlert.asStateFlow()

        fun loadLastAlert(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val title = prefs.getString(KEY_LAST_TITLE, null)
            _lastAlert.value = if (title != null) LastAlert(title, prefs.getLong(KEY_LAST_TIME, 0L)) else null
        }

        /** Builds and posts the notification for one push message. Returns false if it was dropped. */
        /** Downloads a full-size picture from the owner's database. Blocks; call off the main thread. */
        fun fetchFullPicture(context: Context, linkCode: String, pictureId: String): ByteArray? {
            return try {
                if (FirebaseApp.getApps(context).isEmpty()) return null
                val options = FirebaseApp.getInstance().options
                val projectId = options.projectId ?: return null
                SecurityCamApp.getInstance().firestorePictures.fetch(projectId, options.apiKey, linkCode, pictureId)
            } catch (e: Exception) {
                Log.w(TAG, "Full-size picture not fetched: ${e.message}")
                null
            }
        }

        fun show(context: Context, data: Map<String, String>, fullPicture: ByteArray? = null): Boolean {
            val title = data[PushMessage.KEY_TITLE]?.takeIf { it.isNotBlank() } ?: return false
            val text = data[PushMessage.KEY_MESSAGE].orEmpty()
            val type = data[PushMessage.KEY_TYPE] ?: PushMessage.TYPE_ALERT
            val time = data[PushMessage.KEY_TIME]?.toLongOrNull() ?: System.currentTimeMillis()

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val slot = prefs.getInt(KEY_NEXT_ID, 0)
            prefs.edit()
                .putString(KEY_LAST_TITLE, title)
                .putLong(KEY_LAST_TIME, time)
                .putInt(KEY_NEXT_ID, (slot + 1) % NOTIFICATION_ID_COUNT)
                .apply()
            _lastAlert.value = LastAlert(title, time)

            // Keep it for the receiver screen's list, whether or not a notification can be shown.
            try {
                SecurityCamApp.getInstance().receivedAlerts.add(
                    type, title, text, time,
                    pictureJpeg = fullPicture ?: PushMessage.thumbnail(data),
                    pictureId = data[PushMessage.KEY_PICTURE_ID],
                    isFullPicture = fullPicture != null
                )
            } catch (e: Exception) {
                Log.w(TAG, "Unable to store the received alert: ${e.message}")
            }

            val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!allowed) {
                Log.w(TAG, "Notifications are not permitted; alert not shown.")
                return false
            }

            // Urgent for possible intruders; quiet for family arriving and for tests.
            val urgent = type == PushMessage.TYPE_ALERT
            val channel = if (urgent) SecurityCamApp.CHANNEL_ALERTS else ensureArrivalChannel(context)
            val builder = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setWhen(time)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(if (urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context, 0,
                        Intent(context, ReceiverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )

            val picture = (fullPicture ?: PushMessage.thumbnail(data))
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (picture != null) {
                builder.setLargeIcon(picture).setStyle(
                    NotificationCompat.BigPictureStyle().bigPicture(picture)
                        .bigLargeIcon(null as android.graphics.Bitmap?)
                )
            }

            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID_FIRST + slot, builder.build())
            return true
        }

        private fun ensureArrivalChannel(context: Context): String {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ARRIVALS) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ARRIVALS,
                        context.getString(R.string.channel_arrival_name),
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = context.getString(R.string.channel_arrival_desc) }
                )
            }
            return CHANNEL_ARRIVALS
        }
    }
}
