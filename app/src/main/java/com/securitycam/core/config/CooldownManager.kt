package com.securitycam.core.config

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Temporal rate limiter that suppresses external notifications during a configurable cooldown window.
 * Ensures local database and snapshot persistence can proceed while preventing alert spam.
 */
class CooldownManager(
    initialCooldownSeconds: Int = DEFAULT_COOLDOWN_SECONDS,
    private val timeProvider: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val DEFAULT_COOLDOWN_SECONDS = 30
        const val MIN_COOLDOWN_SECONDS = 10
        const val MAX_COOLDOWN_SECONDS = 300
    }

    enum class State {
        READY,
        ACTIVE_COOLDOWN
    }

    private val cooldownSec = AtomicInteger(
        initialCooldownSeconds.coerceIn(MIN_COOLDOWN_SECONDS, MAX_COOLDOWN_SECONDS)
    )

    private val lastAlertTimestampMs = AtomicLong(-1L)

    var cooldownSeconds: Int
        get() = cooldownSec.get()
        set(value) {
            cooldownSec.set(value.coerceIn(MIN_COOLDOWN_SECONDS, MAX_COOLDOWN_SECONDS))
        }

    /**
     * Checks if external alert dispatch is currently permitted at [nowMs].
     */
    fun shouldAlert(nowMs: Long = timeProvider()): Boolean {
        val last = lastAlertTimestampMs.get()
        if (last < 0L) return true
        val elapsedMs = nowMs - last
        return elapsedMs >= (cooldownSec.get() * 1000L)
    }

    /**
     * Records that an external alert was dispatched at [nowMs], entering active cooldown.
     */
    fun recordAlertDispatched(nowMs: Long = timeProvider()) {
        lastAlertTimestampMs.set(nowMs)
    }

    /**
     * Calculates remaining cooldown time in milliseconds. Returns 0 if ready.
     */
    fun getRemainingCooldownMs(nowMs: Long = timeProvider()): Long {
        val last = lastAlertTimestampMs.get()
        if (last < 0L) return 0L
        val elapsed = nowMs - last
        val totalCooldownMs = cooldownSec.get() * 1000L
        val remaining = totalCooldownMs - elapsed
        return if (remaining > 0L) remaining else 0L
    }

    /**
     * Gets the current operational state of the cooldown manager.
     */
    fun getState(nowMs: Long = timeProvider()): State {
        return if (shouldAlert(nowMs)) State.READY else State.ACTIVE_COOLDOWN
    }

    /**
     * Resets cooldown, immediately allowing the next alert.
     */
    fun reset() {
        lastAlertTimestampMs.set(-1L)
    }
}
