package com.securitycam.core.config

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction for persisting and observing application surveillance settings.
 */
interface SettingsRepository {
    fun getSettings(): SecuritySettings
    fun updateSettings(settings: SecuritySettings)
    fun observeSettings(): Flow<SecuritySettings>

    fun isArmed(): Boolean
    fun setArmed(armed: Boolean)

    fun getSensitivity(): Int
    fun setSensitivity(sensitivity: Int)

    fun getCooldownSeconds(): Int
    fun setCooldownSeconds(seconds: Int)

    fun getPhoneNumbers(): List<String>
    fun setPhoneNumbers(numbers: List<String>)

    fun getEmailAddresses(): List<String>
    fun setEmailAddresses(emails: List<String>)

    fun resetToDefaults()
}
