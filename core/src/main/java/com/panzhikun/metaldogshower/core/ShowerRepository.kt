package com.panzhikun.metaldogshower.core

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.ResponseBody
import retrofit2.Response

interface ShowerRepository {
    val mode: RepositoryMode

    suspend fun resolveDevice(alias: String): DeviceInfo

    /** Performs exactly one mark GET followed by exactly one send-code GET. */
    suspend fun requestOtp(phone: String)

    /** Returns the session token. There is intentionally no refresh-token operation. */
    suspend fun login(phone: String, code: String): String

    suspend fun status(route: DeviceRoute): ShowerStatus

    suspend fun control(route: DeviceRoute, desiredOpen: Boolean): ControlResult
}

object ShowerRepositories {
    /** Fake-first is intentional: callers only use the network after explicitly selecting it. */
    fun default(): ShowerRepository = FakeShowerRepository()

    fun production(tokenProvider: TokenProvider): ShowerRepository = RealShowerRepository(
        api = OfficialNetworkFactory.production(tokenProvider),
    )

    internal fun realForTesting(
        baseUrl: HttpUrl,
        tokenProvider: TokenProvider,
        requestGate: RequestGate = RequestGate(),
        connectTimeoutMillis: Long = 1_000L,
        readTimeoutMillis: Long = 1_000L,
        writeTimeoutMillis: Long = 1_000L,
        callTimeoutMillis: Long = 2_000L,
    ): ShowerRepository = RealShowerRepository(
        api = OfficialNetworkFactory.create(
            baseUrl = baseUrl,
            tokenProvider = tokenProvider,
            connectTimeoutMillis = connectTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
            writeTimeoutMillis = writeTimeoutMillis,
            callTimeoutMillis = callTimeoutMillis,
        ),
        requestGate = requestGate,
    )
}

class FakeShowerRepository(
    private val requestGate: RequestGate = RequestGate(),
    initialStatus: ShowerStatus = ShowerStatus(isOpen = false, remainingSeconds = 20 * 60),
) : ShowerRepository {
    override val mode = RepositoryMode.FAKE

    private val stateMutex = Mutex()
    private val fakeStatuses = mutableMapOf<DeviceRoute, ShowerStatus>()
    private val defaultInitialStatus = initialStatus

    override suspend fun resolveDevice(alias: String): DeviceInfo {
        validateIdentifier(alias, "alias")
        return DeviceInfo(
            deviceId = "FAKE-SHOWER",
            deviceName = "模拟淋浴",
            stadiumId = 1L,
            type = DeviceInfo.TYPE_SHOWER,
        )
    }

    override suspend fun requestOtp(phone: String) {
        validatePhone(phone)
    }

    override suspend fun login(phone: String, code: String): String {
        validatePhone(phone)
        validateOtp(code)
        return "fake-token-" + "x".repeat(128)
    }

    override suspend fun status(route: DeviceRoute): ShowerStatus {
        validateRoute(route)
        return stateMutex.withLock { fakeStatuses.getOrPut(route) { defaultInitialStatus } }
    }

    override suspend fun control(route: DeviceRoute, desiredOpen: Boolean): ControlResult {
        validateRoute(route)
        rejectKnownState(route, desiredOpen)?.let { return it }
        return when (val gated = requestGate.run(desiredOpen) {
            // Check again while serialized so a preceding control cannot race into a duplicate.
            rejectKnownState(route, desiredOpen)?.let { return@run it }
            val updated = stateMutex.withLock {
                val current = fakeStatuses.getOrPut(route) { defaultInitialStatus }
                current.copy(isOpen = desiredOpen).also { fakeStatuses[route] = it }
            }
            ControlResult.Confirmed(updated)
        }) {
            is GateResult.Executed -> gated.value
            is GateResult.Rejected -> ControlResult.Rejected(
                reason = gated.reason,
                retryAfterMillis = gated.retryAfterMillis,
            )
        }
    }

    private suspend fun rejectKnownState(
        route: DeviceRoute,
        desiredOpen: Boolean,
    ): ControlResult.Rejected? =
        stateMutex.withLock {
            val current = fakeStatuses.getOrPut(route) { defaultInitialStatus }
            if (current.isOpen == desiredOpen) {
                alreadyInDesiredState(desiredOpen)
            } else {
                null
            }
        }
}

