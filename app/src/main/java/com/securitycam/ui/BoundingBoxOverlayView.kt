package com.securitycam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.securitycam.core.surveillance.NormalizedRect

/**
 * Custom overlay view positioned directly over [androidx.camera.view.PreviewView].
 * Renders real-time neon motion bounding boxes, targeting reticles, and motion percentage badges.
 * Supports horizontal mirroring for front-facing camera feeds.
 */
class BoundingBoxOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = COLOR_ARMED
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_ARMED_FILL
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = COLOR_ARMED
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        style = Paint.Style.FILL
        isFakeBoldText = true
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_ARMED_BADGE
        style = Paint.Style.FILL
    }

    var boxes: List<NormalizedRect> = emptyList()
        set(value) {
            field = value
            postInvalidate()
        }

    var motionScore: Float = 0.0f
        set(value) {
            field = value
            postInvalidate()
        }

    var isMirrored: Boolean = false
        set(value) {
            field = value
            postInvalidate()
        }

    var isArmed: Boolean = true
        set(value) {
            field = value
            updatePaintColors()
            postInvalidate()
        }

    private fun updatePaintColors() {
        val strokeColor = if (isArmed) COLOR_ARMED else COLOR_DISARMED
        val fillColor = if (isArmed) COLOR_ARMED_FILL else COLOR_DISARMED_FILL
        val badgeColor = if (isArmed) COLOR_ARMED_BADGE else COLOR_DISARMED_BADGE

        boxPaint.color = strokeColor
        cornerPaint.color = strokeColor
        fillPaint.color = fillColor
        textBgPaint.color = badgeColor
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (boxes.isEmpty()) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        for (box in boxes) {
            val rect = transformRect(box, viewW, viewH, isMirrored)

            // 1. Semi-transparent box fill
            canvas.drawRect(rect, fillPaint)

            // 2. Neon bounding box stroke
            canvas.drawRect(rect, boxPaint)

            // 3. Corner targeting reticles / brackets
            val boxW = rect.width()
            val boxH = rect.height()
            val cornerLen = minOf(boxW * 0.22f, boxH * 0.22f, 40f).coerceAtLeast(12f)

            // Top-Left corner
            canvas.drawLine(rect.left, rect.top, rect.left + cornerLen, rect.top, cornerPaint)
            canvas.drawLine(rect.left, rect.top, rect.left, rect.top + cornerLen, cornerPaint)

            // Top-Right corner
            canvas.drawLine(rect.right, rect.top, rect.right - cornerLen, rect.top, cornerPaint)
            canvas.drawLine(rect.right, rect.top, rect.right, rect.top + cornerLen, cornerPaint)

            // Bottom-Left corner
            canvas.drawLine(rect.left, rect.bottom, rect.left + cornerLen, rect.bottom, cornerPaint)
            canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - cornerLen, cornerPaint)

            // Bottom-Right corner
            canvas.drawLine(rect.right, rect.bottom, rect.right - cornerLen, rect.bottom, cornerPaint)
            canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - cornerLen, cornerPaint)

            // 4. Motion Score Tag Badge
            val label = formatMotionLabel(motionScore)
            val textWidth = textPaint.measureText(label)
            val badgeHeight = 44f
            val badgePadding = 12f
            val badgeTop = if (rect.top - badgeHeight - 8f >= 0f) {
                rect.top - badgeHeight - 8f
            } else {
                rect.top + 8f
            }
            val badgeBottom = badgeTop + badgeHeight
            val badgeLeft = rect.left.coerceAtMost(viewW - textWidth - badgePadding * 2)
            val badgeRight = badgeLeft + textWidth + badgePadding * 2

            val tagRect = RectF(badgeLeft, badgeTop, badgeRight, badgeBottom)
            canvas.drawRoundRect(tagRect, 8f, 8f, textBgPaint)
            canvas.drawText(label, badgeLeft + badgePadding, badgeBottom - 12f, textPaint)
        }
    }

    companion object {
        const val COLOR_ARMED = 0xFFFF1744.toInt() // Neon Red
        const val COLOR_ARMED_FILL = 0x33FF1744.toInt() // 20% alpha
        const val COLOR_ARMED_BADGE = 0xCCFF1744.toInt() // 80% alpha

        const val COLOR_DISARMED = 0xFFFFAB00.toInt() // Amber
        const val COLOR_DISARMED_FILL = 0x22FFAB00.toInt()
        const val COLOR_DISARMED_BADGE = 0xCCFFAB00.toInt()

        /**
         * Transforms a normalized bounding box [0..1] into view pixel coordinates,
         * with optional horizontal mirroring for front-facing lenses.
         */
        fun transformRect(
            box: NormalizedRect,
            viewWidth: Float,
            viewHeight: Float,
            isMirrored: Boolean
        ): RectF {
            val leftNorm = if (isMirrored) (1.0f - box.right) else box.left
            val rightNorm = if (isMirrored) (1.0f - box.left) else box.right

            val left = (leftNorm * viewWidth).coerceIn(0f, viewWidth)
            val right = (rightNorm * viewWidth).coerceIn(0f, viewWidth)
            val top = (box.top * viewHeight).coerceIn(0f, viewHeight)
            val bottom = (box.bottom * viewHeight).coerceIn(0f, viewHeight)

            return RectF(
                minOf(left, right),
                minOf(top, bottom),
                maxOf(left, right),
                maxOf(top, bottom)
            )
        }

        /**
         * Formats the motion score percentage label.
         */
        fun formatMotionLabel(motionScore: Float): String {
            val percent = (motionScore.coerceIn(0f, 1f) * 100).toInt()
            return "MOTION $percent%"
        }
    }
}
