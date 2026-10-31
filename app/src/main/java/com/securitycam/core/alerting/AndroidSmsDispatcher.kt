package com.securitycam.core.alerting

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Transport abstraction for SMS sending, decoupling from Android hardware for unit testing.
 */
interface SmsTransport {
    fun isTelephonySupported(): Boolean
    fun hasSmsPermission(): Boolean
    fun sendMultipart(destination: String, text: String): Boolean
}

class DefaultAndroidSmsTransport(private val context: Context) : SmsTransport {
    override fun isTelephonySupported(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }

    override fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun sendMultipart(destination: String, text: String): Boolean {
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(text)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(destination, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(destination, null, text, null, null)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}

class AndroidSmsDispatcher(
    private val transport: SmsTransport,
    private val webhookClient: OkHttpClient = OkHttpClient(),
    private val webhookUrlProvider: () -> String? = { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : SmsDispatcher {

    override suspend fun sendSms(phoneNumbers: List<String>, message: String): DispatchResult = withContext(ioDispatcher) {
        if (phoneNumbers.isEmpty()) {
            return@withContext DispatchResult(
                isSuccess = false,
                deliveredCount = 0,
                failedCount = 0,
                errorMessage = "No recipient phone numbers configured"
            )
        }

        // Check if cellular telephony is available and permitted
        if (transport.isTelephonySupported() && transport.hasSmsPermission()) {
            var delivered = 0
            var failed = 0
            val errors = mutableListOf<String>()

            for (phone in phoneNumbers) {
                val success = transport.sendMultipart(phone, message)
                if (success) {
                    delivered++
                } else {
                    failed++
                    errors.add("Failed to transmit SMS to $phone")
                }
            }

            return@withContext DispatchResult(
                isSuccess = delivered > 0 && failed == 0,
                deliveredCount = delivered,
                failedCount = failed,
                errorMessage = if (errors.isNotEmpty()) errors.joinToString("; ") else null
            )
        }

        // Fallback: Wi-Fi device or SMS not permitted -> Attempt Webhook if configured
        val webhookUrl = webhookUrlProvider()
        if (!webhookUrl.isNullOrBlank()) {
            return@withContext sendWebhookFallback(webhookUrl, phoneNumbers, message)
        }

        return@withContext DispatchResult(
            isSuccess = false,
            deliveredCount = 0,
            failedCount = phoneNumbers.size,
            errorMessage = "Telephony unavailable/unpermitted and no Webhook URL configured"
        )
    }

    private fun sendWebhookFallback(url: String, phoneNumbers: List<String>, message: String): DispatchResult {
        return try {
            val json = JSONObject().apply {
                put("type", "SMS_ALERT_FALLBACK")
                put("recipients", JSONArray(phoneNumbers))
                put("message", message)
                put("timestamp", System.currentTimeMillis())
            }

            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(body).build()

            webhookClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    DispatchResult(isSuccess = true, deliveredCount = phoneNumbers.size, failedCount = 0)
                } else {
                    DispatchResult(
                        isSuccess = false,
                        deliveredCount = 0,
                        failedCount = phoneNumbers.size,
                        errorMessage = "Webhook HTTP error: ${response.code}"
                    )
                }
            }
        } catch (e: IOException) {
            DispatchResult(
                isSuccess = false,
                deliveredCount = 0,
                failedCount = phoneNumbers.size,
                errorMessage = "Webhook network error: ${e.message}"
            )
        }
    }
}
