package com.securitycam.ui

import com.securitycam.core.storage.MotionEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class EventGroupingTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0, zone: TimeZone = utc): Long {
        val calendar = Calendar.getInstance(zone)
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute, 0)
        return calendar.timeInMillis
    }

    private fun event(id: String, timestamp: Long) = MotionEventEntity(
        id = id,
        timestamp = timestamp,
        imagePath = "/tmp/$id.jpg",
        motionScore = 0.5f,
        isLowLight = false
    )

    @Test
    fun emptyListProducesNoRows() {
        assertTrue(EventGrouping.group(emptyList(), utc).isEmpty())
    }

    @Test
    fun groupsByDayNewestFirstWithHeaderCounts() {
        val events = listOf(
            event("old", at(2026, 11, 7, 9)),
            event("b", at(2026, 11, 9, 8)),
            event("a", at(2026, 11, 9, 23, 59)),
            event("c", at(2026, 11, 8, 0))
        )

        val rows = EventGrouping.group(events, utc)

        assertEquals(
            listOf(
                HistoryRow.Header(at(2026, 11, 9, 0), 2),
                HistoryRow.Event(events[2]),
                HistoryRow.Event(events[1]),
                HistoryRow.Header(at(2026, 11, 8, 0), 1),
                HistoryRow.Event(events[3]),
                HistoryRow.Header(at(2026, 11, 7, 0), 1),
                HistoryRow.Event(events[0])
            ),
            rows
        )
    }

    @Test
    fun dayBoundaryFollowsTheGivenTimeZone() {
        val kolkata = TimeZone.getTimeZone("Asia/Kolkata")
        // 20:00 UTC on the 8th is 01:30 on the 9th in Kolkata.
        val timestamp = at(2026, 11, 8, 20)

        assertEquals(at(2026, 11, 8, 0), EventGrouping.startOfDay(timestamp, utc))
        assertEquals(at(2026, 11, 9, 0, zone = kolkata), EventGrouping.startOfDay(timestamp, kolkata))
    }

    @Test
    fun dayKindDistinguishesTodayYesterdayAndOlder() {
        val now = at(2026, 11, 9, 15)

        assertEquals(EventGrouping.DayKind.TODAY, EventGrouping.dayKind(at(2026, 11, 9, 0), now, utc))
        assertEquals(EventGrouping.DayKind.YESTERDAY, EventGrouping.dayKind(at(2026, 11, 8, 0), now, utc))
        assertEquals(EventGrouping.DayKind.OTHER, EventGrouping.dayKind(at(2026, 11, 7, 0), now, utc))
    }

    @Test
    fun yesterdayIsFoundAcrossDaylightSavingChange() {
        val newYork = TimeZone.getTimeZone("America/New_York")
        // Clocks went back on 1 Nov 2026, so that day is 25 hours long.
        val now = at(2026, 11, 2, 10, zone = newYork)
        val yesterdayStart = at(2026, 11, 1, 0, zone = newYork)

        assertEquals(EventGrouping.DayKind.YESTERDAY, EventGrouping.dayKind(yesterdayStart, now, newYork))
    }

    @Test
    fun countTodayIgnoresOtherDays() {
        val now = at(2026, 11, 9, 15)
        val events = listOf(
            event("a", at(2026, 11, 9, 0)),
            event("b", at(2026, 11, 9, 14)),
            event("c", at(2026, 11, 8, 23, 59))
        )

        assertEquals(2, EventGrouping.countToday(events, now, utc))
        assertEquals(0, EventGrouping.countToday(emptyList(), now, utc))
    }
}
