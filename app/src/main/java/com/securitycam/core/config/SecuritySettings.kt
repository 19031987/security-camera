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
    val knownPeopleEnabled: Boolean = false,
    /** Send SMS alerts as picture messages (MMS); falls back to text if that fails. */
    val smsSendPicture: Boolean = true,
    /** Upload each alert picture to the owner's Google Drive. */
    val driveEnabled: Boolean = false,
    /** Id of the "Security Camera" folder in Drive, remembered after the first upload. */
    val driveFolderId: String = "",
    /** "AWAY" sends alerts; "HOME" records events quietly. See AlertPolicy. */
    val alertMode: String = "AWAY",
    val notifyPerson: Boolean = true,
    val notifyAnimal: Boolean = true,
    val notifyVehicle: Boolean = false,
    /** Movement the model could not put a name to (curtains, shadows, branches). */
    val notifyOther: Boolean = false,
    /** Motion zone, as fractions of the picture. The defaults cover the whole picture. */
    val zoneLeft: Float = 0f,
    val zoneTop: Float = 0f,
    val zoneRight: Float = 1f,
    val zoneBottom: Float = 1f,
    /** Send push notifications to linked phones through the owner's Firebase project. */
    val pushEnabled: Boolean = false,
    /** Camera role: the code family phones enter to receive this camera's notifications. Secret. */
    val pushLinkCode: String = "",
    /** Camera role: the Firebase service-account key file's contents. Secret. */
    val pushServiceAccountJson: String = "",
    /** Also send a quiet notification when an enrolled household member is recognised. */
    val pushNotifyArrivals: Boolean = true,
    /** What this phone is for: "CAMERA", "RECEIVER", or empty until chosen on first launch. */
    val deviceRole: String = "",
    /** Receiver role: the camera's link code this phone is subscribed with. Secret. */
    val receiverLinkCode: String = ""
) {
    companion object {
        const val ROLE_CAMERA = "CAMERA"
        const val ROLE_RECEIVER = "RECEIVER"
    }
}
