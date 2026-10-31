package com.securitycam.core.alerting

/**
 * Interface for cellular and webhook SMS dispatch.
 */
interface SmsDispatcher {
    suspend fun sendSms(phoneNumbers: List<String>, message: String): DispatchResult
}
