package com.securitycam.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.securitycam.R
import com.securitycam.SecurityCamApp
import com.securitycam.core.remote.AlertEventClient
import com.securitycam.core.remote.AlertEventClient.PollResult
import com.securitycam.core.remote.AlertEventHub
import com.securitycam.core.remote.ViewerServer
import com.securitycam.ui.ReceiverActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the receiver phone currently knows about its link to the camera phone. */
data class ReceiverState(
    val status: Status = Status.OFF,
    val detail: String = "",
    val lastAlertTitle: String? = null,
    val lastAlertTime: Long = 0L
) {
    enum class Status { OFF, CONNECTING, CONNECTED, UNREACHABLE, WRONG_PASSWORD, LOCKED_OUT, BAD_ADDRESS }
}

/**
 * Runs on the owner's own phone. Keeps a direct connection to the camera phone's built-in
 * server and turns the alerts it publishes into notifications. No outside service is involved.
 */
class ReceiverService : LifecycleService() {

    private var pollJob: Job? = null
    private var nextNotificationId = NOTIFICATION_ID_ALERT_FIRST

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            pollJob?.cancel()
            _state.value = _state.value.copy(status = ReceiverState.Status.OFF, detail = "")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val ongoing = buildOngoingNotification(getString(R.string.receiver_status_connecting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID_ONGOING, ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID_ONGOING, ongoing)
        }

        // Restart the loop on every start so changed address or password take effect.
        pollJob?.cancel()
        pollJob = lifecycleScope.launch(Dispatchers.IO) { pollLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    private suspend fun pollLoop() {
        val settings = SecurityCamApp.getInstance().settingsRepository.getSettings()
        val baseUrl = AlertEventClient.parseAddress(settings.receiverCameraAddress)
        if (baseUrl == null || !ViewerServer.isAcceptablePassword(settings.receiverPassword)) {
            update(ReceiverState.Status.BAD_ADDRESS)
            return
        }
        val client = AlertEventClient(baseUrl, settings.receiverPassword)

        update(ReceiverState.Status.CONNECTING)
        var lastId = -1L
        var retryDelayMs = RETRY_MIN_MS
        while (currentCoroutineContext().isActive) {
            val result = client.poll(lastId)
            // A poll can outlive a restart of this loop; a superseded loop must not act on it.
            currentCoroutineContext().ensureActive()
            when (result) {
                is PollResult.Success -> {
                    retryDelayMs = RETRY_MIN_MS
                    update(ReceiverState.Status.CONNECTED)
                    // The very first answer only tells us where the camera is up to.
                    if (lastId >= 0) {
                        result.events.forEach { showAlert(client, it) }
                    }
                    lastId = result.latest
                }
                is PollResult.Rejected -> {
                    update(
                        if (result.lockedOut) ReceiverState.Status.LOCKED_OUT else ReceiverState.Status.WRONG_PASSWORD
                    )
                    if (!result.lockedOut) {
                        // Retrying a wrong password would only get this phone locked out.
                        return
                    }
                    delay(LOCKED_OUT_RETRY_MS)
                }
                is PollResult.Failed -> {
                    update(ReceiverState.Status.UNREACHABLE, result.reason)
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
                }
            }
        }
    }

    private fun update(status: ReceiverState.Status, detail: String = "") {
        val changed = _state.value.status != status
        _state.value = _state.value.copy(status = status, detail = detail)
        if (changed) {
            val text = when (status) {
                ReceiverState.Status.CONNECTED -> getString(R.string.receiver_status_connected)
                ReceiverState.Status.CONNECTING -> getString(R.string.receiver_status_connecting)
                ReceiverState.Status.UNREACHABLE -> getString(R.string.receiver_status_unreachable, detail)
                ReceiverState.Status.WRONG_PASSWORD -> getString(R.string.receiver_status_wrong_password)
                ReceiverState.Status.LOCKED_OUT -> getString(R.string.receiver_status_locked_out)
                ReceiverState.Status.BAD_ADDRESS -> getString(R.string.receiver_status_bad_address)
                ReceiverState.Status.OFF -> getString(R.string.receiver_status_off)
            }
            notifyIfAllowed(NOTIFICATION_ID_ONGOING, buildOngoingNotification(text))
        }
    }

    private fun showAlert(client: AlertEventClient, event: AlertEventHub.Event) {
        _state.value = _state.value.copy(lastAlertTitle = event.title, lastAlertTime = event.time)

        val isArrival = event.type == AlertEventHub.TYPE_ARRIVAL
        val channel = if (isArrival) ensureArrivalChannel() else SecurityCamApp.CHANNEL_ALERTS
        val builder = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(event.title)
            .setContentText(event.message)
            .setWhen(event.time)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .setPriority(if (isArrival) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (isArrival) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_ALARM)

        val picture = event.eventId?.let { client.fetchImage(it) }
            ?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        if (picture != null) {
            builder.setLargeIcon(picture)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as android.graphics.Bitmap?))
        }

        notifyIfAllowed(nextNotificationId, builder.build())
        nextNotificationId = if (nextNotificationId >= NOTIFICATION_ID_ALERT_LAST) {
            NOTIFICATION_ID_ALERT_FIRST
        } else {
            nextNotificationId + 1
        }
    }

    private fun notifyIfAllowed(id: Int, notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!allowed) {
            Log.w(TAG, "Notifications are not permitted; alert not shown.")
            return
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
    }

    private fun ensureArrivalChannel(): String {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ARRIVALS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ARRIVALS,
                    getString(R.string.channel_arrival_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = getString(R.string.channel_arrival_desc) }
            )
        }
        return CHANNEL_ARRIVALS
    }

    private fun buildOngoingNotification(text: String): Notification =
        NotificationCompat.Builder(this, SecurityCamApp.CHANNEL_SURVEILLANCE)
            .setSmallIcon(R.drawable.ic_stat_surveillance)
            .setContentTitle(getString(R.string.notification_receiver_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, ReceiverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        private const val TAG = "ReceiverService"
        const val ACTION_STOP = "com.securitycam.service.ACTION_RECEIVER_STOP"
        const val CHANNEL_ARRIVALS = "arrival_channel"
        private const val NOTIFICATION_ID_ONGOING = 1002
        private const val NOTIFICATION_ID_ALERT_FIRST = 3000
        private const val NOTIFICATION_ID_ALERT_LAST = 3019
        private const val RETRY_MIN_MS = 5_000L
        private const val RETRY_MAX_MS = 60_000L
        private const val LOCKED_OUT_RETRY_MS = ViewerServer.LOCKOUT_MS + 15_000L

        private val _state = MutableStateFlow(ReceiverState())
        val state: StateFlow<ReceiverState> = _state.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ReceiverService::class.java))
        }

        fun stop(context: Context) {
            // Delivered to a running service only; if it isn't running there is nothing to stop.
            try {
                context.startService(Intent(context, ReceiverService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                Log.w(TAG, "Receiver service was not running: ${e.message}")
            }
            _state.value = _state.value.copy(status = ReceiverState.Status.OFF, detail = "")
        }
    }
}
