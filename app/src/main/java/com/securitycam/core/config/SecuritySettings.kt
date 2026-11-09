package com.securitycam.core.config

/**
 * Immutable configuration snapshot for surveillance parameters, alerting recipients, and SMTP credentials.
 */
data class SecuritySettings(
    val phoneNumbers: List<String> = emptyList(),
    val emailAddresses: List<String> = emptyList(),
    val smsEnabled: Boolean = false,
    val emailEnabled: Boolean = false,
    val webhookUrl: String? = null,
    val smtpHost: String = "smtp.gmail.com",
    val smtpPort: Int = 587,
    val smtpUsername: String = "",
    val smtpPassword: String = "",
    val smtpSenderEmail: String = "",
    val smtpSenderName: String = "Security Camera",
    val smtpUseTls: Boolean = true,
    val smtpUseSsl: Boolean = false,
    val smtpTimeoutMs: Int = 15000,
    val sensitivity: Int = 50,
    val cooldownSeconds: Int = 30,
    val isArmed: Boolean = true,
    val cameraFacing: String = "BACK",
    val lowLightCompensation: Boolean = true,
    val isPatrolModeEnabled: Boolean = false,
    val patrolIntervalSeconds: Int = 30
)
