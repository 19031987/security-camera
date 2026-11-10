package com.securitycam.core.alerting

import java.util.Calendar
import java.util.TimeZone

/**
 * A weekly timetable for the Away and Home modes: Away during one daily window on the chosen
 * weekdays, Home the rest of the time. Pure logic; the mode is worked out whenever it is needed,
 * so nothing has to wake the phone at the switching times.
 */
data class ModeSchedule(
    /** Minutes after midnight at which Away starts. */
    val awayStartMinutes: Int,
    /** Minutes after midnight at which Away ends. If not after the start, the window runs overnight. */
    val awayEndMinutes: Int,
    /** Weekdays on which an Away window starts, as bits: Monday = 1, Tuesday = 2 … Sunday = 64. */
    val awayDays: Int
) {
    val isValid: Boolean
        get() = awayStartMinutes in 0 until MINUTES_PER_DAY && awayEndMinutes in 0 until MINUTES_PER_DAY &&
            awayStartMinutes != awayEndMinutes && (awayDays and ALL_DAYS) != 0

    /** [AlertPolicy.MODE_AWAY] or [AlertPolicy.MODE_HOME] at the given moment. */
    fun modeAt(timeMillis: Long, zone: TimeZone = TimeZone.getDefault()): String {
        if (!isValid) return AlertPolicy.MODE_AWAY
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = timeMillis }
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val today = dayBit(calendar.get(Calendar.DAY_OF_WEEK))
        val yesterday = previousDay(today)

        val away = if (awayStartMinutes < awayEndMinutes) {
            (awayDays and today) != 0 && minutes >= awayStartMinutes && minutes < awayEndMinutes
        } else {
            // Overnight: the evening part belongs to today's window, the morning part to yesterday's.
            ((awayDays and today) != 0 && minutes >= awayStartMinutes) ||
                ((awayDays and yesterday) != 0 && minutes < awayEndMinutes)
        }
        return if (away) AlertPolicy.MODE_AWAY else AlertPolicy.MODE_HOME
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
        const val MONDAY = 1
        const val SUNDAY = 64
        const val ALL_DAYS = 127
        const val WEEKDAYS = 31

        /** Monday to Friday, 09:00 to 18:00. */
        val DEFAULT = ModeSchedule(9 * 60, 18 * 60, WEEKDAYS)

        /** Converts [Calendar.DAY_OF_WEEK] (Sunday = 1) to this class's bit (Monday = 1). */
        fun dayBit(calendarDay: Int): Int = when (calendarDay) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 4
            Calendar.THURSDAY -> 8
            Calendar.FRIDAY -> 16
            Calendar.SATURDAY -> 32
            else -> 64
        }

        private fun previousDay(bit: Int): Int = if (bit == MONDAY) SUNDAY else bit shr 1

        fun formatTime(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
    }
}
