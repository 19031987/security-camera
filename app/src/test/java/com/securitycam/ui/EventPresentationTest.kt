package com.securitycam.ui

import com.securitycam.core.storage.MotionEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Unit test suite verifying EventAdapter presentation formatting,
 * DiffUtil item equality logic, and delivery status chip mapping.
 */
class EventPresentationTest {

    private val diffCallback = EventAdapter.EventDiffCallback()

    @Test
    fun testTimestampFormattingNotNullAndValid() {
        val testTime = 1700000000000L
        val formatted = EventAdapter.formatTimestamp(testTime)
        assertNotNull(formatted)
        assertTrue("Formatted date must not be empty", formatted.isNotEmpty())
        // Checks that year is formatted correctly
        assertTrue("Formatted date should contain year", formatted.contains("2023"))
    }

    @Test
    fun testMotionScoreFormatting() {
        assertEquals("Motion Score: 88%", EventAdapter.formatMotionScore(0.884f))
        assertEquals("Motion Score: 0%", EventAdapter.formatMotionScore(0.0f))
        assertEquals("Motion Score: 100%", EventAdapter.formatMotionScore(1.0f))
        assertEquals("Motion Score: 100%", EventAdapter.formatMotionScore(1.5f))
        assertEquals("Motion Score: 0%", EventAdapter.formatMotionScore(-0.2f))
    }

    @Test
    fun testStatusChipTextMapping() {
        assertEquals("SMS: Sent", EventAdapter.formatStatusChipText("SMS", MotionEventEntity.STATUS_SENT))
        assertEquals("SMS: Failed", EventAdapter.formatStatusChipText("SMS", MotionEventEntity.STATUS_FAILED))
        assertEquals("SMS: Cooldown", EventAdapter.formatStatusChipText("SMS", MotionEventEntity.STATUS_SUPPRESSED))
        assertEquals("SMS: Disabled", EventAdapter.formatStatusChipText("SMS", MotionEventEntity.STATUS_DISABLED))

        assertEquals("Email: Sent", EventAdapter.formatStatusChipText("Email", MotionEventEntity.STATUS_SENT))
        assertEquals("Email: Failed", EventAdapter.formatStatusChipText("Email", MotionEventEntity.STATUS_FAILED))
        assertEquals("Email: Cooldown", EventAdapter.formatStatusChipText("Email", MotionEventEntity.STATUS_SUPPRESSED))
        assertEquals("Email: Disabled", EventAdapter.formatStatusChipText("Email", MotionEventEntity.STATUS_DISABLED))
    }

    @Test
    fun testStatusChipColorMapping() {
        assertEquals(0xFF00E676.toInt(), EventAdapter.getStatusChipColor(MotionEventEntity.STATUS_SENT))
        assertEquals(0xFFFF1744.toInt(), EventAdapter.getStatusChipColor(MotionEventEntity.STATUS_FAILED))
        assertEquals(0xFFFFAB00.toInt(), EventAdapter.getStatusChipColor(MotionEventEntity.STATUS_SUPPRESSED))
        assertEquals(0xFF94A3B8.toInt(), EventAdapter.getStatusChipColor(MotionEventEntity.STATUS_DISABLED))
    }

    @Test
    fun testDiffCallbackEqualityChecks() {
        val id1 = UUID.randomUUID().toString()
        val id2 = UUID.randomUUID().toString()

        val eventA = MotionEventEntity(
            id = id1,
            timestamp = 1000L,
            imagePath = "/path/to/img1.jpg",
            motionScore = 0.5f,
            isLowLight = false,
            smsStatus = MotionEventEntity.STATUS_DISABLED,
            emailStatus = MotionEventEntity.STATUS_DISABLED
        )

        val eventAUpdated = eventA.copy(
            smsStatus = MotionEventEntity.STATUS_SENT,
            emailStatus = MotionEventEntity.STATUS_SENT
        )

        val eventB = MotionEventEntity(
            id = id2,
            timestamp = 2000L,
            imagePath = "/path/to/img2.jpg",
            motionScore = 0.75f,
            isLowLight = false,
            smsStatus = MotionEventEntity.STATUS_SENT,
            emailStatus = MotionEventEntity.STATUS_SENT
        )

        // areItemsTheSame checks primary key (id)
        assertTrue(diffCallback.areItemsTheSame(eventA, eventAUpdated))
        assertFalse(diffCallback.areItemsTheSame(eventA, eventB))

        // areContentsTheSame checks full data equality
        assertFalse(diffCallback.areContentsTheSame(eventA, eventAUpdated))
        assertTrue(diffCallback.areContentsTheSame(eventA, eventA.copy()))
        assertFalse(diffCallback.areContentsTheSame(eventA, eventB))
    }
}
