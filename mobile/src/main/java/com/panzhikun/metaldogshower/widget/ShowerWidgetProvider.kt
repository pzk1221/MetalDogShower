package com.panzhikun.metaldogshower.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.panzhikun.metaldogshower.MainActivity
import com.panzhikun.metaldogshower.R

/** Binds the widget only; all network work is performed by WidgetActionJobService. */
class ShowerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { appWidgetId ->
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { ShowerWidgetStateStore.removeWidget(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            WidgetActionIntents.ACTION_SELECT_ROOM -> handleRoomSelection(context, intent)
            WidgetActionIntents.ACTION_EXECUTE -> handleAction(context, intent)
        }
    }

    private fun handleRoomSelection(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val room = WidgetRoom.fromStorageId(intent.getIntExtra(WidgetActionIntents.EXTRA_ROOM, 0))
            ?: return
        if (!ShowerWidgetStateStore.hasWidget(context, appWidgetId)) return
        ShowerWidgetStateStore.selectRoom(context, appWidgetId, room)
        updateWidget(context, AppWidgetManager.getInstance(context), appWidgetId)
    }

    private fun handleAction(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val room = WidgetRoom.fromStorageId(intent.getIntExtra(WidgetActionIntents.EXTRA_ROOM, 0))
            ?: return
        val kind = runCatching {
            WidgetActionKind.valueOf(intent.getStringExtra(WidgetActionIntents.EXTRA_KIND).orEmpty())
        }.getOrNull() ?: return
        if (!ShowerWidgetStateStore.hasWidget(context, appWidgetId)) return

        val snapshot = ShowerWidgetStateStore.snapshot(context)
        val ready = snapshot.canRequestControl(room) &&
            ShowerWidgetBridge.canControl(context.applicationContext, room)
        if (!ready) {
            // Configuration/login is the only case that opens the app. A configured widget
            // action never launches a dialog or confirmation activity.
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("opened_from_widget", true),
            )
            return
        }
        if (!ShowerWidgetStateStore.markPending(context, room, kind)) return
        if (!WidgetActionJobService.schedule(context, room, kind)) {
            ShowerWidgetStateStore.clearPending(context)
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val component = ComponentName(appContext, ShowerWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { appWidgetId ->
                updateWidget(appContext, manager, appWidgetId)
            }
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            ShowerWidgetStateStore.clearStalePending(context)
            val snapshot = ShowerWidgetStateStore.snapshot(context)
            val selectedRoom = ShowerWidgetStateStore.selectedRoom(context, appWidgetId)
            val roomSnapshot = snapshot.room(selectedRoom)
            val widgetOptions = manager.getAppWidgetOptions(appWidgetId)
            val ready = snapshot.canRequestControl(selectedRoom) &&
                ShowerWidgetBridge.canControl(context.applicationContext, selectedRoom)
            val views = responsiveViews(
                context = context,
                manager = manager,
                appWidgetId = appWidgetId,
                selectedRoom = selectedRoom,
                snapshot = snapshot,
                roomSnapshot = roomSnapshot,
                ready = ready,
                options = widgetOptions,
            )
            manager.updateAppWidget(appWidgetId, views)
        }

        private fun responsiveViews(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            selectedRoom: WidgetRoom,
            snapshot: ShowerWidgetSnapshot,
            roomSnapshot: WidgetRoomSnapshot,
            ready: Boolean,
            options: Bundle,
        ): RemoteViews {
            val compact = bindLayout(
                context, R.layout.shower_control_widget_compact, appWidgetId, selectedRoom,
                snapshot, roomSnapshot, ready,
            )
            val standard = bindLayout(
                context, R.layout.shower_control_widget, appWidgetId, selectedRoom,
                snapshot, roomSnapshot, ready,
            )
            val wide = bindLayout(
                context, R.layout.shower_control_widget_wide, appWidgetId, selectedRoom,
                snapshot, roomSnapshot, ready,
            )
            val wideTall = bindLayout(
                context, R.layout.shower_control_widget_wide_tall, appWidgetId, selectedRoom,
                snapshot, roomSnapshot, ready,
            )
            val expanded = bindLayout(
                context, R.layout.shower_control_widget_expanded, appWidgetId, selectedRoom,
                snapshot, roomSnapshot, ready,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return RemoteViews(
                    mapOf(
                        SizeF(180f, 104f) to compact,
                        SizeF(250f, 118f) to standard,
                        SizeF(330f, 118f) to wide,
                        SizeF(330f, 180f) to wideTall,
                        SizeF(250f, 180f) to expanded,
                    ),
                )
            }
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 118)
            return when {
                width >= 315 && height >= 160 -> wideTall
                height >= 160 -> expanded
                width < 220 -> compact
                width >= 315 -> wide
                else -> standard
            }
        }

