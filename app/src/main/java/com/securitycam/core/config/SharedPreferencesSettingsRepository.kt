package com.securitycam.core.config

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class SharedPreferencesSettingsRepository(
    private val prefs: SharedPreferences,
    private val securePrefs: SharedPreferences = prefs,
    private val gson: Gson = Gson()
) : SettingsRepository {

    companion object {
        const val PREF_FILE_NAME = "security_cam_settings"
        const val SECURE_PREFS_FILE = "security_cam_credentials"

        private const val KEY_PHONE_NUMBERS = "pref_phone_numbers"
        private const val KEY_EMAIL_ADDRESSES = "pref_email_addresses"
        private const val KEY_SMS_ENABLED = "pref_sms_enabled"
        private const val KEY_EMAIL_ENABLED = "pref_email_enabled"
        private const val KEY_WEBHOOK_URL = "pref_webhook_url"
        private const val KEY_SMTP_HOST = "pref_smtp_host"
        private const val KEY_SMTP_PORT = "pref_smtp_port"
        private const val KEY_SMTP_USERNAME = "pref_smtp_username"
        private const val KEY_SMTP_PASSWORD = "pref_smtp_password"
        private const val KEY_SMTP_SENDER_EMAIL = "pref_smtp_sender_email"
        private const val KEY_SMTP_SENDER_NAME = "pref_smtp_sender_name"
        private const val KEY_SMTP_USE_TLS = "pref_smtp_use_tls"
        private const val KEY_SMTP_USE_SSL = "pref_smtp_use_ssl"
        private const val KEY_SMTP_TIMEOUT = "pref_smtp_timeout"
        private const val KEY_SENSITIVITY = "pref_sensitivity"
        private const val KEY_COOLDOWN_SECONDS = "pref_cooldown_seconds"
        private const val KEY_IS_ARMED = "pref_is_armed"
        private const val KEY_CAMERA_FACING = "pref_camera_facing"
        private const val KEY_LOW_LIGHT = "pref_low_light"
        private const val KEY_PATROL_MODE_ENABLED = "pref_patrol_mode_enabled"
        private const val KEY_PATROL_INTERVAL_SECONDS = "pref_patrol_interval_seconds"
        private const val KEY_NIGHT_VISION = "pref_night_vision"
        private const val KEY_REMOTE_VIEW_ENABLED = "pref_remote_view_enabled"
        private const val KEY_REMOTE_VIEW_PASSWORD = "pref_remote_view_password"
        private const val KEY_KNOWN_PEOPLE_ENABLED = "pref_known_people_enabled"
        private const val KEY_SMS_SEND_PICTURE = "pref_sms_send_picture"
        private const val KEY_DRIVE_ENABLED = "pref_drive_enabled"
        private const val KEY_DRIVE_FOLDER_ID = "pref_drive_folder_id"
        private const val KEY_ALERT_MODE = "pref_alert_mode"
        private const val KEY_NOTIFY_PERSON = "pref_notify_person"
        private const val KEY_NOTIFY_ANIMAL = "pref_notify_animal"
        private const val KEY_NOTIFY_VEHICLE = "pref_notify_vehicle"
        private const val KEY_NOTIFY_OTHER = "pref_notify_other"
        private const val KEY_ZONE_LEFT = "pref_zone_left"
        private const val KEY_ZONE_TOP = "pref_zone_top"
        private const val KEY_ZONE_RIGHT = "pref_zone_right"
        private const val KEY_ZONE_BOTTOM = "pref_zone_bottom"
        private const val KEY_PUSH_ENABLED = "pref_push_enabled"
        private const val KEY_PUSH_LINK_CODE = "pref_push_link_code"
        private const val KEY_PUSH_SERVICE_ACCOUNT = "pref_push_service_account"
        private const val KEY_PUSH_ARRIVALS = "pref_push_arrivals"
        private const val KEY_DEVICE_ROLE = "pref_device_role"
        private const val KEY_RECEIVER_LINK_CODE = "pref_receiver_link_code"
        private const val KEY_RECORD_CLIPS = "pref_record_clips"
        private const val KEY_SCHEDULE_ENABLED = "pref_schedule_enabled"
        private const val KEY_SCHEDULE_START = "pref_schedule_away_start"
        private const val KEY_SCHEDULE_END = "pref_schedule_away_end"
        private const val KEY_SCHEDULE_DAYS = "pref_schedule_away_days"

        fun create(context: Context): SharedPreferencesSettingsRepository {
            val sp = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
            val secureSp = SecurePreferencesFactory.create(context)
            return SharedPreferencesSettingsRepository(sp, secureSp)
        }
    }

    private val stringListType = object : TypeToken<List<String>>() {}.type

    override fun getSettings(): SecuritySettings {
        val phonesJson = prefs.getString(KEY_PHONE_NUMBERS, null)
        val emailsJson = prefs.getString(KEY_EMAIL_ADDRESSES, null)

        val phones: List<String> = if (!phonesJson.isNullOrBlank()) {
            try {
                gson.fromJson(phonesJson, stringListType) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val emails: List<String> = if (!emailsJson.isNullOrBlank()) {
            try {
                gson.fromJson(emailsJson, stringListType) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        // Retrieve SMTP password from secure encrypted storage (with seamless migration from legacy plaintext prefs)
        val password = if (securePrefs.contains(KEY_SMTP_PASSWORD)) {
            securePrefs.getString(KEY_SMTP_PASSWORD, "") ?: ""
        } else if (prefs.contains(KEY_SMTP_PASSWORD)) {
            val legacyPassword = prefs.getString(KEY_SMTP_PASSWORD, "") ?: ""
            securePrefs.edit().putString(KEY_SMTP_PASSWORD, legacyPassword).apply()
            prefs.edit().remove(KEY_SMTP_PASSWORD).apply()
            legacyPassword
        } else {
            ""
        }

        return SecuritySettings(
            phoneNumbers = phones,
            emailAddresses = emails,
            smsEnabled = prefs.getBoolean(KEY_SMS_ENABLED, false),
            emailEnabled = prefs.getBoolean(KEY_EMAIL_ENABLED, false),
            webhookUrl = prefs.getString(KEY_WEBHOOK_URL, null),
            smtpHost = prefs.getString(KEY_SMTP_HOST, "smtp.gmail.com") ?: "smtp.gmail.com",
            smtpPort = prefs.getInt(KEY_SMTP_PORT, 587),
            smtpUsername = prefs.getString(KEY_SMTP_USERNAME, "") ?: "",
            smtpPassword = password,
            smtpSenderEmail = prefs.getString(KEY_SMTP_SENDER_EMAIL, "") ?: "",
            smtpSenderName = prefs.getString(KEY_SMTP_SENDER_NAME, "Security Camera") ?: "Security Camera",
            smtpUseTls = prefs.getBoolean(KEY_SMTP_USE_TLS, true),
            smtpUseSsl = prefs.getBoolean(KEY_SMTP_USE_SSL, false),
            smtpTimeoutMs = prefs.getInt(KEY_SMTP_TIMEOUT, 15000),
            sensitivity = prefs.getInt(KEY_SENSITIVITY, 50),
            cooldownSeconds = prefs.getInt(KEY_COOLDOWN_SECONDS, 30),
            isArmed = prefs.getBoolean(KEY_IS_ARMED, true),
            cameraFacing = prefs.getString(KEY_CAMERA_FACING, "BACK") ?: "BACK",
            lowLightCompensation = prefs.getBoolean(KEY_LOW_LIGHT, true),
            isPatrolModeEnabled = prefs.getBoolean(KEY_PATROL_MODE_ENABLED, false),
            patrolIntervalSeconds = prefs.getInt(KEY_PATROL_INTERVAL_SECONDS, 30),
            nightVisionEnabled = prefs.getBoolean(KEY_NIGHT_VISION, true),
            remoteViewEnabled = prefs.getBoolean(KEY_REMOTE_VIEW_ENABLED, false),
            remoteViewPassword = securePrefs.getString(KEY_REMOTE_VIEW_PASSWORD, "") ?: "",
            knownPeopleEnabled = prefs.getBoolean(KEY_KNOWN_PEOPLE_ENABLED, false),
            smsSendPicture = prefs.getBoolean(KEY_SMS_SEND_PICTURE, true),
            driveEnabled = prefs.getBoolean(KEY_DRIVE_ENABLED, false),
            driveFolderId = prefs.getString(KEY_DRIVE_FOLDER_ID, "") ?: "",
            alertMode = prefs.getString(KEY_ALERT_MODE, "AWAY") ?: "AWAY",
            notifyPerson = prefs.getBoolean(KEY_NOTIFY_PERSON, true),
            notifyAnimal = prefs.getBoolean(KEY_NOTIFY_ANIMAL, true),
            notifyVehicle = prefs.getBoolean(KEY_NOTIFY_VEHICLE, false),
            notifyOther = prefs.getBoolean(KEY_NOTIFY_OTHER, false),
            zoneLeft = prefs.getFloat(KEY_ZONE_LEFT, 0f),
            zoneTop = prefs.getFloat(KEY_ZONE_TOP, 0f),
            zoneRight = prefs.getFloat(KEY_ZONE_RIGHT, 1f),
            zoneBottom = prefs.getFloat(KEY_ZONE_BOTTOM, 1f),
            pushEnabled = prefs.getBoolean(KEY_PUSH_ENABLED, false),
            pushLinkCode = securePrefs.getString(KEY_PUSH_LINK_CODE, "") ?: "",
            pushServiceAccountJson = securePrefs.getString(KEY_PUSH_SERVICE_ACCOUNT, "") ?: "",
            pushNotifyArrivals = prefs.getBoolean(KEY_PUSH_ARRIVALS, true),
            deviceRole = prefs.getString(KEY_DEVICE_ROLE, "") ?: "",
            receiverLinkCode = securePrefs.getString(KEY_RECEIVER_LINK_CODE, "") ?: "",
            recordClips = prefs.getBoolean(KEY_RECORD_CLIPS, true),
            scheduleEnabled = prefs.getBoolean(KEY_SCHEDULE_ENABLED, false),
            scheduleAwayStartMinutes = prefs.getInt(KEY_SCHEDULE_START, 9 * 60),
            scheduleAwayEndMinutes = prefs.getInt(KEY_SCHEDULE_END, 18 * 60),
            scheduleAwayDays = prefs.getInt(KEY_SCHEDULE_DAYS, 31)
        )
    }

    override fun updateSettings(settings: SecuritySettings) {
        // Persist password to secure preferences and ensure removed from plaintext prefs
        securePrefs.edit()
            .putString(KEY_SMTP_PASSWORD, settings.smtpPassword)
            .putString(KEY_REMOTE_VIEW_PASSWORD, settings.remoteViewPassword)
            .putString(KEY_PUSH_LINK_CODE, settings.pushLinkCode)
            .putString(KEY_PUSH_SERVICE_ACCOUNT, settings.pushServiceAccountJson)
            .putString(KEY_RECEIVER_LINK_CODE, settings.receiverLinkCode)
            .apply()

        prefs.edit()
            .putString(KEY_PHONE_NUMBERS, gson.toJson(settings.phoneNumbers))
            .putString(KEY_EMAIL_ADDRESSES, gson.toJson(settings.emailAddresses))
            .putBoolean(KEY_SMS_ENABLED, settings.smsEnabled)
            .putBoolean(KEY_EMAIL_ENABLED, settings.emailEnabled)
            .putString(KEY_WEBHOOK_URL, settings.webhookUrl)
            .putString(KEY_SMTP_HOST, settings.smtpHost)
            .putInt(KEY_SMTP_PORT, settings.smtpPort)
            .putString(KEY_SMTP_USERNAME, settings.smtpUsername)
            .remove(KEY_SMTP_PASSWORD) // Ensure never stored in plaintext prefs
            .putString(KEY_SMTP_SENDER_EMAIL, settings.smtpSenderEmail)
            .putString(KEY_SMTP_SENDER_NAME, settings.smtpSenderName)
            .putBoolean(KEY_SMTP_USE_TLS, settings.smtpUseTls)
            .putBoolean(KEY_SMTP_USE_SSL, settings.smtpUseSsl)
            .putInt(KEY_SMTP_TIMEOUT, settings.smtpTimeoutMs)
            .putInt(KEY_SENSITIVITY, settings.sensitivity.coerceIn(1, 100))
            .putInt(KEY_COOLDOWN_SECONDS, settings.cooldownSeconds.coerceIn(10, 300))
            .putBoolean(KEY_IS_ARMED, settings.isArmed)
            .putString(KEY_CAMERA_FACING, settings.cameraFacing)
            .putBoolean(KEY_LOW_LIGHT, settings.lowLightCompensation)
            .putBoolean(KEY_PATROL_MODE_ENABLED, settings.isPatrolModeEnabled)
            .putInt(KEY_PATROL_INTERVAL_SECONDS, settings.patrolIntervalSeconds.coerceIn(10, 300))
            .putBoolean(KEY_NIGHT_VISION, settings.nightVisionEnabled)
            .putBoolean(KEY_REMOTE_VIEW_ENABLED, settings.remoteViewEnabled)
            .putBoolean(KEY_KNOWN_PEOPLE_ENABLED, settings.knownPeopleEnabled)
            .putBoolean(KEY_SMS_SEND_PICTURE, settings.smsSendPicture)
            .putBoolean(KEY_DRIVE_ENABLED, settings.driveEnabled)
            .putString(KEY_DRIVE_FOLDER_ID, settings.driveFolderId)
            .putString(KEY_ALERT_MODE, settings.alertMode)
            .putBoolean(KEY_NOTIFY_PERSON, settings.notifyPerson)
            .putBoolean(KEY_NOTIFY_ANIMAL, settings.notifyAnimal)
            .putBoolean(KEY_NOTIFY_VEHICLE, settings.notifyVehicle)
            .putBoolean(KEY_NOTIFY_OTHER, settings.notifyOther)
            .putFloat(KEY_ZONE_LEFT, settings.zoneLeft)
            .putFloat(KEY_ZONE_TOP, settings.zoneTop)
            .putFloat(KEY_ZONE_RIGHT, settings.zoneRight)
            .putFloat(KEY_ZONE_BOTTOM, settings.zoneBottom)
            .putBoolean(KEY_PUSH_ENABLED, settings.pushEnabled)
            .putBoolean(KEY_PUSH_ARRIVALS, settings.pushNotifyArrivals)
            .putString(KEY_DEVICE_ROLE, settings.deviceRole)
            .putBoolean(KEY_RECORD_CLIPS, settings.recordClips)
            .putBoolean(KEY_SCHEDULE_ENABLED, settings.scheduleEnabled)
            .putInt(KEY_SCHEDULE_START, settings.scheduleAwayStartMinutes)
            .putInt(KEY_SCHEDULE_END, settings.scheduleAwayEndMinutes)
            .putInt(KEY_SCHEDULE_DAYS, settings.scheduleAwayDays)
            .apply()
    }

    override fun observeSettings(): Flow<SecuritySettings> = callbackFlow {
        trySend(getSettings())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(getSettings())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.distinctUntilChanged()

    override fun isArmed(): Boolean = prefs.getBoolean(KEY_IS_ARMED, true)

    override fun setArmed(armed: Boolean) {
        prefs.edit().putBoolean(KEY_IS_ARMED, armed).apply()
    }

    override fun getSensitivity(): Int = prefs.getInt(KEY_SENSITIVITY, 50)

    override fun setSensitivity(sensitivity: Int) {
        prefs.edit().putInt(KEY_SENSITIVITY, sensitivity.coerceIn(1, 100)).apply()
    }

    override fun getCooldownSeconds(): Int = prefs.getInt(KEY_COOLDOWN_SECONDS, 30)

    override fun setCooldownSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_COOLDOWN_SECONDS, seconds.coerceIn(10, 300)).apply()
    }

    override fun getPhoneNumbers(): List<String> = getSettings().phoneNumbers

    override fun setPhoneNumbers(numbers: List<String>) {
        val valid = RecipientValidator.filterValidPhones(numbers)
        prefs.edit().putString(KEY_PHONE_NUMBERS, gson.toJson(valid)).apply()
    }

    override fun getEmailAddresses(): List<String> = getSettings().emailAddresses

    override fun setEmailAddresses(emails: List<String>) {
        val valid = RecipientValidator.filterValidEmails(emails)
        prefs.edit().putString(KEY_EMAIL_ADDRESSES, gson.toJson(valid)).apply()
    }

    override fun isPatrolModeEnabled(): Boolean = prefs.getBoolean(KEY_PATROL_MODE_ENABLED, false)

    override fun setPatrolModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PATROL_MODE_ENABLED, enabled).apply()
    }

    override fun getPatrolIntervalSeconds(): Int = prefs.getInt(KEY_PATROL_INTERVAL_SECONDS, 30)

    override fun setPatrolIntervalSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_PATROL_INTERVAL_SECONDS, seconds.coerceIn(10, 300)).apply()
    }

    override fun resetToDefaults() {
        prefs.edit().clear().apply()
        securePrefs.edit().clear().apply()
    }
}
