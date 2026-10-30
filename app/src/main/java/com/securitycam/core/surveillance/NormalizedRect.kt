package com.securitycam.core.surveillance

/**
 * Normalized bounding box where coordinates range from 0.0 to 1.0 relative to frame dimensions.
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    init {
        require(left in 0f..1f) { "left must be in [0, 1], got $left" }
        require(top in 0f..1f) { "top must be in [0, 1], got $top" }
        require(right in 0f..1f) { "right must be in [0, 1], got $right" }
        require(bottom in 0f..1f) { "bottom must be in [0, 1], got $bottom" }
        require(right >= left) { "right ($right) must be >= left ($left)" }
        require(bottom >= top) { "bottom ($bottom) must be >= top ($top)" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height
}
