package com.panzhikun.metaldogshower.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.core.content.edit

/**
 * Stores only non-sensitive widget presentation state. The shared controller should publish after
 * login/binding changes and after every explicitly verified room status.
 */
object ShowerWidgetStateStore {
    private const val PREFERENCES_NAME = "shower_widget_display_state"
    private const val KEY_LOGGED_IN = "logged_in"
    private const val KEY_WATCH_BOUND = "watch_bound"
    private const val KEY_ROOM_ONE_CONFIGURED = "room_one_configured"
    private const val KEY_ROOM_ONE_STATE = "room_one_state"
    private const val KEY_ROOM_ONE_TIME = "room_one_time"
    private const val KEY_ROOM_TWO_CONFIGURED = "room_two_configured"
    private const val KEY_ROOM_TWO_STATE = "room_two_state"
    private const val KEY_ROOM_TWO_TIME = "room_two_time"
    private const val KEY_PENDING_ROOM = "pending_action_room"
    private const val KEY_PENDING_KIND = "pending_action_kind"
    private const val KEY_PENDING_AT = "pending_action_at"
    private const val KEY_SELECTED_ROOM_PREFIX = "selected_room_"
    private const val STALE_PENDING_ACTION_MILLIS = 5L * 60L * 1_000L

    private val pendingLock = Any()

    fun publish(context: Context, snapshot: ShowerWidgetSnapshot) {
        preferences(context).edit {
            putBoolean(KEY_LOGGED_IN, snapshot.isLoggedIn)
            putBoolean(KEY_WATCH_BOUND, snapshot.isWatchBound)
            putBoolean(KEY_ROOM_ONE_CONFIGURED, snapshot.roomOne.isConfigured)
            putString(KEY_ROOM_ONE_STATE, snapshot.roomOne.state.name)
            putLong(KEY_ROOM_ONE_TIME, snapshot.roomOne.confirmedAtEpochMillis.coerceAtLeast(0L))
            putBoolean(KEY_ROOM_TWO_CONFIGURED, snapshot.roomTwo.isConfigured)
            putString(KEY_ROOM_TWO_STATE, snapshot.roomTwo.state.name)
            putLong(KEY_ROOM_TWO_TIME, snapshot.roomTwo.confirmedAtEpochMillis.coerceAtLeast(0L))
            if (snapshot.pendingAction == null) {
                remove(KEY_PENDING_ROOM)
                remove(KEY_PENDING_KIND)
                remove(KEY_PENDING_AT)
            } else {
                putInt(KEY_PENDING_ROOM, snapshot.pendingAction.room.storageId)
                putString(KEY_PENDING_KIND, snapshot.pendingAction.kind.name)
                putLong(KEY_PENDING_AT, snapshot.pendingAction.startedAtEpochMillis)
            }
        }
        ShowerWidgetProvider.updateAll(context)
    }

    fun publishAvailability(context: Context, isLoggedIn: Boolean, isWatchBound: Boolean) {
        preferences(context).edit {
            putBoolean(KEY_LOGGED_IN, isLoggedIn)
            putBoolean(KEY_WATCH_BOUND, isWatchBound)
        }
        ShowerWidgetProvider.updateAll(context)
    }

    /** Claims the single process-wide widget action slot. Repeated taps are ignored safely. */
    internal fun markPending(
        context: Context,
        room: WidgetRoom,
        kind: WidgetActionKind,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        var staleAction: WidgetPendingAction? = null
        val claimed = synchronized(pendingLock) {
            val existing = readPending(context)
            if (existing != null) {
                if (nowEpochMillis - existing.startedAtEpochMillis <= STALE_PENDING_ACTION_MILLIS) {
                    return@synchronized false
                }
                // A stale marker can only be left by process death. Clear it before accepting the
                // new tap; control state is unknown because the previous POST may have reached the
                // device. Publish UNKNOWN after leaving the monitor to avoid recursive updates.
                staleAction = existing
                preferences(context).edit {
                    remove(KEY_PENDING_ROOM)
                    remove(KEY_PENDING_KIND)
                    remove(KEY_PENDING_AT)
                }
            }
            preferences(context).edit {
                putInt(KEY_PENDING_ROOM, room.storageId)
                putString(KEY_PENDING_KIND, kind.name)
                putLong(KEY_PENDING_AT, nowEpochMillis)
            }
            true
        }
        if (!claimed) return false
        staleAction?.takeIf { it.kind != WidgetActionKind.REFRESH }?.let {
            publishAmbiguous(context, it.room, authenticationRequired = false)
        }
        ShowerWidgetProvider.updateAll(context)
        return true
    }

    internal fun clearPending(context: Context) {
        synchronized(pendingLock) {
            preferences(context).edit {
                remove(KEY_PENDING_ROOM)
                remove(KEY_PENDING_KIND)
                remove(KEY_PENDING_AT)
            }
        }
        ShowerWidgetProvider.updateAll(context)
    }

