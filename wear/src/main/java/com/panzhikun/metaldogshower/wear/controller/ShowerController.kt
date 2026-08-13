package com.panzhikun.metaldogshower.wear.controller

import kotlinx.coroutines.flow.StateFlow

internal enum class RunMode {
    FAKE,
    REAL,
}

internal enum class BathroomSlot(
    val number: Int,
    val displayName: String,
) {
    BATHROOM_1(number = 1, displayName = "浴室1"),
    BATHROOM_2(number = 2, displayName = "浴室2"),
    ;

    companion object {
        fun fromNumber(number: Int): BathroomSlot? = entries.firstOrNull { it.number == number }
    }
}

internal enum class BindingState {
    LOADING,
    BOUND,
    UNBOUND,
}

internal data class ShowerBinding(
    val slot: BathroomSlot,
    val deviceAlias: String?,
    val state: BindingState,
)

internal data class ShowerStatus(
    val deviceAlias: String,
    val remainingSeconds: Int,
    val isOpen: Boolean,
    val mode: RunMode,
    val revision: Long = 0L,
    val authenticationRequired: Boolean = false,
    val isStateKnown: Boolean = true,
    val hasInternetCapability: Boolean = true,
)

internal sealed interface ControllerResult {
    data object Ready : ControllerResult
    data class Confirmed(val status: ShowerStatus) : ControllerResult
    data class Ambiguous(
        val observedStatus: ShowerStatus? = null,
        val verificationAttempted: Boolean = true,
        val authenticationRequired: Boolean = false,
        val userMessage: String = "结果无法确认，请勿重复开关；仅可手动刷新状态",
    ) : ControllerResult
    data object Unauthorized : ControllerResult
    data object Busy : ControllerResult
    data class Cooldown(val remainingMillis: Long) : ControllerResult
    data class Rejected(val userMessage: String) : ControllerResult
}

/**
 * The UI depends only on this boundary. A real implementation can replace the
 * fake without giving Composables access to tokens, device IDs, or HTTP types.
 */
internal interface ShowerController {
    val status: StateFlow<ShowerStatus>
    val bindings: StateFlow<List<ShowerBinding>>
    val selectedSlot: StateFlow<BathroomSlot?>

    /** Selects a bound route without network I/O and invalidates old status. */
    fun selectSlot(slot: BathroomSlot): Boolean

    /** Removes any implicit route choice, including on a fresh Tile launch. */
    fun clearSelection()

    /** Loads and validates the locally encrypted binding without performing network I/O. */
    suspend fun loadLocalBinding(): ControllerResult

    /** One explicit status read. Implementations must not start polling. */
    suspend fun refresh(): ControllerResult

    /**
     * One switch request. On ambiguous I/O, the implementation owns the single
     * allowed verification read before returning [ControllerResult.Ambiguous].
     * The UI must never repeat either the switch or that verification read.
     */
    suspend fun setOpen(open: Boolean): ControllerResult
}
