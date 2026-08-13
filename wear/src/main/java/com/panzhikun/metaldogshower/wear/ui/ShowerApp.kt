package com.panzhikun.metaldogshower.wear.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Text
import com.panzhikun.metaldogshower.wear.controller.BathroomSlot
import com.panzhikun.metaldogshower.wear.controller.BindingState
import com.panzhikun.metaldogshower.wear.controller.ControllerResult
import com.panzhikun.metaldogshower.wear.controller.RunMode
import com.panzhikun.metaldogshower.wear.controller.ShowerBinding
import com.panzhikun.metaldogshower.wear.controller.ShowerController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil
import kotlin.math.min

private enum class OperationPhase {
    IDLE,
    SENDING,
    VERIFYING,
    COOLDOWN,
    UNCERTAIN,
}

private data class PendingControl(
    val targetOpen: Boolean,
    val slot: BathroomSlot,
    val deviceAlias: String,
)

private val BrandNavy = Color(0xFF111638)
private val BrandSurface = Color(0xFF20264A)
private val BrandOrange = Color(0xFFE96B3A)
private val BrandIvory = Color(0xFFF7F1E3)
private val SecondaryText = Color(0xFFC9C3B8)
private val AmoledBlack = Color.Black

@Composable
internal fun ShowerApp(controller: ShowerController) {
    val status by controller.status.collectAsStateWithLifecycle()
    val bindings by controller.bindings.collectAsStateWithLifecycle()
    val selectedSlot by controller.selectedSlot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var pendingControl by remember { mutableStateOf<PendingControl?>(null) }
    var phase by remember { mutableStateOf(OperationPhase.IDLE) }
    var cooldownSeconds by remember { mutableIntStateOf(0) }
    var transientMessage by remember { mutableStateOf<String?>(null) }
    var forceAuthMessage by remember { mutableStateOf(status.authenticationRequired) }
    var authenticationFailureMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(status.revision) {
        forceAuthMessage = status.authenticationRequired
        if (!status.authenticationRequired) authenticationFailureMessage = null
    }

    suspend fun finishWithCooldown(seconds: Int = 5) {
        val safeSeconds = seconds.coerceAtLeast(1)
        cooldownSeconds = safeSeconds
        phase = OperationPhase.COOLDOWN
        // The visible countdown owns its per-second state. Keeping this parent
        // coroutine asleep avoids recomposing the whole scrollable screen.
        delay(safeSeconds * 1_000L)
        cooldownSeconds = 0
        phase = OperationPhase.IDLE
    }

    fun handleFinalResult(result: ControllerResult) {
        when (result) {
            ControllerResult.Ready -> Unit
            is ControllerResult.Confirmed -> {
                transientMessage = if (result.status.isOpen) "已开启" else "已关闭"
            }

            ControllerResult.Unauthorized -> {
                forceAuthMessage = true
                authenticationFailureMessage = "登录已失效，请在手机重新登录"
                transientMessage = authenticationFailureMessage
            }

            ControllerResult.Busy -> transientMessage = "操作进行中，请稍候"
            is ControllerResult.Cooldown -> {
                transientMessage = "冷却中，请稍候"
                cooldownSeconds = ceil(result.remainingMillis / 1_000.0).toInt().coerceAtLeast(1)
            }

            is ControllerResult.Rejected -> transientMessage = result.userMessage
            is ControllerResult.Ambiguous -> {
                if (result.authenticationRequired) {
                    forceAuthMessage = true
                    authenticationFailureMessage = result.userMessage
                }
                transientMessage = if (result.verificationAttempted) {
                    result.userMessage
                } else {
                    "结果不明确，请稍后手动刷新"
                }
            }
        }
    }

    suspend fun refreshWithUiTimeout(): ControllerResult = withTimeoutOrNull(20_000L) {
        controller.refresh()
    } ?: ControllerResult.Rejected("状态核对超时，请检查手表网络后重试")

    fun selectAndRefresh(binding: ShowerBinding) {
        val recoveringFromReplacedBinding =
            phase == OperationPhase.UNCERTAIN && selectedSlot == null
        if (phase != OperationPhase.IDLE && !recoveringFromReplacedBinding) return
        if (binding.state != BindingState.BOUND || !controller.selectSlot(binding.slot)) {
            transientMessage = when (binding.state) {
                BindingState.LOADING -> "正在读取${binding.slot.displayName}绑定"
                BindingState.UNBOUND -> "${binding.slot.displayName}未绑定"
                BindingState.BOUND -> "暂时无法选择${binding.slot.displayName}"
            }
            return
        }

        phase = OperationPhase.VERIFYING
        transientMessage = "正在刷新${binding.slot.displayName}状态"
        scope.launch {
            try {
                handleFinalResult(refreshWithUiTimeout())
            } finally {
                phase = OperationPhase.IDLE
            }
        }
    }

    // This screen only has a handful of elements. A regular lazy list avoids
    // the continuous scale/alpha measurement cost of ScalingLazyColumn on
    // older Watch4 hardware while keeping every action reachable by scrolling.
    val listState = rememberLazyListState()
    val titleScrollFraction by remember(listState) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                min(1f, listState.firstVisibleItemScrollOffset / 72f)
            }
        }
    }
    val countdownBaselineElapsedRealtime = remember(
        selectedSlot,
        status.revision,
        status.remainingSeconds,
        status.isOpen,
    ) {
        SystemClock.elapsedRealtime()
    }
    val cooldownDeadlineElapsedRealtime = remember(phase, cooldownSeconds) {
        if (phase == OperationPhase.COOLDOWN) {
            SystemClock.elapsedRealtime() + cooldownSeconds.coerceAtLeast(1) * 1_000L
        } else {
            0L
        }
    }
    val selectedTitleBase = selectedSlot?.let { slot ->
        status.deviceAlias.trim().let { alias ->
            if (alias.isBlank() || alias == slot.displayName) {
                slot.displayName
            } else {
                "${slot.displayName} · $alias"
            }
        }
    } ?: "金属狗淋浴"
    // Production stays visually clean; a test build must remain unmistakable.
    val selectedTitle = if (status.mode == RunMode.FAKE) {
        "$selectedTitleBase · 演示"
    } else {
        selectedTitleBase
    }

    MaterialTheme(
        colors = MaterialTheme.colors.copy(
            primary = BrandOrange,
            primaryVariant = BrandOrange,
            secondary = BrandIvory,
            background = AmoledBlack,
            surface = BrandSurface,
            onPrimary = BrandNavy,
            onSecondary = BrandNavy,
            onBackground = BrandIvory,
            onSurface = BrandIvory,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(top = 28.dp, bottom = 44.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                item(key = "title") {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = (36f + 16f * titleScrollFraction).dp)
                            .graphicsLayer {
                                val scale = 1f - 0.22f * titleScrollFraction
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0.5f, 0f)
                            },
                        text = selectedTitle,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                item(key = "bathroom_selector") {
                    BathroomSelector(
                        bindings = bindings,
                        selectedSlot = selectedSlot,
                        enabled = (
                            phase == OperationPhase.IDLE ||
                                (phase == OperationPhase.UNCERTAIN && selectedSlot == null)
                            ) && !forceAuthMessage,
                        onSelect = ::selectAndRefresh,
                    )
                }

                item(key = "countdown") {
                    DisplayCountdown(
                        hasSelection = selectedSlot != null,
                        isStateKnown = status.isStateKnown,
                        isOpen = status.isOpen,
                        remainingSeconds = status.remainingSeconds,
                        statusRevision = status.revision,
                        baselineElapsedRealtime = countdownBaselineElapsedRealtime,
                    )
                }
                item(key = "status_label") {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 34.dp),
                        text = when {
                            selectedSlot == null -> "请先选择浴室"
                            !status.hasInternetCapability -> "手表未连接可用网络"
                            !status.isStateKnown -> "状态尚未核对"
                            status.isOpen -> "淋浴已开启"
                            else -> "淋浴已关闭"
                        },
                        textAlign = TextAlign.Center,
                        color = if (status.isOpen && status.isStateKnown) {
                            Color(0xFF67D391)
                        } else {
                            SecondaryText
                        },
                        fontSize = 13.sp,
                    )
                }

                if (
                    selectedSlot != null &&
                    !forceAuthMessage &&
                    status.isStateKnown &&
                    status.hasInternetCapability
                ) {
                    item(key = "control_action") {
                        Chip(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 28.dp)
                                .height(48.dp),
                            enabled = phase == OperationPhase.IDLE,
                            colors = ChipDefaults.primaryChipColors(
                                backgroundColor = BrandOrange,
                                contentColor = BrandNavy,
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            onClick = {
                                selectedSlot?.let { slot ->
                                    transientMessage = null
                                    pendingControl = PendingControl(
                                        targetOpen = !status.isOpen,
                                        slot = slot,
                                        deviceAlias = status.deviceAlias,
                                    )
                                }
                            },
                            label = {
                                Text(
                                    modifier = Modifier.fillMaxWidth(),
                                    text = if (status.isOpen) {
                                        "关闭${selectedSlot?.displayName}"
                                    } else {
                                        "开启${selectedSlot?.displayName}"
                                    },
                                    textAlign = TextAlign.Center,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            },
                        )
                    }
                } else if (
                    selectedSlot != null &&
                    phase == OperationPhase.IDLE &&
                    !forceAuthMessage
                ) {
                    item(key = "refresh_action") {
                        Chip(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 28.dp)
                                .height(48.dp),
                            colors = ChipDefaults.primaryChipColors(
                                backgroundColor = BrandSurface,
                                contentColor = BrandIvory,
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            onClick = {
                                phase = OperationPhase.VERIFYING
                                transientMessage = "正在刷新${selectedSlot?.displayName}状态"
                                scope.launch {
                                    try {
                                        handleFinalResult(refreshWithUiTimeout())
                                    } finally {
                                        phase = OperationPhase.IDLE
                                    }
                                }
                            },
                            label = {
                                Text(
                                    modifier = Modifier.fillMaxWidth(),
                                    text = "刷新${selectedSlot?.displayName}状态",
                                    textAlign = TextAlign.Center,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            },
                        )
                    }
                }

                if (phase != OperationPhase.IDLE) {
                    item(key = "operation_phase") {
                        OperationLabel(
                            phase = phase,
                            cooldownDeadlineElapsedRealtime = cooldownDeadlineElapsedRealtime,
                        )
                    }
                }
                if (
                    phase == OperationPhase.UNCERTAIN &&
                    selectedSlot != null &&
                    !forceAuthMessage
                ) {
                    item(key = "uncertain_refresh") {
                        Chip(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 28.dp)
                                .height(48.dp),
                            colors = ChipDefaults.primaryChipColors(
                                backgroundColor = BrandSurface,
                                contentColor = BrandIvory,
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            onClick = {
                                phase = OperationPhase.VERIFYING
                                transientMessage = "正在核对${selectedSlot?.displayName}状态"
                                scope.launch {
                                    try {
                                        when (val refreshResult = refreshWithUiTimeout()) {
                                            is ControllerResult.Confirmed,
                                            ControllerResult.Unauthorized,
                                            ControllerResult.Ready,
                                            -> {
                                                handleFinalResult(refreshResult)
                                                phase = OperationPhase.IDLE
                                            }

                                            else -> {
                                                handleFinalResult(refreshResult)
                                                phase = OperationPhase.UNCERTAIN
                                            }
                                        }
                                    } finally {
                                        if (phase == OperationPhase.VERIFYING) {
                                            phase = OperationPhase.UNCERTAIN
                                        }
                                    }
                                }
                            },
                            label = {
                                Text(
                                    modifier = Modifier.fillMaxWidth(),
                                    text = "仅刷新所选浴室状态",
                                    textAlign = TextAlign.Center,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            },
                        )
                    }
                }

                if (forceAuthMessage) {
                    item(key = "authentication_message") {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 34.dp),
                            text = authenticationFailureMessage ?: if (
                                bindings.none { it.state == BindingState.BOUND }
                            ) {
                                "请在手机绑定至少一个浴室"
                            } else {
                                "请在手机重新登录"
                            },
                            textAlign = TextAlign.Center,
                            color = Color(0xFFFFB4AB),
                            fontSize = 11.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else if (transientMessage != null) {
                    item(key = "transient_message") {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 34.dp),
                            text = transientMessage.orEmpty(),
                            textAlign = TextAlign.Center,
                            color = SecondaryText,
                            fontSize = 11.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                item(key = "footer_spacer") { Spacer(modifier = Modifier.height(8.dp)) }
            }

            PositionIndicator(
                lazyListState = listState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 3.dp),
            )
        }

        pendingControl?.let { pending ->
            ConfirmationDialog(
                pending = pending,
                onCancel = { pendingControl = null },
                onConfirm = {
                    pendingControl = null
                    if (controller.selectedSlot.value != pending.slot) {
                        transientMessage = "浴室选择已变化，请重新选择"
                    } else {
                        scope.launch {
                            phase = OperationPhase.VERIFYING
                            transientMessage = "发送前正在核对${pending.slot.displayName}状态"
                            try {
                                when (val switchResult = controller.setOpen(pending.targetOpen)) {
                                    is ControllerResult.Ambiguous -> {
                                        // The controller already owns the only verification GET.
                                        handleFinalResult(switchResult)
                                        if (switchResult.observedStatus != null) {
                                            finishWithCooldown()
                                        } else {
                                            phase = OperationPhase.UNCERTAIN
                                        }
                                    }

                                    ControllerResult.Unauthorized -> {
                                        handleFinalResult(switchResult)
                                        phase = OperationPhase.IDLE
                                    }

                                    ControllerResult.Busy -> {
                                        handleFinalResult(switchResult)
                                        phase = OperationPhase.IDLE
                                    }

                                    is ControllerResult.Cooldown -> {
                                        handleFinalResult(switchResult)
                                        finishWithCooldown(cooldownSeconds)
                                    }

                                    else -> {
                                        handleFinalResult(switchResult)
                                        if (switchResult is ControllerResult.Confirmed) {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                        finishWithCooldown()
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                transientMessage = "结果无法确认，请现场确认后再操作"
                                phase = OperationPhase.UNCERTAIN
                            } finally {
                                if (phase == OperationPhase.VERIFYING) {
                                    phase = OperationPhase.UNCERTAIN
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun DisplayCountdown(
    hasSelection: Boolean,
    isStateKnown: Boolean,
    isOpen: Boolean,
    remainingSeconds: Int,
    statusRevision: Long,
    baselineElapsedRealtime: Long,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var displayedSeconds by remember(
        hasSelection,
        isStateKnown,
        isOpen,
        remainingSeconds,
        statusRevision,
        baselineElapsedRealtime,
    ) {
        mutableIntStateOf(
            remainingAt(
                baselineSeconds = remainingSeconds,
                baselineElapsedRealtime = baselineElapsedRealtime,
                countsDown = hasSelection && isStateKnown && isOpen,
            ),
        )
    }

    // The per-second state is deliberately scoped to this small composable.
    // It therefore redraws only the digits, not the list or its controls.
    LaunchedEffect(
        hasSelection,
        isStateKnown,
        isOpen,
        remainingSeconds,
        statusRevision,
        baselineElapsedRealtime,
        lifecycleOwner,
    ) {
        if (!hasSelection || !isStateKnown || !isOpen || remainingSeconds <= 0) {
            return@LaunchedEffect
        }
        val baselineSeconds = remainingSeconds.coerceAtLeast(0)
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val elapsedMillis = elapsedSince(baselineElapsedRealtime)
                val nextSeconds = remainingAt(
                    baselineSeconds = baselineSeconds,
                    baselineElapsedRealtime = baselineElapsedRealtime,
                    countsDown = true,
                )
                displayedSeconds = nextSeconds
                if (nextSeconds == 0) break
                delay((1_000L - elapsedMillis % 1_000L).coerceAtLeast(50L))
            }
        }
    }

    Text(
        text = if (hasSelection && isStateKnown) {
            formatDuration(displayedSeconds)
        } else {
            "--:--"
        },
        fontSize = 38.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun BathroomSelector(
    bindings: List<ShowerBinding>,
    selectedSlot: BathroomSlot?,
    enabled: Boolean,
    onSelect: (ShowerBinding) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BathroomSlot.entries.forEach { slot ->
            val binding = bindings.firstOrNull { it.slot == slot }
                ?: ShowerBinding(slot, null, BindingState.UNBOUND)
            val isSelected = selectedSlot == slot
            Chip(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp),
                // Keep loading/unbound labels readable on AMOLED black. The click handler
                // reports their state locally and never starts a network request.
                enabled = enabled,
                colors = ChipDefaults.primaryChipColors(
                    backgroundColor = if (isSelected) BrandOrange else BrandSurface,
                    contentColor = if (isSelected) BrandNavy else BrandIvory,
                ),
                contentPadding = PaddingValues(horizontal = 6.dp),
                onClick = { onSelect(binding) },
                label = {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = when (binding.state) {
                            BindingState.LOADING -> "${slot.displayName} · 读取中"
                            BindingState.BOUND -> if (isSelected) {
                                "● ${slot.displayName}"
                            } else {
                                slot.displayName
                            }
                            BindingState.UNBOUND -> "${slot.displayName} · 未绑定"
                        },
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun OperationLabel(
    phase: OperationPhase,
    cooldownDeadlineElapsedRealtime: Long,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (phase == OperationPhase.COOLDOWN) {
            CooldownLabel(deadlineElapsedRealtime = cooldownDeadlineElapsedRealtime)
        } else {
            Text(
                text = when (phase) {
                    OperationPhase.IDLE -> ""
                    OperationPhase.SENDING -> "正在发送"
                    OperationPhase.VERIFYING -> "正在核对所选浴室"
                    OperationPhase.COOLDOWN -> ""
                    OperationPhase.UNCERTAIN -> "结果无法确认，请勿重复开关"
                },
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun CooldownLabel(deadlineElapsedRealtime: Long) {
    var displayedSeconds by remember(deadlineElapsedRealtime) {
        mutableIntStateOf(secondsUntil(deadlineElapsedRealtime))
    }
    LaunchedEffect(deadlineElapsedRealtime) {
        while (displayedSeconds > 1) {
            delay(1_000L)
            displayedSeconds = secondsUntil(deadlineElapsedRealtime)
        }
    }
    Text(
        text = "防误触冷却 ${displayedSeconds}s",
        fontSize = 11.sp,
    )
}

@Composable
private fun ConfirmationDialog(
    pending: PendingControl,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onCancel) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AmoledBlack, CircleShape)
                .padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (pending.targetOpen) {
                        "确认开启${pending.slot.displayName}？"
                    } else {
                        "确认关闭${pending.slot.displayName}？"
                    },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${pending.deviceAlias} · 先核对状态，控制请求最多一次",
                    color = Color(0xFFBDBDBD),
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.Center) {
                    Button(onClick = onCancel, modifier = Modifier.size(54.dp)) {
                        Text("取消", fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(onClick = onConfirm, modifier = Modifier.size(54.dp)) {
                        Text("确认", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

private fun formatDuration(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val minutes = safe / 60
    val seconds = safe % 60
    return buildString(capacity = 5) {
        if (minutes < 10) append('0')
        append(minutes)
        append(':')
        if (seconds < 10) append('0')
        append(seconds)
    }
}

private fun elapsedSince(baselineElapsedRealtime: Long): Long =
    (SystemClock.elapsedRealtime() - baselineElapsedRealtime).coerceAtLeast(0L)

private fun remainingAt(
    baselineSeconds: Int,
    baselineElapsedRealtime: Long,
    countsDown: Boolean,
): Int {
    val safeSeconds = baselineSeconds.coerceAtLeast(0)
    if (!countsDown) return safeSeconds
    return (safeSeconds - (elapsedSince(baselineElapsedRealtime) / 1_000L).toInt())
        .coerceAtLeast(0)
}

private fun secondsUntil(deadlineElapsedRealtime: Long): Int =
    ceil(
        (deadlineElapsedRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L) / 1_000.0,
    ).toInt().coerceAtLeast(1)
