package com.securitycam.core.surveillance

/**
 * The part of the picture where movement counts, as fractions of the picture's width and
 * height. Movement entirely outside it (a road in the background, a neighbour's garden) is ignored.
 */
data class MotionZone(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    /** A zone covering (nearly) everything behaves as no zone at all. */
    val isWholePicture: Boolean
        get() = left <= EDGE && top <= EDGE && right >= 1f - EDGE && bottom >= 1f - EDGE

    /** False for corrupt values; callers then fall back to the whole picture. */
    val isValid: Boolean
        get() = left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f &&
            right - left >= MIN_SIDE && bottom - top >= MIN_SIDE

    /**
     * True if this movement counts. A box counts when its centre is inside the zone or at
     * least [MIN_OVERLAP] of its area is, so someone stepping across the edge is still caught.
     * With no boxes to judge by, the movement counts: ignoring it would be the unsafe choice.
     */
    fun contains(boxes: List<NormalizedRect>): Boolean {
        if (isWholePicture || !isValid || boxes.isEmpty()) return true
        return boxes.any { box ->
            val centerX = (box.left + box.right) / 2
            val centerY = (box.top + box.bottom) / 2
            if (centerX in left..right && centerY in top..bottom) return@any true

            val overlapWidth = minOf(box.right, right) - maxOf(box.left, left)
            val overlapHeight = minOf(box.bottom, bottom) - maxOf(box.top, top)
            if (overlapWidth <= 0f || overlapHeight <= 0f) return@any false
            val boxArea = box.area
            boxArea > 0f && (overlapWidth * overlapHeight) / boxArea >= MIN_OVERLAP
        }
    }

    companion object {
        val WHOLE_PICTURE = MotionZone()
        const val MIN_SIDE = 0.15f
        const val MIN_OVERLAP = 0.25f
        private const val EDGE = 0.01f
    }
}
