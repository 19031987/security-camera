package com.securitycam.config

import com.securitycam.core.config.CooldownManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CooldownManagerTest {

    @Test
    fun testInitialStatePermitsAlert() {
        val cooldown = CooldownManager(initialCooldownSeconds = 30)
        assertTrue(cooldown.shouldAlert(1000L))
        assertEquals(0L, cooldown.getRemainingCooldownMs(1000L))
        assertEquals(CooldownManager.State.READY, cooldown.getState(1000L))
    }

    @Test
    fun testCooldownWindowSuppressionAndExpiration() {
        var currentTime = 100_000L
        val cooldown = CooldownManager(
            initialCooldownSeconds = 30,
            timeProvider = { currentTime }
        )

        // Initial alert permitted
        assertTrue(cooldown.shouldAlert())
        cooldown.recordAlertDispatched()

        // Transitioned to ACTIVE_COOLDOWN
        assertEquals(CooldownManager.State.ACTIVE_COOLDOWN, cooldown.getState())
        assertFalse(cooldown.shouldAlert())
        assertEquals(30_000L, cooldown.getRemainingCooldownMs())

        // Halfway through cooldown (15s elapsed)
        currentTime += 15_000L
        assertFalse(cooldown.shouldAlert())
        assertEquals(15_000L, cooldown.getRemainingCooldownMs())

        // 1 ms before expiration (29.999s elapsed)
        currentTime = 100_000L + 29_999L
        assertFalse(cooldown.shouldAlert())
        assertEquals(1L, cooldown.getRemainingCooldownMs())

        // Exactly at expiration (30.000s elapsed)
        currentTime = 100_000L + 30_000L
        assertTrue(cooldown.shouldAlert())
        assertEquals(0L, cooldown.getRemainingCooldownMs())
        assertEquals(CooldownManager.State.READY, cooldown.getState())
    }

    @Test
    fun testDynamicCooldownConfigurationClamping() {
        val cooldown = CooldownManager()

        // Minimum clamping (10s)
        cooldown.cooldownSeconds = 5
        assertEquals(10, cooldown.cooldownSeconds)

        // Maximum clamping (300s)
        cooldown.cooldownSeconds = 500
        assertEquals(300, cooldown.cooldownSeconds)

        // Valid in-range assignment
        cooldown.cooldownSeconds = 45
        assertEquals(45, cooldown.cooldownSeconds)
    }

    @Test
    fun testResetClearsCooldownImmediately() {
        val cooldown = CooldownManager(initialCooldownSeconds = 30)
        cooldown.recordAlertDispatched(nowMs = 1000L)
        assertFalse(cooldown.shouldAlert(nowMs = 2000L))

        cooldown.reset()
        assertTrue(cooldown.shouldAlert(nowMs = 2000L))
        assertEquals(0L, cooldown.getRemainingCooldownMs(nowMs = 2000L))
    }

    @Test
    fun testMultiCycleAlerts() {
        var time = 0L
        val cooldown = CooldownManager(initialCooldownSeconds = 20, timeProvider = { time })

        // Cycle 1
        assertTrue(cooldown.shouldAlert())
        cooldown.recordAlertDispatched()

        time = 10_000L
        assertFalse(cooldown.shouldAlert())

        time = 20_000L
        assertTrue(cooldown.shouldAlert())

        // Cycle 2
        cooldown.recordAlertDispatched()
        assertFalse(cooldown.shouldAlert())

        time = 40_000L
        assertTrue(cooldown.shouldAlert())
    }
}
