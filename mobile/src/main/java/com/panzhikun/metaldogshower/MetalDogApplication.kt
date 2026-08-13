package com.panzhikun.metaldogshower

import android.app.Application
import com.panzhikun.metaldogshower.core.ShowerRepositories
import com.panzhikun.metaldogshower.core.ShowerRepository
import com.panzhikun.metaldogshower.core.TokenProvider
import com.panzhikun.metaldogshower.provision.ProvisioningManager
import com.panzhikun.metaldogshower.session.SecureSessionStore
import com.panzhikun.metaldogshower.widget.ShowerWidgetBridge
import com.panzhikun.metaldogshower.widget.ShowerWidgetSnapshot
import com.panzhikun.metaldogshower.widget.ShowerWidgetStateStore
import com.panzhikun.metaldogshower.widget.WidgetRoom
import com.panzhikun.metaldogshower.widget.WidgetRoomSnapshot
import kotlinx.coroutines.sync.Mutex

class MetalDogApplication : Application() {
    /** Serializes protected phone requests with credential/route mutation and widget control. */
    val phoneOperationMutex = Mutex()

    val sessionStore: SecureSessionStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SecureSessionStore(this)
    }

    val repository: ShowerRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ShowerRepositories.production(
            TokenProvider { sessionStore.tokenOrNull() },
        )
    }

    val provisioningManager: ProvisioningManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ProvisioningManager(this)
    }

    private val phoneWidgetController: PhoneWidgetController by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PhoneWidgetController(this)
    }

    override fun onCreate() {
        super.onCreate()
        // A process restart can interrupt a widget control after the POST. Expire that visual
        // pending marker before publishing the regular session snapshot so it cannot look live.
        ShowerWidgetStateStore.clearStalePending(this)
        ShowerWidgetBridge.install(phoneWidgetController)
        publishWidgetSession(preserveVerifiedStatus = true)
        BackgroundPollingScheduler.reconcile(this)
    }

    /** Used only by the scheduled JobService; the implementation performs status GETs, never POSTs. */
    suspend fun refreshConfiguredRoomsForSchedule() {
        phoneWidgetController.refreshConfiguredRooms(this)
    }

    fun publishWidgetSession(preserveVerifiedStatus: Boolean) {
        val session = sessionStore.snapshotOrNull()
        val previous = ShowerWidgetStateStore.snapshot(this)
        fun roomSnapshot(slot: Int, widgetRoom: WidgetRoom): WidgetRoomSnapshot {
            if (session?.room(slot) == null) return WidgetRoomSnapshot()
            return if (preserveVerifiedStatus) {
                previous.room(widgetRoom).copy(isConfigured = true)
            } else {
                WidgetRoomSnapshot(isConfigured = true)
            }
        }
        ShowerWidgetStateStore.publish(
            this,
            ShowerWidgetSnapshot(
                isLoggedIn = session != null,
                isWatchBound = session?.watchBound == true,
                roomOne = roomSnapshot(1, WidgetRoom.ROOM_ONE),
                roomTwo = roomSnapshot(2, WidgetRoom.ROOM_TWO),
                pendingAction = previous.pendingAction,
            ),
        )
    }
}
