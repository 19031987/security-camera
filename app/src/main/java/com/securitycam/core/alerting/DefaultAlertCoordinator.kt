package com.securitycam.core.alerting

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
    private val pictureDispatcher: PictureMessageDispatcher? = null,
    private val cloudUploader: CloudUploader? = null
) : AlertCoordinator {

    companion object {
        const val TAG = "DefaultAlertCoordinator"
        const val BURST_DEBOUNCE_MS = 3000L
    }

    @Volatile
    private var lastRecordedEventTimestamp: Long = 0L

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

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
            recognizedPerson = payload.recognizedPeople,
            detectedLabel = payload.detectedLabel
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

        // Step 2c: Alert rules. Home mode and kinds the owner didn't select are recorded quietly,
        // again without using up the cooldown.
        if (AlertPolicy.decide(settingsRepository.getSettings(), payload.detectedKind, payload.isLowLight) !=
            AlertPolicy.Verdict.ALERT
        ) {
            eventRepository.updateSmsStatus(payload.eventId, MotionEventEntity.STATUS_QUIET)
            eventRepository.updateEmailStatus(payload.eventId, MotionEventEntity.STATUS_QUIET)

            return@withContext AlertDispatchSummary(
                eventId = payload.eventId,
                isArmed = true,
                isSuppressedByCooldown = false,
                smsResult = null,
                emailResult = null,
                localEventSaved = true,
                isQuietByRules = true
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
        var smsSentAsPicture = false

        val formattedDate = dateFormat.format(Date(payload.timestamp))
        val scorePercent = (payload.motionScore * 100).toInt()
        // "Person", "Animal (dog)", "Vehicle (car)", or "Motion" when the model couldn't tell.
        val what = payload.detectedLabel?.takeIf { it.isNotBlank() } ?: "Motion"

        // R6: Post high-priority BigPictureStyle notification to CHANNEL_ALERTS
        postLocalAlertNotification(payload, scorePercent, formattedDate)

        // Step 5: Multi-channel dispatch
        if (settings.smsEnabled && validPhones.isNotEmpty()) {
            val smsText = "[SECURITY CAM ALERT] $what detected at $formattedDate (Score: $scorePercent%, Camera: ${payload.cameraFacing}). Snapshot recorded."
            // Picture message first; if the network won't take it, the text still goes out.
            var res: DispatchResult? = null
            if (settings.smsSendPicture && pictureDispatcher != null && payload.screenshotFile.length() > 0) {
                val picture = pictureDispatcher.send(
                    validPhones, "Security Camera: $what detected at $formattedDate.", payload.screenshotFile
                )
                if (picture.isSuccess && picture.failedCount == 0) {
                    res = picture
                    smsSentAsPicture = true
                }
            }
            if (res == null) {
                res = smsDispatcher.sendSms(validPhones, smsText)
            }
            smsResult = res
            eventRepository.updateSmsStatus(
                payload.eventId,
                if (res.isSuccess) MotionEventEntity.STATUS_SENT else MotionEventEntity.STATUS_FAILED
            )
        }

        if (settings.emailEnabled && validEmails.isNotEmpty()) {
            val emailSubject = "[SECURITY ALERT] $what Detected - Camera ${payload.cameraFacing}"
            val emailBody = """
                <html>
                <body style="font-family: Arial, sans-serif; color: #333;">
                    <h2 style="color: #d32f2f;">⚠️ Security Camera Alert: $what Detected</h2>
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

        var driveResult: DispatchResult? = null
        if (settings.driveEnabled && cloudUploader != null) {
            val name = "alert_" + fileNameFormat.format(Date(payload.timestamp)) +
                "_" + what.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-') + ".jpg"
            driveResult = cloudUploader.upload(payload.screenshotFile, name)
        }

        AlertDispatchSummary(
            eventId = payload.eventId,
            isArmed = true,
            isSuppressedByCooldown = false,
            smsResult = smsResult,
            emailResult = emailResult,
            localEventSaved = true,
            smsSentAsPicture = smsSentAsPicture,
            driveResult = driveResult
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
