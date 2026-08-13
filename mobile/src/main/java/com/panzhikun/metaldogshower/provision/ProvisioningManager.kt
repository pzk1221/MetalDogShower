package com.panzhikun.metaldogshower.provision

import android.content.Context
import android.util.Base64
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.panzhikun.metaldogshower.core.OfficialProtocol
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

private const val REQUEST_PATH = "/provision/request"
private const val PUBLIC_KEY_PATH = "/provision/public-key"
private const val ENVELOPE_PATH = "/provision/envelope"
private const val RESULT_PATH = "/provision/result"
private const val PROTOCOL_VERSION = 1
private const val RESPONSE_TIMEOUT_MS = 20_000L
private const val PROVISIONING_TOTAL_TIMEOUT_MS = 60_000L
private const val AAD_PREFIX = "MetalDogShowerProvisioning:v1"

data class ProvisioningDevice(
    val slot: Int,
    val brandId: Long,
    val stadiumId: Long,
    val deviceId: String,
    val deviceName: String,
) {
    init {
        require(slot in 1..2)
        require(brandId > 0L)
        require(stadiumId > 0L)
        require(
            deviceId.isNotBlank() &&
                deviceId.length <= OfficialProtocol.MAX_IDENTIFIER_LENGTH &&
                deviceId.none(Char::isISOControl),
        )
        require(deviceName.isNotBlank() && deviceName.length <= 32 && deviceName.none(Char::isISOControl))
    }
}

data class ProvisioningConfig(
    val credentialId: String,
    val devices: List<ProvisioningDevice>,
) {
    init {
        require(runCatching { UUID.fromString(credentialId) }.isSuccess)
        require(devices.size in 1..2)
        require(devices.map(ProvisioningDevice::slot).distinct().size == devices.size)
        require(devices.map(ProvisioningDevice::deviceId).distinct().size == devices.size)
    }
}

data class ProvisioningReceipt(val watchName: String)

class ProvisioningException(message: String) : Exception(message)

/**
 * One-shot end-to-end encrypted provisioning over the Wear Message API.
 *
 * The watch generates a non-exportable RSA key in AndroidKeyStore. The phone uses its public key
 * to wrap a fresh AES key, then sends an AES-GCM envelope. This transport never writes an extra
 * plaintext token copy; the phone's durable session is protected separately by AndroidKeyStore.
 */
