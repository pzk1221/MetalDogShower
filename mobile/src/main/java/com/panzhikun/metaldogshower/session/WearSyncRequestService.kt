package com.panzhikun.metaldogshower.session

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.panzhikun.metaldogshower.MetalDogApplication
import com.panzhikun.metaldogshower.PollingSettingsStore
import com.panzhikun.metaldogshower.provision.ProvisioningConfig
import com.panzhikun.metaldogshower.provision.ProvisioningDevice
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Handles a secret-free request from the same-signed Wear app when its local binding is missing.
 * The response reuses the existing one-shot encrypted provisioning protocol.
 */
class WearSyncRequestService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != SYNC_REQUEST_PATH || event.data.size !in 2..MAX_MESSAGE_BYTES) return
        val valid = runCatching {
            val json = JSONObject(event.data.toString(Charsets.UTF_8))
            json.length() == 1 && json.getInt("version") == VERSION
        }.getOrDefault(false)
        if (!valid) return

        val app = application as? MetalDogApplication ?: return
        runBlocking {
            if (!app.phoneOperationMutex.tryLock()) return@runBlocking
            try {
                val session = app.sessionStore.snapshotOrNull() ?: return@runBlocking
                if (!PollingSettingsStore(app).claimWatchSyncRequest(session.credentialId, event.sourceNodeId)) {
                    return@runBlocking
                }
                val tokenCopy = session.token.toByteArray(Charsets.UTF_8)
                try {
                    app.provisioningManager.provision(
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
                        targetNodeId = event.sourceNodeId,
                    )
                    if (
                        app.sessionStore.markWatchBoundIfMatches(
                            session.credentialId,
                            session.showers,
                            bound = true,
                        )
                    ) {
                        app.publishWidgetSession(preserveVerifiedStatus = true)
                    }
                } finally {
                    tokenCopy.fill(0)
                }
            } catch (_: Exception) {
                // Best effort. The watch can request again after the bounded cooldown.
            } finally {
                app.phoneOperationMutex.unlock()
            }
        }
    }

    private companion object {
        const val SYNC_REQUEST_PATH = "/session/sync-request"
        const val VERSION = 1
        const val MAX_MESSAGE_BYTES = 1_024
    }
}
