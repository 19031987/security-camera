package com.securitycam.core.alerting

import java.io.File

/** Sends a push notification to the owner's phone. */
interface PushDispatcher {
    /**
     * @param imageFile optional screenshot shown in the notification
     * @param urgent true for possible intruders (loud), false for information such as a
     *               household member arriving (normal priority)
     */
    suspend fun sendPush(title: String, message: String, imageFile: File?, urgent: Boolean): DispatchResult
}