private class RealShowerRepository(
    private val api: OfficialApi,
    private val requestGate: RequestGate = RequestGate(),
) : ShowerRepository {
    override val mode = RepositoryMode.PRODUCTION

    private val statusCacheMutex = Mutex()
    private val statusCache = mutableMapOf<DeviceRoute, ShowerStatus>()
    private val uncertainRoutes = mutableSetOf<DeviceRoute>()

    override suspend fun resolveDevice(alias: String): DeviceInfo {
        validateIdentifier(alias, "alias")
        val dto = api.deviceInfo(alias).bodyOrThrow("device info")
        val deviceId = dto.deviceId?.takeIf(String::isNotBlank)
            ?: throw ProtocolException("device info is missing dev_id")
        val stadiumId = dto.stadiumId?.takeIf { it > 0L }
            ?: throw ProtocolException("device info is missing stadium_id")
        val type = dto.type?.takeIf(String::isNotBlank)
            ?: throw ProtocolException("device info is missing type")
        return DeviceInfo(
            deviceId = deviceId,
            deviceName = dto.deviceName.orEmpty(),
            stadiumId = stadiumId,
            type = type,
        )
    }

    override suspend fun requestOtp(phone: String) {
        validatePhone(phone)
        val mark = api.otpMark(phone)
            .bodyOrThrow("OTP mark")
            .mark
            ?.takeIf(String::isNotBlank)
            ?: throw ProtocolException("OTP mark response is missing mark")
        val key = OtpKeyGenerator.generate(phone, mark)
        api.sendOtp(phone = phone, appId = "null", key = key)
            .discardBodyOrThrow()
    }

    override suspend fun login(phone: String, code: String): String {
        validatePhone(phone)
        validateOtp(code)
        return api.otpLogin(
            OtpLoginRequestDto(
                phone = phone,
                validateCode = code,
            ),
        ).bodyOrThrow("OTP login")
            .token
            ?.takeIf { it.length > OfficialProtocol.MIN_TOKEN_LENGTH_EXCLUSIVE }
            ?: throw ProtocolException("OTP login response contains no usable token")
    }

    override suspend fun status(route: DeviceRoute): ShowerStatus {
        validateRoute(route)
        return fetchStatus(route)
    }

    override suspend fun control(route: DeviceRoute, desiredOpen: Boolean): ControlResult {
        validateRoute(route)
        rejectUncertainRoute(route)?.let { return it }
        rejectKnownState(route, desiredOpen)?.let { return it }
        return when (val gated = requestGate.run(desiredOpen) {
            // Repeat under the control mutex to close the race with an earlier control result.
            rejectUncertainRoute(route)?.let { return@run it }
            rejectKnownState(route, desiredOpen)?.let { return@run it }
            try {
                api.switchShower(
                    SwitchRequestDto(
                        deviceId = route.deviceId,
                        switchValue = if (desiredOpen) 1 else 0,
                    ),
                ).discardBodyOrThrow()
            } catch (cancelled: CancellationException) {
                // Cancellation can race with bytes already leaving the device. Never convert it to
                // a safe rejection; persist the in-process uncertain lock before propagating.
                withContext(NonCancellable) { markRouteUncertain(route) }
                throw cancelled
            } catch (auth: AuthenticationRequiredException) {
                throw auth
            } catch (http: ApiHttpException) {
                if (http.statusCode != 408 && http.statusCode < 500) {
                    // An explicit 4xx rejection (for example 403 or 429) is not replayed and does
                    // not need an extra status GET. Let the UI show the bounded business error.
                    throw http
                }
                return@run observeOnceAfterAmbiguousFailure(route)
            } catch (_: IOException) {
                // Once the call is attempted, every transport failure is ambiguous. Never repeat
                // the POST; observe state exactly once and let the UI present that safe result.
                return@run observeOnceAfterAmbiguousFailure(route)
            }

            // Even after a successful switch response, the mandatory confirmation read can
            // fail. The command has already been accepted at that point, so its outcome must
            // remain ambiguous and the route stays locked until an explicit status read works.
            try {
                ControlResult.Confirmed(fetchStatus(route))
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { markRouteUncertain(route) }
                throw cancelled
            } catch (_: AuthenticationRequiredException) {
                markRouteUncertain(route)
                ControlResult.Ambiguous(observedStatus = null, authenticationRequired = true)
            } catch (_: IOException) {
                markRouteUncertain(route)
                ControlResult.Ambiguous(observedStatus = null)
            }
        }) {
            is GateResult.Executed -> gated.value
            is GateResult.Rejected -> ControlResult.Rejected(
                reason = gated.reason,
                retryAfterMillis = gated.retryAfterMillis,
            )
        }
    }

    private suspend fun fetchStatus(route: DeviceRoute): ShowerStatus {
        val shower = api.showerStatus(
            brandId = route.brandId,
            stadiumId = route.stadiumId,
            deviceId = route.deviceId,
        ).bodyOrThrow("shower status").shower
            ?: throw ProtocolException("shower status response is missing shower")

        val remainingSeconds = shower.remainingSeconds
            ?: throw ProtocolException("shower status response is missing rest_time")
        val openedValue = shower.isOpened
            ?: throw ProtocolException("shower status response is missing is_opened")
        if (openedValue != 0 && openedValue != 1) {
            throw ProtocolException("shower status response contains invalid is_opened")
        }
        val status = ShowerStatus(
            isOpen = openedValue == 1,
            remainingSeconds = remainingSeconds.coerceAtLeast(0),
        )
        statusCacheMutex.withLock {
            statusCache[route] = status
            uncertainRoutes.remove(route)
        }
        return status
    }

    private suspend fun observeOnceAfterAmbiguousFailure(route: DeviceRoute): ControlResult.Ambiguous {
        // The POST may already have taken effect. Lock before the verification GET starts so a
        // cancellation during that GET cannot leave the route looking safe to control again.
        markRouteUncertain(route)
        return try {
            ControlResult.Ambiguous(fetchStatus(route))
        } catch (_: AuthenticationRequiredException) {
            ControlResult.Ambiguous(observedStatus = null, authenticationRequired = true)
        } catch (_: IOException) {
            ControlResult.Ambiguous(observedStatus = null)
        }
    }

    private suspend fun markRouteUncertain(route: DeviceRoute) {
        statusCacheMutex.withLock { uncertainRoutes.add(route) }
    }

    private suspend fun rejectUncertainRoute(route: DeviceRoute): ControlResult.Ambiguous? =
        statusCacheMutex.withLock {
            if (route in uncertainRoutes) ControlResult.Ambiguous(observedStatus = null) else null
        }

    private suspend fun rejectKnownState(
        route: DeviceRoute,
        desiredOpen: Boolean,
    ): ControlResult.Rejected? = statusCacheMutex.withLock {
        statusCache[route]
            ?.takeIf { it.isOpen == desiredOpen }
            ?.let { alreadyInDesiredState(desiredOpen) }
    }
}

