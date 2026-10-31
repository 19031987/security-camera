package com.securitycam.core.alerting

import com.securitycam.core.config.CooldownManager
import com.securitycam.core.config.RecipientValidator
import com.securitycam.core.config.SettingsRepository
import com.securitycam.core.storage.EventRepository
import com.securitycam.core.storage.MotionEventEntity
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AlertCoordinator {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun isArmed(): Boolean = settingsRepository.isArmed()

    override fun setArmed(armed: Boolean) {
        settingsRepository.setArmed(armed)
    }

    override suspend fun onMotionDetected(payload: AlertPayload): AlertDispatchSummary = withContext(ioDispatcher) {
        // Step 1: GUARANTEE local event is recorded into Room database first
        val eventEntity = MotionEventEntity(
            id = payload.eventId,
            timestamp = payload.timestamp,
            imagePath = payload.screenshotFile.absolutePath,
            motionScore = payload.motionScore,
            isLowLight = false,
            smsStatus = MotionEventEntity.STATUS_DISABLED,
            emailStatus = MotionEventEntity.STATUS_DISABLED
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
}
