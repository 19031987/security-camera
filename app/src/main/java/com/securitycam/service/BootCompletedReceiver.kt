package com.securitycam.service

import com.securitycam.core.config.SecuritySettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.securitycam.SecurityCamApp
import com.securitycam.core.config.SharedPreferencesSettingsRepository

/**
 * BroadcastReceiver triggered on system boot (RECEIVE_BOOT_COMPLETED).
 * Automatically launches SurveillanceService as a foreground service if surveillance is armed.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BootCompletedReceiver"
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == ACTION_QUICKBOOT) {
            Log.i(TAG, "Device boot completed broadcast received ($action). Checking armed status...")

            val settingsRepo = try {
                SecurityCamApp.getInstance().settingsRepository
            } catch (e: Exception) {
                SharedPreferencesSettingsRepository.create(context)
            }

            val settings = settingsRepo.getSettings()
            if (settings.deviceRole == SecuritySettings.ROLE_RECEIVER) {
                // This phone only receives alerts; it must not start its own camera.
                if (settings.receiverEnabled) {
                    Log.i(TAG, "Receiver role. Reconnecting to the camera phone...")
                    ReceiverService.start(context)
                }
                return
            }

            val armed = settingsRepo.isArmed()
            if (armed) {
                Log.i(TAG, "Surveillance is armed. Starting SurveillanceService in foreground...")
                SurveillanceService.startService(context)
            } else {
                Log.i(TAG, "Surveillance is disarmed. Auto-start skipped.")
            }
        }
    }
}
