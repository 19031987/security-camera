package com.securitycam.core.push

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.securitycam.core.alerting.DispatchResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Tells the owner's and family's phones about an event. */
interface PushNotifier {
    suspend fun notify(type: String, title: String, message: String, time: Long, image: File?): DispatchResult
}

/** Push notifications through Firebase, to every phone linked with the camera's code. */
class FcmPushNotifier(
    private val sender: FcmSender,
    private val linkCodeProvider: () -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PushNotifier {

    override suspend fun notify(type: String, title: String, message: String, time: Long, image: File?): DispatchResult =
        withContext(ioDispatcher) {
            val topic = PushMessage.topicFor(linkCodeProvider())
                ?: return@withContext DispatchResult(false, 0, 1, "No link code has been created")
            val thumbnail = image?.let { makeThumbnail(it) }
            sender.sendToTopic(topic, PushMessage.build(type, title, message, time, thumbnail))
        }

    companion object {
        private const val THUMBNAIL_WIDTH = 160

        /**
         * A small JPEG of the picture that fits in a push message: scaled to 160 px wide, then
         * compressed harder until it is under the size limit. Returns null if it can't be made.
         */
        fun makeThumbnail(image: File): ByteArray? {
            if (!image.isFile || image.length() == 0L) return null
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(image.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                val sample = (bounds.outWidth / (THUMBNAIL_WIDTH * 2)).coerceAtLeast(1)
                val decoded = BitmapFactory.decodeFile(
                    image.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }
                ) ?: return null
                val height = (decoded.height * THUMBNAIL_WIDTH / decoded.width).coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(decoded, THUMBNAIL_WIDTH, height, true)
                for (quality in intArrayOf(45, 35, 25, 15, 8)) {
                    val out = ByteArrayOutputStream()
                    small.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    if (out.size() <= PushMessage.MAX_THUMBNAIL_BYTES) return out.toByteArray()
                }
                null
            } catch (e: Exception) {
                null
            }
        }
    }
}
