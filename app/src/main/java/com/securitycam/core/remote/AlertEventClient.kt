package com.securitycam.core.remote

import com.google.gson.Gson
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Used on a receiver phone to collect alerts straight from the camera phone's built-in server.
 * No outside service is involved: the two phones talk to each other directly.
 */
class AlertEventClient(
    private val baseUrl: HttpUrl,
    private val password: String,
    private val client: OkHttpClient = defaultClient(),
    private val gson: Gson = Gson()
) {
    sealed class PollResult {
        data class Success(val latest: Long, val events: List<AlertEventHub.Event>) : PollResult()

        /** The camera refused the password, or has locked this phone out after wrong ones. */
        data class Rejected(val lockedOut: Boolean) : PollResult()
        data class Failed(val reason: String) : PollResult()
    }

    /** Wire format of the camera's `/events` answer. */
    class EventsResponse(val latest: Long = 0, val events: List<AlertEventHub.Event>? = null)

    /**
     * Asks for events after [afterId]. The camera holds the request open until something
     * happens or about 25 seconds pass. Pass -1 to just learn the camera's latest id.
     */
    fun poll(afterId: Long): PollResult {
        val url = baseUrl.newBuilder().addPathSegment("events")
            .addQueryParameter("after", afterId.toString()).build()
        return try {
            client.newCall(authorized(url)).execute().use { response ->
                when {
                    response.code == 401 -> PollResult.Rejected(lockedOut = false)
                    response.code == 429 -> PollResult.Rejected(lockedOut = true)
                    !response.isSuccessful -> PollResult.Failed("The camera answered ${response.code}")
                    else -> {
                        val parsed = gson.fromJson(response.body?.string().orEmpty(), EventsResponse::class.java)
                            ?: return PollResult.Failed("The camera sent an empty answer")
                        PollResult.Success(parsed.latest, parsed.events.orEmpty())
                    }
                }
            }
        } catch (e: Exception) {
            PollResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** The screenshot of an event, or null if it can't be fetched. */
    fun fetchImage(eventId: String): ByteArray? {
        val url = baseUrl.newBuilder().addPathSegment("event-image")
            .addQueryParameter("id", eventId).build()
        return try {
            client.newCall(authorized(url)).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun authorized(url: HttpUrl): Request =
        Request.Builder().url(url).header("Authorization", Credentials.basic(USER_NAME, password)).build()

    companion object {
        private const val USER_NAME = "receiver"

        /** Longer than the camera's hold time, so a quiet poll ends normally rather than timing out. */
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()

        /**
         * Turns what a person types ("192.168.1.20", "192.168.1.20:8080", "http://cam.local:8080/")
         * into the camera's base address, or null if it can't be one. The port defaults to 8080.
         */
        fun parseAddress(input: String): HttpUrl? {
            val typed = input.trim()
            if (typed.isEmpty() || typed.any { it.isWhitespace() }) return null
            val withScheme = (if (typed.contains("://")) typed else "http://$typed").trimEnd('/')
            if (!withScheme.startsWith("http://", true) && !withScheme.startsWith("https://", true)) return null
            // Nothing after the scheme ("http://") is not an address.
            if (withScheme.substringAfter("://").isEmpty()) return null
            val parsed = withScheme.toHttpUrlOrNull() ?: return null
            val hasExplicitPort = Regex(":\\d+$").containsMatchIn(withScheme.substringAfter("://").substringBefore('/'))
            val port = if (hasExplicitPort) parsed.port else ViewerServer.DEFAULT_PORT
            return HttpUrl.Builder().scheme(parsed.scheme).host(parsed.host).port(port).build()
        }
    }
}
