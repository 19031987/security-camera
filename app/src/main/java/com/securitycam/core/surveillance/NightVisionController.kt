package com.securitycam.core.surveillance

/**
 * Decides when the camera should switch into its night configuration (longer exposure).
 *
 * The decision is based on a scene-brightness index derived from the sensor's own exposure
 * settings ([sceneBrightness]), not on how bright the delivered image looks. That matters:
 * once night mode lengthens the exposure the image gets brighter, and a decision based on
 * the image alone would immediately switch night mode off again and oscillate.
 *
 * Two thresholds (hysteresis) and a required run of consecutive frames keep a passing
 * shadow or a brief torch beam from flipping the mode.
 */
class NightVisionController(
    private val enterBelow: Float = DEFAULT_ENTER_BELOW,
    private val exitAbove: Float = DEFAULT_EXIT_ABOVE,
    private val framesToSwitch: Int = DEFAULT_FRAMES_TO_SWITCH
) {
    init {
        require(enterBelow < exitAbove) { "enterBelow must be lower than exitAbove" }
        require(framesToSwitch >= 1) { "framesToSwitch must be at least 1" }
    }

    var isActive: Boolean = false
        private set

    private var streak = 0

    /**
     * Feeds one frame's scene brightness. A null value means the camera did not report its
     * exposure for this frame; the current state is kept.
     */
    fun update(sceneBrightness: Float?): Boolean {
        if (sceneBrightness == null) return isActive

        val wantsActive = if (isActive) sceneBrightness <= exitAbove else sceneBrightness < enterBelow
        if (wantsActive != isActive) {
            streak++
            if (streak >= framesToSwitch) {
                isActive = wantsActive
                streak = 0
            }
        } else {
            streak = 0
        }
        return isActive
    }

    fun reset() {
        isActive = false
        streak = 0
    }

    companion object {
        const val DEFAULT_ENTER_BELOW = 1.0f
        const val DEFAULT_EXIT_ABOVE = 3.0f
        const val DEFAULT_FRAMES_TO_SWITCH = 20

        /** Images darker than this on average get digitally brightened. */
        const val DIGITAL_BOOST_BELOW_LUMINANCE = 60f

        /**
         * Brightness of the scene itself, independent of how the camera exposed it:
         * image luminance divided by the exposure used to get it (ISO x seconds).
         * A lit room is roughly 5-20, a room lit by a TV or street light is below 1.
         * Returns null when the camera did not report usable exposure values.
         */
        fun sceneBrightness(averageLuminance: Float, iso: Int?, exposureTimeNs: Long?): Float? {
            if (iso == null || exposureTimeNs == null || iso <= 0 || exposureTimeNs <= 0L) return null
            val exposure = iso.toFloat() * (exposureTimeNs / 1_000_000_000f)
            return averageLuminance.coerceAtLeast(0f) / exposure
        }
    }
}