        private fun bindLayout(
            context: Context,
            layout: Int,
            appWidgetId: Int,
            selectedRoom: WidgetRoom,
            snapshot: ShowerWidgetSnapshot,
            roomSnapshot: WidgetRoomSnapshot,
            ready: Boolean,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, layout)
            bindRoomTabs(context, views, appWidgetId, selectedRoom, snapshot)
            bindRoomStatus(context, views, snapshot.isLoggedIn, selectedRoom, roomSnapshot, snapshot.pendingAction)
            val actionEnabled = ready && snapshot.pendingAction == null
            bindControlActions(context, views, appWidgetId, selectedRoom, actionEnabled)
            bindRefreshAction(context, views, appWidgetId, selectedRoom, actionEnabled)
            views.setOnClickPendingIntent(R.id.widget_title, mainActivityIntent(context, appWidgetId))
            return views
        }

        private fun bindRoomTabs(
            context: Context,
            views: RemoteViews,
            appWidgetId: Int,
            selectedRoom: WidgetRoom,
            snapshot: ShowerWidgetSnapshot,
        ) {
            val roomOneSelected = selectedRoom == WidgetRoom.ROOM_ONE
            views.setInt(
                R.id.widget_room_one,
                "setBackgroundResource",
                if (roomOneSelected) R.drawable.widget_tab_selected else R.drawable.widget_tab_unselected,
            )
            views.setInt(
                R.id.widget_room_two,
                "setBackgroundResource",
                if (!roomOneSelected) R.drawable.widget_tab_selected else R.drawable.widget_tab_unselected,
            )
            views.setDayNightTextColor(
                context,
                R.id.widget_room_one,
                if (roomOneSelected) R.color.widget_on_tab_selected else R.color.widget_on_surface,
            )
            views.setDayNightTextColor(
                context,
                R.id.widget_room_two,
                if (!roomOneSelected) R.color.widget_on_tab_selected else R.color.widget_on_surface,
            )
            views.setFloat(R.id.widget_room_one, "setAlpha", if (snapshot.roomOne.isConfigured) 1f else 0.58f)
            views.setFloat(R.id.widget_room_two, "setAlpha", if (snapshot.roomTwo.isConfigured) 1f else 0.58f)
            views.setOnClickPendingIntent(
                R.id.widget_room_one,
                roomSelectionIntent(context, appWidgetId, WidgetRoom.ROOM_ONE),
            )
            views.setOnClickPendingIntent(
                R.id.widget_room_two,
                roomSelectionIntent(context, appWidgetId, WidgetRoom.ROOM_TWO),
            )
        }

        private fun bindRoomStatus(
            context: Context,
            views: RemoteViews,
            isLoggedIn: Boolean,
            selectedRoom: WidgetRoom,
            roomSnapshot: WidgetRoomSnapshot,
            pending: WidgetPendingAction?,
        ) {
            val pendingLabel = pending?.let {
                when {
                    it.room != selectedRoom -> "另一浴室处理中"
                    it.kind == WidgetActionKind.OPEN -> "正在开启"
                    it.kind == WidgetActionKind.CLOSE -> "正在关闭"
                    else -> "正在刷新"
                }
            }
            val displayState = roomSnapshot.freshDisplayState(System.currentTimeMillis())
            val stateLabel = pendingLabel ?: context.getString(
                when {
                    !isLoggedIn -> R.string.widget_setup_needed
                    !roomSnapshot.isConfigured -> R.string.widget_room_unconfigured
                    else -> when (displayState) {
                        WidgetShowerState.OPEN -> R.string.widget_state_open
                        WidgetShowerState.CLOSED -> R.string.widget_state_closed
                        WidgetShowerState.UNKNOWN -> R.string.widget_state_needs_check
                    }
                },
            )
            views.setTextViewText(R.id.widget_status, stateLabel)
            views.setDayNightTextColor(
                context,
                R.id.widget_status,
                when {
                    pending != null -> R.color.widget_pending_text
                    !isLoggedIn || !roomSnapshot.isConfigured -> R.color.widget_muted
                    displayState == WidgetShowerState.OPEN -> R.color.widget_open_text
                    displayState == WidgetShowerState.CLOSED -> R.color.widget_closed_text
                    else -> R.color.widget_muted
                },
            )
            views.setViewVisibility(
                R.id.widget_live_dot,
                if (pending != null || !isLoggedIn || !roomSnapshot.isConfigured || displayState == WidgetShowerState.UNKNOWN) {
                    View.INVISIBLE
                } else View.VISIBLE,
            )
            views.setInt(
                R.id.widget_live_dot,
                "setBackgroundResource",
                if (displayState == WidgetShowerState.OPEN) R.drawable.widget_status_dot_open
                else R.drawable.widget_status_dot_closed,
            )
        }

        private fun bindControlActions(
            context: Context,
            views: RemoteViews,
            appWidgetId: Int,
            selectedRoom: WidgetRoom,
            enabled: Boolean,
        ) {
            views.setOnClickPendingIntent(
                R.id.widget_open,
                widgetActionIntent(context, appWidgetId, selectedRoom, WidgetActionKind.OPEN),
            )
            views.setOnClickPendingIntent(
                R.id.widget_close,
                widgetActionIntent(context, appWidgetId, selectedRoom, WidgetActionKind.CLOSE),
            )
            val alpha = if (enabled) 1f else 0.42f
            views.setFloat(R.id.widget_open, "setAlpha", alpha)
            views.setFloat(R.id.widget_close, "setAlpha", alpha)
        }

        private fun bindRefreshAction(
            context: Context,
            views: RemoteViews,
            appWidgetId: Int,
            selectedRoom: WidgetRoom,
            enabled: Boolean,
        ) {
            views.setOnClickPendingIntent(
                R.id.widget_refresh,
                widgetActionIntent(context, appWidgetId, selectedRoom, WidgetActionKind.REFRESH),
            )
            views.setFloat(R.id.widget_refresh, "setAlpha", if (enabled) 1f else 0.42f)
        }

        private fun roomSelectionIntent(
            context: Context,
            appWidgetId: Int,
            room: WidgetRoom,
        ): PendingIntent {
            val intent = Intent(context, ShowerWidgetProvider::class.java)
                .setAction(WidgetActionIntents.ACTION_SELECT_ROOM)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .setData(widgetUri("select", appWidgetId, room.storageId))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .putExtra(WidgetActionIntents.EXTRA_ROOM, room.storageId)
            return PendingIntent.getBroadcast(
                context,
                requestCode(appWidgetId, 10 + room.storageId),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun widgetActionIntent(
            context: Context,
            appWidgetId: Int,
            room: WidgetRoom,
            kind: WidgetActionKind,
        ): PendingIntent {
            val intent = Intent(context, ShowerWidgetProvider::class.java)
                .setAction(WidgetActionIntents.ACTION_EXECUTE)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .setData(widgetUri("action", appWidgetId, room.storageId, kind.ordinal))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .putExtra(WidgetActionIntents.EXTRA_ROOM, room.storageId)
                .putExtra(WidgetActionIntents.EXTRA_KIND, kind.name)
            return PendingIntent.getBroadcast(
                context,
                requestCode(appWidgetId, 20 + room.storageId * 3 + kind.ordinal),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun mainActivityIntent(
            context: Context,
            appWidgetId: Int,
        ): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .setData(widgetUri("open", appWidgetId))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                context,
                requestCode(appWidgetId, 1),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun requestCode(appWidgetId: Int, actionCode: Int): Int = 31 * appWidgetId + actionCode

        private fun widgetUri(action: String, vararg identity: Int): Uri = Uri.Builder()
            .scheme("metaldog-widget")
            .authority(action)
            .apply { identity.forEach { appendPath(it.toString()) } }
            .build()

        private fun RemoteViews.setDayNightTextColor(
            context: Context,
            viewId: Int,
            colorResource: Int,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setColorStateList(viewId, "setTextColor", colorResource)
            } else {
                setTextColor(viewId, context.getColor(colorResource))
            }
        }
    }
}
