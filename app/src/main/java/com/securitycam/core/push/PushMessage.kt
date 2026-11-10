package com.securitycam.core.push

import java.security.SecureRandom
import java.util.Base64

/**
 * What travels in a push notification, and the limits it must fit in. Google delivers at most
 * 4 KB per message, so the picture is a small thumbnail; the full picture stays on the camera
 * phone (and in SMS, email or Drive if those are on).
 */
object PushMessage {

    const val KEY_TYPE = "type"
    const val KEY_TITLE = "title"
    const val KEY_MESSAGE = "message"
    const val KEY_TIME = "time"
    const val KEY_THUMBNAIL = "thumb"

    /** Id of the full-size picture in the owner's database, when one was stored. */
    const val KEY_PICTURE_ID = "pic"

    const val TYPE_ALERT = "alert"
    const val TYPE_ARRIVAL = "arrival"
    const val TYPE_TEST = "test"

    /** Kept under Google's 4096-byte limit with room for its own envelope. */
    const val MAX_DATA_BYTES = 3600

    /**
     * Largest JPEG that still fits once it is base64-encoded (which adds a third) next to a
     * full-length title and message in plain characters.
     */
    const val MAX_THUMBNAIL_BYTES = 2300
    const val MAX_TITLE_CHARS = 80
    const val MAX_MESSAGE_CHARS = 200

    private const val CODE_LENGTH = 24
    private const val CODE_ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /**
     * Builds the data map. If the text and thumbnail together would be too large the thumbnail
     * is dropped first, then the message is shortened, so something always gets through.
     */
    fun build(
        type: String,
        title: String,
        message: String,
        time: Long,
        thumbnailJpeg: ByteArray?,
        pictureId: String? = null
    ): Map<String, String> {
        val data = LinkedHashMap<String, String>()
        data[KEY_TYPE] = type
        data[KEY_TITLE] = title.take(MAX_TITLE_CHARS)
        data[KEY_MESSAGE] = message.take(MAX_MESSAGE_CHARS)
        data[KEY_TIME] = time.toString()
        if (pictureId != null && FirestorePictures.isSafeSegment(pictureId)) data[KEY_PICTURE_ID] = pictureId
        if (thumbnailJpeg != null && thumbnailJpeg.isNotEmpty() && thumbnailJpeg.size <= MAX_THUMBNAIL_BYTES) {
            data[KEY_THUMBNAIL] = Base64.getEncoder().encodeToString(thumbnailJpeg)
        }
        if (sizeOf(data) > MAX_DATA_BYTES) data.remove(KEY_THUMBNAIL)
        while (sizeOf(data) > MAX_DATA_BYTES && data.getValue(KEY_MESSAGE).isNotEmpty()) {
            data[KEY_MESSAGE] = data.getValue(KEY_MESSAGE).dropLast(40)
        }
        return data
    }

    fun sizeOf(data: Map<String, String>): Int =
        data.entries.sumOf { it.key.toByteArray(Charsets.UTF_8).size + it.value.toByteArray(Charsets.UTF_8).size }

    fun thumbnail(data: Map<String, String>): ByteArray? = try {
        data[KEY_THUMBNAIL]?.let { Base64.getDecoder().decode(it) }
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * A new link code. Whoever has it can receive this camera's notifications, so it is long,
     * random, and leaves out characters that are easily confused when typed (0/O, 1/l/I).
     */
    fun newLinkCode(random: SecureRandom = SecureRandom()): String =
        String(CharArray(CODE_LENGTH) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] })

    /** The topic a link code maps to, or null if the code can't be one of ours. */
    fun topicFor(linkCode: String): String? {
        val topic = FcmSender.TOPIC_PREFIX + linkCode.trim()
        return if (FcmSender.isValidTopic(topic)) topic else null
    }
}
