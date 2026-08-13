package com.panzhikun.metaldogshower.session

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.panzhikun.metaldogshower.MetalDogApplication
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Receives a credential-scoped, secret-free 401 notice from the same-signed watch app. */
class WearSessionInvalidationService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != SESSION_INVALID_PATH || event.data.size !in 1..MAX_MESSAGE_BYTES) return
        val credentialId = runCatching {
            val json = JSONObject(String(event.data, StandardCharsets.UTF_8))
            require(json.length() == 2)
            require(json.getInt("version") == VERSION)
            val raw = json.getString("credentialId")
            val parsed = UUID.fromString(raw)
            require(parsed.toString() == raw)
            raw
        }.getOrNull() ?: return

        val app = application as? MetalDogApplication ?: return
        // Only an exact generation match may clear. A delayed 401 from the previous login cannot
        // touch a newly logged-in credential. WearableListenerService invokes this callback on a
        // background thread; keeping the callback alive until the mutex-protected invalidation
        // finishes prevents the service lifecycle from cancelling or dropping the message.
        runBlocking {
            invalidateCredentialUnderLock(
                mutex = app.phoneOperationMutex,
                clearIfMatches = { app.sessionStore.clearIfCredentialMatches(credentialId) },
                publishClearedSession = {
                    app.publishWidgetSession(preserveVerifiedStatus = false)
                },
            )
        }
    }

    private companion object {
        const val SESSION_INVALID_PATH = "/session/invalid"
        const val MAX_MESSAGE_BYTES = 1_024
        const val VERSION = 1
    }
}

/** Serializes a watch-originated invalidation with every phone GET/control/configuration change. */
internal suspend fun invalidateCredentialUnderLock(
    mutex: Mutex,
    clearIfMatches: () -> Boolean,
    publishClearedSession: () -> Unit,
): Boolean = mutex.withLock {
    val cleared = clearIfMatches()
    if (cleared) publishClearedSession()
    cleared
}
