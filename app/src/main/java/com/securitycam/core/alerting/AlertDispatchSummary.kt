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
    val pushResult: DispatchResult? = null,
    val whatsappResult: DispatchResult? = null
)
