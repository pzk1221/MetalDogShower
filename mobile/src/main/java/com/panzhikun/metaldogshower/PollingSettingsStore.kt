package com.panzhikun.metaldogshower

import android.content.Context
import androidx.core.content.edit

enum class BackgroundPollingMode {
    DAILY,
    ONCE,
}

data class BackgroundPollingSettings(
    val enabled: Boolean = false,
    val mode: BackgroundPollingMode = BackgroundPollingMode.DAILY,
    val intervalMinutes: Int = 15,
    val dailyStartMinute: Int = 8 * 60,
    val dailyEndMinute: Int = 22 * 60,
    val onceStartAtMillis: Long = 0L,
    val onceEndAtMillis: Long = 0L,
)

/** Non-sensitive polling preferences. Tokens and device routes are never stored here. */
data class PollingSettings(
    val enabled: Boolean = false,
    val intervalSeconds: Int = 300,
    val background: BackgroundPollingSettings = BackgroundPollingSettings(),
)

class PollingSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun read(nowMillis: Long = System.currentTimeMillis()): PollingSettings {
        val defaultWindow = defaultOneTimeWindow(nowMillis)
        return PollingSettings(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            intervalSeconds = normalizeInterval(
                preferences.getInt(KEY_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS),
            ),
            background = normalizeBackground(
                BackgroundPollingSettings(
                    enabled = preferences.getBoolean(KEY_BACKGROUND_ENABLED, false),
                    mode = preferences.getString(KEY_BACKGROUND_MODE, null)
                        ?.let { stored ->
                            runCatching { BackgroundPollingMode.valueOf(stored) }.getOrNull()
                        }
                        ?: BackgroundPollingMode.DAILY,
                    intervalMinutes = preferences.getInt(
                        KEY_BACKGROUND_INTERVAL_MINUTES,
                        DEFAULT_BACKGROUND_INTERVAL_MINUTES,
                    ),
                    dailyStartMinute = preferences.getInt(
                        KEY_BACKGROUND_DAILY_START_MINUTE,
                        DEFAULT_DAILY_START_MINUTE,
                    ),
                    dailyEndMinute = preferences.getInt(
                        KEY_BACKGROUND_DAILY_END_MINUTE,
                        DEFAULT_DAILY_END_MINUTE,
                    ),
                    onceStartAtMillis = preferences.getLong(
                        KEY_BACKGROUND_ONCE_START_AT,
                        defaultWindow.first,
                    ),
                    onceEndAtMillis = preferences.getLong(
                        KEY_BACKGROUND_ONCE_END_AT,
                        defaultWindow.second,
                    ),
                ),
                nowMillis,
            ),
        )
    }

    fun setEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_ENABLED, enabled) }
    }

    fun setIntervalSeconds(intervalSeconds: Int) {
        preferences.edit { putInt(KEY_INTERVAL_SECONDS, normalizeInterval(intervalSeconds)) }
    }

    fun setBackground(settings: BackgroundPollingSettings): BackgroundPollingSettings {
        val normalized = normalizeBackground(settings, System.currentTimeMillis())
        preferences.edit {
            putBoolean(KEY_BACKGROUND_ENABLED, normalized.enabled)
            putString(KEY_BACKGROUND_MODE, normalized.mode.name)
            putInt(KEY_BACKGROUND_INTERVAL_MINUTES, normalized.intervalMinutes)
            putInt(KEY_BACKGROUND_DAILY_START_MINUTE, normalized.dailyStartMinute)
            putInt(KEY_BACKGROUND_DAILY_END_MINUTE, normalized.dailyEndMinute)
            putLong(KEY_BACKGROUND_ONCE_START_AT, normalized.onceStartAtMillis)
            putLong(KEY_BACKGROUND_ONCE_END_AT, normalized.onceEndAtMillis)
        }
        return normalized
    }

    /** Limits automatic retries to once per credential generation every five minutes. */
    fun claimAutoSync(credentialId: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val previousCredential = preferences.getString(KEY_AUTO_SYNC_CREDENTIAL, null)
        val previousAt = preferences.getLong(KEY_AUTO_SYNC_AT, 0L)
        if (previousCredential == credentialId && nowMillis - previousAt < AUTO_SYNC_COOLDOWN_MILLIS) {
            return false
        }
        preferences.edit {
            putString(KEY_AUTO_SYNC_CREDENTIAL, credentialId)
            putLong(KEY_AUTO_SYNC_AT, nowMillis)
        }
        return true
    }

    fun claimWatchSyncRequest(
        credentialId: String,
        nodeId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val key = "$credentialId|$nodeId"
        val previousKey = preferences.getString(KEY_WATCH_REQUEST, null)
        val previousAt = preferences.getLong(KEY_WATCH_REQUEST_AT, 0L)
        if (previousKey == key && nowMillis - previousAt < WATCH_REQUEST_COOLDOWN_MILLIS) return false
        preferences.edit {
            putString(KEY_WATCH_REQUEST, key)
            putLong(KEY_WATCH_REQUEST_AT, nowMillis)
        }
        return true
    }

    private companion object {
        const val PREFERENCES_NAME = "app_display_preferences"
        const val KEY_ENABLED = "foreground_polling_enabled"
        const val KEY_INTERVAL_SECONDS = "foreground_polling_interval_seconds"
        const val KEY_BACKGROUND_ENABLED = "background_polling_enabled"
        const val KEY_BACKGROUND_MODE = "background_polling_mode"
        const val KEY_BACKGROUND_INTERVAL_MINUTES = "background_polling_interval_minutes"
        const val KEY_BACKGROUND_DAILY_START_MINUTE = "background_polling_daily_start_minute"
        const val KEY_BACKGROUND_DAILY_END_MINUTE = "background_polling_daily_end_minute"
        const val KEY_BACKGROUND_ONCE_START_AT = "background_polling_once_start_at"
        const val KEY_BACKGROUND_ONCE_END_AT = "background_polling_once_end_at"
        const val KEY_AUTO_SYNC_CREDENTIAL = "last_auto_sync_credential"
        const val KEY_AUTO_SYNC_AT = "last_auto_sync_at"
        const val KEY_WATCH_REQUEST = "last_watch_sync_request"
        const val KEY_WATCH_REQUEST_AT = "last_watch_sync_request_at"
        const val DEFAULT_INTERVAL_SECONDS = 300
        const val DEFAULT_BACKGROUND_INTERVAL_MINUTES = 15
        const val DEFAULT_DAILY_START_MINUTE = 8 * 60
        const val DEFAULT_DAILY_END_MINUTE = 22 * 60
        const val AUTO_SYNC_COOLDOWN_MILLIS = 5 * 60 * 1_000L
        const val WATCH_REQUEST_COOLDOWN_MILLIS = 60 * 1_000L
        const val MINUTE_MILLIS = 60_000L
        val ALLOWED_INTERVALS = setOf(60, 300, 600, 1800)

        fun normalizeInterval(value: Int): Int =
            if (value in ALLOWED_INTERVALS) value else DEFAULT_INTERVAL_SECONDS

        fun normalizeBackground(
            value: BackgroundPollingSettings,
            nowMillis: Long,
        ): BackgroundPollingSettings {
            val interval = if (value.intervalMinutes in BackgroundPollingIntervalMinutes) {
                value.intervalMinutes
            } else {
                DEFAULT_BACKGROUND_INTERVAL_MINUTES
            }
            val defaultWindow = defaultOneTimeWindow(nowMillis)
            val start = value.onceStartAtMillis.takeIf { it > 0L } ?: defaultWindow.first
            val minimumEnd = start + interval * MINUTE_MILLIS
            val end = value.onceEndAtMillis.takeIf { it >= minimumEnd } ?: maxOf(
                defaultWindow.second,
                minimumEnd,
            )
            return value.copy(
                intervalMinutes = interval,
                dailyStartMinute = value.dailyStartMinute.coerceIn(0, MINUTES_PER_DAY - 1),
                dailyEndMinute = value.dailyEndMinute.coerceIn(0, MINUTES_PER_DAY - 1),
                onceStartAtMillis = start,
                onceEndAtMillis = end,
            )
        }

        fun defaultOneTimeWindow(nowMillis: Long): Pair<Long, Long> {
            val nextQuarter = ((nowMillis + 15 * MINUTE_MILLIS - 1L) / (15 * MINUTE_MILLIS)) *
                (15 * MINUTE_MILLIS)
            return nextQuarter to nextQuarter + 2 * 60 * MINUTE_MILLIS
        }
    }
}

internal const val MINUTES_PER_DAY = 24 * 60

internal val PollingIntervals = listOf(
    60 to "1 分钟",
    300 to "5 分钟",
    600 to "10 分钟",
    1800 to "30 分钟",
)

internal val BackgroundPollingIntervalMinutes = listOf(5, 10, 15, 30, 60)
