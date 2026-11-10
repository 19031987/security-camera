package com.securitycam.core.push

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.securitycam.core.alerting.DispatchResult
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.Signature
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Sends push notifications from the camera phone through the owner's own Firebase project
 * (Firebase Cloud Messaging, HTTP v1 API). No server of ours is involved: the phone signs a
 * short-lived request with the project's private key and Google delivers the message to every
 * phone subscribed to the camera's topic.
 */
class FcmSender(
    /** The loaded key file, or null when the owner hasn't loaded one yet. */
    private val accountProvider: () -> ServiceAccount?,
    private val client: OkHttpClient = defaultClient(),
    private val fcmBase: String = "https://fcm.googleapis.com",
    private val clock: () -> Long = System::currentTimeMillis,
    private val gson: Gson = Gson()
) {
    private class TokenResponse(val access_token: String? = null, val expires_in: Long = 0)

    private var cachedToken: String? = null
    private var cachedTokenFor: String? = null
    private var cachedTokenExpiresAt = 0L

    /**
     * Sends [data] to everyone subscribed to [topic]. Data-only and high priority, so the
     * receiving app is woken to build the notification itself, even when it is closed.
     */
    @Synchronized
    fun sendToTopic(topic: String, data: Map<String, String>): DispatchResult {
        if (!isValidTopic(topic)) return failure("The link code is not valid")
        val account = accountProvider() ?: return failure("No Firebase key file is loaded")
        return try {
            val token = accessToken(account)
            val body = gson.toJson(
                mapOf(
                    "message" to mapOf(
                        "topic" to topic,
                        "data" to data,
                        "android" to mapOf("priority" to "HIGH", "ttl" to "$TTL_SECONDS" + "s")
                    )
                )
            )
            val request = Request.Builder()
                .url("$fcmBase/v1/projects/${account.projectId}/messages:send")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> DispatchResult(isSuccess = true, deliveredCount = 1, failedCount = 0)
                    response.code == 401 -> {
                        // The cached token was rejected; a fresh one is fetched next time.
                        cachedToken = null
                        failure("Google rejected the request (401): ${errorMessage(text)}")
                    }
                    else -> failure("Google answered ${response.code}: ${errorMessage(text)}")
                }
            }
        } catch (e: Exception) {
            failure("Could not send the notification: ${e.message}")
        }
    }

    /**
     * The loaded account and a current access token for it, for other Google services in the
     * same project (the picture database). Throws if there is no key or Google refuses it.
     */
    @Synchronized
    fun accountAndToken(): Pair<ServiceAccount, String> {
        val account = accountProvider() ?: throw IllegalStateException("No Firebase key file is loaded")
        return account to accessToken(account)
    }

    /** Proves the key file works by fetching a token, without sending anything. */
    @Synchronized
    fun checkKey(): DispatchResult {
        val account = accountProvider() ?: return failure("No Firebase key file is loaded")
        return try {
            cachedToken = null
            accessToken(account)
            DispatchResult(isSuccess = true, deliveredCount = 0, failedCount = 0)
        } catch (e: Exception) {
            failure("The key file was not accepted: ${e.message}")
        }
    }

    private fun accessToken(account: ServiceAccount): String {
        val now = clock()
        cachedToken?.let { if (cachedTokenFor == account.clientEmail && now < cachedTokenExpiresAt) return it }

        val assertion = signedJwt(account, now / 1000)
        val form = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", assertion)
            .build()
        client.newCall(Request.Builder().url(account.tokenUri).post(form).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Google answered ${response.code}: ${errorMessage(text)}")
            }
            val parsed = gson.fromJson(text, TokenResponse::class.java)
            val token = parsed?.access_token ?: throw IllegalStateException("No access token was returned")
            cachedToken = token
            cachedTokenFor = account.clientEmail
            // Renew a minute early so a token never expires mid-request.
            cachedTokenExpiresAt = now + (parsed.expires_in.coerceAtLeast(120) - 60) * 1000
            return token
        }
    }

    private fun errorMessage(body: String): String {
        // Google uses two shapes: {"error": {"message": ...}} from the messaging service and
        // {"error": "code", "error_description": ...} from the sign-in service.
        return try {
            val json = gson.fromJson(body, JsonObject::class.java)
            val error = json?.get("error")
            when {
                error != null && error.isJsonObject ->
                    error.asJsonObject.get("message")?.asString ?: "request failed"
                json?.get("error_description") != null -> json.get("error_description").asString
                error != null && error.isJsonPrimitive -> error.asString
                else -> "request failed"
            }
        } catch (_: Exception) {
            "request failed"
        }
    }

    private fun failure(message: String) =
        DispatchResult(isSuccess = false, deliveredCount = 0, failedCount = 1, errorMessage = message)

    companion object {
        /** Sending notifications, and reading and writing the project's database for pictures. */
        const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging " +
            "https://www.googleapis.com/auth/datastore"
        const val TOPIC_PREFIX = "cam_"
        private const val TTL_SECONDS = 600
        private const val TOKEN_LIFETIME_SECONDS = 3600L
        private val JSON = "application/json; charset=UTF-8".toMediaType()
        private val TOPIC_PATTERN = Regex("^cam_[A-Za-z0-9]{20,60}$")

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        fun isValidTopic(topic: String): Boolean = TOPIC_PATTERN.matches(topic)

        /** The request Google exchanges for an access token: a JWT signed with the project's key. */
        internal fun signedJwt(account: ServiceAccount, nowSeconds: Long): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val gson = Gson()
            val header = encoder.encodeToString(gson.toJson(mapOf("alg" to "RS256", "typ" to "JWT")).toByteArray())
            val claims = encoder.encodeToString(
                gson.toJson(
                    mapOf(
                        "iss" to account.clientEmail,
                        "scope" to SCOPE,
                        "aud" to account.tokenUri,
                        "iat" to nowSeconds,
                        "exp" to nowSeconds + TOKEN_LIFETIME_SECONDS
                    )
                ).toByteArray()
            )
            val signingInput = "$header.$claims"
            val signature = Signature.getInstance("SHA256withRSA").apply {
                initSign(account.privateKey)
                update(signingInput.toByteArray(Charsets.US_ASCII))
            }.sign()
            return signingInput + "." + encoder.encodeToString(signature)
        }
    }
}
