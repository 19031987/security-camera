package com.securitycam.core.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Factory for creating hardware-backed EncryptedSharedPreferences with graceful
 * fallback to private SharedPreferences in headless/Robolectric test environments.
 */
object SecurePreferencesFactory {
    private const val TAG = "SecurePrefsFactory"
    const val SECURE_PREFS_FILE = "security_cam_credentials"

    fun create(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            Log.w(TAG, "EncryptedSharedPreferences initialization failed (${t.message}); using private SharedPreferences fallback", t)
            context.getSharedPreferences(SECURE_PREFS_FILE, Context.MODE_PRIVATE)
        }
    }
}