class ProvisioningManager(context: Context) {
    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)
    private val secureRandom = SecureRandom()

    suspend fun hasConnectedWatch(): Boolean =
        runCatching { nodeClient.connectedNodes.awaitResult().isNotEmpty() }.getOrDefault(false)

    /** Best-effort, credential-scoped local logout notice. It contains no token and is never retried. */
    suspend fun notifySessionClear(credentialId: String) {
        if (runCatching { UUID.fromString(credentialId) }.isFailure) return
        withTimeoutOrNull(SESSION_CLEAR_TIMEOUT_MS) {
            val nodes = runCatching { nodeClient.connectedNodes.awaitResult() }.getOrNull().orEmpty()
            if (nodes.isEmpty()) return@withTimeoutOrNull
            val payload = JSONObject()
                .put("version", PROTOCOL_VERSION)
                .put("credentialId", credentialId)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
            try {
                nodes.forEach { node ->
                    runCatching {
                        messageClient.sendMessage(node.id, SESSION_CLEAR_PATH, payload).awaitResult()
                    }
                }
            } finally {
                payload.fill(0)
            }
        }
    }

    suspend fun provision(
        tokenUtf8: ByteArray,
        config: ProvisioningConfig,
        targetNodeId: String? = null,
    ): ProvisioningReceipt {
        require(tokenUtf8.isNotEmpty())
        return try {
            try {
                // This hard deadline also covers Play-services Tasks such as node discovery,
                // listener registration and message sends. No cloned token can remain held by a
                // stalled provisioning operation.
                withTimeout(PROVISIONING_TOTAL_TIMEOUT_MS) {
                    provisionWhileHoldingToken(tokenUtf8, config, targetNodeId)
                }
            } catch (_: TimeoutCancellationException) {
                throw ProvisioningException("手表绑定超时；不会自动重发，请手动检查后再试")
            }
        } finally {
            tokenUtf8.fill(0)
        }
    }

    private suspend fun provisionWhileHoldingToken(
        tokenUtf8: ByteArray,
        config: ProvisioningConfig,
        targetNodeId: String?,
    ): ProvisioningReceipt {
        val node = selectTargetNode(targetNodeId)
        val requestId = UUID.randomUUID().toString()
        val publicKeyReply = CompletableDeferred<PublicKeyReply>()
        val resultReply = CompletableDeferred<Boolean>()

        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.sourceNodeId != node.id) return@OnMessageReceivedListener
            when (event.path) {
                PUBLIC_KEY_PATH -> receivePublicKey(event, requestId, publicKeyReply)
                RESULT_PATH -> receiveResult(event, requestId, resultReply)
            }
        }

        try {
            messageClient.addListener(listener).awaitResult()
            val request = JSONObject()
                .put("version", PROTOCOL_VERSION)
                .put("requestId", requestId)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
            try {
                messageClient.sendMessage(node.id, REQUEST_PATH, request).awaitResult()
            } finally {
                request.fill(0)
            }

            val keyReply = try {
                withTimeout(RESPONSE_TIMEOUT_MS) { publicKeyReply.await() }
            } catch (_: TimeoutCancellationException) {
                throw ProvisioningException("手表未返回安全公钥，请确认手表绑定页已打开后手动重试")
            }

            val envelope = try {
                createEnvelope(requestId, keyReply, tokenUtf8, config)
            } finally {
                keyReply.publicKeyDer.fill(0)
            }
            try {
                messageClient.sendMessage(node.id, ENVELOPE_PATH, envelope).awaitResult()
            } finally {
                envelope.fill(0)
            }

            val saved = try {
                withTimeout(RESPONSE_TIMEOUT_MS) { resultReply.await() }
            } catch (_: TimeoutCancellationException) {
                throw ProvisioningException("未收到手表保存确认；不会自动重发，请检查手表后手动重试")
            }
            if (!saved) {
                throw ProvisioningException("手表未能安全保存绑定信息，请在手表端检查后手动重试")
            }
            return ProvisioningReceipt(node.displayName.ifBlank { "已配对手表" })
        } finally {
            // Start removal but never suspend cleanup ahead of token wiping. This also runs when
            // listener registration itself times out or is cancelled.
            runCatching { messageClient.removeListener(listener) }
        }
    }

    private suspend fun selectTargetNode(targetNodeId: String?): Node {
        val connected = nodeClient.connectedNodes.awaitResult()
        if (connected.isEmpty()) {
            throw ProvisioningException("没有找到已连接的 Wear OS 手表")
        }
        if (targetNodeId != null) {
            return connected.firstOrNull { it.id == targetNodeId }
                ?: throw ProvisioningException("请求同步的手表当前未连接")
        }
        if (connected.size == 1) return connected.single()

        val nearby = connected.filter { it.isNearby }
        if (nearby.size == 1) return nearby.single()
        throw ProvisioningException("检测到多块手表，请暂时只连接要绑定的那一块")
    }

    private fun receivePublicKey(
        event: MessageEvent,
        requestId: String,
        deferred: CompletableDeferred<PublicKeyReply>,
    ) {
        if (deferred.isCompleted) return
        runCatching {
            require(event.data.size in 1..MAX_PUBLIC_KEY_MESSAGE_BYTES)
            val json = JSONObject(String(event.data, StandardCharsets.UTF_8))
            if (json.optString("requestId") != requestId) return
            require(json.optInt("version") == PROTOCOL_VERSION)
            val publicKeyDer = Base64.decode(json.getString("publicKey"), Base64.NO_WRAP).also { decoded ->
                require(decoded.size in MIN_RSA_DER_BYTES..MAX_RSA_DER_BYTES)
            }
            val challengeBase64 = json.getString("challenge")
            val challenge = Base64.decode(challengeBase64, Base64.NO_WRAP)
            try {
                require(challenge.size == CHALLENGE_BYTES)
                require(Base64.encodeToString(challenge, Base64.NO_WRAP) == challengeBase64)
                PublicKeyReply(publicKeyDer, challengeBase64)
            } finally {
                challenge.fill(0)
            }
        }.onSuccess { deferred.complete(it) }
            .onFailure {
                deferred.completeExceptionally(
                    ProvisioningException("手表返回的安全公钥无效"),
                )
            }
    }

    private fun receiveResult(
        event: MessageEvent,
        requestId: String,
        deferred: CompletableDeferred<Boolean>,
    ) {
        if (deferred.isCompleted) return
        runCatching {
            require(event.data.size in 1..MAX_RESULT_MESSAGE_BYTES)
            val json = JSONObject(String(event.data, StandardCharsets.UTF_8))
            if (json.optString("requestId") != requestId) return
            require(json.optInt("version") == PROTOCOL_VERSION)
            json.getBoolean("success")
        }.onSuccess { deferred.complete(it) }
            .onFailure {
                deferred.completeExceptionally(
                    ProvisioningException("手表返回的保存结果无效"),
                )
            }
    }

    private fun createEnvelope(
        requestId: String,
        keyReply: PublicKeyReply,
        tokenUtf8: ByteArray,
        config: ProvisioningConfig,
    ): ByteArray {
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(keyReply.publicKeyDer)) as? RSAPublicKey
            ?: throw ProvisioningException("手表公钥类型无效")
        if (publicKey.modulus.bitLength() < 2048) {
            throw ProvisioningException("手表公钥强度不足")
        }

        val aesKey = ByteArray(32).also(secureRandom::nextBytes)
        val iv = ByteArray(12).also(secureRandom::nextBytes)
        val plaintext = SensitiveProvisioningJson.build(tokenUtf8, config)
        val aad = "$AAD_PREFIX:$requestId:${keyReply.challengeBase64}"
            .toByteArray(StandardCharsets.UTF_8)
        try {
            val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
            aesCipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                GCMParameterSpec(128, iv),
            )
            aesCipher.updateAAD(aad)
            val ciphertext = aesCipher.doFinal(plaintext)

            val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            rsaCipher.init(
                Cipher.ENCRYPT_MODE,
                publicKey,
                OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    // AndroidKeyStore on Watch4/API 30 authorizes OAEP's MGF1 SHA-1 default.
                    MGF1ParameterSpec.SHA1,
                    PSource.PSpecified.DEFAULT,
                ),
            )
            val encryptedKey = rsaCipher.doFinal(aesKey)
            try {
                return JSONObject()
                    .put("version", PROTOCOL_VERSION)
                    .put("requestId", requestId)
                    .put("challenge", keyReply.challengeBase64)
                    .put("encryptedKey", Base64.encodeToString(encryptedKey, Base64.NO_WRAP))
                    .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                    .put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .toString()
                    .toByteArray(StandardCharsets.UTF_8)
            } finally {
                encryptedKey.fill(0)
                ciphertext.fill(0)
            }
        } catch (exception: ProvisioningException) {
            throw exception
        } catch (_: Exception) {
            throw ProvisioningException("无法建立与手表的安全传输")
        } finally {
            aesKey.fill(0)
            iv.fill(0)
            plaintext.fill(0)
            aad.fill(0)
        }
    }
}

