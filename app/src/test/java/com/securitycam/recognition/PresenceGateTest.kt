package com.securitycam.recognition

import com.securitycam.core.recognition.FaceVerdict
import com.securitycam.core.recognition.PresenceGate
import com.securitycam.core.recognition.PresenceGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceGateTest {

    private val noFace = FaceVerdict.NO_FACES
    private val asha = FaceVerdict(setOf("Asha"), 0)
    private val stranger = FaceVerdict(emptySet(), 1)
    private val ashaWithStranger = FaceVerdict(setOf("Asha"), 1)

    private fun gate() = PresenceGate(holdMs = 6_000, quietMs = 120_000)

    @Test
    fun motionWithNoFaceIsHeldThenAlerted() {
        val g = gate()
        assertEquals(Decision.Hold, g.evaluate(0, noFace))
        assertTrue(g.isHolding)
        assertEquals(Decision.Hold, g.evaluate(3_000, noFace))
        assertEquals(Decision.Alert, g.evaluate(6_000, noFace))
        assertFalse(g.isHolding)
    }

    @Test
    fun aStrangerAlertsImmediately() {
        assertEquals(Decision.Alert, gate().evaluate(0, stranger))
    }

    @Test
    fun aStrangerAlertsEvenDuringAHold() {
        val g = gate()
        g.evaluate(0, noFace)
        assertEquals(Decision.Alert, g.evaluate(1_000, stranger))
        assertFalse(g.isHolding)
    }

    @Test
    fun aKnownPersonSeenDuringTheHoldCancelsTheAlert() {
        val g = gate()
        assertEquals(Decision.Hold, g.evaluate(0, noFace))
        assertEquals(Decision.Suppress(setOf("Asha")), g.evaluate(2_000, asha))
        assertFalse(g.isHolding)
        assertFalse(g.expireHold(10_000))
    }

    @Test
    fun aKnownPersonWithAStrangerStillAlerts() {
        assertEquals(Decision.Alert, gate().evaluate(0, ashaWithStranger))
    }

    @Test
    fun afterAKnownPersonMotionWithoutAFaceStaysQuietForAWhile() {
        val g = gate()
        g.evaluate(0, asha)
        assertEquals(Decision.Suppress(setOf("Asha")), g.evaluate(60_000, noFace))
        assertEquals(Decision.Suppress(setOf("Asha")), g.evaluate(119_999, noFace))
    }

    @Test
    fun theQuietPeriodEnds() {
        val g = gate()
        g.evaluate(0, asha)
        assertEquals(Decision.Hold, g.evaluate(120_000, noFace))
        assertEquals(Decision.Alert, g.evaluate(126_000, noFace))
    }

    @Test
    fun seeingTheKnownPersonAgainExtendsTheQuietPeriod() {
        val g = gate()
        g.evaluate(0, asha)
        g.evaluate(100_000, asha)
        assertEquals(Decision.Suppress(setOf("Asha")), g.evaluate(200_000, noFace))
    }

    @Test
    fun aStrangerEndsTheQuietPeriod() {
        val g = gate()
        g.evaluate(0, asha)
        assertEquals(Decision.Alert, g.evaluate(10_000, stranger))
        // No longer quiet: faceless motion is held and alerted again.
        assertEquals(Decision.Hold, g.evaluate(11_000, noFace))
        assertEquals(Decision.Alert, g.evaluate(17_000, noFace))
    }

    @Test
    fun theTimerEndsAHoldWhenMotionStops() {
        val g = gate()
        g.evaluate(0, noFace)
        assertFalse(g.expireHold(5_999))
        assertTrue(g.expireHold(6_000))
        assertFalse(g.isHolding)
        // Only once.
        assertFalse(g.expireHold(7_000))
    }

    @Test
    fun theTimerDoesNothingWhenNothingIsHeld() {
        assertFalse(gate().expireHold(100_000))
    }

    @Test
    fun resetClearsHoldAndQuietPeriod() {
        val g = gate()
        g.evaluate(0, asha)
        g.reset()
        assertEquals(Decision.Hold, g.evaluate(1_000, noFace))
    }
}
