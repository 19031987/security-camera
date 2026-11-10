package com.securitycam.core.push

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Live view through the owner's own Firebase database, so it works on any network.
 *
 * A family phone writes a small "I am watching" note; while a recent one exists the camera
 * phone writes its newest picture about once a second, and the family phone reads it. It is a
 * fast slideshow rather than video, with nothing between the two phones but the database.
 *
 * As with pictures, the camera signs its requests with the project key and family phones use
 * only the link code. The database rules limit what a code holder can do to exactly these two
 * documents.
 */
class FirestoreLive(
    private val client: OkHttpClient = defaultClient(),
    private val base: String = "https://firestore.googleapis.com",
    private val gson: Gson = Gson()
) {
    class Frame(val sequence: Long, val jpeg: ByteArray)

    /** Family phone: says "I am watching" (a fresh [nonce] each time) or "I have stopped". */
    fun writeRequest(projectId: String, apiKey: String, linkCode: String, nonce: String): Boolean {
        if (!FirestorePictures.isSafeSegment(linkCode) || !isValidNonce(nonce)) return false
        val body = gson.toJson(mapOf("fields" to mapOf(FIELD_NONCE to mapOf("stringValue" to nonce))))
        return try {
            val url = documentUrl(projectId, linkCode, DOC_REQUEST).toHttpUrl().newBuilder()
                .addQueryParameter("key", apiKey).build()
            client.newCall(Request.Builder().url(url).patch(body.toRequestBody(JSON)).build())
                .execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /** Camera: the latest watch note, or null if there is none or it can't be read. */
    fun readRequest(projectId: String, token: String, linkCode: String): String? {
        if (!FirestorePictures.isSafeSegment(linkCode)) return null
        return try {
            val request = Request.Builder().url(documentUrl(projectId, linkCode, DOC_REQUEST))
                .header("Authorization", "Bearer $token").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                fields(response.body?.string())?.getAsJsonObject(FIELD_NONCE)?.get("stringValue")?.asString
                    ?.takeIf { isValidNonce(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Camera: publishes its newest picture. */
    fun writeFrame(projectId: String, token: String, linkCode: String, sequence: Long, jpeg: ByteArray): Boolean {
        if (!FirestorePictures.isSafeSegment(linkCode) || jpeg.isEmpty() ||
            jpeg.size > FirestorePictures.MAX_PICTURE_BYTES
        ) {
            return false
        }
        val body = gson.toJson(
            mapOf(
                "fields" to mapOf(
                    FIELD_JPEG to mapOf("bytesValue" to Base64.getEncoder().encodeToString(jpeg)),
                    FIELD_SEQUENCE to mapOf("integerValue" to sequence.toString())
                )
            )
        )
        return try {
            val request = Request.Builder().url(documentUrl(projectId, linkCode, DOC_FRAME))
                .header("Authorization", "Bearer $token").patch(body.toRequestBody(JSON)).build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /** Family phone: the camera's newest picture, or null if there is none or it can't be read. */
    fun readFrame(projectId: String, apiKey: String, linkCode: String): Frame? {
        if (!FirestorePictures.isSafeSegment(linkCode)) return null
        return try {
            val url = documentUrl(projectId, linkCode, DOC_FRAME).toHttpUrl().newBuilder()
                .addQueryParameter("key", apiKey).build()
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val fields = fields(response.body?.string()) ?: return null
                val sequence = fields.getAsJsonObject(FIELD_SEQUENCE)?.get("integerValue")?.asString?.toLongOrNull()
                    ?: return null
                val encoded = fields.getAsJsonObject(FIELD_JPEG)?.get("bytesValue")?.asString ?: return null
                val jpeg = Base64.getDecoder().decode(encoded)
                if (jpeg.isEmpty()) null else Frame(sequence, jpeg)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fields(body: String?): JsonObject? =
        gson.fromJson(body.orEmpty(), JsonObject::class.java)?.getAsJsonObject("fields")

    private fun documentUrl(projectId: String, linkCode: String, document: String): String =
        "$base/v1/projects/$projectId/databases/(default)/documents/" +
            "${FirestorePictures.COLLECTION}/$linkCode/$LIVE/$document"

    companion object {
        const val LIVE = "live"
        const val DOC_REQUEST = "request"
        const val DOC_FRAME = "frame"
        private const val FIELD_NONCE = "nonce"
        private const val FIELD_JPEG = "jpeg"
        private const val FIELD_SEQUENCE = "seq"

        /** Written when the family phone closes the live view, so the camera can stop at once. */
        const val NONCE_STOP = "stop"
        private const val NONCE_LENGTH = 16
        private const val NONCE_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val NONCE_PATTERN = Regex("^[a-z0-9]{4,40}$")
        private val JSON = "application/json; charset=UTF-8".toMediaType()

        fun newNonce(random: SecureRandom = SecureRandom()): String =
            String(CharArray(NONCE_LENGTH) { NONCE_ALPHABET[random.nextInt(NONCE_ALPHABET.length)] })

        fun isValidNonce(nonce: String): Boolean = NONCE_PATTERN.matches(nonce)

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Camera side: decides whether to be sending live pictures, from the watch notes it sees.
 *
 * Each new note buys [grantMs] of streaming, timed on the camera's own clock, so it doesn't
 * matter if the two phones' clocks disagree. A family phone renews its note well within that
 * time; if it vanishes without saying stop, streaming ends by itself when the grant runs out.
 */
class LiveViewGate(private val grantMs: Long = DEFAULT_GRANT_MS) {
    private var lastNonce: String? = null
    private var primed = false
    private var streamUntil = 0L

    /**
     * Feeds the note read from the database ([nonce] is null when there is none).
     * The first reading only records what is already there: a note left over from an earlier
     * session must not start the camera streaming when the app starts.
     */
    fun onRequestSeen(nonce: String?, now: Long) {
        if (!primed) {
            primed = true
            lastNonce = nonce
            return
        }
        if (nonce == null || nonce == lastNonce) return
        lastNonce = nonce
        streamUntil = if (nonce == FirestoreLive.NONCE_STOP) 0L else now + grantMs
    }

    fun isStreaming(now: Long): Boolean = now < streamUntil

    companion object {
        const val DEFAULT_GRANT_MS = 60_000L
    }
}
