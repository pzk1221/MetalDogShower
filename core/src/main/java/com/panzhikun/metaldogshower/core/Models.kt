package com.panzhikun.metaldogshower.core

/** Public metadata returned while resolving a QR alias. */
data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val stadiumId: Long,
    val type: String,
) {
    val isShower: Boolean
        get() = type == TYPE_SHOWER

    companion object {
        const val TYPE_SHOWER = "shower"
    }
}

/**
 * The protected route selected by the user during setup.
 *
 * No real device or stadium identifier is compiled into the core module.
 */
data class DeviceRoute(
    val brandId: Long,
    val stadiumId: Long,
    val deviceId: String,
)

data class ShowerStatus(
    val isOpen: Boolean,
    val remainingSeconds: Int,
)

sealed interface ControlResult {
    /** The switch request completed and the single follow-up status read succeeded. */
    data class Confirmed(val status: ShowerStatus) : ControlResult

    /**
     * The switch POST timed out, so it was not repeated. Exactly one status read was attempted.
     * [observedStatus] is null when that read also failed.
     */
    data class Ambiguous(
        val observedStatus: ShowerStatus?,
        /** The mandatory verification read returned 401 after the POST may have taken effect. */
        val authenticationRequired: Boolean = false,
    ) : ControlResult

    /** The request was blocked locally before any network operation. */
    data class Rejected(
        val reason: ControlRejection,
        val retryAfterMillis: Long,
    ) : ControlResult
}

enum class ControlRejection {
    DEBOUNCED,
    COOLDOWN,
    ALREADY_OPEN,
    ALREADY_CLOSED,
}

enum class RepositoryMode {
    FAKE,
    PRODUCTION,
}
