package com.panzhikun.metaldogshower

import com.panzhikun.metaldogshower.widget.WidgetRefreshResult
import com.panzhikun.metaldogshower.widget.WidgetShowerState
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PollingSettingsTest {
    @Test
    fun foregroundPollingIntervalsAreUniqueAndNeverMoreFrequentThanOneMinute() {
        val seconds = PollingIntervals.map { it.first }

        assertEquals(seconds.distinct(), seconds)
        assertTrue(seconds.all { it >= 60 })
    }

    @Test
    fun dailyScheduleUsesTheNextIntervalInsideTheWindow() {
        val now = utc(2026, Calendar.AUGUST, 11, 8, 7)
        val settings = BackgroundPollingSettings(
            enabled = true,
            mode = BackgroundPollingMode.DAILY,
            intervalMinutes = 5,
            dailyStartMinute = 8 * 60,
            dailyEndMinute = 10 * 60,
        )

        assertTrue(BackgroundPollingPlanner.isActive(settings, now, UTC))
        assertEquals(
            utc(2026, Calendar.AUGUST, 11, 8, 10),
            BackgroundPollingPlanner.nextTriggerAt(settings, now, false, UTC),
        )
        assertEquals(
            now + 1_000L,
            BackgroundPollingPlanner.nextTriggerAt(settings, now, true, UTC),
        )
    }

    @Test
    fun crossMidnightDailyWindowRemainsActiveAfterMidnight() {
        val now = utc(2026, Calendar.AUGUST, 12, 0, 6)
        val settings = BackgroundPollingSettings(
            enabled = true,
            mode = BackgroundPollingMode.DAILY,
            intervalMinutes = 10,
            dailyStartMinute = 22 * 60,
            dailyEndMinute = 2 * 60,
        )

        assertTrue(BackgroundPollingPlanner.isActive(settings, now, UTC))
        assertEquals(
            utc(2026, Calendar.AUGUST, 12, 0, 10),
            BackgroundPollingPlanner.nextTriggerAt(settings, now, false, UTC),
        )
    }

    @Test
    fun oneTimeScheduleStopsAfterItsEnd() {
        val start = utc(2026, Calendar.AUGUST, 11, 18, 0)
        val end = utc(2026, Calendar.AUGUST, 11, 19, 0)
        val settings = BackgroundPollingSettings(
            enabled = true,
            mode = BackgroundPollingMode.ONCE,
            intervalMinutes = 15,
            onceStartAtMillis = start,
            onceEndAtMillis = end,
        )

        assertFalse(BackgroundPollingPlanner.isActive(settings, end, UTC))
        assertNull(BackgroundPollingPlanner.nextTriggerAt(settings, end, false, UTC))
    }

    @Test(expected = IllegalArgumentException::class)
    fun widgetRefreshCannotPublishUnknownAsConfirmed() {
        WidgetRefreshResult.Updated(WidgetShowerState.UNKNOWN)
    }

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        GregorianCalendar(UTC).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis

    private companion object {
        val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    }
}
