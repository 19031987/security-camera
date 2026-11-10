package com.securitycam.core.alerting

/**
 * Detailed outcome summary of motion alerting evaluation and dispatch.
 */
data class AlertDispatchSummary(
    val eventId: String,
    val isArmed: Boolean,
    val isSuppressedByCooldown: Boolean,
    val smsResult: DispatchResult?,
    val emailResult: DispatchResult?,
    val localEventSaved: Boolean,
    val isSuppressedByKnownPerson: Boolean = false,
    /** True when the SMS alert went out as a picture message rather than text only. */
    val smsSentAsPicture: Boolean = false,
    val driveResult: DispatchResult? = null,
    /** True when the event was recorded but the alert rules (mode or chosen kinds) kept it quiet. */
    val isQuietByRules: Boolean = false,
    val pushResult: DispatchResult? = null
)
