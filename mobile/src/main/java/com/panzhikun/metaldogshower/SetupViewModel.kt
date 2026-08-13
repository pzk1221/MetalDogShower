package com.panzhikun.metaldogshower

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.panzhikun.metaldogshower.connectivity.ConnectivityMonitor
import com.panzhikun.metaldogshower.core.AuthenticationRequiredException
import com.panzhikun.metaldogshower.core.DeviceInfo
import com.panzhikun.metaldogshower.core.DeviceRoute
import com.panzhikun.metaldogshower.core.InvalidInputException
import com.panzhikun.metaldogshower.core.ProtocolException
import com.panzhikun.metaldogshower.provision.ProvisioningConfig
import com.panzhikun.metaldogshower.provision.ProvisioningDevice
import com.panzhikun.metaldogshower.provision.ProvisioningException
import com.panzhikun.metaldogshower.qr.QrAliasParser
import com.panzhikun.metaldogshower.session.ConfiguredShower
import com.panzhikun.metaldogshower.session.SessionStorageException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

data class RoomSetupUi(
    val slot: Int,
    val label: String,
    val configured: Boolean,
)

data class SetupUiState(
    val hasInternet: Boolean = false,
    val qrValue: String = "",
    val phone: String = "",
    val otp: String = "",
    val selectedSetupSlot: Int = 1,
    val candidateDeviceName: String? = null,
    val rooms: List<RoomSetupUi> = defaultRoomUi(),
    val isBusy: Boolean = false,
    val busyLabel: String? = null,
    val otpCooldownSeconds: Int = 0,
    val loggedIn: Boolean = false,
    val watchBound: Boolean = false,
    val pollingEnabled: Boolean = false,
    val pollingIntervalSeconds: Int = 300,
    val backgroundPolling: BackgroundPollingSettings = BackgroundPollingSettings(),
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

class SetupViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MetalDogApplication
    private val repository = app.repository
    private val connectivity = ConnectivityMonitor(application)
    private val pollingSettings = PollingSettingsStore(application)
    private val pendingRooms = linkedMapOf<Int, ConfiguredShower>()

    private val initialSession = app.sessionStore.snapshotOrNull()
    private val initialPollingSettings = pollingSettings.read()
    private val _state = MutableStateFlow(
        SetupUiState(
            rooms = roomUi(initialSession?.showers.orEmpty()),
            loggedIn = initialSession != null,
            watchBound = initialSession?.watchBound == true,
            pollingEnabled = initialPollingSettings.enabled,
            pollingIntervalSeconds = initialPollingSettings.intervalSeconds,
            backgroundPolling = initialPollingSettings.background,
        ),
    )
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    private var cooldownJob: Job? = null
    private var qrGeneration: Long = 0L

    init {
        initialSession?.showers?.forEach { pendingRooms[it.slot] = it }
        viewModelScope.launch {
            connectivity.isInternetCapable.collect { available ->
                _state.update { it.copy(hasInternet = available) }
            }
        }
        viewModelScope.launch {
            app.sessionStore.state.collect { session ->
                if (session != null) {
                    pendingRooms.clear()
                    session.showers.forEach { pendingRooms[it.slot] = it }
                }
                _state.update {
                    it.copy(
                        loggedIn = session != null,
                        watchBound = session?.watchBound == true,
                        rooms = roomUi(session?.showers ?: pendingRooms.values.toList()),
                    )
                }
                BackgroundPollingScheduler.reconcile(app)
            }
        }
        viewModelScope.launch {
            delay(700L)
            maybeAutoProvision()
        }
    }

    fun setPollingEnabled(enabled: Boolean) {
        pollingSettings.setEnabled(enabled)
        _state.update { it.copy(pollingEnabled = enabled) }
    }

    fun setPollingIntervalSeconds(intervalSeconds: Int) {
        pollingSettings.setIntervalSeconds(intervalSeconds)
        _state.update {
            it.copy(pollingIntervalSeconds = pollingSettings.read().intervalSeconds)
        }
    }

    fun setBackgroundPollingEnabled(enabled: Boolean) {
        updateBackgroundPolling { it.copy(enabled = enabled) }
    }

    fun setBackgroundPollingMode(mode: BackgroundPollingMode) {
        updateBackgroundPolling { it.copy(mode = mode) }
    }

    fun setBackgroundPollingIntervalMinutes(intervalMinutes: Int) {
        updateBackgroundPolling { current ->
            val minimumEnd = current.onceStartAtMillis + intervalMinutes.coerceAtLeast(1) * 60_000L
            current.copy(
                intervalMinutes = intervalMinutes,
                onceEndAtMillis = maxOf(current.onceEndAtMillis, minimumEnd),
            )
        }
    }

    fun setBackgroundDailyStartMinute(minuteOfDay: Int) {
        updateBackgroundPolling { it.copy(dailyStartMinute = minuteOfDay) }
    }

    fun setBackgroundDailyEndMinute(minuteOfDay: Int) {
        updateBackgroundPolling { it.copy(dailyEndMinute = minuteOfDay) }
    }

    fun setBackgroundOnceStartAtMillis(startAtMillis: Long) {
        updateBackgroundPolling { current ->
            val minimumEnd = startAtMillis + current.intervalMinutes * 60_000L
            current.copy(
                onceStartAtMillis = startAtMillis,
                onceEndAtMillis = maxOf(current.onceEndAtMillis, minimumEnd),
            )
        }
    }

    fun setBackgroundOnceEndAtMillis(endAtMillis: Long) {
        updateBackgroundPolling { current ->
            current.copy(
                onceEndAtMillis = maxOf(
                    endAtMillis,
                    current.onceStartAtMillis + current.intervalMinutes * 60_000L,
                ),
            )
        }
    }

    private fun updateBackgroundPolling(
        transform: (BackgroundPollingSettings) -> BackgroundPollingSettings,
    ) {
        val updated = pollingSettings.setBackground(transform(_state.value.backgroundPolling))
        _state.update { it.copy(backgroundPolling = updated) }
        BackgroundPollingScheduler.reconcile(app, immediateIfActive = true)
    }

    /** Automatically retries the first phone-to-watch sync once per credential generation. */
    fun maybeAutoProvision() {
        viewModelScope.launch {
            if (!app.provisioningManager.hasConnectedWatch()) return@launch
            val session = app.sessionStore.snapshotOrNull() ?: return@launch
            if (session.watchBound || !pollingSettings.claimAutoSync(session.credentialId)) return@launch
            provisionWatch(auto = true)
        }
    }

    fun selectSetupSlot(slot: Int) {
        if (slot !in 1..2 || _state.value.isBusy) return
        qrGeneration++
        _state.update {
            it.copy(
                selectedSetupSlot = slot,
                qrValue = "",
                candidateDeviceName = null,
                statusMessage = null,
                errorMessage = null,
            )
        }
    }

    fun setQrValue(value: String) {
        if (_state.value.isBusy) return
        qrGeneration++
        _state.update {
            it.copy(
                qrValue = value.take(MAX_QR_LENGTH),
                candidateDeviceName = null,
                statusMessage = null,
                errorMessage = null,
            )
        }
    }

    fun acceptIncomingQr(value: String) {
        if (_state.value.isBusy) return
        setQrValue(value)
        if (value.isNotBlank()) {
            _state.update { it.copy(statusMessage = "已读取链接；请确认浴室编号后点击“识别并保存”") }
        }
    }

    fun setPhone(value: String) {
        if (_state.value.loggedIn) return
        _state.update {
            it.copy(phone = value.filter(Char::isDigit).take(11), errorMessage = null)
        }
    }

    fun setOtp(value: String) {
        if (_state.value.loggedIn) return
        _state.update {
            it.copy(otp = value.filter(Char::isDigit).take(6), errorMessage = null)
        }
    }

    fun resolveDevice() {
        val snapshot = _state.value
        val expectedCredentialId = app.sessionStore.snapshotOrNull()?.credentialId
        val requestedQr = snapshot.qrValue
        val requestedSlot = snapshot.selectedSetupSlot
        val requestedGeneration = qrGeneration
        if (!beginOperation("正在通过官方 HTTPS 接口识别浴室$requestedSlot…", requireInternet = true)) return
        viewModelScope.launch {
            try {
                val alias = QrAliasParser.parse(requestedQr)
                val device = repository.resolveDevice(alias)
                require(device.type.equals(DeviceInfo.TYPE_SHOWER, ignoreCase = true)) {
                    "该二维码不是淋浴设备"
                }
                require(device.deviceId.isNotBlank() && device.stadiumId > 0)
                if (
                    requestedGeneration != qrGeneration ||
                    _state.value.qrValue != requestedQr ||
                    _state.value.selectedSetupSlot != requestedSlot
                ) return@launch

                val configured = ConfiguredShower(
                    slot = requestedSlot,
                    name = "浴室$requestedSlot",
                    route = DeviceRoute(
                        brandId = OFFICIAL_BRAND_ID,
                        stadiumId = device.stadiumId,
                        deviceId = device.deviceId,
                    ),
                )
                var staleWatchCredentialId: String? = null
                val committed = app.phoneOperationMutex.withLock {
                    val currentSession = app.sessionStore.snapshotOrNull()
                    if (currentSession?.credentialId != expectedCredentialId) return@withLock false
                    val currentRooms = currentSession?.showers ?: pendingRooms.values.toList()
                    val duplicateSlot = currentRooms.firstOrNull {
                        it.slot != requestedSlot && it.route.deviceId == configured.route.deviceId
                    }?.slot
                    require(duplicateSlot == null) { "该二维码已经配置为浴室$duplicateSlot" }
                    val updatedRooms = currentRooms.associateBy(ConfiguredShower::slot).toMutableMap()
                        .apply { put(requestedSlot, configured) }
                        .values
                        .sortedBy(ConfiguredShower::slot)
                    if (currentSession != null) {
                        staleWatchCredentialId =
                            app.sessionStore.updateShowersAndRotateCredential(updatedRooms)
                        app.publishWidgetSession(preserveVerifiedStatus = false)
                    } else {
                        pendingRooms.clear()
                        updatedRooms.forEach { pendingRooms[it.slot] = it }
                        _state.update { it.copy(rooms = roomUi(updatedRooms)) }
                    }
                    true
                }
                if (!committed) {
                    fail("登录状态已变化，本次识别结果未保存，请重新确认")
                    return@launch
                }
                staleWatchCredentialId?.let { app.provisioningManager.notifySessionClear(it) }
                _state.update {
                    it.copy(
                        candidateDeviceName = device.deviceName.ifBlank { configured.name },
                        qrValue = "",
                        watchBound = false,
                        statusMessage = "${configured.name}已保存。二维码原链接未被访问。",
                    )
                }
            } catch (exception: IllegalArgumentException) {
                fail(exception.message ?: "二维码内容无效")
            } catch (exception: Throwable) {
                handleFailure(exception)
            } finally {
                endOperation()
                maybeAutoProvision()
            }
        }
    }

    fun removeRoom(slot: Int) {
        if (slot !in 1..2 || _state.value.isBusy || pendingRooms[slot] == null) return
        if (pendingRooms.size <= 1) {
            fail("至少保留一间已配置浴室")
            return
        }
        if (!beginOperation("正在移除浴室$slot…", requireInternet = false)) return
        viewModelScope.launch {
            var staleWatchCredentialId: String? = null
            try {
                app.phoneOperationMutex.withLock {
                    val currentSession = app.sessionStore.snapshotOrNull()
                    val currentRooms = currentSession?.showers ?: pendingRooms.values.toList()
                    val updated = currentRooms.filterNot { it.slot == slot }
                    require(updated.isNotEmpty()) { "至少保留一间已配置浴室" }
                    if (currentSession != null) {
                        staleWatchCredentialId =
                            app.sessionStore.updateShowersAndRotateCredential(updated)
                        app.publishWidgetSession(preserveVerifiedStatus = false)
                    } else {
                        pendingRooms.remove(slot)
                        _state.update { it.copy(rooms = roomUi(updated)) }
                    }
                }
                staleWatchCredentialId?.let { app.provisioningManager.notifySessionClear(it) }
                _state.update {
                    it.copy(
                        watchBound = false,
                        statusMessage = "浴室$slot 已移除；如手表曾绑定，请重新同步。",
                        errorMessage = null,
                    )
                }
            } catch (exception: Throwable) {
                handleFailure(exception)
            } finally {
                endOperation()
                if (app.sessionStore.snapshotOrNull() != null) maybeAutoProvision()
            }
        }
    }

    fun requestOtp() {
        val snapshot = _state.value
        if (snapshot.loggedIn || snapshot.otpCooldownSeconds > 0) return
        if (pendingRooms.isEmpty()) {
            fail("请先识别至少一间浴室")
            return
        }
        if (!PHONE_PATTERN.matches(snapshot.phone)) {
            fail("请输入 11 位中国大陆手机号")
            return
        }
        if (!beginOperation("正在请求短信验证码…", requireInternet = true)) return
        startOtpCooldown()

        viewModelScope.launch {
            try {
                repository.requestOtp(snapshot.phone)
                _state.update {
                    it.copy(statusMessage = "验证码已请求。若未收到，请倒计时结束后手动再试。")
                }
            } catch (exception: Throwable) {
                handleFailure(exception)
            } finally {
                endOperation()
            }
        }
    }

    fun login() {
        val snapshot = _state.value
        val rooms = pendingRooms.values.sortedBy(ConfiguredShower::slot)
        if (snapshot.loggedIn) return
        if (rooms.isEmpty()) {
            fail("请先识别至少一间浴室")
            return
        }
        if (!PHONE_PATTERN.matches(snapshot.phone)) {
            fail("请输入 11 位中国大陆手机号")
            return
        }
        if (!OTP_PATTERN.matches(snapshot.otp)) {
            fail("请输入收到的 6 位短信验证码")
            return
        }
        if (!beginOperation("正在登录官方服务…", requireInternet = true)) return

        viewModelScope.launch {
            try {
                val token = repository.login(snapshot.phone, snapshot.otp)
                require(token.isNotBlank())
                app.phoneOperationMutex.withLock {
                    check(app.sessionStore.snapshotOrNull() == null) { "A session already exists" }
                    app.sessionStore.replace(
                        credentialId = UUID.randomUUID().toString(),
                        token = token,
                        showers = rooms,
                        watchBound = false,
                    )
                }
                app.publishWidgetSession(preserveVerifiedStatus = false)
                _state.update {
                    it.copy(
                        phone = "",
                        otp = "",
                        loggedIn = true,
                        statusMessage = "登录成功。凭据已由手机系统密钥加密保存。",
                    )
                }
            } catch (exception: Throwable) {
                _state.update { it.copy(otp = "") }
                handleFailure(exception)
            } finally {
                endOperation()
                if (app.sessionStore.snapshotOrNull() != null) maybeAutoProvision()
            }
        }
    }

    fun provisionWatch(auto: Boolean = false) {
        if (app.sessionStore.snapshotOrNull() == null) {
            markSessionUnavailable()
            return
        }
        if (!beginOperation(
                if (auto) "正在自动同步手表…" else "正在与手表建立端到端加密连接…",
                requireInternet = false,
            )
        ) return

        viewModelScope.launch {
            var staleCredentialId: String? = null
            try {
                app.phoneOperationMutex.withLock {
                    val session = app.sessionStore.snapshotOrNull()
                        ?: throw SessionStorageException("No active phone session")
                    val tokenCopy = session.token.toByteArray(Charsets.UTF_8)
                    try {
                        val receipt = app.provisioningManager.provision(
                            tokenUtf8 = tokenCopy,
                            config = ProvisioningConfig(
                                credentialId = session.credentialId,
                                devices = session.showers.map { room ->
                                    ProvisioningDevice(
                                        slot = room.slot,
                                        brandId = room.route.brandId,
                                        stadiumId = room.route.stadiumId,
                                        deviceId = room.route.deviceId,
                                        deviceName = room.name,
                                    )
                                },
                            ),
                        )
                        if (
                            app.sessionStore.markWatchBoundIfMatches(
                                session.credentialId,
                                session.showers,
                                bound = true,
                            )
                        ) {
                            app.publishWidgetSession(preserveVerifiedStatus = true)
                            _state.update {
                                it.copy(
                                    watchBound = true,
                                    statusMessage = "${receipt.watchName}已确认安全保存；手机登录继续保留。",
                                )
                            }
                        } else {
                            staleCredentialId = session.credentialId
                            fail("手机会话在同步期间已变化；旧同步结果已作废")
                        }
                    } finally {
                        tokenCopy.fill(0)
                    }
                }
            } catch (exception: Throwable) {
                handleFailure(exception)
            } finally {
                endOperation()
            }
            staleCredentialId?.let { app.provisioningManager.notifySessionClear(it) }
        }
    }

    fun logout() {
        if (!beginOperation("正在退出并清除登录…", requireInternet = false)) return
        viewModelScope.launch {
            var credentialId: String? = null
            try {
                app.phoneOperationMutex.withLock {
                    credentialId = app.sessionStore.snapshotOrNull()?.credentialId
                    app.sessionStore.clear()
                    app.publishWidgetSession(preserveVerifiedStatus = false)
                }
                pendingRooms.clear()
                qrGeneration++
                cooldownJob?.cancel()
                _state.value = SetupUiState(
                    hasInternet = connectivity.hasInternetCapability(),
                    pollingEnabled = pollingSettings.read().enabled,
                    pollingIntervalSeconds = pollingSettings.read().intervalSeconds,
                    backgroundPolling = pollingSettings.read().background,
                    statusMessage = "手机登录已清除；若手表当前在线会尝试通知。离线手表请在表端清除或稍后重新同步。",
                )
            } catch (exception: Throwable) {
                handleFailure(exception)
            } finally {
                endOperation()
            }
            credentialId?.let { app.provisioningManager.notifySessionClear(it) }
        }
    }

    private fun markSessionUnavailable() {
        _state.update {
            it.copy(
                loggedIn = false,
                watchBound = false,
                statusMessage = null,
                errorMessage = "登录信息不可用，请重新短信验证码登录",
            )
        }
    }

    private fun beginOperation(label: String, requireInternet: Boolean): Boolean {
        val snapshot = _state.value
        if (snapshot.isBusy) return false
        if (requireInternet && !connectivity.hasInternetCapability()) {
            fail("手机当前没有可用互联网，请恢复网络后手动重试")
            return false
        }
        _state.update {
            it.copy(
                isBusy = true,
                busyLabel = label,
                statusMessage = null,
                errorMessage = null,
            )
        }
        return true
    }

    private fun endOperation() {
        _state.update { it.copy(isBusy = false, busyLabel = null) }
    }

    private fun startOtpCooldown() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            for (seconds in OTP_COOLDOWN_SECONDS downTo 1) {
                _state.update { it.copy(otpCooldownSeconds = seconds) }
                delay(1_000)
            }
            _state.update { it.copy(otpCooldownSeconds = 0) }
        }
    }

    private fun handleFailure(throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        val message = when (throwable) {
            is AuthenticationRequiredException ->
                "官方公开接口拒绝了本次请求；现有登录未被清除"
            is InvalidInputException -> "输入内容无效，请检查后手动重试"
            is ProtocolException -> "官方服务拒绝请求或响应异常；不会自动重试"
            is ProvisioningException -> throwable.message ?: "手表同步未完成"
            is SessionStorageException -> "无法安全保存手机登录，请检查系统锁屏后重试"
            is SocketTimeoutException -> "网络请求超时；不会自动重试，请手动再试"
            is UnknownHostException -> "无法连接官方服务，请检查网络或 DNS"
            is IOException -> "网络连接失败；不会自动重试，请手动再试"
            else -> "操作未完成；为避免重复请求，不会自动重试"
        }
        fail(message)
    }

    private fun fail(message: String) {
        _state.update { it.copy(errorMessage = message, statusMessage = null) }
    }

    override fun onCleared() {
        cooldownJob?.cancel()
    }

    private companion object {
        val PHONE_PATTERN = Regex("^1[3-9][0-9]{9}$")
        val OTP_PATTERN = Regex("^\\d{6}$")
        const val OTP_COOLDOWN_SECONDS = 60
        const val MAX_QR_LENGTH = 2_048
        const val OFFICIAL_BRAND_ID = 1041L
    }
}

private fun roomUi(showers: Collection<ConfiguredShower>): List<RoomSetupUi> = (1..2).map { slot ->
    RoomSetupUi(
        slot = slot,
        label = "浴室$slot",
        configured = showers.any { it.slot == slot },
    )
}

private fun defaultRoomUi(): List<RoomSetupUi> = roomUi(emptyList())
