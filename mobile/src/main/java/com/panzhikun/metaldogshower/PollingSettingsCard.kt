package com.panzhikun.metaldogshower

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
internal fun PollingSettingsCard(
    state: SetupUiState,
    onEnabledChanged: (Boolean) -> Unit,
    onIntervalChanged: (Int) -> Unit,
    onBackgroundEnabledChanged: (Boolean) -> Unit,
    onBackgroundModeChanged: (BackgroundPollingMode) -> Unit,
    onBackgroundIntervalChanged: (Int) -> Unit,
    onDailyStartChanged: (Int) -> Unit,
    onDailyEndChanged: (Int) -> Unit,
    onOnceStartChanged: (Long) -> Unit,
    onOnceEndChanged: (Long) -> Unit,
    onRefreshNow: () -> Unit,
) {
    val context = LocalContext.current
    val background = state.backgroundPolling
    val now = System.currentTimeMillis()
    val nextTrigger = BackgroundPollingPlanner.nextTriggerAt(
        settings = background,
        nowMillis = now,
        immediateIfActive = false,
    )

    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "前台状态轮询",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "打开应用时定期刷新两间浴室。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.pollingEnabled,
                    onCheckedChange = onEnabledChanged,
                    enabled = !state.isBusy && state.loggedIn,
                )
            }
            Spacer(Modifier.height(12.dp))
            PollingIntervals.chunked(2).forEach { rowIntervals ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowIntervals.forEach { (seconds, label) ->
                        FilterChip(
                            selected = state.pollingIntervalSeconds == seconds,
                            onClick = { onIntervalChanged(seconds) },
                            enabled = !state.isBusy && state.pollingEnabled,
                            label = { Text(label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "后台定时刷新",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "在指定时间段更新状态和小组件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = background.enabled,
                    onCheckedChange = onBackgroundEnabledChanged,
                    enabled = !state.isBusy && state.loggedIn,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = background.mode == BackgroundPollingMode.DAILY,
                    onClick = { onBackgroundModeChanged(BackgroundPollingMode.DAILY) },
                    enabled = background.enabled && !state.isBusy,
                    label = { Text("每天") },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = background.mode == BackgroundPollingMode.ONCE,
                    onClick = { onBackgroundModeChanged(BackgroundPollingMode.ONCE) },
                    enabled = background.enabled && !state.isBusy,
                    label = { Text("仅一次") },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "刷新间隔",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BackgroundPollingIntervalMinutes.chunked(3).forEach { rowIntervals ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowIntervals.forEach { minutes ->
                        FilterChip(
                            selected = background.intervalMinutes == minutes,
                            onClick = { onBackgroundIntervalChanged(minutes) },
                            enabled = background.enabled && !state.isBusy,
                            label = { Text("$minutes 分钟") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - rowIntervals.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            Spacer(Modifier.height(10.dp))
            when (background.mode) {
                BackgroundPollingMode.DAILY -> {
                    Text(
                        "每日时间段",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                showTimePicker(context, background.dailyStartMinute, onDailyStartChanged)
                            },
                            enabled = background.enabled && !state.isBusy,
                            modifier = Modifier.weight(1f),
                        ) { Text("开始 ${formatMinuteOfDay(background.dailyStartMinute)}") }
                        OutlinedButton(
                            onClick = {
                                showTimePicker(context, background.dailyEndMinute, onDailyEndChanged)
                            },
                            enabled = background.enabled && !state.isBusy,
                            modifier = Modifier.weight(1f),
                        ) { Text("结束 ${formatMinuteOfDay(background.dailyEndMinute)}") }
                    }
                    if (background.dailyEndMinute <= background.dailyStartMinute) {
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "结束时间不晚于开始时间时，按跨午夜时间段运行。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                BackgroundPollingMode.ONCE -> {
                    Text(
                        "单次时间段",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            showDateTimePicker(context, background.onceStartAtMillis, onOnceStartChanged)
                        },
                        enabled = background.enabled && !state.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("开始 ${formatDateTime(background.onceStartAtMillis)}") }
                    Spacer(Modifier.height(7.dp))
                    OutlinedButton(
                        onClick = {
                            showDateTimePicker(context, background.onceEndAtMillis, onOnceEndChanged)
                        },
                        enabled = background.enabled && !state.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("结束 ${formatDateTime(background.onceEndAtMillis)}") }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    !background.enabled -> "后台定时器已关闭。"
                    nextTrigger == null && background.mode == BackgroundPollingMode.ONCE ->
                        "本次时间段已经结束，不会继续后台刷新。"
                    nextTrigger == null -> "当前计划没有可执行的时间段。"
                    else -> "预计下次刷新：${formatDateTime(nextTrigger)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                "系统省电或无网络时可能延后。定时刷新不会执行开关。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onRefreshNow,
                enabled = !state.isBusy && state.loggedIn && state.hasInternet,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("立即核对两间浴室状态") }
        }
    }
}

private fun showTimePicker(
    context: Context,
    initialMinuteOfDay: Int,
    onPicked: (Int) -> Unit,
) {
    val normalized = initialMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    TimePickerDialog(
        context,
        { _, hour, minute -> onPicked(hour * 60 + minute) },
        normalized / 60,
        normalized % 60,
        true,
    ).show()
}

private fun showDateTimePicker(
    context: Context,
    initialMillis: Long,
    onPicked: (Long) -> Unit,
) {
    val initial = Calendar.getInstance().apply { timeInMillis = initialMillis }
    DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val selected = Calendar.getInstance().apply {
                        set(year, month, day, hour, minute, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onPicked(selected.timeInMillis)
                },
                initial.get(Calendar.HOUR_OF_DAY),
                initial.get(Calendar.MINUTE),
                true,
            ).show()
        },
        initial.get(Calendar.YEAR),
        initial.get(Calendar.MONTH),
        initial.get(Calendar.DAY_OF_MONTH),
    ).show()
}

private fun formatMinuteOfDay(minuteOfDay: Int): String {
    val normalized = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    return String.format(Locale.CHINA, "%02d:%02d", normalized / 60, normalized % 60)
}

private fun formatDateTime(epochMillis: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(epochMillis))
