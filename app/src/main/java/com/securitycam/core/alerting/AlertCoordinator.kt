package com.securitycam.core.alerting

/**
 * Gatekeeper for motion alert evaluation, cooldown management, and multi-channel dispatch.
 */
interface AlertCoordinator {
    suspend fun onMotionDetected(payload: AlertPayload): AlertDispatchSummary
    fun isArmed(): Boolean
    fun setArmed(armed: Boolean)
    suspend fun sendTestAlert(): AlertDispatchSummary
}
