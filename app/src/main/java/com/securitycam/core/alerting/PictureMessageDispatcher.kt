package com.securitycam.core.alerting

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Sends a text with a picture to phone numbers. */
interface PictureMessageDispatcher {
    suspend fun send(phoneNumbers: List<String>, text: String, image: File): DispatchResult
}

/**
 * Picture messages (MMS) from this phone's own SIM, through the mobile network only.
 * Needs a SIM whose plan includes MMS, and mobile data switched on for it.
 * Each recipient gets their own message, so recipients don't see each other's numbers.
 */
class AndroidMmsDispatcher(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PictureMessageDispatcher {

    override suspend fun send(phoneNumbers: List<String>, text: String, image: File): DispatchResult =
        withContext(ioDispatcher) {
            if (phoneNumbers.isEmpty()) {
                return@withContext failure(0, "No phone numbers are set up")
            }
            val jpeg = try {
                jpegWithinLimit(image)
            } catch (e: Exception) {
                return@withContext failure(phoneNumbers.size, "The picture could not be read: ${e.message}")
            }

            var delivered = 0
            var firstError: String? = null
            for (number in phoneNumbers) {
                val error = sendOne(number, text, jpeg)
                if (error == null) delivered++ else if (firstError == null) firstError = error
            }
            DispatchResult(
                isSuccess = delivered > 0,
                deliveredCount = delivered,
                failedCount = phoneNumbers.size - delivered,
                errorMessage = firstError
            )
        }

    /** Returns null when the network accepted the message, otherwise what went wrong. */
    private suspend fun sendOne(number: String, text: String, jpeg: ByteArray): String? {
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val pduFile = File(File(context.cacheDir, PDU_DIR).apply { mkdirs() }, "send_$id.dat")
        val action = "$ACTION_SENT_PREFIX.$id"
        val outcome = CompletableDeferred<Int>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                outcome.complete(resultCode)
            }
        }
        return try {
            pduFile.writeBytes(MmsPduEncoder.encodeSendRequest("T$id", listOf(number), text, jpeg))
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pduFile)
            // The phone's messaging service reads the file on this app's behalf.
            context.grantUriPermission(PHONE_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            val sentIntent = PendingIntent.getBroadcast(
                context, 0,
                Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            smsManager().sendMultimediaMessage(context, uri, null, null, sentIntent)

            when (val code = withTimeoutOrNull(SEND_TIMEOUT_MS) { outcome.await() }) {
                null -> "The mobile network did not confirm the picture message in time"
                Activity.RESULT_OK -> null
                else -> "The mobile network refused the picture message (code $code)"
            }
        } catch (e: SecurityException) {
            "SMS permission is not granted"
        } catch (e: Exception) {
            Log.w(TAG, "MMS send failed: ${e.message}")
            "Picture message could not be sent: ${e.message}"
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            pduFile.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }

    /** Mobile networks reject large picture messages, so the JPEG is shrunk until it fits. */
    private fun jpegWithinLimit(image: File): ByteArray {
        val original = image.readBytes()
        if (original.size <= MAX_IMAGE_BYTES) return original

        var bitmap = BitmapFactory.decodeByteArray(original, 0, original.size)
            ?: throw IllegalArgumentException("not an image")
        var quality = 80
        while (true) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= MAX_IMAGE_BYTES || (quality <= 40 && bitmap.width <= 320)) {
                return out.toByteArray()
            }
            if (quality > 40) {
                quality -= 20
            } else {
                bitmap = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
                quality = 80
            }
        }
    }

    private fun failure(failed: Int, message: String) =
        DispatchResult(isSuccess = false, deliveredCount = 0, failedCount = failed, errorMessage = message)

    companion object {
        private const val TAG = "AndroidMmsDispatcher"
        private const val PDU_DIR = "mms"
        private const val PHONE_PACKAGE = "com.android.phone"
        private const val ACTION_SENT_PREFIX = "com.securitycam.MMS_SENT"
        private const val SEND_TIMEOUT_MS = 90_000L
        const val MAX_IMAGE_BYTES = 250 * 1024
    }
}
