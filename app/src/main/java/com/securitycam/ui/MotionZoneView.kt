package com.securitycam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.securitycam.core.surveillance.MotionZone
import kotlin.math.abs

/**
 * Shows the motion zone over the live picture and, in edit mode, lets it be dragged:
 * a corner to resize, the middle to move. Everything outside the zone is dimmed.
 */
class MotionZoneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var zone: MotionZone = MotionZone.WHOLE_PICTURE
        set(value) {
            field = if (value.isValid) value else MotionZone.WHOLE_PICTURE
            invalidate()
        }

    var isEditing: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val dimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFF4FD8EB.toInt()
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4FD8EB.toInt() }
    private val handleRadius = 10f * density
    private val touchSlop = 32f * density
    private val rect = RectF()

    private enum class Drag { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private var drag = Drag.NONE
    private var lastX = 0f
    private var lastY = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isEditing && zone.isWholePicture) return

        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(zone.left * w, zone.top * h, zone.right * w, zone.bottom * h)

        // Dim the four strips outside the zone.
        canvas.drawRect(0f, 0f, w, rect.top, dimPaint)
        canvas.drawRect(0f, rect.bottom, w, h, dimPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dimPaint)
        canvas.drawRect(rect.right, rect.top, w, rect.bottom, dimPaint)
        canvas.drawRect(rect, borderPaint)

        if (isEditing) {
            canvas.drawCircle(rect.left, rect.top, handleRadius, handlePaint)
            canvas.drawCircle(rect.right, rect.top, handleRadius, handlePaint)
            canvas.drawCircle(rect.left, rect.bottom, handleRadius, handlePaint)
            canvas.drawCircle(rect.right, rect.bottom, handleRadius, handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEditing || width == 0 || height == 0) return false
        val w = width.toFloat()
        val h = height.toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rect.set(zone.left * w, zone.top * h, zone.right * w, zone.bottom * h)
                drag = pickDrag(event.x, event.y)
                lastX = event.x
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return drag != Drag.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.x - lastX) / w
                val dy = (event.y - lastY) / h
                lastX = event.x
                lastY = event.y
                zone = adjust(zone, drag, dx, dy)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = Drag.NONE
                return true
            }
        }
        return false
    }

    private fun pickDrag(x: Float, y: Float): Drag {
        fun near(px: Float, py: Float) = abs(x - px) <= touchSlop && abs(y - py) <= touchSlop
        return when {
            near(rect.left, rect.top) -> Drag.TOP_LEFT
            near(rect.right, rect.top) -> Drag.TOP_RIGHT
            near(rect.left, rect.bottom) -> Drag.BOTTOM_LEFT
            near(rect.right, rect.bottom) -> Drag.BOTTOM_RIGHT
            rect.contains(x, y) -> Drag.MOVE
            else -> Drag.NONE
        }
    }

    companion object {
        /** Applies a drag to the zone, keeping it inside the picture and above the minimum size. */
        internal fun adjust(zone: MotionZone, handle: Any, dx: Float, dy: Float): MotionZone {
            val min = MotionZone.MIN_SIDE
            var l = zone.left
            var t = zone.top
            var r = zone.right
            var b = zone.bottom
            when (handle as Drag) {
                Drag.MOVE -> {
                    val moveX = dx.coerceIn(-l, 1f - r)
                    val moveY = dy.coerceIn(-t, 1f - b)
                    l += moveX; r += moveX; t += moveY; b += moveY
                }
                Drag.TOP_LEFT -> {
                    l = (l + dx).coerceIn(0f, r - min); t = (t + dy).coerceIn(0f, b - min)
                }
                Drag.TOP_RIGHT -> {
                    r = (r + dx).coerceIn(l + min, 1f); t = (t + dy).coerceIn(0f, b - min)
                }
                Drag.BOTTOM_LEFT -> {
                    l = (l + dx).coerceIn(0f, r - min); b = (b + dy).coerceIn(t + min, 1f)
                }
                Drag.BOTTOM_RIGHT -> {
                    r = (r + dx).coerceIn(l + min, 1f); b = (b + dy).coerceIn(t + min, 1f)
                }
                Drag.NONE -> Unit
            }
            return MotionZone(l, t, r, b)
        }
    }
}
