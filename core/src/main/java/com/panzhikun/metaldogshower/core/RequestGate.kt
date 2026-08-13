package com.panzhikun.metaldogshower.core

import kotlinx.coroutines.sync.Mutex

fun interface MonotonicClock {
    fun nowMillis(): Long
}

object SystemMonotonicClock : MonotonicClock {
    override fun nowMillis(): Long = System.nanoTime() / 1_000_000L
}

sealed interface GateResult<out T> {
    data class Executed<T>(val value: T) : GateResult<T>

    data class Rejected(
        val reason: ControlRejection,
        val retryAfterMillis: Long,
    ) : GateResult<Nothing>
}

/**
 * Serializes control operations and rejects accidental repeat taps locally.
 *
 * The request timestamp is captured before waiting for the mutex. This means a second tap made
 * while the first network operation is in flight is still rejected after it reaches the lock.
 */
class RequestGate(
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
) {
    private val mutex = Mutex()
    private var lastAcceptedAtMillis: Long? = null
    private var lastDesiredOpen: Boolean? = null

    init {
        require(debounceMillis >= 0) { "debounceMillis must be non-negative" }
        require(cooldownMillis >= debounceMillis) {
            "cooldownMillis must be greater than or equal to debounceMillis"
        }
    }

    suspend fun <T> run(desiredOpen: Boolean, block: suspend () -> T): GateResult<T> {
        val requestedAtMillis = clock.nowMillis()
        mutex.lock()
        try {
            val lastAcceptedAt = lastAcceptedAtMillis
            if (lastAcceptedAt != null) {
                val elapsed = (requestedAtMillis - lastAcceptedAt).coerceAtLeast(0L)
                if (lastDesiredOpen == desiredOpen && elapsed < debounceMillis) {
                    return GateResult.Rejected(
                        reason = ControlRejection.DEBOUNCED,
                        retryAfterMillis = debounceMillis - elapsed,
                    )
                }
                if (elapsed < cooldownMillis) {
                    return GateResult.Rejected(
                        reason = ControlRejection.COOLDOWN,
                        retryAfterMillis = cooldownMillis - elapsed,
                    )
                }
            }

            lastAcceptedAtMillis = requestedAtMillis
            lastDesiredOpen = desiredOpen
            return GateResult.Executed(block())
        } finally {
            mutex.unlock()
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 2_000L
        const val DEFAULT_COOLDOWN_MILLIS = 5_000L
    }
}

