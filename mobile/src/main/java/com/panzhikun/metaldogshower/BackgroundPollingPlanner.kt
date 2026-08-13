package com.panzhikun.metaldogshower

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.max

/** Pure schedule calculation kept separate from Android background execution for unit testing. */
internal object BackgroundPollingPlanner {
    private const val MINUTE_MILLIS = 60_000L
    private const val IMMEDIATE_DELAY_MILLIS = 1_000L

    fun isActive(
        settings: BackgroundPollingSettings,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Boolean = activeWindow(settings, nowMillis, timeZone) != null

    fun nextTriggerAt(
        settings: BackgroundPollingSettings,
        nowMillis: Long,
        immediateIfActive: Boolean,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Long? {
        if (!settings.enabled) return null
        val intervalMillis = settings.intervalMinutes.coerceAtLeast(1) * MINUTE_MILLIS
        val active = activeWindow(settings, nowMillis, timeZone)
        if (active != null) {
            if (immediateIfActive) {
                return (nowMillis + IMMEDIATE_DELAY_MILLIS).takeIf { it < active.endMillis }
                    ?: nextWindowStart(settings, nowMillis, timeZone)
            }
            val elapsed = (nowMillis - active.startMillis).coerceAtLeast(0L)
            val next = active.startMillis + ((elapsed / intervalMillis) + 1L) * intervalMillis
            if (next < active.endMillis) return next
        }
        return nextWindowStart(settings, nowMillis, timeZone)
    }

    private fun activeWindow(
        settings: BackgroundPollingSettings,
        nowMillis: Long,
        timeZone: TimeZone,
    ): PollingWindow? = when (settings.mode) {
        BackgroundPollingMode.ONCE -> PollingWindow(
            settings.onceStartAtMillis,
            settings.onceEndAtMillis,
        ).takeIf { nowMillis >= it.startMillis && nowMillis < it.endMillis }

        BackgroundPollingMode.DAILY -> dailyWindows(settings, nowMillis, timeZone)
            .firstOrNull { nowMillis >= it.startMillis && nowMillis < it.endMillis }
    }

    private fun nextWindowStart(
        settings: BackgroundPollingSettings,
        nowMillis: Long,
        timeZone: TimeZone,
    ): Long? = when (settings.mode) {
        BackgroundPollingMode.ONCE -> settings.onceStartAtMillis.takeIf {
            it >= nowMillis && settings.onceEndAtMillis > it
        }

        BackgroundPollingMode.DAILY -> dailyWindows(settings, nowMillis, timeZone)
            .asSequence()
            .map(PollingWindow::startMillis)
            .filter { it > nowMillis }
            .minOrNull()
    }

    private fun dailyWindows(
        settings: BackgroundPollingSettings,
        nowMillis: Long,
        timeZone: TimeZone,
    ): List<PollingWindow> = (-1..2).map { dayOffset ->
        val start = localMinuteAtDayOffset(
            nowMillis,
            dayOffset,
            settings.dailyStartMinute,
            timeZone,
        )
        val endDayOffset = if (settings.dailyEndMinute <= settings.dailyStartMinute) {
            dayOffset + 1
        } else {
            dayOffset
        }
        val end = localMinuteAtDayOffset(
            nowMillis,
            endDayOffset,
            settings.dailyEndMinute,
            timeZone,
        )
        PollingWindow(start, max(end, start + MINUTE_MILLIS))
    }.sortedBy(PollingWindow::startMillis)

    private fun localMinuteAtDayOffset(
        nowMillis: Long,
        dayOffset: Int,
        minuteOfDay: Int,
        timeZone: TimeZone,
    ): Long = Calendar.getInstance(timeZone).run {
        timeInMillis = nowMillis
        add(Calendar.DAY_OF_YEAR, dayOffset)
        set(Calendar.HOUR_OF_DAY, minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1) / 60)
        set(Calendar.MINUTE, minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1) % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    private data class PollingWindow(
        val startMillis: Long,
        val endMillis: Long,
    )
}
