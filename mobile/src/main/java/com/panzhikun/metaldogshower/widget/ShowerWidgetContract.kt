package com.panzhikun.metaldogshower.widget

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

internal object WidgetActionIntents {
    const val ACTION_SELECT_ROOM =
        "com.panzhikun.metaldogshower.widget.action.SELECT_ROOM"
    const val ACTION_EXECUTE =
        "com.panzhikun.metaldogshower.widget.action.EXECUTE"
    const val EXTRA_ROOM = "widget_room"
    const val EXTRA_KIND = "widget_action_kind"
}

/** The two physical rooms exposed by the home-screen widget. */
enum class WidgetRoom(val storageId: Int) {
    ROOM_ONE(1),
    ROOM_TWO(2),
    ;

    companion object {
        fun fromStorageId(value: Int): WidgetRoom? = entries.firstOrNull { it.storageId == value }
    }
}

/** A status that was explicitly confirmed by a status request or a completed command. */
enum class WidgetShowerState {
    OPEN,
    CLOSED,
    UNKNOWN,
}

/** A direct action initiated from a widget. It is executed once in WidgetActionJobService. */
enum class WidgetActionKind {
    OPEN,
    CLOSE,
    REFRESH,
}

data class WidgetPendingAction(
    val room: WidgetRoom,
    val kind: WidgetActionKind,
    val startedAtEpochMillis: Long,
)

data class WidgetRoomSnapshot(
    val isConfigured: Boolean = false,
    val state: WidgetShowerState = WidgetShowerState.UNKNOWN,
    val confirmedAtEpochMillis: Long = 0L,
) {
    /** A cached value remains a display hint only briefly; control eligibility never uses it. */
    fun freshDisplayState(nowEpochMillis: Long): WidgetShowerState {
        if (state == WidgetShowerState.UNKNOWN || confirmedAtEpochMillis <= 0L) {
            return WidgetShowerState.UNKNOWN
        }
        val ageMillis = nowEpochMillis - confirmedAtEpochMillis
        return if (ageMillis in 0L..WIDGET_STATUS_FRESHNESS_MILLIS) {
            state
        } else {
            WidgetShowerState.UNKNOWN
        }
    }
}

internal const val WIDGET_STATUS_FRESHNESS_MILLIS = 2L * 60L * 1_000L

/**
 * Non-sensitive display state mirrored into the widget. Tokens and device credentials must never
 * be added to this model or written to the widget preferences.
 */
data class ShowerWidgetSnapshot(
    val isLoggedIn: Boolean = false,
    val isWatchBound: Boolean = false,
    val roomOne: WidgetRoomSnapshot = WidgetRoomSnapshot(),
    val roomTwo: WidgetRoomSnapshot = WidgetRoomSnapshot(),
    val pendingAction: WidgetPendingAction? = null,
) {
    fun room(room: WidgetRoom): WidgetRoomSnapshot = when (room) {
        WidgetRoom.ROOM_ONE -> roomOne
        WidgetRoom.ROOM_TWO -> roomTwo
    }

    /**
     * Local-only eligibility for opening the confirmation screen. A cached state is deliberately
     * not required: it may be unknown or stale, because the controller always performs a fresh,
     * route-bound status request after confirmation and before any control POST.
     */
    fun canRequestControl(room: WidgetRoom): Boolean {
        val selected = room(room)
        return isLoggedIn && selected.isConfigured
    }
}

data class WidgetCommand(
    val room: WidgetRoom,
    val desiredState: WidgetShowerState,
) {
    init {
        require(desiredState != WidgetShowerState.UNKNOWN)
    }
}

/**
 * Narrow integration point for the shared phone/watch controller.
 *
 * Install an implementation once from Application.onCreate. The confirmation activity calls
 * [execute] exactly once after a direct widget action; the widget provider itself never performs
 * network or control work.
 */
interface ShowerWidgetController {
    /** Local-only session/route check. Implementations must not perform network work here. */
    fun canControl(context: Context, room: WidgetRoom): Boolean

    /**
     * Performs one user-confirmed attempt. Implementations must capture the credential and route
     * identity before suspension, discard stale responses, and never retry a control POST.
     */
    suspend fun execute(context: Context, command: WidgetCommand): WidgetCommandResult

    /** Performs exactly one status GET for the selected room and never sends a control POST. */
    suspend fun refresh(context: Context, room: WidgetRoom): WidgetRefreshResult
}

sealed interface WidgetCommandResult {
    data class Applied(
        val confirmedState: WidgetShowerState,
        val confirmedAtEpochMillis: Long = System.currentTimeMillis(),
    ) : WidgetCommandResult {
        init {
            require(confirmedState != WidgetShowerState.UNKNOWN)
            require(confirmedAtEpochMillis > 0L)
        }
    }

    /** The command outcome cannot be proven; callers must not retry it automatically. */
    data class Ambiguous(val authenticationRequired: Boolean = false) : WidgetCommandResult

    data object SessionUnavailable : WidgetCommandResult

    /** Use only when the controller can prove that no control POST has an unknown outcome. */
    data class Failed(val reason: FailureReason = FailureReason.UNKNOWN) : WidgetCommandResult
}

sealed interface WidgetRefreshResult {
    data class Updated(
        val state: WidgetShowerState,
        val confirmedAtEpochMillis: Long = System.currentTimeMillis(),
    ) : WidgetRefreshResult {
        init {
            require(state != WidgetShowerState.UNKNOWN)
            require(confirmedAtEpochMillis > 0L)
        }
    }

    data object SessionUnavailable : WidgetRefreshResult
    data class Failed(val reason: FailureReason = FailureReason.UNKNOWN) : WidgetRefreshResult
}

enum class FailureReason {
    NO_INTERNET,
    BUSY,
    REJECTED,
    UNKNOWN,
}

object ShowerWidgetBridge {
    @Volatile
    private var controller: ShowerWidgetController? = null
    private val commandInFlight = AtomicBoolean(false)

    fun install(controller: ShowerWidgetController) {
        this.controller = controller
    }

    fun clear() {
        controller = null
    }

    internal fun canControl(context: Context, room: WidgetRoom): Boolean {
        val activeController = controller ?: return false
        return runCatching { activeController.canControl(context, room) }.getOrDefault(false)
    }

    internal suspend fun executeOnce(
        context: Context,
        command: WidgetCommand,
    ): WidgetCommandResult {
        val activeController = controller ?: return WidgetCommandResult.SessionUnavailable
        if (!commandInFlight.compareAndSet(false, true)) {
            return WidgetCommandResult.Failed(FailureReason.BUSY)
        }
        return try {
            activeController.execute(context, command)
        } finally {
            commandInFlight.set(false)
        }
    }

    internal suspend fun refreshOnce(context: Context, room: WidgetRoom): WidgetRefreshResult {
        val activeController = controller ?: return WidgetRefreshResult.SessionUnavailable
        if (!commandInFlight.compareAndSet(false, true)) {
            return WidgetRefreshResult.Failed(FailureReason.BUSY)
        }
        return try {
            activeController.refresh(context, room)
        } finally {
            commandInFlight.set(false)
        }
    }
}
