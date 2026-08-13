package com.panzhikun.metaldogshower.wear.provisioning

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.Wearable
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Best-effort Watch-to-phone 401 notice.
 *
 * The caller clears local credentials before invoking this. Each currently
 * connected node receives at most one Message API send attempt: no token, ACK,
 * retry, background service, or claim that logout is atomic across devices.
 */
internal class SessionInvalidationNotifier(context: Context) {
    private val nodeClient = Wearable.getNodeClient(context.applicationContext)
    private val messageClient = Wearable.getMessageClient(context.applicationContext)

    suspend fun notifyInvalid(credentialId: String) {
        if (!ProvisionedConfigParser.isCanonicalCredentialId(credentialId)) return
        val payload = JSONObject()
            .put("version", ProvisioningProtocol.VERSION)
            .put("credentialId", credentialId)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_MESSAGE_BYTES) return

        // The payload is deliberately non-sensitive and remains unchanged after
        // sendMessage is called: a timed-out Play-services Task may still finish
        // after this coroutine returns.
        withTimeoutOrNull(SEND_BUDGET_MILLIS) {
            val nodes = try {
                nodeClient.connectedNodes.awaitOnce()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withTimeoutOrNull
            }
            nodes.forEach { node ->
                try {
                    messageClient.sendMessage(
                        node.id,
                        ProvisioningProtocol.SESSION_INVALID_PATH,
                        payload,
                    ).awaitOnce()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // One attempt only. Continue to another already-connected
                    // phone node, but never retry this node.
                }
            }
        }
    }

    private companion object {
        const val MAX_MESSAGE_BYTES = 1_024
        const val SEND_BUDGET_MILLIS = 3_000L
    }
}

private suspend fun <T> Task<T>.awaitOnce(): T =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { completed ->
            if (!continuation.isActive) return@addOnCompleteListener
            when {
                completed.isSuccessful -> continuation.resumeWith(Result.success(completed.result))
                completed.isCanceled -> continuation.cancel()
                else -> continuation.resumeWithException(
                    completed.exception ?: IllegalStateException("Wear message task failed"),
                )
            }
        }
    }
