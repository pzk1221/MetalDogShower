package com.panzhikun.metaldogshower.wear.provisioning

import android.os.SystemClock

internal object ProvisioningSessionStore {
    private var pending: PendingSession? = null

    @Synchronized
    fun begin(sourceNodeId: String, requestId: String, challengeBase64: String): Boolean {
        val current = pending
        if (current != null &&
            current.sourceNodeId == sourceNodeId &&
            current.requestId == requestId
        ) {
            return false
        }
        pending = PendingSession(
            sourceNodeId = sourceNodeId,
            requestId = requestId,
            challengeBase64 = challengeBase64,
            expiresAtElapsedMillis = SystemClock.elapsedRealtime() + SESSION_TTL_MILLIS,
        )
        return true
    }

    /** Consumes the matching session before decryption to reject any replay. */
    @Synchronized
    fun consume(sourceNodeId: String, requestId: String, challengeBase64: String): Boolean {
        val candidate = pending ?: return false
        pending = null
        return candidate.sourceNodeId == sourceNodeId &&
            candidate.requestId == requestId &&
            candidate.challengeBase64 == challengeBase64 &&
            candidate.expiresAtElapsedMillis >= SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun cancel(sourceNodeId: String, requestId: String) {
        val candidate = pending ?: return
        if (candidate.sourceNodeId == sourceNodeId && candidate.requestId == requestId) {
            pending = null
        }
    }

    private data class PendingSession(
        val sourceNodeId: String,
        val requestId: String,
        val challengeBase64: String,
        val expiresAtElapsedMillis: Long,
    )

    private const val SESSION_TTL_MILLIS = 2 * 60 * 1_000L
}
