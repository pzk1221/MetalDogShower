package com.panzhikun.metaldogshower.wear.provisioning

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class ProvisionedConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @SuppressLint("ApplySharedPref") // Result ACK requires durable storage first.
    fun save(plaintextJson: ByteArray): Boolean {
        val config = validatePayload(plaintextJson)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // AndroidKeyStore enforces randomized encryption for this key and therefore
        // must generate the GCM IV itself. Supplying a caller-generated IV is rejected
        // on the target Samsung Watch4.
        cipher.init(Cipher.ENCRYPT_MODE, localKey())
        val iv = cipher.iv
        require(iv.size == 12)
        cipher.updateAAD(ProvisioningProtocol.LOCAL_AAD.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plaintextJson)
        return try {
            synchronized(storageLock) {
                val currentRevision = preferences.getLong(KEY_CONFIG_REVISION, 0L)
                val nextRevision = if (currentRevision == Long.MAX_VALUE) 1L else currentRevision + 1L
                preferences.edit()
                    .putInt(KEY_VERSION, ProvisioningProtocol.VERSION)
                    .putLong(KEY_CONFIG_REVISION, nextRevision)
                    .putString(KEY_CREDENTIAL_ID, config.credentialId)
                    .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .commit()
            }
        } finally {
            iv.fill(0)
            encrypted.fill(0)
        }
    }

    fun hasConfig(): Boolean =
        preferences.getInt(KEY_VERSION, 0) == ProvisioningProtocol.VERSION &&
            preferences.contains(KEY_IV) && preferences.contains(KEY_CIPHERTEXT)

    @SuppressLint("ApplySharedPref") // Authentication failure must clear durable credentials first.
    fun clear(): Boolean = synchronized(storageLock) {
        preferences.edit().clear().commit()
    }

    fun currentCredentialIdOrNull(): String? = synchronized(storageLock) {
        preferences.getString(KEY_CREDENTIAL_ID, null)
            ?.takeIf(ProvisionedConfigParser::isCanonicalCredentialId)
    }

    /** Local monotonic revision closes the same-credential replacement window. */
    fun currentConfigRevision(): Long = synchronized(storageLock) {
        if (
            preferences.getInt(KEY_VERSION, 0) != ProvisioningProtocol.VERSION ||
            !preferences.contains(KEY_IV) ||
            !preferences.contains(KEY_CIPHERTEXT)
        ) {
            0L
        } else {
            preferences.getLong(KEY_CONFIG_REVISION, 0L).coerceAtLeast(0L)
        }
    }

    /** Persists the generated ID used while reading a legacy v1 payload. */
    @SuppressLint("ApplySharedPref")
    fun rememberCredentialId(credentialId: String): Boolean = synchronized(storageLock) {
        require(ProvisionedConfigParser.isCanonicalCredentialId(credentialId))
        val current = preferences.getString(KEY_CREDENTIAL_ID, null)
        when {
            current == credentialId -> true
            current != null -> false
            else -> preferences.edit().putString(KEY_CREDENTIAL_ID, credentialId).commit()
        }
    }

    /** Atomic late-message guard: only the currently stored login may be cleared. */
    @SuppressLint("ApplySharedPref")
    fun clearIfCredentialMatches(credentialId: String): Boolean = synchronized(storageLock) {
        if (!ProvisionedConfigParser.isCanonicalCredentialId(credentialId)) return@synchronized false
        if (preferences.getString(KEY_CREDENTIAL_ID, null) != credentialId) return@synchronized false
        preferences.edit().clear().commit()
    }

    /** The caller owns the returned plaintext and must overwrite it after use. */
    fun load(): ByteArray? {
        if (!hasConfig()) return null
        val iv = Base64.decode(preferences.getString(KEY_IV, null), Base64.NO_WRAP)
        val ciphertext = Base64.decode(preferences.getString(KEY_CIPHERTEXT, null), Base64.NO_WRAP)
        return try {
            require(iv.size == 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, localKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(ProvisioningProtocol.LOCAL_AAD.toByteArray(Charsets.UTF_8))
            cipher.doFinal(ciphertext)
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun validatePayload(payload: ByteArray): ProvisionedConfig {
        require(payload.isNotEmpty() && payload.size <= MAX_PLAINTEXT_BYTES)
        return ProvisionedConfigParser.parse(payload)
    }

    private fun localKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(LOCAL_AES_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    LOCAL_AES_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val LOCAL_AES_ALIAS = "metal_dog_local_config_aes_v1"
        const val PREFERENCES_NAME = "provisioned_config_v1"
        const val KEY_VERSION = "version"
        const val KEY_CONFIG_REVISION = "config_revision"
        const val KEY_CREDENTIAL_ID = "credential_id"
        const val KEY_IV = "iv"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val MAX_PLAINTEXT_BYTES = 32 * 1_024
        val storageLock = Any()
    }
}
