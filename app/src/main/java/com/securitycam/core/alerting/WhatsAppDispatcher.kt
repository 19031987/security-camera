package com.securitycam.core.alerting

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A person who gets WhatsApp alerts: their number and the key CallMeBot gave them. */
data class WhatsAppContact(
    val phone: String = "",
    val apiKey: String = ""
)

/** Sends a WhatsApp text message to each contact. */
interface WhatsAppDispatcher {
    suspend fun send(contacts: List<WhatsAppContact>, message: String): DispatchResult
}

/**
 * WhatsApp alerts through CallMeBot (https://www.callmebot.com), a free third-party service.
 *
 * WhatsApp has no way for an app to message people directly. With CallMeBot each contact
 * opts in once by sending a WhatsApp message to the CallMeBot number and receives a personal
 * key; messages can then be sent to that contact with their number and key. Text only.
 *
 * CallMeBot is not run by WhatsApp or Meta. It sees the phone numbers and the alert text.
 */
class CallMeBotWhatsAppDispatcher(
    private val client: OkHttpClient = defaultClient(),
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : WhatsAppDispatcher {

    override suspend fun send(contacts: List<WhatsAppContact>, message: String): DispatchResult =
        withContext(ioDispatcher) {
            val valid = contacts.filter { isValidContact(it) }
            if (valid.isEmpty()) {
                return@withContext DispatchResult(
                    isSuccess = false, deliveredCount = 0, failedCount = 0,
                    errorMessage = "No WhatsApp contacts are set up"
                )
            }

            var delivered = 0
            var firstError: String? = null
            for (contact in valid) {
                val error = sendOne(contact, message)
                if (error == null) delivered++ else if (firstError == null) firstError = error
            }
            DispatchResult(
                isSuccess = delivered > 0,
                deliveredCount = delivered,
                failedCount = valid.size - delivered,
                errorMessage = firstError
            )
        }

    /** Returns null on success, or a short description of what went wrong. */
    private fun sendOne(contact: WhatsAppContact, message: String): String? {
        val url = endpoint.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("phone", contact.phone)
            ?.addQueryParameter("text", message)
            ?.addQueryParameter("apikey", contact.apiKey)
            ?.build()
            ?: return "The WhatsApp service address is not valid"
        return try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    !response.isSuccessful -> "WhatsApp service answered ${response.code} for ${maskPhone(contact.phone)}"
                    // The service reports some problems (such as a wrong key) in the page text.
                    REJECTION_WORDS.any { body.contains(it, ignoreCase = true) } ->
                        "WhatsApp service rejected the message for ${maskPhone(contact.phone)}. Check the key."
                    else -> null
                }
            }
        } catch (e: Exception) {
            "Could not reach the WhatsApp service: ${e.message}"
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.callmebot.com/whatsapp.php"
        private val KEY_PATTERN = Regex("^[A-Za-z0-9]{3,32}$")
        private val PHONE_PATTERN = Regex("^\\+[1-9][0-9]{6,14}$")
        private val REJECTION_WORDS = listOf("apikey is invalid", "invalid apikey", "error")

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        fun isValidApiKey(apiKey: String): Boolean = KEY_PATTERN.matches(apiKey)

        fun isValidContact(contact: WhatsAppContact): Boolean =
            PHONE_PATTERN.matches(contact.phone) && isValidApiKey(contact.apiKey)

        /** Keeps full phone numbers out of error messages and logs. */
        fun maskPhone(phone: String): String =
            if (phone.length <= 4) "****" else "*".repeat(phone.length - 4) + phone.takeLast(4)
    }
}
