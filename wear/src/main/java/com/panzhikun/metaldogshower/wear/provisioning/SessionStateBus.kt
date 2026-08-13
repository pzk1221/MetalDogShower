package com.panzhikun.metaldogshower.wear.provisioning

import java.util.WeakHashMap

internal fun interface CredentialClearListener {
    fun onCredentialCleared(credentialId: String)
}

internal fun interface ConfigReplacedListener {
    fun onConfigReplaced(credentialId: String)
}

/** Process-local notification; an absent process observes the cleared store on next launch. */
internal object SessionStateBus {
    private val clearListeners = WeakHashMap<CredentialClearListener, Unit>()
    private val replacementListeners = WeakHashMap<ConfigReplacedListener, Unit>()

    @Synchronized
    fun registerClearListener(listener: CredentialClearListener) {
        clearListeners[listener] = Unit
    }

    @Synchronized
    fun unregisterClearListener(listener: CredentialClearListener) {
        clearListeners.remove(listener)
    }

    @Synchronized
    fun registerReplacementListener(listener: ConfigReplacedListener) {
        replacementListeners[listener] = Unit
    }

    @Synchronized
    fun unregisterReplacementListener(listener: ConfigReplacedListener) {
        replacementListeners.remove(listener)
    }

    fun notifyCredentialCleared(credentialId: String) {
        val snapshot = synchronized(this) { clearListeners.keys.toList() }
        snapshot.forEach { listener ->
            try {
                listener.onCredentialCleared(credentialId)
            } catch (_: Exception) {
                // A process-local UI listener must never break the durable
                // clear performed by the WearableListenerService.
            }
        }
    }

    fun notifyConfigReplaced(credentialId: String) {
        val snapshot = synchronized(this) { replacementListeners.keys.toList() }
        snapshot.forEach { listener ->
            try {
                listener.onConfigReplaced(credentialId)
            } catch (_: Exception) {
                // Durable provisioning succeeded already; listener failures
                // must not suppress its result ACK.
            }
        }
    }
}