private suspend fun <T> Task<T>.awaitResult(): T =
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            when {
                task.isSuccessful -> continuation.resumeWith(Result.success(task.result))
                task.isCanceled -> continuation.cancel()
                else -> continuation.resumeWithException(
                    task.exception ?: IllegalStateException("Wear operation failed"),
                )
            }
        }
    }

internal object SensitiveProvisioningJson {
    fun build(tokenUtf8: ByteArray, config: ProvisioningConfig): ByteArray {
        val builder = WipeableByteBuilder()
        try {
            builder.appendAscii("{\"version\":2,\"token\":")
            builder.appendQuotedUtf8(tokenUtf8)
            builder.appendAscii(",\"credentialId\":")
            builder.appendQuotedString(config.credentialId)
            builder.appendAscii(",\"devices\":[")
            config.devices.sortedBy(ProvisioningDevice::slot).forEachIndexed { index, device ->
                if (index > 0) builder.appendAscii(",")
                builder.appendAscii("{\"slot\":${device.slot}")
                builder.appendAscii(",\"brandId\":${device.brandId}")
                builder.appendAscii(",\"stadiumId\":${device.stadiumId}")
                builder.appendAscii(",\"deviceId\":")
                builder.appendQuotedString(device.deviceId)
                builder.appendAscii(",\"deviceName\":")
                builder.appendQuotedString(device.deviceName)
                builder.appendAscii("}")
            }
            builder.appendAscii("]}")
            return builder.copyBytes()
        } finally {
            builder.close()
        }
    }
}

private const val MAX_PUBLIC_KEY_MESSAGE_BYTES = 4_096
private const val MAX_RESULT_MESSAGE_BYTES = 1_024
private const val MIN_RSA_DER_BYTES = 256
private const val MAX_RSA_DER_BYTES = 1_024
private const val CHALLENGE_BYTES = 32
private const val SESSION_CLEAR_PATH = "/session/clear"
private const val SESSION_CLEAR_TIMEOUT_MS = 5_000L

private data class PublicKeyReply(
    val publicKeyDer: ByteArray,
    val challengeBase64: String,
)

private class WipeableByteBuilder : AutoCloseable {
    private var bytes = ByteArray(256)
    private var size = 0

    fun appendAscii(text: String) {
        text.forEach { char ->
            require(char.code <= 0x7f)
            append(char.code.toByte())
        }
    }

    fun appendQuotedString(value: String) {
        val utf8 = value.toByteArray(StandardCharsets.UTF_8)
        try {
            appendQuotedUtf8(utf8)
        } finally {
            utf8.fill(0)
        }
    }

    fun appendQuotedUtf8(value: ByteArray) {
        append('"'.code.toByte())
        value.forEach { byte ->
            when (byte.toInt() and 0xff) {
                0x22 -> appendAscii("\\\"")
                0x5c -> appendAscii("\\\\")
                0x08 -> appendAscii("\\b")
                0x0c -> appendAscii("\\f")
                0x0a -> appendAscii("\\n")
                0x0d -> appendAscii("\\r")
                0x09 -> appendAscii("\\t")
                in 0x00..0x1f -> {
                    appendAscii("\\u00")
                    val valueInt = byte.toInt() and 0xff
                    append(HEX[valueInt ushr 4].code.toByte())
                    append(HEX[valueInt and 0x0f].code.toByte())
                }
                else -> append(byte)
            }
        }
        append('"'.code.toByte())
    }

    fun copyBytes(): ByteArray = bytes.copyOf(size)

    private fun append(value: Byte) {
        ensureCapacity(size + 1)
        bytes[size++] = value
    }

    private fun ensureCapacity(required: Int) {
        if (required <= bytes.size) return
        val old = bytes
        bytes = old.copyOf(maxOf(required, old.size * 2))
        old.fill(0)
    }

    override fun close() {
        bytes.fill(0)
        size = 0
    }

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}
