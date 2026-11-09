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
    val patrolIntervalSeconds: Int = 30,
    /** Lengthen exposure and brighten images automatically when the scene is dark. */
    val nightVisionEnabled: Boolean = true,
    /** Serve the password-protected live view page on the local network. */
    val remoteViewEnabled: Boolean = false,
    /** Password for the live view page. Kept in encrypted storage, never in plain preferences. */
    val remoteViewPassword: String = "",
    /** Hold alerts briefly and skip them when an enrolled household member is recognised. */
    val knownPeopleEnabled: Boolean = false
)