private fun alreadyInDesiredState(desiredOpen: Boolean) = ControlResult.Rejected(
    reason = if (desiredOpen) {
        ControlRejection.ALREADY_OPEN
    } else {
        ControlRejection.ALREADY_CLOSED
    },
    retryAfterMillis = 0L,
)

private fun validatePhone(phone: String) {
    if (!PHONE_PATTERN.matches(phone)) {
        throw InvalidInputException("phone must be an 11-digit mainland China mobile number")
    }
}

private fun validateOtp(code: String) {
    if (!OTP_PATTERN.matches(code)) {
        throw InvalidInputException("OTP must contain exactly 6 digits")
    }
}

private fun validateRoute(route: DeviceRoute) {
    if (route.brandId <= 0L) throw InvalidInputException("brandId must be positive")
    if (route.stadiumId <= 0L) throw InvalidInputException("stadiumId must be positive")
    validateIdentifier(route.deviceId, "deviceId")
}

private fun validateIdentifier(value: String, name: String) {
    if (
        value.isBlank() ||
        value.length > OfficialProtocol.MAX_IDENTIFIER_LENGTH ||
        value.any(Char::isISOControl)
    ) {
        throw InvalidInputException("$name is invalid")
    }
}

private fun <T> Response<T>.bodyOrThrow(operation: String): T {
    if (code() == 401) {
        errorBody()?.close()
        throw AuthenticationRequiredException()
    }
    if (!isSuccessful) {
        errorBody()?.close()
        throw ApiHttpException(code())
    }
    return body() ?: throw ProtocolException("$operation response body is empty")
}

private fun Response<ResponseBody>.discardBodyOrThrow() {
    if (code() == 401) {
        errorBody()?.close()
        throw AuthenticationRequiredException()
    }
    if (!isSuccessful) {
        errorBody()?.close()
        throw ApiHttpException(code())
    }
    body()?.close()
}

private val PHONE_PATTERN = Regex("^1[3-9][0-9]{9}$")
private val OTP_PATTERN = Regex("^[0-9]{6}$")
