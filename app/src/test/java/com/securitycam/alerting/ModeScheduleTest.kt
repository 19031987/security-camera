package com.securitycam.alerting

import com.securitycam.core.alerting.AlertPolicy
import com.securitycam.core.alerting.AlertPolicy.Verdict
import com.securitycam.core.alerting.ModeSchedule
import com.securitycam.core.config.SecuritySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ModeScheduleTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val away = AlertPolicy.MODE_AWAY
    private val home = AlertPolicy.MODE_HOME

    /** 9 November 2026 is a Monday. */
    private fun at(day: Int, hour: Int, minute: Int = 0, zone: TimeZone = utc): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(2026, Calendar.NOVEMBER, day, hour, minute, 0)
        }.timeInMillis

    private val workHours = ModeSchedule(9 * 60, 18 * 60, ModeSchedule.WEEKDAYS)

    @Test
    fun theTestCalendarStartsOnAMonday() {
        val calendar = Calendar.getInstance(utc).apply { timeInMillis = at(9, 12) }
        assertEquals(Calendar.MONDAY, calendar.get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun awayInsideTheWindowAndHomeOutsideIt() {
        assertEquals(home, workHours.modeAt(at(9, 8, 59), utc))
        assertEquals(away, workHours.modeAt(at(9, 9, 0), utc))
        assertEquals(away, workHours.modeAt(at(9, 13), utc))
        assertEquals(away, workHours.modeAt(at(9, 17, 59), utc))
        assertEquals(home, workHours.modeAt(at(9, 18, 0), utc))
        assertEquals(home, workHours.modeAt(at(9, 23), utc))
    }

    @Test
    fun daysThatAreNotChosenStayHomeAllDay() {
        // Saturday the 14th and Sunday the 15th.
        assertEquals(home, workHours.modeAt(at(14, 12), utc))
        assertEquals(home, workHours.modeAt(at(15, 12), utc))
        // Friday the 13th is a weekday.
        assertEquals(away, workHours.modeAt(at(13, 12), utc))
    }

    @Test
    fun anOvernightWindowRunsIntoTheNextMorning() {
        // 22:00 to 06:00, starting on Monday only.
        val night = ModeSchedule(22 * 60, 6 * 60, ModeSchedule.MONDAY)

        assertEquals(home, night.modeAt(at(9, 21, 59), utc))
        assertEquals(away, night.modeAt(at(9, 22, 0), utc))
        assertEquals(away, night.modeAt(at(10, 3), utc))
        assertEquals(home, night.modeAt(at(10, 6, 0), utc))
        // Tuesday evening has no window of its own.
        assertEquals(home, night.modeAt(at(10, 23), utc))
        // Monday morning belongs to Sunday's window, which isn't chosen.
        assertEquals(home, night.modeAt(at(9, 3), utc))
    }

    @Test
    fun anOvernightWindowStartingOnSundayReachesMondayMorning() {
        val night = ModeSchedule(22 * 60, 6 * 60, ModeSchedule.SUNDAY)
        assertEquals(away, night.modeAt(at(15, 23), utc))
        assertEquals(away, night.modeAt(at(16, 5), utc))
        assertEquals(home, night.modeAt(at(16, 7), utc))
    }

    @Test
    fun theHoursFollowThePhonesTimeZone() {
        val kolkata = TimeZone.getTimeZone("Asia/Kolkata")
        // 10:00 in Kolkata on Monday is 04:30 UTC.
        val moment = at(9, 10, zone = kolkata)
        assertEquals(away, workHours.modeAt(moment, kolkata))
        assertEquals(home, workHours.modeAt(moment, utc))
    }

    @Test
    fun aTimetableThatCannotWorkIsInvalid() {
        assertTrue(workHours.isValid)
        assertFalse(ModeSchedule(9 * 60, 9 * 60, ModeSchedule.ALL_DAYS).isValid)
        assertFalse(ModeSchedule(9 * 60, 18 * 60, 0).isValid)
        assertFalse(ModeSchedule(-5, 18 * 60, ModeSchedule.ALL_DAYS).isValid)
        assertFalse(ModeSchedule(9 * 60, 24 * 60, ModeSchedule.ALL_DAYS).isValid)
        // An unusable timetable never silences the camera.
        assertEquals(away, ModeSchedule(9 * 60, 9 * 60, ModeSchedule.ALL_DAYS).modeAt(at(9, 12), utc))
    }

    @Test
    fun timesAreShownAsTwentyFourHourClock() {
        assertEquals("09:00", ModeSchedule.formatTime(9 * 60))
        assertEquals("18:30", ModeSchedule.formatTime(18 * 60 + 30))
        assertEquals("00:05", ModeSchedule.formatTime(5))
    }

    // How the timetable feeds the alert rules

    private val scheduled = SecuritySettings(scheduleEnabled = true, alertMode = AlertPolicy.MODE_HOME)

    @Test
    fun whenSwitchedOnTheTimetableOverridesTheModeChosenByHand() {
        // Chosen by hand: Home. Timetable at Monday noon: Away.
        assertEquals(away, AlertPolicy.effectiveMode(scheduled, at(9, 12), utc))
        assertEquals(home, AlertPolicy.effectiveMode(scheduled, at(9, 20), utc))
        assertEquals(Verdict.ALERT, AlertPolicy.decide(scheduled, AlertPolicy.KIND_PERSON, false, at(9, 12), utc))
        assertEquals(Verdict.QUIET_HOME_MODE, AlertPolicy.decide(scheduled, AlertPolicy.KIND_PERSON, false, at(9, 20), utc))
    }

    @Test
    fun whenSwitchedOffTheHandChosenModeApplies() {
        val manual = scheduled.copy(scheduleEnabled = false)
        assertEquals(home, AlertPolicy.effectiveMode(manual, at(9, 12), utc))
        assertEquals(away, AlertPolicy.effectiveMode(manual.copy(alertMode = AlertPolicy.MODE_AWAY), at(9, 20), utc))
    }

    @Test
    fun anInvalidTimetableFallsBackToTheHandChosenMode() {
        val broken = scheduled.copy(scheduleAwayDays = 0)
        assertEquals(home, AlertPolicy.effectiveMode(broken, at(9, 12), utc))
    }
}
