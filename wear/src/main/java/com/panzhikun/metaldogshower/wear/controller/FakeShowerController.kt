package com.panzhikun.metaldogshower.wear.controller

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

internal class FakeShowerController(
    boundSlotCount: Int = 2,
    showDemoLabel: Boolean = true,
) : ShowerController {
    private val runMode = if (showDemoLabel) RunMode.FAKE else RunMode.REAL
    private val requestGate = Mutex()
    private val boundSlots = BathroomSlot.entries.take(boundSlotCount.coerceIn(1, 2)).toSet()
    private val mutableBindings = MutableStateFlow(
        BathroomSlot.entries.map { slot ->
            ShowerBinding(
                slot = slot,
                deviceAlias = if (slot in boundSlots) "模拟${slot.displayName}" else null,
                state = if (slot in boundSlots) BindingState.BOUND else BindingState.UNBOUND,
            )
        },
    )
    private val mutableSelectedSlot = MutableStateFlow<BathroomSlot?>(null)
    private val mutableStatus = MutableStateFlow(unselectedStatus())
    private val deviceStates = boundSlots.associateWith {
        FakeDeviceState(remainingSeconds = 20 * 60, isOpen = false)
    }.toMutableMap()
    private val lastControlAtMillis = mutableMapOf<BathroomSlot, Long>()

    override val bindings: StateFlow<List<ShowerBinding>> = mutableBindings.asStateFlow()
    override val selectedSlot: StateFlow<BathroomSlot?> = mutableSelectedSlot.asStateFlow()
    override val status: StateFlow<ShowerStatus> = mutableStatus.asStateFlow()

    override fun selectSlot(slot: BathroomSlot): Boolean {
        if (requestGate.isLocked || slot !in boundSlots) return false
        mutableSelectedSlot.value = slot
        mutableStatus.value = ShowerStatus(
            deviceAlias = aliasFor(slot),
            remainingSeconds = 0,
            isOpen = false,
            mode = runMode,
            revision = mutableStatus.value.revision + 1,
            isStateKnown = false,
        )
        return true
    }

    override fun clearSelection() {
        mutableSelectedSlot.value = null
        mutableStatus.value = unselectedStatus(mutableStatus.value.revision + 1)
    }

    override suspend fun loadLocalBinding(): ControllerResult = ControllerResult.Ready

    override suspend fun refresh(): ControllerResult {
        if (!requestGate.tryLock()) return ControllerResult.Busy
        return try {
            val slot = mutableSelectedSlot.value
                ?: return ControllerResult.Rejected("请先选择浴室1或浴室2")
            val state = deviceStates[slot]
                ?: return ControllerResult.Rejected("${slot.displayName}尚未绑定")
            delay(220)
            val refreshed = ShowerStatus(
                deviceAlias = aliasFor(slot),
                remainingSeconds = state.remainingSeconds,
                isOpen = state.isOpen,
                mode = runMode,
                revision = mutableStatus.value.revision + 1,
                isStateKnown = true,
            )
            mutableStatus.value = refreshed
            ControllerResult.Confirmed(refreshed)
        } finally {
            requestGate.unlock()
        }
    }

    override suspend fun setOpen(open: Boolean): ControllerResult {
        if (!requestGate.tryLock()) return ControllerResult.Busy
        return try {
            val slot = mutableSelectedSlot.value
                ?: return ControllerResult.Rejected("请先选择浴室")
            val state = deviceStates[slot]
                ?: return ControllerResult.Rejected("${slot.displayName}尚未绑定")
            if (!mutableStatus.value.isStateKnown) {
                return ControllerResult.Rejected("请先刷新并确认${slot.displayName}状态")
            }

            val now = SystemClock.elapsedRealtime()
            val lastControl = lastControlAtMillis[slot]
            if (lastControl != null) {
                val elapsed = now - lastControl
                if (elapsed < CONTROL_COOLDOWN_MILLIS) {
                    return ControllerResult.Cooldown(CONTROL_COOLDOWN_MILLIS - elapsed)
                }
            }

            delay(420)
            state.isOpen = open
            lastControlAtMillis[slot] = SystemClock.elapsedRealtime()
            val changed = ShowerStatus(
                deviceAlias = aliasFor(slot),
                remainingSeconds = state.remainingSeconds,
                isOpen = open,
                mode = runMode,
                revision = mutableStatus.value.revision + 1,
                isStateKnown = true,
            )
            mutableStatus.value = changed
            ControllerResult.Confirmed(changed)
        } finally {
            requestGate.unlock()
        }
    }

    private fun aliasFor(slot: BathroomSlot): String =
        mutableBindings.value.first { it.slot == slot }.deviceAlias ?: slot.displayName

    private fun unselectedStatus(revision: Long = 0L) = ShowerStatus(
        deviceAlias = "请选择浴室",
        remainingSeconds = 0,
        isOpen = false,
        mode = runMode,
        revision = revision,
        isStateKnown = false,
    )

    private data class FakeDeviceState(
        val remainingSeconds: Int,
        var isOpen: Boolean,
    )

    private companion object {
        const val CONTROL_COOLDOWN_MILLIS = 5_000L
    }
}
