package com.panzhikun.metaldogshower

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.util.UUID
import org.json.JSONObject

/** Uploads history over HTTPS using an installation-specific Android Keystore signing key. */
class ShowerHistoryBackupClient(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun backup(snapshot: ShowerHistorySnapshot): Boolean {
        return runCatching {
            val deviceId = deviceId()
            val publicKey = signingKey().public.encoded
            post(
                REGISTER_URL,
                JSONObject().apply {
                    put("deviceId", deviceId)
                    put("publicKey", Base64.encodeToString(publicKey, Base64.NO_WRAP))
                }.toString(),
            )
            val payload = snapshotPayload(snapshot)
            val timestamp = System.currentTimeMillis()
            val signedInput = "$deviceId\n$timestamp\n$payload".toByteArray(StandardCharsets.UTF_8)
            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(signingKey().private)
            signer.update(signedInput)
            post(
                BACKUP_URL,
                JSONObject().apply {
                    put("deviceId", deviceId)
                    put("timestamp", timestamp)
                    put("payload", payload)
                    put("signature", Base64.encodeToString(signer.sign(), Base64.NO_WRAP))
                }.toString(),
            )
            true
        }.getOrDefault(false)
    }

    private fun snapshotPayload(snapshot: ShowerHistorySnapshot): String = JSONObject().apply {
        put("schemaVersion", 1)
        put("updatedAt", snapshot.updatedAtEpochMillis)
        put("records", org.json.JSONArray().apply {
            snapshot.records.forEach { record ->
                put(JSONObject().apply {
                    put("id", record.id)
                    put("roomSlot", record.roomSlot)
                    put("roomName", record.roomName)
                    put("startedAt", record.startedAtEpochMillis)
                    put("endedAt", record.endedAtEpochMillis)
                })
            }
        })
        put("activeSessions", org.json.JSONArray().apply {
            snapshot.activeSessions.forEach { active ->
                put(JSONObject().apply {
                    put("roomSlot", active.roomSlot)
                    put("roomName", active.roomName)
                    put("startedAt", active.startedAtEpochMillis)
                })
            }
        })
    }.toString()

    private fun post(endpoint: String, body: String) {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setFixedLengthStreamingMode(body.toByteArray(StandardCharsets.UTF_8).size)
        }
        try {
            connection.outputStream.use { output ->
                output.write(body.toByteArray(StandardCharsets.UTF_8))
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = connection.errorStream?.let { stream ->
                    BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
                }.orEmpty()
                throw IllegalStateException("backup HTTP $status ${error.take(160)}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun deviceId(): String {
        val existing = preferences.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_DEVICE_ID, created).commit()
        return created
    }

    private fun signingKey(): java.security.KeyPair {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = store.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) return java.security.KeyPair(existing.certificate.publicKey, existing.privateKey)
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build(),
        )
        val pair = generator.generateKeyPair()
        return pair
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "metaldog_history_backup_signing_key"
        const val PREFERENCES_NAME = "shower_history_backup"
        const val KEY_DEVICE_ID = "device_id"
        const val REGISTER_URL = "https://cpina.online/api/metaldog/v1/register"
        const val BACKUP_URL = "https://cpina.online/api/metaldog/v1/backup"
    }
}
