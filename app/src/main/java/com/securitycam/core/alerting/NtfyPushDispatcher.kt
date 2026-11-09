package com.securitycam.core.alerting

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Push notifications through ntfy (https://ntfy.sh), an open-source notification service.
 * The camera publishes to a topic; the owner's phone subscribes to the same topic in the
 * ntfy app. No account is needed.
 *
 * Anyone who knows the topic name can read what is published to it, including screenshots,
 * so the topic is treated as a secret: it is generated randomly and stored encrypted.
 */
class NtfyPushDispatcher(
    private val configProvider: () -> Config,
    private val client: OkHttpClient = defaultClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PushDispatcher {

    data class Config(val serverUrl: String, val topic: String)

    override suspend fun sendPush(
        title: String,
        message: String,
        imageFile: File?,
        urgent: Boolean
    ): DispatchResult = withContext(ioDispatcher) {
        val config = configProvider()
        if (!isValidTopic(config.topic)) {
            return@withContext failure("No notification topic is set")
        }
        val base = normalizeServerUrl(config.serverUrl)?.toHttpUrlOrNull()
            ?: return@withContext failure("The notification server address is not valid")
        val url = base.newBuilder().addPathSegment(config.topic).build()

        try {
            val builder = Request.Builder()
                .url(url)
                .header("Title", headerValue(title))
                .header("Priority", if (urgent) "urgent" else "default")
                .header("Tags", if (urgent) "rotating_light" else "house")

            val hasImage = imageFile != null && imageFile.exists() && imageFile.length() > 0
            if (hasImage) {
                // With an attachment the file is the body and the text travels in a header.
                builder.header("Message", headerValue(message))
                    .header("Filename", "camera.jpg")
                    .put(imageFile!!.asRequestBody("image/jpeg".toMediaType()))
            } else {
                builder.post(message.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            }

            client.newCall(builder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    DispatchResult(isSuccess = true, deliveredCount = 1, failedCount = 0)
                } else {
                    failure("Notification server answered ${response.code}")
                }
            }
        } catch (e: Exception) {
            failure("Could not reach the notification server: ${e.message}")
        }
    }

    private fun failure(message: String) =
        DispatchResult(isSuccess = false, deliveredCount = 0, failedCount = 1, errorMessage = message)

    companion object {
        const val DEFAULT_SERVER = "https://ntfy.sh"
        const val MIN_TOPIC_LENGTH = 16
        private const val GENERATED_TOPIC_LENGTH = 28
        private const val TOPIC_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private val TOPIC_PATTERN = Regex("^[A-Za-z0-9_-]{$MIN_TOPIC_LENGTH,64}$")

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        /** Long enough that it can't be guessed, and only characters ntfy accepts. */
        fun isValidTopic(topic: String): Boolean = TOPIC_PATTERN.matches(topic)

        /** A random topic name nobody else will guess. */
        fun generateTopic(random: SecureRandom = SecureRandom()): String {
            val chars = CharArray(GENERATED_TOPIC_LENGTH) { TOPIC_ALPHABET[random.nextInt(TOPIC_ALPHABET.length)] }
            return "cam-" + String(chars)
        }

        /**
         * Returns the server address without a trailing slash, or null if it is not an https
         * address. Plain http is refused because topic and screenshots would travel unencrypted.
         */
        fun normalizeServerUrl(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return DEFAULT_SERVER
            if (!trimmed.startsWith("https://", ignoreCase = true)) return null
            return if (trimmed.toHttpUrlOrNull() != null) trimmed else null
        }

        /**
         * HTTP headers can only carry plain ASCII, so other text (for example a name in another
         * script) is sent in the RFC 2047 encoded form that ntfy decodes. Line breaks are removed.
         */
        fun headerValue(text: String): String {
            val singleLine = text.replace(Regex("[\\r\\n]+"), " ").trim()
            val isPlainAscii = singleLine.all { it.code in 0x20..0x7E }
            return if (isPlainAscii) {
                singleLine
            } else {
                "=?UTF-8?B?" + Base64.getEncoder().encodeToString(singleLine.toByteArray(Charsets.UTF_8)) + "?="
            }
        }
    }
}
