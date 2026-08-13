package com.panzhikun.metaldogshower.wear.provisioning

import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class ProvisioningListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            ProvisioningProtocol.REQUEST_PATH -> handleRequest(messageEvent)
            ProvisioningProtocol.ENVELOPE_PATH -> handleEnvelope(messageEvent)
            ProvisioningProtocol.SESSION_CLEAR_PATH -> handleSessionClear(messageEvent)
        }
    }

    private fun handleSessionClear(event: MessageEvent) {
        if (event.data.size !in 2..MAX_SESSION_CLEAR_BYTES) return
        val message = runCatching { JSONObject(event.data.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        if (message.optInt("version", 0) != ProvisioningProtocol.VERSION) return
        val credentialId = message.optString("credentialId", "")
        if (!ProvisionedConfigParser.isCanonicalCredentialId(credentialId)) return

        // Idempotent compare-and-clear. There is deliberately no ACK, retry,
        // network request, token data, or claim of atomic phone/watch logout.
        if (ProvisionedConfigStore(applicationContext).clearIfCredentialMatches(credentialId)) {
            SessionStateBus.notifyCredentialCleared(credentialId)
        }
    }

    private fun handleRequest(event: MessageEvent) {
        if (event.data.size !in 2..MAX_REQUEST_BYTES) return
        val request = runCatching { JSONObject(event.data.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        if (request.optInt("version", 0) != ProvisioningProtocol.VERSION) return
        val requestId = request.optString("requestId", "")
        if (!validRequestId(requestId)) return
        if (ProvisioningReplayStore(applicationContext).wasRequestConsumed(event.sourceNodeId, requestId)) return

        val publicKey = runCatching { ProvisioningCrypto.publicKeyX509() }.getOrNull() ?: return
        if (publicKey.size !in MIN_PUBLIC_KEY_BYTES..MAX_PUBLIC_KEY_BYTES) {
            publicKey.fill(0)
            return
        }
        val challengeBytes = ByteArray(CHALLENGE_BYTES).also(SecureRandom()::nextBytes)
        val challengeBase64 = Base64.encodeToString(challengeBytes, Base64.NO_WRAP)
        challengeBytes.fill(0)
        if (!ProvisioningSessionStore.begin(event.sourceNodeId, requestId, challengeBase64)) {
            publicKey.fill(0)
            return
        }
        val response = JSONObject()
            .put("version", ProvisioningProtocol.VERSION)
            .put("requestId", requestId)
            .put("publicKey", Base64.encodeToString(publicKey, Base64.NO_WRAP))
            .put("challenge", challengeBase64)
            .toString()
            .toByteArray(Charsets.UTF_8)
        publicKey.fill(0)
        if (!send(event.sourceNodeId, ProvisioningProtocol.PUBLIC_KEY_PATH, response)) {
            ProvisioningSessionStore.cancel(event.sourceNodeId, requestId)
        }
    }

    private fun handleEnvelope(event: MessageEvent) {
        if (event.data.size !in 2..MAX_ENVELOPE_BYTES) return
        val envelope = runCatching { JSONObject(event.data.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        val requestId = envelope.optString("requestId", "")
        val challengeBase64 = envelope.optString("challenge", "")
        if (envelope.optInt("version", 0) != ProvisioningProtocol.VERSION || !validRequestId(requestId)) return
        if (!validChallenge(challengeBase64)) return
        if (!ProvisioningSessionStore.consume(event.sourceNodeId, requestId, challengeBase64)) return

        var encryptedKey = ByteArray(0)
        var iv = ByteArray(0)
        var ciphertext = ByteArray(0)
        var aesKey = ByteArray(0)
        var plaintext = ByteArray(0)
        var storedCredentialId: String? = null
        val stored = try {
            encryptedKey = decode(envelope, "encryptedKey", 1, 512)
            iv = decode(envelope, "iv", 12, 12)
            ciphertext = decode(envelope, "ciphertext", 17, MAX_CIPHERTEXT_BYTES)
            if (!ProvisioningReplayStore(applicationContext).markConsumed(
                    sourceNodeId = event.sourceNodeId,
                    requestId = requestId,
                    encryptedKey = encryptedKey,
                    iv = iv,
                    ciphertext = ciphertext,
                )
            ) {
                throw SecurityException("Replay rejected")
            }
            aesKey = ProvisioningCrypto.decryptEnvelopeKey(encryptedKey)
            plaintext = ProvisioningCrypto.decryptPayload(
                aesKey = aesKey,
                iv = iv,
                ciphertext = ciphertext,
                requestId = requestId,
                challengeBase64 = challengeBase64,
            )
            val store = ProvisionedConfigStore(applicationContext)
            store.save(plaintext).also { saved ->
                if (saved) storedCredentialId = store.currentCredentialIdOrNull()
            }
        } catch (_: Exception) {
            false
        } finally {
            encryptedKey.fill(0)
            iv.fill(0)
            ciphertext.fill(0)
            aesKey.fill(0)
            plaintext.fill(0)
        }

        if (stored && storedCredentialId != null) {
            SessionStateBus.notifyConfigReplaced(requireNotNull(storedCredentialId))
        }

        val result = JSONObject()
            .put("version", ProvisioningProtocol.VERSION)
            .put("requestId", requestId)
            .put("success", stored)
            .apply {
                if (!stored) put("error", "STORE_FAILED")
            }
            .toString()
            .toByteArray(Charsets.UTF_8)
        send(event.sourceNodeId, ProvisioningProtocol.RESULT_PATH, result)
    }

    private fun decode(json: JSONObject, field: String, min: Int, max: Int): ByteArray {
        val encoded = json.optString(field, "")
        require(encoded.isNotEmpty() && encoded.length <= max * 2)
        return Base64.decode(encoded, Base64.NO_WRAP).also { require(it.size in min..max) }
    }

    /** WearableListenerService callbacks use its background looper. Wait for a
     * bounded completion so the service cannot exit before Play services has
     * accepted the public key/result. Never retry here. */
    private fun send(nodeId: String, path: String, data: ByteArray): Boolean = try {
        Tasks.await(
            Wearable.getMessageClient(this).sendMessage(nodeId, path, data),
            SEND_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    } catch (_: Exception) {
        false
    }

    private fun validRequestId(value: String): Boolean =
        value.length in 16..96 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun validChallenge(value: String): Boolean = runCatching {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        try {
            bytes.size == CHALLENGE_BYTES && Base64.encodeToString(bytes, Base64.NO_WRAP) == value
        } finally {
            bytes.fill(0)
        }
    }.getOrDefault(false)

    private companion object {
        const val MAX_CIPHERTEXT_BYTES = 32 * 1_024 + 16
        const val MAX_REQUEST_BYTES = 4 * 1_024
        const val MAX_ENVELOPE_BYTES = 64 * 1_024
        const val MAX_SESSION_CLEAR_BYTES = 1 * 1_024
        const val MIN_PUBLIC_KEY_BYTES = 256
        const val MAX_PUBLIC_KEY_BYTES = 1_024
        const val CHALLENGE_BYTES = 32
        const val SEND_TIMEOUT_SECONDS = 10L
    }
}
