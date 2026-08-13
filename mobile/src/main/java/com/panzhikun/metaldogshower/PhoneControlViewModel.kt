package com.panzhikun.metaldogshower

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.panzhikun.metaldogshower.connectivity.ConnectivityMonitor
import com.panzhikun.metaldogshower.core.ApiHttpException
import com.panzhikun.metaldogshower.core.AuthenticationRequiredException
import com.panzhikun.metaldogshower.core.ControlRejection
import com.panzhikun.metaldogshower.core.ControlResult
import com.panzhikun.metaldogshower.core.ShowerStatus
import com.panzhikun.metaldogshower.session.ConfiguredShower
import com.panzhikun.metaldogshower.session.PersistedSession
import com.panzhikun.metaldogshower.widget.ShowerWidgetStateStore
import com.panzhikun.metaldogshower.widget.WidgetRoom
import com.panzhikun.metaldogshower.widget.WidgetShowerState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

data class PhoneRoomUi(
    val slot: Int,
    val name: String,
    val configured: Boolean,
    val selected: Boolean,
    val stateKnown: Boolean,
    val uncertain: Boolean,
    val isOpen: Boolean,
    val remainingSeconds: Int,
    val confirmedAtEpochMillis: Long,
)

data class PhoneControlUiState(
    val loggedIn: Boolean = false,
    val hasInternet: Boolean = false,
    val rooms: List<PhoneRoomUi> = controlRoomUi(null, null, emptyMap()),
    val selectedSlot: Int? = null,
    val isBusy: Boolean = false,
    val busyLabel: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

class PhoneControlViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MetalDogApplication
    private val repository = app.repository
    private val connectivity = ConnectivityMonitor(application)
    private val observations = mutableMapOf<Int, RoomObservation>()
    private val _state = MutableStateFlow(
        PhoneControlUiState(
            loggedIn = app.sessionStore.hasUsableSession(),
            rooms = controlRoomUi(app.sessionStore.snapshotOrNull(), null, observations),
        ),
    )
    val state: StateFlow<PhoneControlUiState> = _state.asStateFlow()

    private var operationGeneration = 0L
    private var lastSessionFingerprint = sessionFingerprint(app.sessionStore.snapshotOrNull())

    init {
        viewModelScope.launch {
            connectivity.isInternetCapable.collect { available ->
                _state.update { it.copy(hasInternet = available) }
            }
        }
        viewModelScope.launch {
            app.sessionStore.state.collect { session ->
                val nextFingerprint = sessionFingerprint(session)
                if (nextFingerprint != lastSessionFingerprint) {
                    operationGeneration++
                    lastSessionFingerprint = nextFingerprint
                }
                val validRoutes = session?.showers?.associate { it.slot to it.route }.orEmpty()
                observations.entries.removeAll { (slot, observation) ->
                    validRoutes[slot] != observation.room.route
                }
                val selected = _state.value.selectedSlot?.takeIf { validRoutes.containsKey(it) }
                _state.update {
                    it.copy(
                        loggedIn = session != null,
                        selectedSlot = selected,
                        rooms = controlRoomUi(session, selected, observations),
                        isBusy = if (session == null) false else it.isBusy,
                        busyLabel = if (session == null) null else it.busyLabel,
                    )
                }
            }
        }
    }

    /** Selecting a room is explicit and always invalidates its visible state before one fresh GET. */
    fun selectRoom(slot: Int) {
        val session = app.sessionStore.snapshotOrNull() ?: return sessionUnavailable()
        val room = session.room(slot)
        if (room == null) {
            _state.update {
                it.copy(
                    selectedSlot = null,
                    rooms = controlRoomUi(session, null, observations),
                    errorMessage = "浴室$slot 尚未配置，请先扫描该浴室二维码",
                    statusMessage = null,
                )
            }
            return
        }
        if (_state.value.isBusy) return
        observations.remove(slot)
        _state.update {
            it.copy(
                selectedSlot = slot,
                rooms = controlRoomUi(session, slot, observations),
                statusMessage = "已选择${room.name}，正在核对当前状态…",
                errorMessage = null,
            )
        }
        refreshSelected()
    }

    fun refreshSelected() {
        val session = app.sessionStore.snapshotOrNull() ?: return sessionUnavailable()
        val slot = _state.value.selectedSlot ?: run {
            fail("请先选择浴室1或浴室2")
            return
        }
        val room = session.room(slot) ?: run {
            fail("该浴室尚未配置")
            return
        }
        if (!beginOperation("正在刷新${room.name}…")) return
        observations.remove(slot)
        render(session)
        val generation = ++operationGeneration
        viewModelScope.launch {
            try {
                app.phoneOperationMutex.withLock {
                    if (!isCurrent(generation, session, room)) return@withLock
                    val status = repository.status(room.route)
                    if (!isCurrent(generation, session, room)) return@withLock
                    val confirmedAt = System.currentTimeMillis()
                    observations[slot] = RoomObservation(room, status, confirmedAt, false)
                    publishWidgetStatus(slot, status, confirmedAt)
                    render(session, statusMessage = "${room.name}状态已核对")
                }
            } catch (exception: Throwable) {
                if (exception is CancellationException) throw exception
                if (isCurrent(generation, session, room)) {
                    handleFailure(exception, room, session.credentialId)
                }
            } finally {
                if (generation == operationGeneration) endOperation()
            }
        }
    }

    /**
     * Foreground-only, GET-only polling used by the user-configured setting.
     * It never queues behind a control request and never sends a switch POST.
     */
    fun pollConfiguredRooms() {
        val session = app.sessionStore.snapshotOrNull() ?: return
        if (_state.value.isBusy || !connectivity.hasInternetCapability()) return
        if (!app.phoneOperationMutex.tryLock()) return

        viewModelScope.launch {
            try {
                for (room in session.showers.sortedBy(ConfiguredShower::slot)) {
                    if (!isSessionRouteCurrent(session, room)) return@launch
                    try {
                        val status = repository.status(room.route)
                        if (!isSessionRouteCurrent(session, room)) return@launch
                        val confirmedAt = System.currentTimeMillis()
                        observations[room.slot] = RoomObservation(room, status, confirmedAt, false)
                        publishWidgetStatus(room.slot, status, confirmedAt)
                    } catch (authentication: AuthenticationRequiredException) {
                        handleFailure(authentication, room, session.credentialId)
                        return@launch
                    } catch (_: IOException) {
                        observations.remove(room.slot)
                        publishWidgetUnknown(room.slot)
                    }
                }
                if (app.sessionStore.snapshotOrNull()?.credentialId == session.credentialId) {
                    render(session)
                }
            } finally {
                app.phoneOperationMutex.unlock()
            }
        }
    }

    /** Called only after the UI has shown a confirmation containing the concrete room name. */
    fun controlSelected(desiredOpen: Boolean) {
        val session = app.sessionStore.snapshotOrNull() ?: return sessionUnavailable()
        val slot = _state.value.selectedSlot ?: run {
            fail("请先选择浴室1或浴室2")
            return
        }
        val room = session.room(slot) ?: run {
            fail("该浴室尚未配置")
            return
        }
        if (!beginOperation("正在核对并${if (desiredOpen) "开启" else "关闭"}${room.name}…")) return
        val generation = ++operationGeneration
        viewModelScope.launch {
            var controlStarted = false
            if (!app.phoneOperationMutex.tryLock()) {
                fail("另一操作进行中，本次未发送")
                if (generation == operationGeneration) endOperation()
                return@launch
            }
            try {
                    if (!isCurrent(generation, session, room)) return@launch
                    // A fresh route-bound state read is mandatory before every phone-side POST. If
                    // it fails, no switch request is attempted.
                    val before = repository.status(room.route)
                    if (!isCurrent(generation, session, room)) return@launch
                    val beforeConfirmedAt = System.currentTimeMillis()
                    observations[slot] = RoomObservation(room, before, beforeConfirmedAt, false)
                    publishWidgetStatus(slot, before, beforeConfirmedAt)
                    if (before.isOpen == desiredOpen) {
                        render(session, statusMessage = "${room.name}已经${if (desiredOpen) "开启" else "关闭"}，未发送开关请求")
                        return@launch
                    }

                    // Fail closed before the POST can leave the process. Confirmed/rejected results
                    // restore a known state; cancellation or process death leaves UNKNOWN visible.
                    controlStarted = true
                    observations[slot] = RoomObservation(room, null, 0L, true)
                    publishWidgetUnknown(slot)
                    val result = repository.control(room.route, desiredOpen)
                    if (!isSessionRouteCurrent(session, room)) return@launch
                    if (result is ControlResult.Ambiguous && result.authenticationRequired) {
                        val cleared = app.sessionStore.clearIfCredentialMatches(session.credentialId)
                        if (cleared) app.publishWidgetSession(preserveVerifiedStatus = false)
                        app.provisioningManager.notifySessionClear(session.credentialId)
                        if (cleared) {
                            sessionUnavailable(
                                "登录已失效；刚才对${room.name}的操作结果无法确认。请现场确认，重新登录后先刷新状态，勿重复开关。",
                            )
                        }
                        return@launch
                    }
                    if (!isCurrent(generation, session, room)) return@launch
                    when (result) {
                    is ControlResult.Confirmed -> {
                        val confirmedAt = System.currentTimeMillis()
                        observations[slot] = RoomObservation(
                            room,
                            result.status,
                            confirmedAt,
                            false,
                        )
                        publishWidgetStatus(slot, result.status, confirmedAt)
                        render(session, statusMessage = "${room.name}已确认${if (result.status.isOpen) "开启" else "关闭"}")
                    }
                    is ControlResult.Ambiguous -> {
                        val observed = result.observedStatus
                        if (observed == null) {
                            observations[slot] = RoomObservation(room, null, 0L, true)
                            publishWidgetUnknown(slot)
                            render(
                                session,
                                errorMessage = "${room.name}操作结果无法确认。请现场确认；只能手动刷新状态，切勿重复点击。",
                            )
                        } else {
                            val confirmedAt = System.currentTimeMillis()
                            observations[slot] = RoomObservation(
                                room,
                                observed,
                                confirmedAt,
                                false,
                            )
                            publishWidgetStatus(slot, observed, confirmedAt)
                            render(
                                session,
                                statusMessage = "开关响应不明确；已通过一次状态核对：${room.name}${if (observed.isOpen) "开启" else "关闭"}",
                            )
                        }
                    }
                    is ControlResult.Rejected -> {
                        observations[slot] = RoomObservation(
                            room,
                            before,
                            beforeConfirmedAt,
                            false,
                        )
                        publishWidgetStatus(slot, before, beforeConfirmedAt)
                        val message = when (result.reason) {
                            ControlRejection.ALREADY_OPEN -> "${room.name}已经开启，未重复发送"
                            ControlRejection.ALREADY_CLOSED -> "${room.name}已经关闭，未重复发送"
                            ControlRejection.DEBOUNCED -> "点击过快，本次未发送"
                            ControlRejection.COOLDOWN -> "控制冷却中，本次未发送"
                        }
                        render(session, statusMessage = message)
                    }
                }
            } catch (exception: Throwable) {
                if (exception is CancellationException) {
                    if (controlStarted && isSessionRouteCurrent(session, room)) {
                        observations[slot] = RoomObservation(room, null, 0L, true)
                        publishWidgetUnknown(slot)
                    }
                    throw exception
                }
                if (isCurrent(generation, session, room)) {
                    handleFailure(exception, room, session.credentialId)
                } else if (
                    exception is AuthenticationRequiredException &&
                    isSessionRouteCurrent(session, room)
                ) {
                    handleFailure(exception, room, session.credentialId)
                }
            } finally {
                app.phoneOperationMutex.unlock()
                if (generation == operationGeneration) endOperation()
            }
        }
    }

    fun clearMessages() {
        _state.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    private fun beginOperation(label: String): Boolean {
        if (_state.value.isBusy) return false
        if (!connectivity.hasInternetCapability()) {
            fail("手机当前没有可用互联网")
            return false
        }
        _state.update {
            it.copy(isBusy = true, busyLabel = label, statusMessage = null, errorMessage = null)
        }
        return true
    }

    private fun endOperation() {
        _state.update { it.copy(isBusy = false, busyLabel = null) }
    }

    private fun isCurrent(
        generation: Long,
        session: PersistedSession,
        room: ConfiguredShower,
    ): Boolean {
        val current = app.sessionStore.snapshotOrNull() ?: return false
        return generation == operationGeneration &&
            current.credentialId == session.credentialId &&
            current.room(room.slot)?.route == room.route &&
            _state.value.selectedSlot == room.slot
    }

    private fun isSessionRouteCurrent(session: PersistedSession, room: ConfiguredShower): Boolean {
        val current = app.sessionStore.snapshotOrNull() ?: return false
        return current.credentialId == session.credentialId &&
            current.room(room.slot)?.route == room.route
    }

    private fun handleFailure(
        throwable: Throwable,
        room: ConfiguredShower,
        expectedCredentialId: String,
    ) {
        observations.remove(room.slot)
        publishWidgetUnknown(room.slot)
        if (throwable is AuthenticationRequiredException) {
            val cleared = app.sessionStore.clearIfCredentialMatches(expectedCredentialId)
            if (cleared) {
                app.publishWidgetSession(preserveVerifiedStatus = false)
                sessionUnavailable("官方登录已失效，请重新短信验证码登录。")
            }
            viewModelScope.launch { app.provisioningManager.notifySessionClear(expectedCredentialId) }
            return
        }
        val message = when (throwable) {
            is ApiHttpException -> when (throwable.statusCode) {
                403 -> "官方服务拒绝控制${room.name}，未自动重试"
                429 -> "官方服务请求过于频繁，未自动重试"
                else -> "官方服务返回 ${throwable.statusCode}，未自动重试"
            }
            is IOException -> "无法核对${room.name}状态；未发送或重发开关请求"
            else -> "${room.name}操作未完成；不会自动重试"
        }
        render(app.sessionStore.snapshotOrNull(), errorMessage = message)
    }

    private fun sessionUnavailable(message: String = "请先在手机完成登录") {
        observations.clear()
        _state.value = PhoneControlUiState(
            hasInternet = connectivity.hasInternetCapability(),
            errorMessage = message,
        )
    }

    private fun fail(message: String) {
        _state.update { it.copy(errorMessage = message, statusMessage = null) }
    }

    private fun publishWidgetStatus(slot: Int, status: ShowerStatus, confirmedAt: Long) {
        ShowerWidgetStateStore.publishRoomStatus(
            getApplication(),
            if (slot == 1) WidgetRoom.ROOM_ONE else WidgetRoom.ROOM_TWO,
            if (status.isOpen) WidgetShowerState.OPEN else WidgetShowerState.CLOSED,
            confirmedAt,
        )
    }

    private fun publishWidgetUnknown(slot: Int) {
        ShowerWidgetStateStore.publishRoomStatus(
            getApplication(),
            if (slot == 1) WidgetRoom.ROOM_ONE else WidgetRoom.ROOM_TWO,
            WidgetShowerState.UNKNOWN,
            0L,
        )
    }

    private fun render(
        session: PersistedSession?,
        statusMessage: String? = null,
        errorMessage: String? = null,
    ) {
        val selected = _state.value.selectedSlot?.takeIf { session?.room(it) != null }
        _state.update {
            it.copy(
                loggedIn = session != null,
                selectedSlot = selected,
                rooms = controlRoomUi(session, selected, observations),
                statusMessage = statusMessage,
                errorMessage = errorMessage,
            )
        }
    }
}

private fun sessionFingerprint(session: PersistedSession?): String? = session?.let {
    buildString {
        append(it.credentialId)
        it.showers.sortedBy(ConfiguredShower::slot).forEach { room ->
            append('|').append(room.slot)
            append('|').append(room.route.brandId)
            append('|').append(room.route.stadiumId)
            append('|').append(room.route.deviceId)
        }
    }
}

private data class RoomObservation(
    val room: ConfiguredShower,
    val status: ShowerStatus?,
    val confirmedAtEpochMillis: Long,
    val uncertain: Boolean,
)

private fun controlRoomUi(
    session: PersistedSession?,
    selectedSlot: Int?,
    observations: Map<Int, RoomObservation>,
): List<PhoneRoomUi> = (1..2).map { slot ->
    val room = session?.room(slot)
    val observation = observations[slot]?.takeIf { it.room.route == room?.route }
    PhoneRoomUi(
        slot = slot,
        name = room?.name ?: "浴室$slot",
        configured = room != null,
        selected = slot == selectedSlot,
        stateKnown = observation?.status != null && !observation.uncertain,
        uncertain = observation?.uncertain == true,
        isOpen = observation?.status?.isOpen == true,
        remainingSeconds = observation?.status?.remainingSeconds ?: 0,
        confirmedAtEpochMillis = observation?.confirmedAtEpochMillis ?: 0L,
    )
}