    /** A process death cannot leave a tappable action forever. Control state is made UNKNOWN. */
    internal fun clearStalePending(context: Context, nowEpochMillis: Long = System.currentTimeMillis()) {
        val pending = readPending(context) ?: return
        if (nowEpochMillis - pending.startedAtEpochMillis <= STALE_PENDING_ACTION_MILLIS) return
        synchronized(pendingLock) {
            preferences(context).edit {
                remove(KEY_PENDING_ROOM)
                remove(KEY_PENDING_KIND)
                remove(KEY_PENDING_AT)
            }
        }
        if (pending.kind != WidgetActionKind.REFRESH) {
            publishAmbiguous(context, pending.room, authenticationRequired = false)
        }
        ShowerWidgetProvider.updateAll(context)
    }

    fun publishRoomStatus(
        context: Context,
        room: WidgetRoom,
        state: WidgetShowerState,
        confirmedAtEpochMillis: Long,
    ) {
        val stateKey = when (room) {
            WidgetRoom.ROOM_ONE -> KEY_ROOM_ONE_STATE
            WidgetRoom.ROOM_TWO -> KEY_ROOM_TWO_STATE
        }
        val timeKey = when (room) {
            WidgetRoom.ROOM_ONE -> KEY_ROOM_ONE_TIME
            WidgetRoom.ROOM_TWO -> KEY_ROOM_TWO_TIME
        }
        preferences(context).edit {
            putString(stateKey, state.name)
            putLong(timeKey, confirmedAtEpochMillis.coerceAtLeast(0L))
        }
        ShowerWidgetProvider.updateAll(context)
    }

    internal fun publishAmbiguous(
        context: Context,
        room: WidgetRoom,
        authenticationRequired: Boolean,
    ) {
        val stateKey = when (room) {
            WidgetRoom.ROOM_ONE -> KEY_ROOM_ONE_STATE
            WidgetRoom.ROOM_TWO -> KEY_ROOM_TWO_STATE
        }
        val timeKey = when (room) {
            WidgetRoom.ROOM_ONE -> KEY_ROOM_ONE_TIME
            WidgetRoom.ROOM_TWO -> KEY_ROOM_TWO_TIME
        }
        preferences(context).edit {
            putString(stateKey, WidgetShowerState.UNKNOWN.name)
            putLong(timeKey, 0L)
            if (authenticationRequired) putBoolean(KEY_LOGGED_IN, false)
        }
        ShowerWidgetProvider.updateAll(context)
    }

    fun snapshot(context: Context): ShowerWidgetSnapshot {
        val prefs = preferences(context)
        return ShowerWidgetSnapshot(
            isLoggedIn = prefs.getBoolean(KEY_LOGGED_IN, false),
            isWatchBound = prefs.getBoolean(KEY_WATCH_BOUND, false),
            roomOne = WidgetRoomSnapshot(
                isConfigured = prefs.getBoolean(KEY_ROOM_ONE_CONFIGURED, false),
                state = prefs.readState(KEY_ROOM_ONE_STATE),
                confirmedAtEpochMillis = prefs.getLong(KEY_ROOM_ONE_TIME, 0L),
            ),
            roomTwo = WidgetRoomSnapshot(
                isConfigured = prefs.getBoolean(KEY_ROOM_TWO_CONFIGURED, false),
                state = prefs.readState(KEY_ROOM_TWO_STATE),
                confirmedAtEpochMillis = prefs.getLong(KEY_ROOM_TWO_TIME, 0L),
            ),
            pendingAction = readPending(context),
        )
    }

    internal fun pendingAction(context: Context): WidgetPendingAction? = readPending(context)

    internal fun selectedRoom(context: Context, appWidgetId: Int): WidgetRoom {
        val stored = preferences(context).getInt(
            KEY_SELECTED_ROOM_PREFIX + appWidgetId,
            WidgetRoom.ROOM_ONE.storageId,
        )
        return WidgetRoom.fromStorageId(stored) ?: WidgetRoom.ROOM_ONE
    }

    internal fun selectRoom(context: Context, appWidgetId: Int, room: WidgetRoom) {
        preferences(context).edit {
            putInt(KEY_SELECTED_ROOM_PREFIX + appWidgetId, room.storageId)
        }
    }

    internal fun removeWidget(context: Context, appWidgetId: Int) {
        preferences(context).edit {
            remove(KEY_SELECTED_ROOM_PREFIX + appWidgetId)
        }
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private fun readPending(context: Context): WidgetPendingAction? {
        val prefs = preferences(context)
        val room = WidgetRoom.fromStorageId(prefs.getInt(KEY_PENDING_ROOM, 0)) ?: return null
        val kind = runCatching {
            WidgetActionKind.valueOf(prefs.getString(KEY_PENDING_KIND, null).orEmpty())
        }.getOrNull() ?: return null
        val at = prefs.getLong(KEY_PENDING_AT, 0L)
        if (at <= 0L) return null
        return WidgetPendingAction(room, kind, at)
    }

    private fun android.content.SharedPreferences.readState(key: String): WidgetShowerState {
        val value = getString(key, null) ?: return WidgetShowerState.UNKNOWN
        return runCatching { WidgetShowerState.valueOf(value) }.getOrDefault(WidgetShowerState.UNKNOWN)
    }

    internal fun hasWidget(context: Context, appWidgetId: Int): Boolean {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return false
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, ShowerWidgetProvider::class.java)
        return manager.getAppWidgetIds(component).contains(appWidgetId)
    }
}
