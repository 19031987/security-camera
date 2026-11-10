package com.securitycam.core.push

import com.google.gson.Gson
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * The private key file from the owner's Firebase project (Project settings, Service accounts).
 * It lets this phone ask Google to deliver notifications. It is a secret: it is kept in
 * encrypted storage on the camera phone and must never be shared or committed.
 */
class ServiceAccount private constructor(
    val projectId: String,
    val clientEmail: String,
    val tokenUri: String,
    val privateKey: PrivateKey
) {
    private class Raw(
        val type: String? = null,
        val project_id: String? = null,
        val private_key: String? = null,
        val client_email: String? = null,
        val token_uri: String? = null
    )

    companion object {
        const val DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"

        /** Returns null if [json] is not a usable service-account key file. */
        fun parse(json: String, gson: Gson = Gson()): ServiceAccount? {
            return try {
                val raw = gson.fromJson(json, Raw::class.java) ?: return null
                if (raw.type != "service_account") return null
                val projectId = raw.project_id?.takeIf { it.isNotBlank() } ?: return null
                val email = raw.client_email?.takeIf { it.isNotBlank() } ?: return null
                val pem = raw.private_key?.takeIf { it.isNotBlank() } ?: return null
                val der = Base64.getDecoder().decode(
                    pem.replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replace("\\s".toRegex(), "")
                )
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
                ServiceAccount(projectId, email, raw.token_uri?.takeIf { it.isNotBlank() } ?: DEFAULT_TOKEN_URI, key)
            } catch (e: Exception) {
                null
            }
        }
    }
}
