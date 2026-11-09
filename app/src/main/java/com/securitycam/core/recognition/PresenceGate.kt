package com.securitycam.core.recognition

/**
 * Decides, snapshot by snapshot, whether motion should raise an alert when known-people
 * recognition is on.
 *
 * A person's face is rarely visible in the first frame that shows motion, so the alert is
 * held for a short while to give the camera a chance to see who it is:
 *
 *  - a face nobody recognises            -> alert at once (even if a known person is also there)
 *  - an enrolled person, no strangers    -> no alert, and stay quiet for [quietMs] afterwards
 *                                           so they can move around or turn away
 *  - no usable face                      -> hold for up to [holdMs], then alert
 *
 * Not thread safe; the caller serialises access.
 */
class PresenceGate(
    val holdMs: Long = DEFAULT_HOLD_MS,
    val quietMs: Long = DEFAULT_QUIET_MS
) {
    sealed class Decision {
        object Alert : Decision()
        object Hold : Decision()
        data class Suppress(val names: Set<String>) : Decision()
    }

    private var holdStartedAt: Long? = null
    private var quietUntil: Long = 0L
    private var lastKnownNames: Set<String> = emptySet()

    val isHolding: Boolean get() = holdStartedAt != null

    fun evaluate(now: Long, verdict: FaceVerdict): Decision {
        if (verdict.unknownCount > 0) {
            quietUntil = 0L
            holdStartedAt = null
            return Decision.Alert
        }
        if (verdict.knownNames.isNotEmpty()) {
            quietUntil = now + quietMs
            lastKnownNames = verdict.knownNames
            holdStartedAt = null
            return Decision.Suppress(verdict.knownNames)
        }
        if (now < quietUntil) {
            return Decision.Suppress(lastKnownNames)
        }
        val started = holdStartedAt
        if (started == null) {
            holdStartedAt = now
            return Decision.Hold
        }
        if (now - started < holdMs) {
            return Decision.Hold
        }
        holdStartedAt = null
        return Decision.Alert
    }

    /**
     * Called by a timer in case motion stopped before the hold ran out, so no further snapshot
     * arrives to end it. Returns true if a held alert is now due.
     */
    fun expireHold(now: Long): Boolean {
        val started = holdStartedAt ?: return false
        if (now - started < holdMs) return false
        holdStartedAt = null
        return true
    }

    fun reset() {
        holdStartedAt = null
        quietUntil = 0L
        lastKnownNames = emptySet()
    }

    companion object {
        const val DEFAULT_HOLD_MS = 6_000L
        const val DEFAULT_QUIET_MS = 2 * 60 * 1000L
    }
}
