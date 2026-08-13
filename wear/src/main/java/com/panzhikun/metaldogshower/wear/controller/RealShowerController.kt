package com.panzhikun.metaldogshower.wear.controller

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.panzhikun.metaldogshower.core.ApiHttpException
import com.panzhikun.metaldogshower.core.AuthenticationRequiredException
import com.panzhikun.metaldogshower.core.ControlRejection
import com.panzhikun.metaldogshower.core.ControlResult
import com.panzhikun.metaldogshower.core.DeviceRoute
import com.panzhikun.metaldogshower.core.ProtocolException
import com.panzhikun.metaldogshower.core.ShowerRepositories
import com.panzhikun.metaldogshower.core.ShowerRepository
import com.panzhikun.metaldogshower.core.TokenProvider
import com.panzhikun.metaldogshower.wear.provisioning.CredentialClearListener
import com.panzhikun.metaldogshower.wear.provisioning.ConfigReplacedListener
import com.panzhikun.metaldogshower.wear.provisioning.ProvisionedConfigParser
import com.panzhikun.metaldogshower.wear.provisioning.ProvisionedConfigStore
import com.panzhikun.metaldogshower.wear.provisioning.SessionStateBus
import com.panzhikun.metaldogshower.wear.provisioning.SessionInvalidationNotifier
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** Direct Watch-to-official-service controller with two explicit route slots. */
internal class RealShowerController(context: Context) :
    ShowerController,
    AutoCloseable,
    CredentialClearListener,
    ConfigReplacedListener {

    private val appContext = context.applicationContext
    private val configStore = ProvisionedConfigStore(appContext)
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val operationMutex = Mutex()
    private val tokenVault = WipeableTokenVault()
    private val sessionEpoch = AtomicLong(0L)
    private val sessionInvalidationNotifier by lazy(LazyThreadSafetyMode.NONE) {
        SessionInvalidationNotifier(appContext)
    }

    private var repository: ShowerRepository? = null
    private val routes = mutableMapOf<BathroomSlot, DeviceRoute>()
    private val aliases = mutableMapOf<BathroomSlot, String>()
    private var configLoaded = false
    private var currentCredentialId: String? = null
    private var currentConfigRevision = 0L
    private var pendingReplacementCredentialId: String? = null

    private val mutableBindings = MutableStateFlow(initialBindings())
    private val mutableSelectedSlot = MutableStateFlow<BathroomSlot?>(null)
    private val mutableStatus = MutableStateFlow(
        ShowerStatus(
            deviceAlias = if (configStore.hasConfig()) "正在读取绑定" else "尚未绑定",
            remainingSeconds = 0,
            isOpen = false,
            mode = RunMode.REAL,
            authenticationRequired = !configStore.hasConfig(),
            isStateKnown = false,
            hasInternetCapability = hasInternetCapability(),
        ),
    )

    override val bindings: StateFlow<List<ShowerBinding>> = mutableBindings.asStateFlow()
    override val selectedSlot: StateFlow<BathroomSlot?> = mutableSelectedSlot.asStateFlow()
    override val status: StateFlow<ShowerStatus> = mutableStatus.asStateFlow()

    init {
        SessionStateBus.registerClearListener(this)
        SessionStateBus.registerReplacementListener(this)
    }

    @Synchronized
    override fun selectSlot(slot: BathroomSlot): Boolean {
        if (operationMutex.isLocked) return false
        val binding = mutableBindings.value.first { it.slot == slot }
        if (binding.state != BindingState.BOUND) return false

        mutableSelectedSlot.value = slot
        mutableStatus.value = ShowerStatus(
            deviceAlias = binding.deviceAlias ?: slot.displayName,
            remainingSeconds = 0,
            isOpen = false,
            mode = RunMode.REAL,
            revision = mutableStatus.value.revision + 1,
            authenticationRequired = false,
            isStateKnown = false,
            hasInternetCapability = hasInternetCapability(),
        )
        return true
    }

    @Synchronized
    override fun clearSelection() {
        // A Tile/app launch must remove the remembered route even if an older
        // read is finishing. Any late result may update display-only state, but
        // with no selected slot it can never expose a control action.
        mutableSelectedSlot.value = null
        mutableStatus.value = mutableStatus.value.copy(
            deviceAlias = if (configLoaded) "请选择浴室" else mutableStatus.value.deviceAlias,
            remainingSeconds = 0,
            isOpen = false,
            isStateKnown = false,
            revision = mutableStatus.value.revision + 1,
        )
    }

    override suspend fun loadLocalBinding(): ControllerResult {
        if (!operationMutex.tryLock()) return ControllerResult.Busy
        return try {
            if (ensureSession()) ControllerResult.Ready else ControllerResult.Unauthorized
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            resetSessionMemory(showLoading = false)
            markAuthenticationRequired("绑定读取失败，请重新同步")
            ControllerResult.Unauthorized
        } finally {
            operationMutex.unlock()
            applyPendingReplacementIfIdle()
        }
    }

    override suspend fun refresh(): ControllerResult {
        if (!operationMutex.tryLock()) return ControllerResult.Busy
        var operationSession: ActiveRouteSession? = null
        return try {
            invalidateKnownState()
            if (!ensureSession()) return ControllerResult.Unauthorized
            val slot = mutableSelectedSlot.value
                ?: return ControllerResult.Rejected("请先选择浴室1或浴室2")
            val active = activeRouteSession(slot)
                ?: return ControllerResult.Rejected("${slot.displayName}尚未绑定")
            operationSession = active
            if (!isSameSession(active)) return invalidatedSessionResult()
            // Wear OS Bluetooth proxy and some Samsung Wi-Fi states can report no validated
            // capability while direct HTTPS still works. Let the bounded HTTPS call decide.
            updateNetwork(hasInternetCapability())
            val epoch = active.sessionEpoch
            val result = active.repository.status(active.route)
            if (!isSameSession(active)) return invalidatedSessionResult()
            val updated = updateFromCore(result, slot, epoch)
                ?: return invalidatedSessionResult()
            if (!isSameSession(active)) return invalidatedSessionResult()
            ControllerResult.Confirmed(updated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val active = operationSession
            if (active != null && !isSameSession(active)) {
                reloadLatestSessionIfNeeded()
                invalidatedSessionResult()
            } else {
                invalidateKnownState()
                mapFailure(failure, active?.credentialId)
            }
        } finally {
            operationMutex.unlock()
            applyPendingReplacementIfIdle()
        }
    }

    override suspend fun setOpen(open: Boolean): ControllerResult {
        if (!operationMutex.tryLock()) return ControllerResult.Busy
        var operationSession: ActiveRouteSession? = null
        return try {
            if (!ensureSession()) return ControllerResult.Unauthorized
            val slot = mutableSelectedSlot.value
                ?: return ControllerResult.Rejected("请先选择浴室")
            val active = activeRouteSession(slot)
                ?: return ControllerResult.Rejected("${slot.displayName}尚未绑定")
            operationSession = active
            if (!isSameSession(active)) return invalidatedSessionResult()
            if (!mutableStatus.value.isStateKnown) {
                return ControllerResult.Rejected("请先刷新并确认${slot.displayName}状态")
            }
            updateNetwork(hasInternetCapability())
            val epoch = active.sessionEpoch

            // Phone and watch can control the same shower. Never trust the
            // display cache as the final precondition for a switch: fetch this
            // exact route immediately before considering a POST.
            val freshStatus = active.repository.status(active.route)
            updateFromCore(freshStatus, slot, epoch)
                ?: return invalidatedSessionResult()
            if (!isStillSelectedRoute(slot, active, epoch)) {
                return if (!isSameSession(active)) {
                    invalidatedSessionResult()
                } else {
                    ControllerResult.Rejected("浴室选择已变化；未发送控制请求")
                }
            }
            if (freshStatus.isOpen == open) {
                return ControllerResult.Rejected(
                    if (open) {
                        "刚刚刷新确认：当前已经开启，未发送控制请求"
                    } else {
                        "刚刚刷新确认：当前已经关闭，未发送控制请求"
                    },
                )
            }

            when (val result = active.repository.control(active.route, open)) {
                is ControlResult.Confirmed -> {
                    if (!isSameSession(active)) {
                        return ControllerResult.Rejected(
                            if (open) {
                                "开启结果已确认，但绑定已更新；请重新选择并刷新"
                            } else {
                                "关闭结果已确认，但绑定已更新；请重新选择并刷新"
                            },
                        )
                    }
                    val updated = updateFromCore(result.status, slot, epoch)
                        ?: return invalidatedSessionResult()
                    if (!isSameSession(active)) return invalidatedSessionResult()
                    ControllerResult.Confirmed(updated)
                }

                is ControlResult.Ambiguous -> {
                    if (!isSameSession(active)) {
                        return ambiguousAfterSessionChange()
                    }
                    val observed = result.observedStatus?.let {
                        updateFromCore(it, slot, epoch) ?: return ambiguousAfterSessionChange()
                    }
                    if (!isSameSession(active)) return ambiguousAfterSessionChange()
                    var authenticationRequired = false
                    if (result.authenticationRequired) {
                        // The switch may already have reached the device, while the
                        // controller's one allowed verification GET returned 401.
                        // Remove the expired credential but preserve that ambiguity
                        // in the result so the UI cannot offer another switch.
                        val expiredCredentialId = clearCredentialAfterAuthenticationFailure(
                            expectedCredentialId = active.credentialId,
                            alias = "登录已失效",
                        )
                        if (expiredCredentialId != null) {
                            sessionInvalidationNotifier.notifyInvalid(expiredCredentialId)
                        }
                        authenticationRequired = expiredCredentialId != null || !configStore.hasConfig()
                    } else if (observed == null) {
                        mutableStatus.value = mutableStatus.value.copy(
                            isStateKnown = false,
                            revision = mutableStatus.value.revision + 1,
                        )
                    }
                    ControllerResult.Ambiguous(
                        observedStatus = observed,
                        verificationAttempted = true,
                        authenticationRequired = authenticationRequired,
                        userMessage = if (result.authenticationRequired && authenticationRequired) {
                            "刚才结果不明，请现场确认；重新登录后先刷新，勿重复开关"
                        } else if (result.authenticationRequired) {
                            "刚才结果不明，请现场确认；绑定已更新，请重新选择并先刷新，勿重复开关"
                        } else if (observed == null) {
                            "结果无法确认，请勿重复开关；仅可手动刷新状态"
                        } else if (observed.isOpen == open) {
                            if (open) "已通过状态查询确认开启" else "已通过状态查询确认关闭"
                        } else {
                            "已核对状态，设备未切换；不会重复发送"
                        },
                    )
                }

                is ControlResult.Rejected -> {
                    if (!isSameSession(active)) {
                        invalidatedSessionResult()
                    } else {
                        mapLocalRejection(result)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val active = operationSession
            if (active != null && !isSameSession(active)) {
                reloadLatestSessionIfNeeded()
                invalidatedSessionResult()
            } else {
                invalidateKnownState()
                mapFailure(failure, active?.credentialId)
            }
        } finally {
            operationMutex.unlock()
            applyPendingReplacementIfIdle()
        }
    }

    @Synchronized
    private fun ensureSession(): Boolean {
        if (configLoaded && routes.isNotEmpty()) {
            val storedCredentialId = configStore.currentCredentialIdOrNull()
            val storedRevision = configStore.currentConfigRevision()
            if (
                storedCredentialId == currentCredentialId &&
                storedRevision == currentConfigRevision
            ) {
                return true
            }
            pendingReplacementCredentialId = null
            resetSessionMemory(
                showLoading = storedCredentialId != null && configStore.hasConfig(),
            )
        }
        val loadedRevision = configStore.currentConfigRevision()
        val hadStoredConfig = configStore.hasConfig()
        val plaintext = try {
            configStore.load()
        } catch (_: Exception) {
            null
        } ?: run {
            if (hadStoredConfig) {
                // A stale/corrupt ciphertext previously left both buttons in LOADING forever and
                // also blocked the secret-free phone sync request because hasConfig() stayed true.
                configStore.clear()
                resetSessionMemory(showLoading = false)
                markAuthenticationRequired("绑定需要重新同步")
            } else {
                resetSessionMemory(showLoading = false)
                markAuthenticationRequired("尚未绑定")
            }
            return false
        }

        return try {
            val legacyCredentialId = configStore.currentCredentialIdOrNull()
                ?: UUID.randomUUID().toString()
            val config = ProvisionedConfigParser.parse(plaintext, legacyCredentialId)
            if (!configStore.rememberCredentialId(config.credentialId)) {
                clearCredentials(clearStore = true, alias = "绑定数据不一致，请重新同步")
                return false
            }

            tokenVault.replace(config.token.toByteArray(StandardCharsets.UTF_8))
            config.devices.forEach { device ->
                val slot = requireNotNull(BathroomSlot.fromNumber(device.slot))
                routes[slot] = DeviceRoute(
                    brandId = device.brandId,
                    stadiumId = device.stadiumId,
                    deviceId = device.deviceId,
                )
                aliases[slot] = device.deviceName.take(128).ifBlank { slot.displayName }
            }
            // One route-aware repository shares a single bounded HTTP client,
            // status cache and request gate across both slots on a low-RAM watch.
            repository = ShowerRepositories.production(
                TokenProvider { tokenVault.readStringOrNull() },
            )
            currentCredentialId = config.credentialId
            currentConfigRevision = loadedRevision
            configLoaded = true
            sessionEpoch.incrementAndGet()
            mutableBindings.value = BathroomSlot.entries.map { slot ->
                ShowerBinding(
                    slot = slot,
                    deviceAlias = aliases[slot],
                    state = if (slot in routes) BindingState.BOUND else BindingState.UNBOUND,
                )
            }
            mutableSelectedSlot.value = null
            mutableStatus.value = mutableStatus.value.copy(
                deviceAlias = "请选择浴室",
                remainingSeconds = 0,
                isOpen = false,
                authenticationRequired = false,
                isStateKnown = false,
                revision = mutableStatus.value.revision + 1,
            )
            true
        } catch (_: Exception) {
            clearCredentials(clearStore = true, alias = "绑定数据无效")
            false
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    private fun updateFromCore(
        value: com.panzhikun.metaldogshower.core.ShowerStatus,
        slot: BathroomSlot,
        expectedEpoch: Long,
    ): ShowerStatus? {
        if (expectedEpoch != sessionEpoch.get()) return null
        val updated = mutableStatus.value.copy(
            deviceAlias = aliases[slot] ?: slot.displayName,
            remainingSeconds = value.remainingSeconds.coerceAtLeast(0),
            isOpen = value.isOpen,
            authenticationRequired = false,
            isStateKnown = true,
            hasInternetCapability = true,
            revision = mutableStatus.value.revision + 1,
        )
        mutableStatus.value = updated
        return updated
    }

    @Synchronized
    private fun activeRouteSession(slot: BathroomSlot): ActiveRouteSession? {
        val activeRoute = routes[slot] ?: return null
        val activeRepository = repository ?: return null
        val credentialId = currentCredentialId ?: return null
        return ActiveRouteSession(
            route = activeRoute,
            repository = activeRepository,
            sessionEpoch = sessionEpoch.get(),
            credentialId = credentialId,
            configRevision = currentConfigRevision,
        )
    }

    @Synchronized
    private fun isSameSession(active: ActiveRouteSession): Boolean =
        active.sessionEpoch == sessionEpoch.get() &&
            active.credentialId == currentCredentialId &&
            active.credentialId == configStore.currentCredentialIdOrNull() &&
            active.configRevision == currentConfigRevision &&
            active.configRevision == configStore.currentConfigRevision() &&
            pendingReplacementCredentialId == null

    @Synchronized
    private fun isStillSelectedRoute(
        slot: BathroomSlot,
        active: ActiveRouteSession,
        expectedEpoch: Long,
    ): Boolean =
        expectedEpoch == sessionEpoch.get() &&
            mutableSelectedSlot.value == slot &&
            routes[slot] == active.route &&
            repository === active.repository &&
            currentCredentialId == active.credentialId &&
            configStore.currentCredentialIdOrNull() == active.credentialId &&
            currentConfigRevision == active.configRevision &&
            configStore.currentConfigRevision() == active.configRevision &&
            pendingReplacementCredentialId == null

    private fun mapLocalRejection(result: ControlResult.Rejected): ControllerResult =
        when (result.reason) {
            ControlRejection.DEBOUNCED -> ControllerResult.Cooldown(result.retryAfterMillis)
            ControlRejection.COOLDOWN -> ControllerResult.Cooldown(result.retryAfterMillis)
            ControlRejection.ALREADY_OPEN -> ControllerResult.Rejected("当前已经开启")
            ControlRejection.ALREADY_CLOSED -> ControllerResult.Rejected("当前已经关闭")
        }

    private fun invalidatedSessionResult(): ControllerResult =
        if (
            configStore.hasConfig() &&
            configStore.currentCredentialIdOrNull() != null
        ) {
            ControllerResult.Rejected("绑定已更新，请重新选择浴室")
        } else {
            ControllerResult.Unauthorized
        }

    private fun ambiguousAfterSessionChange(): ControllerResult.Ambiguous {
        val authenticationRequired = !configStore.hasConfig()
        return ControllerResult.Ambiguous(
            observedStatus = null,
            verificationAttempted = true,
            authenticationRequired = authenticationRequired,
            userMessage = if (authenticationRequired) {
                "刚才结果不明，请现场确认；重新登录后先刷新，勿重复开关"
            } else {
                "刚才控制结果不明，请现场确认；绑定已更新，请重新选择并先刷新，勿重复开关"
            },
        )
    }

    private suspend fun mapFailure(
        failure: Throwable,
        expectedCredentialId: String?,
    ): ControllerResult = when (failure) {
        is AuthenticationRequiredException -> {
            val expiredCredentialId = clearCredentialAfterAuthenticationFailure(
                expectedCredentialId = expectedCredentialId,
                alias = "登录已失效",
            )
            if (expiredCredentialId != null) {
                sessionInvalidationNotifier.notifyInvalid(expiredCredentialId)
                ControllerResult.Unauthorized
            } else {
                invalidatedSessionResult()
            }
        }

        is ApiHttpException -> if (failure.statusCode == 401) {
            val expiredCredentialId = clearCredentialAfterAuthenticationFailure(
                expectedCredentialId = expectedCredentialId,
                alias = "登录已失效",
            )
            if (expiredCredentialId != null) {
                sessionInvalidationNotifier.notifyInvalid(expiredCredentialId)
                ControllerResult.Unauthorized
            } else {
                invalidatedSessionResult()
            }
        } else {
            ControllerResult.Rejected(
                when (failure.statusCode) {
                    403 -> "会员状态异常或当前无权限"
                    429 -> "请求过于频繁，请稍后手动重试"
                    in 500..599 -> "服务器暂时不可用"
                    else -> "官方服务暂时无法完成请求"
                },
            )
        }

        is ProtocolException -> ControllerResult.Rejected("官方服务响应异常")
        is IOException -> {
            updateNetwork(hasInternetCapability())
            ControllerResult.Rejected("网络连接失败；不会自动重试")
        }

        else -> ControllerResult.Rejected("操作未完成；不会自动重试")
    }

    @Synchronized
    private fun clearCredentialAfterAuthenticationFailure(
        expectedCredentialId: String?,
        alias: String,
    ): String? {
        if (
            expectedCredentialId == null ||
            currentCredentialId != expectedCredentialId ||
            !configStore.clearIfCredentialMatches(expectedCredentialId)
        ) {
            reloadLatestSessionIfNeeded()
            return null
        }
        resetSessionMemory(showLoading = false)
        markAuthenticationRequired(alias)
        return expectedCredentialId
    }

    @Synchronized
    private fun reloadLatestSessionIfNeeded() {
        val storedCredentialId = configStore.currentCredentialIdOrNull()
        val storedRevision = configStore.currentConfigRevision()
        if (storedCredentialId != null && configStore.hasConfig()) {
            if (
                configLoaded &&
                currentCredentialId == storedCredentialId &&
                currentConfigRevision == storedRevision
            ) {
                return
            }
            resetSessionMemory(showLoading = true)
            ensureSession()
            return
        }
        if (configLoaded || currentCredentialId != null || !mutableStatus.value.authenticationRequired) {
            resetSessionMemory(showLoading = false)
            markAuthenticationRequired("需重新绑定")
        }
    }

    private fun updateNetwork(available: Boolean) {
        val current = mutableStatus.value
        if (current.hasInternetCapability == available) return
        mutableStatus.value = current.copy(
            hasInternetCapability = available,
            revision = current.revision + 1,
        )
    }

    private fun invalidateKnownState() {
        if (mutableStatus.value.isStateKnown) {
            mutableStatus.value = mutableStatus.value.copy(
                isStateKnown = false,
                revision = mutableStatus.value.revision + 1,
            )
        }
    }

    /** Samsung's captive-portal validation can be a false negative on a usable
     * Wi-Fi/Bluetooth-proxy connection. Only gate on an active network that
     * advertises INTERNET; the one explicit HTTPS operation is authoritative. */
    private fun hasInternetCapability(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun initialBindings(): List<ShowerBinding> = BathroomSlot.entries.map { slot ->
        ShowerBinding(
            slot = slot,
            deviceAlias = null,
            state = if (configStore.hasConfig()) BindingState.LOADING else BindingState.UNBOUND,
        )
    }

    private fun markAuthenticationRequired(alias: String) {
        mutableSelectedSlot.value = null
        mutableStatus.value = ShowerStatus(
            deviceAlias = alias,
            remainingSeconds = 0,
            isOpen = false,
            mode = RunMode.REAL,
            revision = mutableStatus.value.revision + 1,
            authenticationRequired = true,
            isStateKnown = false,
            hasInternetCapability = hasInternetCapability(),
        )
    }

    @Synchronized
    private fun resetSessionMemory(showLoading: Boolean) {
        repository = null
        routes.clear()
        aliases.clear()
        tokenVault.clear()
        configLoaded = false
        currentCredentialId = null
        currentConfigRevision = 0L
        mutableSelectedSlot.value = null
        sessionEpoch.incrementAndGet()
        mutableBindings.value = BathroomSlot.entries.map { slot ->
            ShowerBinding(
                slot = slot,
                deviceAlias = null,
                state = if (showLoading) BindingState.LOADING else BindingState.UNBOUND,
            )
        }
    }

    @Synchronized
    private fun clearCredentials(clearStore: Boolean, alias: String): String? {
        val credentialId = currentCredentialId ?: configStore.currentCredentialIdOrNull()
        resetSessionMemory(showLoading = false)
        if (clearStore) configStore.clear()
        markAuthenticationRequired(alias)
        return credentialId
    }

    @Synchronized
    override fun onCredentialCleared(credentialId: String) {
        // The store performed the atomic credentialId match before notifying.
        if (currentCredentialId != null && currentCredentialId != credentialId) return
        clearCredentials(clearStore = false, alias = "需重新绑定")
    }

    override fun onConfigReplaced(credentialId: String) {
        synchronized(this) {
            // Record before trying the operation gate so a finishing operation
            // cannot miss a replacement between its final check and unlock.
            pendingReplacementCredentialId = credentialId
        }
        applyPendingReplacementIfIdle()
    }

    private fun applyPendingReplacementIfIdle() {
        if (!operationMutex.tryLock()) return
        try {
            applyPendingReplacementWhileOperationLocked()
        } finally {
            operationMutex.unlock()
        }
    }

    @Synchronized
    private fun applyPendingReplacementWhileOperationLocked() {
        val pending = pendingReplacementCredentialId ?: return
        val storedCredentialId = configStore.currentCredentialIdOrNull()
        pendingReplacementCredentialId = null
        if (storedCredentialId == null || !configStore.hasConfig()) return
        // A newer envelope can overtake an older process-local callback; always
        // load the durable latest value, never the callback payload itself.
        if (storedCredentialId != pending) {
            pendingReplacementCredentialId = storedCredentialId
        }
        resetSessionMemory(showLoading = true)
        ensureSession()
        pendingReplacementCredentialId = null
    }

    override fun close() {
        SessionStateBus.unregisterClearListener(this)
        SessionStateBus.unregisterReplacementListener(this)
        resetSessionMemory(showLoading = configStore.hasConfig())
    }
}

private data class ActiveRouteSession(
    val route: DeviceRoute,
    val repository: ShowerRepository,
    val sessionEpoch: Long,
    val credentialId: String,
    val configRevision: Long,
)

private class WipeableTokenVault {
    private var bytes: ByteArray? = null

    @Synchronized
    fun replace(value: ByteArray) {
        clearLocked()
        bytes = value
    }

    @Synchronized
    fun readStringOrNull(): String? = bytes?.let { String(it, StandardCharsets.UTF_8) }

    @Synchronized
    fun clear() = clearLocked()

    private fun clearLocked() {
        bytes?.fill(0)
        bytes = null
    }
}
