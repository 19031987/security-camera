package com.securitycam.core.alerting

import com.securitycam.core.remote.AlertEventHub
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.app.NotificationCompat
import com.securitycam.R
import com.securitycam.SecurityCamApp
import com.securitycam.core.config.CooldownManager
import com.securitycam.core.config.RecipientValidator
import com.securitycam.core.config.SettingsRepository
import com.securitycam.core.storage.EventRepository
import com.securitycam.core.storage.MotionEventEntity
import com.securitycam.ui.EventHistoryActivity
import com.securitycam.ui.MainActivity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class DefaultAlertCoordinator(
    private val settingsRepository: SettingsRepository,
    private val eventRepository: EventRepository,
    private val cooldownManager: CooldownManager,
    private val smsDispatcher: SmsDispatcher,
    private val emailDispatcher: EmailDispatcher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val context: Context? = null,
    /** Alerts published here are collected by receiver phones directly from this phone. */
    private val alertEventHub: AlertEventHub? = null
) : AlertCoordinator {

    companion object {
        const val TAG = "DefaultAlertCoordinator"
        const val BURST_DEBOUNCE_MS = 3000L

        /** A household member moving around is announced at most once in this period. */
        const val ARRIVAL_ANNOUNCE_INTERVAL_MS = 10 * 60 * 1000L
    }

    @Volatile
    private var lastArrivalAnnouncedAt: Long = 0L

    @Volatile
    private var lastArrivalNames: String? = null

    @Volatile
    private var lastRecordedEventTimestamp: Long = 0L

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun isArmed(): Boolean = settingsRepository.isArmed()

    override fun setArmed(armed: Boolean) {
        settingsRepository.setArmed(armed)
    }

    override suspend fun onMotionDetected(payload: AlertPayload): AlertDispatchSummary = withContext(ioDispatcher) {
        // R1 Defense-in-depth: If rapid duplicate arrives within debounce window, suppress duplicate save
        if (lastRecordedEventTimestamp != 0L && (payload.timestamp - lastRecordedEventTimestamp) < BURST_DEBOUNCE_MS) {
            return@withContext AlertDispatchSummary(
                eventId = payload.eventId,
                isArmed = isArmed(),
                isSuppressedByCooldown = true,
                smsResult = null,
                emailResult = null,
                localEventSaved = false
            )
        }
        lastRecordedEventTimestamp = payload.timestamp

        // Step 1: GUARANTEE local event is recorded into Room database first
        val eventEntity = MotionEventEntity(
            id = payload.eventId,
            timestamp = payload.timestamp,
            imagePath = payload.screenshotFile.absolutePath,
            motionScore = payload.motionScore,
            isLowLight = payload.isLowLight,
            smsStatus = MotionEventEntity.STATUS_DISABLED,
            emailStatus = MotionEventEntity.STATUS_DISABLED,
            recognizedPerson = payload.recognizedPeople
        )
        eventRepository.insertEvent(eventEntity)

        // Step 2: Arm/Disarm Gatekeeping
        if (!isArmed()) {
            return@withContext AlertDispatchSummary(
                eventId = payload.eventId,
                isArmed = false,
                isSuppressedByCooldown = false,
                smsResult = null,
                emailResult = null,
                localEventSaved = true
            )
        }

        // Step 2b: A recognised household member is recorded but never alerted on.
        // This runs before the cooldown so it does not use up the alert window.
        if (payload.suppressAlert) {
            eventRepository.updateSmsStatus(payload.eventId, MotionEventEntity.STATUS_KNOWN_PERSON)
            eventRepository.updateEmailStatus(payload.eventId, MotionEventEntity.STATUS_KNOWN_PERSON)
            announceArrival(payload)

            return@withContext AlertDispatchSummary(
                eventId = payload.eventId,
                isArmed = true,
                isSuppressedByCooldown = false,
                smsResult = null,
                emailResult = null,
                localEventSaved = true,
                isSuppressedByKnownPerson = true
            )
        }

        // Step 3: Cooldown Gatekeeping
        if (!cooldownManager.shouldAlert(payload.timestamp)) {
            eventRepository.updateSmsStatus(payload.eventId, MotionEventEntity.STATUS_SUPPRESSED)
            eventRepository.updateEmailStatus(payload.eventId, MotionEventEntity.STATUS_SUPPRESSED)

            return@withContext AlertDispatchSummary(
                eventId = payload.eventId,
                isArmed = true,
                isSuppressedByCooldown = true,
                smsResult = null,
                emailResult = null,
                localEventSaved = true
            )
        }

        // Step 4: Cooldown permits dispatch -> record transition
        cooldownManager.recordAlertDispatched(payload.timestamp)

        val settings = settingsRepository.getSettings()
        val validPhones = RecipientValidator.filterValidPhones(settings.phoneNumbers)
        val validEmails = RecipientValidator.filterValidEmails(settings.emailAddresses)

        var smsResult: DispatchResult? = null
        var emailResult: DispatchResult? = null

        val formattedDate = dateFormat.format(Date(payload.timestamp))
        val scorePercent = (payload.motionScore * 100).toInt()

        // R6: Post high-priority BigPictureStyle notification to CHANNEL_ALERTS
        postLocalAlertNotification(payload, scorePercent, formattedDate)

        alertEventHub?.publish(
            type = AlertEventHub.TYPE_ALERT,
            title = "Someone is at your door",
            message = "Motion detected at $formattedDate.",
            time = payload.timestamp,
            eventId = payload.eventId
        )

        // Step 5: Multi-channel dispatch
        if (settings.smsEnabled && validPhones.isNotEmpty()) {
            val smsText = "[SECURITY CAM ALERT] Motion detected at $formattedDate (Score: $scorePercent%, Camera: ${payload.cameraFacing}). Snapshot recorded."
            val res = smsDispatcher.sendSms(validPhones, smsText)
            smsResult = res
            eventRepository.updateSmsStatus(
                payload.eventId,
                if (res.isSuccess) MotionEventEntity.STATUS_SENT else MotionEventEntity.STATUS_FAILED
            )
        }

        if (settings.emailEnabled && validEmails.isNotEmpty()) {
            val emailSubject = "[SECURITY ALERT] Motion Detected - Camera ${payload.cameraFacing}"
            val emailBody = """
                <html>
                <body style="font-family: Arial, sans-serif; color: #333;">
                    <h2 style="color: #d32f2f;">⚠️ Security Camera Alert: Motion Detected</h2>
                    <p>Movement was detected by your Android Security Camera.</p>
                    <table style="border-collapse: collapse; width: 100%; max-width: 500px;">
                        <tr><td style="padding: 8px; border: 1px solid #ddd; font-weight: bold;">Timestamp:</td><td style="padding: 8px; border: 1px solid #ddd;">$formattedDate</td></tr>
                        <tr><td style="padding: 8px; border: 1px solid #ddd; font-weight: bold;">Event ID:</td><td style="padding: 8px; border: 1px solid #ddd;">${payload.eventId}</td></tr>
                        <tr><td style="padding: 8px; border: 1px solid #ddd; font-weight: bold;">Motion Score:</td><td style="padding: 8px; border: 1px solid #ddd;">$scorePercent%</td></tr>
                        <tr><td style="padding: 8px; border: 1px solid #ddd; font-weight: bold;">Camera Facing:</td><td style="padding: 8px; border: 1px solid #ddd;">${payload.cameraFacing}</td></tr>
                    </table>
                    <p style="margin-top: 16px;">The captured intruder screenshot is attached below.</p>
                </body>
                </html>
            """.trimIndent()

            val res = emailDispatcher.sendEmail(validEmails, emailSubject, emailBody, payload.screenshotFile)
            emailResult = res
            eventRepository.updateEmailStatus(
                payload.eventId,
                if (res.isSuccess) MotionEventEntity.STATUS_SENT else MotionEventEntity.STATUS_FAILED
            )
        }

        AlertDispatchSummary(
            eventId = payload.eventId,
            isArmed = true,
            isSuppressedByCooldown = false,
            smsResult = smsResult,
            emailResult = emailResult,
            localEventSaved = true
        )
    }

    override suspend fun sendTestAlert(): AlertDispatchSummary = withContext(ioDispatcher) {
        val testId = "test-" + UUID.randomUUID().toString().take(8)
        val now = System.currentTimeMillis()
        val formattedDate = dateFormat.format(Date(now))
        val settings = settingsRepository.getSettings()

        val validPhones = RecipientValidator.filterValidPhones(settings.phoneNumbers)
        val validEmails = RecipientValidator.filterValidEmails(settings.emailAddresses)

        var smsResult: DispatchResult? = null
        var emailResult: DispatchResult? = null

        if (validPhones.isNotEmpty()) {
            val message = "[SECURITY CAM DIAGNOSTIC] Test alert dispatched successfully at $formattedDate."
            smsResult = smsDispatcher.sendSms(validPhones, message)
        }

        if (validEmails.isNotEmpty()) {
            val subject = "[SECURITY CAM DIAGNOSTIC] Test Alert Email"
            val html = """
                <html>
                <body>
                    <h3>Security Camera Diagnostic Verification</h3>
                    <p>This is a test notification confirming your email alert pipeline is configured correctly.</p>
                    <p>Time: $formattedDate</p>
                </body>
                </html>
            """.trimIndent()
            emailResult = emailDispatcher.sendEmail(validEmails, subject, html, null)
        }

        AlertDispatchSummary(
            eventId = testId,
            isArmed = isArmed(),
            isSuppressedByCooldown = false,
            smsResult = smsResult,
            emailResult = emailResult,
            localEventSaved = false
        )
    }

    /**
     * Tells receiver phones that an enrolled household member is home. One person walking
     * about produces many events, so the same people are announced at most once per
     * [ARRIVAL_ANNOUNCE_INTERVAL_MS].
     */
    private fun announceArrival(payload: AlertPayload) {
        val hub = alertEventHub ?: return
        val names = payload.recognizedPeople?.takeIf { it.isNotBlank() } ?: return
        val recentlyAnnounced = names == lastArrivalNames &&
            payload.timestamp - lastArrivalAnnouncedAt < ARRIVAL_ANNOUNCE_INTERVAL_MS
        if (recentlyAnnounced) return

        lastArrivalAnnouncedAt = payload.timestamp
        lastArrivalNames = names
        val verb = if (names.contains(",")) "are" else "is"
        hub.publish(
            type = AlertEventHub.TYPE_ARRIVAL,
            title = "$names $verb home",
            message = "Recognised at ${dateFormat.format(Date(payload.timestamp))}.",
            time = payload.timestamp,
            eventId = payload.eventId
        )
    }

    private fun postLocalAlertNotification(
        payload: AlertPayload,
        scorePercent: Int,
        formattedDate: String
    ) {
        val ctx = context ?: runCatching { SecurityCamApp.getAppContext() }.getOrNull() ?: return
        try {
            val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // 1. Decode screenshot bitmap for BigPictureStyle
            val screenshotBitmap = try {
                if (payload.screenshotFile.exists() && payload.screenshotFile.length() > 0) {
                    BitmapFactory.decodeFile(payload.screenshotFile.absolutePath)
                } else null
            } catch (e: Exception) {
                null
            }

            // 2. ContentIntent: Open Live Preview (MainActivity)
            val openAppIntent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("EXTRA_EVENT_ID", payload.eventId)
            }
            val contentPendingIntent = PendingIntent.getActivity(
                ctx,
                payload.eventId.hashCode(),
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 3. ActionIntent: Open Event History Gallery
            val historyIntent = Intent(ctx, EventHistoryActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("EXTRA_EVENT_ID", payload.eventId)
            }
            val historyPendingIntent = PendingIntent.getActivity(
                ctx,
                payload.eventId.hashCode() + 1,
                historyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 4. Build Notification with BigPictureStyle, High Priority, Sound & Vibration
            val summaryText = "Motion detected (${scorePercent}%, Camera: ${payload.cameraFacing})"
            val builder = NotificationCompat.Builder(ctx, SecurityCamApp.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setContentTitle("🚨 Motion Detected!")
                .setContentText(summaryText)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(contentPendingIntent)
                .addAction(R.drawable.ic_history, "View History", historyPendingIntent)

            if (screenshotBitmap != null) {
                builder.setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(screenshotBitmap)
                        .setSummaryText(summaryText)
                )
            } else {
                builder.setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("Motion detected on Camera ${payload.cameraFacing} at $formattedDate (Score: $scorePercent%). Snapshot recorded.")
                )
            }

            val notificationId = SecurityCamApp.NOTIFICATION_ID_ALERT_BASE + (payload.eventId.hashCode() and 0x7FFF)
            notificationManager.notify(notificationId, builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post local alert notification: ${e.message}", e)
        }
    }
}
