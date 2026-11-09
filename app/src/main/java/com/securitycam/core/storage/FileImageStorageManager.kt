package com.securitycam.core.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.securitycam.core.surveillance.NormalizedRect
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Robust, production-grade image persistence engine.
 * Handles:
 * 1. High-contrast timestamp banner overlay & bounding box annotations.
 * 2. Quality-controlled JPEG encoding with atomic write guarantees (.tmp rename).
 * 3. App-scoped filesDir/events/ directory lifecycle management.
 * 4. FIFO retention pruning (max storage bytes & max event file limit).
 */
class FileImageStorageManager(
    private val context: Context,
    private val maxStorageBytes: Long = ImageStorageManager.DEFAULT_MAX_STORAGE_BYTES,
    private val maxFileCount: Int = ImageStorageManager.DEFAULT_MAX_FILES,
    private val jpegQuality: Int = ImageStorageManager.DEFAULT_JPEG_QUALITY,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ImageStorageManager {

    private val mutex = Mutex()
    private val eventsDir: File by lazy {
        File(context.filesDir, "events").also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    override fun getEventDirectory(): File = eventsDir

    override suspend fun saveScreenshot(
        bitmap: Bitmap,
        timestamp: Long,
        boxes: List<NormalizedRect>
    ): File = withContext(ioDispatcher) {
        val watermarkedBitmap = applyWatermark(bitmap, timestamp, boxes)
        val shortUuid = UUID.randomUUID().toString().replace("-", "").take(8)
        val filename = "event_${timestamp}_${shortUuid}.jpg"
        val targetFile = File(getEventDirectory(), filename)
        val tempFile = File(getEventDirectory(), "$filename.tmp")

        try {
            FileOutputStream(tempFile).use { out ->
                val success = watermarkedBitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    jpegQuality.coerceIn(10, 100),
                    out
                )
                if (!success) {
                    throw IOException("Bitmap compression to JPEG failed")
                }
                out.flush()
            }

            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            // Recycle copy if distinct to conserve memory
            if (watermarkedBitmap != bitmap) {
                watermarkedBitmap.recycle()
            }

            // Auto-prune on save to uphold storage quotas
            mutex.withLock {
                pruneStorageInternal(maxStorageBytes, maxFileCount)
            }

            targetFile
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }

    override suspend fun saveJpegBytes(
        jpegData: ByteArray,
        timestamp: Long
    ): File = withContext(ioDispatcher) {
        require(jpegData.size >= 4) { "JPEG byte array too short (${jpegData.size} bytes)" }
        // Verify SOI magic bytes 0xFF, 0xD8
        require(jpegData[0] == 0xFF.toByte() && jpegData[1] == 0xD8.toByte()) {
            "Provided bytes do not begin with JPEG SOI magic header (0xFF, 0xD8)"
        }

        val shortUuid = UUID.randomUUID().toString().replace("-", "").take(8)
        val filename = "event_${timestamp}_${shortUuid}.jpg"
        val targetFile = File(getEventDirectory(), filename)
        val tempFile = File(getEventDirectory(), "$filename.tmp")

        try {
            FileOutputStream(tempFile).use { out ->
                out.write(jpegData)
                out.flush()
            }

            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            mutex.withLock {
                pruneStorageInternal(maxStorageBytes, maxFileCount)
            }

            targetFile
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }

    override suspend fun pruneStorage(maxBytes: Long, maxFiles: Int): Int = withContext(ioDispatcher) {
        mutex.withLock {
            pruneStorageInternal(maxBytes, maxFiles)
        }
    }

    private fun extractTimestamp(name: String): Long? {
        return try {
            val parts = name.split("_")
            if (parts.size >= 2) parts[1].toLong() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun pruneStorageInternal(maxBytes: Long, maxFiles: Int): Int {
        val files = getEventDirectory().listFiles { f ->
            f.isFile && (f.name.endsWith(".jpg", ignoreCase = true) || f.name.endsWith(".jpeg", ignoreCase = true))
        }?.toList() ?: return 0

        if (files.isEmpty()) return 0

        // Sort ascending: oldest timestamp / modified first (FIFO)
        val sortedFiles = files.sortedWith(
            compareBy<File> { extractTimestamp(it.name) ?: it.lastModified() }
                .thenBy { it.lastModified() }
                .thenBy { it.name }
        )

        var deletedCount = 0
        var remainingCount = sortedFiles.size
        var currentTotalBytes = sortedFiles.sumOf { it.length() }

        for (file in sortedFiles) {
            val exceedsCount = remainingCount > maxFiles
            val exceedsSize = currentTotalBytes > maxBytes

            if (exceedsCount || exceedsSize) {
                val len = file.length()
                if (file.delete()) {
                    deletedCount++
                    remainingCount--
                    currentTotalBytes -= len
                }
            } else {
                break
            }
        }
        return deletedCount
    }

    override suspend fun getStorageUsedBytes(): Long = withContext(ioDispatcher) {
        val files = getEventDirectory().listFiles { f -> f.isFile } ?: return@withContext 0L
        files.sumOf { it.length() }
    }

    override suspend fun getEventFileCount(): Int = withContext(ioDispatcher) {
        val files = getEventDirectory().listFiles { f ->
            f.isFile && (f.name.endsWith(".jpg", ignoreCase = true) || f.name.endsWith(".jpeg", ignoreCase = true))
        } ?: return@withContext 0
        files.size
    }

    override suspend fun deleteImage(file: File): Boolean = withContext(ioDispatcher) {
        if (file.exists() && file.isFile) {
            file.delete()
        } else {
            false
        }
    }

    /**
     * Applies high-contrast bounding boxes and watermark banner onto bitmap.
     */
    private fun applyWatermark(
        source: Bitmap,
        timestamp: Long,
        boxes: List<NormalizedRect>
    ): Bitmap {
        val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val w = mutable.width.toFloat()
        val h = mutable.height.toFloat()

        // 1. Draw Bounding Boxes
        val strokeWidth = max(2f, min(w, h) * 0.005f)
        val boxPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            isAntiAlias = true
        }
        val cornerPaint = Paint().apply {
            color = Color.YELLOW
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        for (box in boxes) {
            val left = (box.left * w).coerceIn(0f, w)
            val top = (box.top * h).coerceIn(0f, h)
            val right = (box.right * w).coerceIn(0f, w)
            val bottom = (box.bottom * h).coerceIn(0f, h)

            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Corner indicator tag
            val tagSize = strokeWidth * 2.5f
            canvas.drawRect(left, top, min(left + tagSize, right), min(top + tagSize, bottom), cornerPaint)
        }

        // 2. Darken Bottom Banner Background (both canvas fill and direct pixel buffer)
        val bannerHeight = max(24f, min(w, h) * 0.065f)
        val bannerRect = RectF(0f, h - bannerHeight, w, h)
        val bannerPaint = Paint().apply {
            color = Color.argb(160, 0, 0, 0) // Semi-transparent black
            style = Paint.Style.FILL
        }
        canvas.drawRect(bannerRect, bannerPaint)

        // Darken banner background pixels and render bounding box edges in pixel buffer BEFORE drawing text
        renderWatermarkPixels(mutable, boxes, bannerHeight)

        // 3. Draw Watermark Monospace Text AFTER darkening
        // Drawing text on top ensures white text pixels (Color.WHITE) remain crisp and are NOT dimmed
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dateString = sdf.format(Date(timestamp))
        val textString = "SECURITY CAM | $dateString | MOTION"

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = bannerHeight * 0.52f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        val x = bannerHeight * 0.35f
        val fontMetrics = textPaint.fontMetrics
        val y = (h - bannerHeight) + (bannerHeight - (fontMetrics.descent + fontMetrics.ascent)) / 2f
        canvas.drawText(textString, x, y, textPaint)

        // Draw crisp white text pixels directly to pixel buffer on top of darkened banner
        val textStartY = ((h - bannerHeight) + (bannerHeight - 5) / 2f).toInt()
        renderWatermarkTextPixels(mutable, textString, x.toInt(), textStartY, bannerHeight)

        return mutable
    }

    private fun renderWatermarkPixels(
        bitmap: Bitmap,
        boxes: List<NormalizedRect>,
        bannerHeight: Float
    ) {
        val w = bitmap.width
        val h = bitmap.height
        val bannerStart = (h - bannerHeight).toInt().coerceIn(0, h)

        // 1. Darken bottom banner region with translucent black (alpha ~ 160/255)
        val alphaFactor = (255 - 160) / 255.0
        for (y in bannerStart until h) {
            for (x in 0 until w) {
                val current = bitmap.getPixel(x, y)
                val r = (Color.red(current) * alphaFactor).toInt().coerceIn(0, 255)
                val g = (Color.green(current) * alphaFactor).toInt().coerceIn(0, 255)
                val b = (Color.blue(current) * alphaFactor).toInt().coerceIn(0, 255)
                bitmap.setPixel(x, y, Color.argb(255, r, g, b))
            }
        }

        // 2. Render bounding box borders and corner tags directly to pixel buffer
        val stroke = max(2, (min(w, h) * 0.005f).toInt())
        val redColor = Color.RED
        val yellowColor = Color.YELLOW

        for (box in boxes) {
            val left = (box.left * w).toInt().coerceIn(0, w - 1)
            val top = (box.top * h).toInt().coerceIn(0, h - 1)
            val right = (box.right * w).toInt().coerceIn(0, w - 1)
            val bottom = (box.bottom * h).toInt().coerceIn(0, h - 1)

            // Horizontal edges (top & bottom) with stroke thickness
            for (s in 0 until stroke) {
                val curTop = (top + s).coerceIn(0, h - 1)
                val curBottom = (bottom - s).coerceIn(0, h - 1)
                for (x in left..right) {
                    bitmap.setPixel(x, curTop, redColor)
                    bitmap.setPixel(x, curBottom, redColor)
                }
            }

            // Vertical edges (left & right) with stroke thickness
            for (s in 0 until stroke) {
                val curLeft = (left + s).coerceIn(0, w - 1)
                val curRight = (right - s).coerceIn(0, w - 1)
                for (y in top..bottom) {
                    bitmap.setPixel(curLeft, y, redColor)
                    bitmap.setPixel(curRight, y, redColor)
                }
            }

            // Corner indicator tag (yellow accent)
            val tagSize = (stroke * 2.5f).toInt()
            for (ty in top until min(top + tagSize, bottom)) {
                for (tx in left until min(left + tagSize, right)) {
                    bitmap.setPixel(tx, ty, yellowColor)
                }
            }
        }
    }

    private fun renderWatermarkTextPixels(
        bitmap: Bitmap,
        text: String,
        startX: Int,
        startY: Int,
        bannerHeight: Float
    ) {
        var curX = startX
        val scale = max(1, (bannerHeight / 16f).toInt())
        val white = Color.WHITE

        for (char in text.uppercase()) {
            val glyph = FONT_3X5[char] ?: FONT_3X5[' '] ?: continue
            for (row in 0 until 5) {
                val rowBits = glyph[row]
                for (col in 0 until 3) {
                    if ((rowBits and (1 shl (2 - col))) != 0) {
                        for (dy in 0 until scale) {
                            for (dx in 0 until scale) {
                                val px = curX + col * scale + dx
                                val py = startY + row * scale + dy
                                if (px in 0 until bitmap.width && py in 0 until bitmap.height) {
                                    bitmap.setPixel(px, py, white)
                                }
                            }
                        }
                    }
                }
            }
            curX += (4 * scale)
        }
    }

    private val FONT_3X5 = mapOf(
        '0' to intArrayOf(0b111, 0b101, 0b101, 0b101, 0b111),
        '1' to intArrayOf(0b010, 0b110, 0b010, 0b010, 0b111),
        '2' to intArrayOf(0b111, 0b001, 0b111, 0b100, 0b111),
        '3' to intArrayOf(0b111, 0b001, 0b111, 0b001, 0b111),
        '4' to intArrayOf(0b101, 0b101, 0b111, 0b001, 0b001),
        '5' to intArrayOf(0b111, 0b100, 0b111, 0b001, 0b111),
        '6' to intArrayOf(0b111, 0b100, 0b111, 0b101, 0b111),
        '7' to intArrayOf(0b111, 0b001, 0b010, 0b010, 0b010),
        '8' to intArrayOf(0b111, 0b101, 0b111, 0b101, 0b111),
        '9' to intArrayOf(0b111, 0b101, 0b111, 0b001, 0b111),
        'A' to intArrayOf(0b111, 0b101, 0b111, 0b101, 0b101),
        'B' to intArrayOf(0b110, 0b101, 0b110, 0b101, 0b110),
        'C' to intArrayOf(0b111, 0b100, 0b100, 0b100, 0b111),
        'D' to intArrayOf(0b110, 0b101, 0b101, 0b101, 0b110),
        'E' to intArrayOf(0b111, 0b100, 0b110, 0b100, 0b111),
        'F' to intArrayOf(0b111, 0b100, 0b110, 0b100, 0b100),
        'G' to intArrayOf(0b111, 0b100, 0b101, 0b101, 0b111),
        'H' to intArrayOf(0b101, 0b101, 0b111, 0b101, 0b101),
        'I' to intArrayOf(0b111, 0b010, 0b010, 0b010, 0b111),
        'J' to intArrayOf(0b001, 0b001, 0b001, 0b101, 0b111),
        'K' to intArrayOf(0b101, 0b110, 0b100, 0b110, 0b101),
        'L' to intArrayOf(0b100, 0b100, 0b100, 0b100, 0b111),
        'M' to intArrayOf(0b101, 0b111, 0b101, 0b101, 0b101),
        'N' to intArrayOf(0b111, 0b101, 0b101, 0b101, 0b101),
        'O' to intArrayOf(0b111, 0b101, 0b101, 0b101, 0b111),
        'P' to intArrayOf(0b111, 0b101, 0b111, 0b100, 0b100),
        'Q' to intArrayOf(0b111, 0b101, 0b101, 0b111, 0b001),
        'R' to intArrayOf(0b110, 0b101, 0b110, 0b101, 0b101),
        'S' to intArrayOf(0b111, 0b100, 0b111, 0b001, 0b111),
        'T' to intArrayOf(0b111, 0b010, 0b010, 0b010, 0b010),
        'U' to intArrayOf(0b101, 0b101, 0b101, 0b101, 0b111),
        'V' to intArrayOf(0b101, 0b101, 0b101, 0b101, 0b010),
        'W' to intArrayOf(0b101, 0b101, 0b101, 0b111, 0b101),
        'X' to intArrayOf(0b101, 0b101, 0b010, 0b101, 0b101),
        'Y' to intArrayOf(0b101, 0b101, 0b010, 0b010, 0b010),
        'Z' to intArrayOf(0b111, 0b001, 0b010, 0b100, 0b111),
        '-' to intArrayOf(0b000, 0b000, 0b111, 0b000, 0b000),
        ':' to intArrayOf(0b000, 0b010, 0b000, 0b010, 0b000),
        '|' to intArrayOf(0b010, 0b010, 0b010, 0b010, 0b010),
        ' ' to intArrayOf(0b000, 0b000, 0b000, 0b000, 0b000)
    )
}
