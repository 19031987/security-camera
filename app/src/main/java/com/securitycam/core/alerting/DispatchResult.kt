package com.securitycam.core.alerting

/**
 * Result metrics for SMS, Webhook, or SMTP email transmission attempts.
 */
data class DispatchResult(
    val isSuccess: Boolean,
    val deliveredCount: Int,
    val failedCount: Int,
    val errorMessage: String? = null
)
