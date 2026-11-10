package com.securitycam.core.push

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.securitycam.core.alerting.DispatchResult
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Full-size alert pictures, kept in the owner's own Firebase database (Firestore) so family
 * phones can fetch them. A push message is too small to carry more than a thumbnail.
 *
 * The camera phone writes with the project's private key. Family phones read without signing
 * in, which the database rules allow only for a single picture whose address they already
 * know: the link code plus a random picture id. Pictures cannot be listed or guessed.
 */
class FirestorePictures(
    private val client: OkHttpClient = defaultClient(),
    private val base: String = "https://firestore.googleapis.com",
    private val gson: Gson = Gson()
) {
    /**
     * Camera side: stores [jpeg] and returns its new id, or a failure.
     * [token] is a Google access token for the project's service account.
     */
    fun upload(projectId: String, token: String, linkCode: String, jpeg: ByteArray, time: Long): UploadResult {
        if (!isSafeSegment(linkCode)) return UploadResult(null, "The link code is not valid")
        if (jpeg.isEmpty()) return UploadResult(null, "There is no picture")
        if (jpeg.size > MAX_PICTURE_BYTES) return UploadResult(null, "The picture is too large to store")
        val id = UUID.randomUUID().toString().replace("-", "")
        val body = gson.toJson(
            mapOf(
                "fields" to mapOf(
                    FIELD_JPEG to mapOf("bytesValue" to Base64.getEncoder().encodeToString(jpeg)),
                    FIELD_TIME to mapOf("integerValue" to time.toString())
                )
            )
        )
        return try {
            val request = Request.Builder()
                .url(documentUrl(projectId, linkCode, id))
                .header("Authorization", "Bearer $token")
                .patch(body.toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    UploadResult(id, null)
                } else {
                    UploadResult(null, "The database answered ${response.code}: ${errorMessage(response.body?.string())}")
                }
            }
        } catch (e: Exception) {
            UploadResult(null, "Could not reach the database: ${e.message}")
        }
    }

    /** Camera side: removes a stored picture. Missing pictures count as removed. */
    fun delete(projectId: String, token: String, linkCode: String, pictureId: String): Boolean {
        if (!isSafeSegment(linkCode) || !isSafeSegment(pictureId)) return false
        return try {
            val request = Request.Builder()
                .url(documentUrl(projectId, linkCode, pictureId))
                .header("Authorization", "Bearer $token")
                .delete()
                .build()
            client.newCall(request).execute().use { it.isSuccessful || it.code == 404 }
        } catch (e: Exception) {
            false
        }
    }

    /** Family-phone side: fetches a picture by its id. Returns null if it can't be fetched. */
    fun fetch(projectId: String, apiKey: String, linkCode: String, pictureId: String): ByteArray? {
        if (!isSafeSegment(linkCode) || !isSafeSegment(pictureId)) return null
        return try {
            val url = documentUrl(projectId, linkCode, pictureId).toHttpUrl().newBuilder()
                .addQueryParameter("key", apiKey)
                .build()
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
                val encoded = json?.getAsJsonObject("fields")?.getAsJsonObject(FIELD_JPEG)?.get("bytesValue")?.asString
                    ?: return null
                Base64.getDecoder().decode(encoded).takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            null
        }
    }

    class UploadResult(val pictureId: String?, val error: String?) {
        fun toDispatchResult() = DispatchResult(pictureId != null, if (pictureId != null) 1 else 0, if (pictureId != null) 0 else 1, error)
    }

    private fun documentUrl(projectId: String, linkCode: String, pictureId: String): String =
        "$base/v1/projects/$projectId/databases/(default)/documents/$COLLECTION/$linkCode/$PICTURES/$pictureId"

    private fun errorMessage(body: String?): String = try {
        gson.fromJson(body.orEmpty(), JsonObject::class.java)
            ?.getAsJsonObject("error")?.get("message")?.asString ?: "request failed"
    } catch (_: Exception) {
        "request failed"
    }

    companion object {
        const val COLLECTION = "cams"
        const val PICTURES = "pictures"
        const val SCOPE = "https://www.googleapis.com/auth/datastore"
        private const val FIELD_JPEG = "jpeg"
        private const val FIELD_TIME = "time"

        /** Firestore allows 1 MiB per document; base64 adds a third, so stay well under. */
        const val MAX_PICTURE_BYTES = 700 * 1024
        private val JSON = "application/json; charset=UTF-8".toMediaType()
        private val SEGMENT = Regex("^[A-Za-z0-9]{8,64}$")

        /** Ids and codes go into a web address, so only plain letters and digits are accepted. */
        fun isSafeSegment(value: String): Boolean = SEGMENT.matches(value)

        /** The database rules the owner pastes into the Firebase console. */
        val RULES = """
            rules_version = '2';
            service cloud.firestore {
              match /databases/{database}/documents {
                match /cams/{code}/pictures/{picture} {
                  allow get: if true;
                }
              }
            }
        """.trimIndent()

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
