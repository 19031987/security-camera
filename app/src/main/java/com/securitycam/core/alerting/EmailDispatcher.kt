package com.securitycam.core.alerting

import java.io.File

/**
 * Interface for background SMTP email dispatch with image attachments.
 */
interface EmailDispatcher {
    suspend fun sendEmail(
        emailAddresses: List<String>,
        subject: String,
        bodyHtml: String,
        attachmentFile: File?
    ): DispatchResult
}
