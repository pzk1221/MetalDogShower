package com.panzhikun.metaldogshower.wear.provisioning

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import java.security.MessageDigest

/**
 * Persists only non-reversible digests. This survives process death and blocks
 * both request-ID reuse and relabeling an old ciphertext with a fresh ID.
 */
internal class ProvisioningReplayStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun wasRequestConsumed(sourceNodeId: String, requestId: String): Boolean =
        synchronized(lock) {
            requestDigest(sourceNodeId, requestId) in history()
        }

    @SuppressLint("ApplySharedPref") // Replay record must be durable before decrypting.
    fun markConsumed(
        sourceNodeId: String,
        requestId: String,
        encryptedKey: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
    ): Boolean = synchronized(lock) {
        val existing = history()
        val requestEntry = requestDigest(sourceNodeId, requestId)
        val envelopeEntry = envelopeDigest(encryptedKey, iv, ciphertext)
        if (requestEntry in existing || envelopeEntry in existing) return@synchronized false

        val updated = (existing + requestEntry + envelopeEntry).takeLast(MAX_HISTORY_ENTRIES)
        preferences.edit().putString(KEY_HISTORY, updated.joinToString(",")).commit()
    }

    private fun history(): List<String> =
        preferences.getString(KEY_HISTORY, null)
            ?.split(',')
            ?.filter(String::isNotEmpty)
            .orEmpty()

    private fun requestDigest(sourceNodeId: String, requestId: String): String =
        digest(
            "request\u0000".toByteArray(Charsets.UTF_8),
            sourceNodeId.toByteArray(Charsets.UTF_8),
            byteArrayOf(0),
            requestId.toByteArray(Charsets.UTF_8),
        )

    private fun envelopeDigest(
        encryptedKey: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
    ): String = digest(
        "envelope\u0000".toByteArray(Charsets.UTF_8),
        lengthPrefix(encryptedKey.size),
        encryptedKey,
        lengthPrefix(iv.size),
        iv,
        lengthPrefix(ciphertext.size),
        ciphertext,
    )

    private fun digest(vararg parts: ByteArray): String {
        val value = MessageDigest.getInstance("SHA-256").run {
            parts.forEach(::update)
            digest()
        }
        return try {
            Base64.encodeToString(value, Base64.NO_WRAP or Base64.URL_SAFE)
        } finally {
            value.fill(0)
        }
    }

    private fun lengthPrefix(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private companion object {
        val lock = Any()
        const val PREFERENCES_NAME = "provisioning_replay_v1"
        const val KEY_HISTORY = "history"
        const val MAX_HISTORY_ENTRIES = 64
    }
}
