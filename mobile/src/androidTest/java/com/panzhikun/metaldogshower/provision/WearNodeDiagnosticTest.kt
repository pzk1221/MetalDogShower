package com.panzhikun.metaldogshower.provision

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Credential-free real-device diagnostics. The first test is read-only; the second performs only
 * the request/public-key half of the local Data Layer handshake, never an envelope or token.
 */
@RunWith(AndroidJUnit4::class)
class WearNodeDiagnosticTest {
    @Test
    fun connectedWatchNodeIsVisible() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val nodes = Tasks.await(
            Wearable.getNodeClient(context).connectedNodes,
            15,
            TimeUnit.SECONDS,
        )

        Log.i(TAG, "connectedNodes count=${nodes.size}")
        nodes.forEachIndexed { index, node ->
            Log.i(
                TAG,
                "node[$index] id=${node.id} name=${node.displayName} nearby=${node.isNearby}",
            )
        }
        assertTrue("Expected at least one connected Wear OS node, found none", nodes.isNotEmpty())
    }

    @Test
    fun oneShotPublicKeyHandshakeSucceeds() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val node = Tasks.await(
            Wearable.getNodeClient(context).connectedNodes,
            15,
            TimeUnit.SECONDS,
        ).single()
        val messageClient = Wearable.getMessageClient(context)
        val requestId = UUID.randomUUID().toString()
        val responseRef = AtomicReference<ByteArray>()
        val responseLatch = CountDownLatch(1)
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.sourceNodeId != node.id || event.path != PUBLIC_KEY_PATH) return@OnMessageReceivedListener
            val matches = runCatching {
                JSONObject(String(event.data, StandardCharsets.UTF_8))
                    .optString("requestId") == requestId
            }.getOrDefault(false)
            if (matches && responseRef.compareAndSet(null, event.data.copyOf())) {
                responseLatch.countDown()
            }
        }
        val request = JSONObject()
            .put("version", 1)
            .put("requestId", requestId)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        try {
            Tasks.await(messageClient.addListener(listener), 10, TimeUnit.SECONDS)
            Tasks.await(
                messageClient.sendMessage(node.id, REQUEST_PATH, request),
                10,
                TimeUnit.SECONDS,
            )
            assertTrue("Watch did not return a public key", responseLatch.await(20, TimeUnit.SECONDS))

            val response = responseRef.get()
            assertNotNull(response)
            val json = JSONObject(String(response, StandardCharsets.UTF_8))
            val publicKey = android.util.Base64.decode(
                json.getString("publicKey"),
                android.util.Base64.NO_WRAP,
            )
            val challenge = android.util.Base64.decode(
                json.getString("challenge"),
                android.util.Base64.NO_WRAP,
            )
            try {
                assertEquals(1, json.getInt("version"))
                assertEquals(requestId, json.getString("requestId"))
                assertTrue("Unexpected RSA public key size", publicKey.size in 256..1_024)
                assertEquals(32, challenge.size)
                Log.i(
                    TAG,
                    "public-key handshake ok node=${node.displayName} keyBytes=${publicKey.size}",
                )
            } finally {
                publicKey.fill(0)
                challenge.fill(0)
                response.fill(0)
            }
        } finally {
            request.fill(0)
            runCatching {
                Tasks.await(messageClient.removeListener(listener), 10, TimeUnit.SECONDS)
            }
        }
    }

    private companion object {
        const val TAG = "MetalDogWearDiag"
        const val REQUEST_PATH = "/provision/request"
        const val PUBLIC_KEY_PATH = "/provision/public-key"
    }
}
