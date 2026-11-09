package com.securitycam.ui

import com.securitycam.core.storage.MotionEventEntity
import java.util.Calendar
import java.util.TimeZone

/** One row of the history list: a day header or an event under it. */
sealed class HistoryRow {
    data class Header(val dayStartMillis: Long, val eventCount: Int) : HistoryRow()
    data class Event(val event: MotionEventEntity) : HistoryRow()
}

/**
 * Groups motion events by calendar day for the history list.
 * Pure functions: the time zone and "now" are passed in so they can be unit tested.
 */
object EventGrouping {

    enum class DayKind { TODAY, YESTERDAY, OTHER }

    fun startOfDay(timestamp: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val calendar = Calendar.getInstance(zone)
        calendar.timeInMillis = timestamp
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /** Newest day first, newest event first within a day, each day preceded by its header. */
    fun group(
        events: List<MotionEventEntity>,
        zone: TimeZone = TimeZone.getDefault()
    ): List<HistoryRow> {
        val rows = ArrayList<HistoryRow>(events.size + 8)
        events.sortedByDescending { it.timestamp }
            .groupBy { startOfDay(it.timestamp, zone) }
            .forEach { (dayStart, dayEvents) ->
                rows.add(HistoryRow.Header(dayStart, dayEvents.size))
                dayEvents.forEach { rows.add(HistoryRow.Event(it)) }
            }
        return rows
    }

    fun dayKind(
        dayStartMillis: Long,
        now: Long,
        zone: TimeZone = TimeZone.getDefault()
    ): DayKind {
        val today = startOfDay(now, zone)
        // One millisecond before today's midnight is always inside yesterday, even across DST changes.
        val yesterday = startOfDay(today - 1, zone)
        return when (dayStartMillis) {
            today -> DayKind.TODAY
            yesterday -> DayKind.YESTERDAY
            else -> DayKind.OTHER
        }
    }

    fun countToday(
        events: List<MotionEventEntity>,
        now: Long,
        zone: TimeZone = TimeZone.getDefault()
    ): Int {
        val today = startOfDay(now, zone)
        return events.count { startOfDay(it.timestamp, zone) == today }
    }
}
