package com.panzhikun.metaldogshower.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.panzhikun.metaldogshower.core.DeviceRoute
import com.panzhikun.metaldogshower.core.OfficialProtocol
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConfiguredShower(
    val slot: Int,
    val name: String,
    val route: DeviceRoute,
) {
    init {
        require(slot in VALID_SLOTS)
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH && name.none(Char::isISOControl))
        require(route.brandId > 0L)
        require(route.stadiumId > 0L)
        require(
            route.deviceId.isNotBlank() &&
                route.deviceId.length <= MAX_DEVICE_ID_LENGTH &&
                route.deviceId.none(Char::isISOControl),
        )
    }

    companion object {
        val VALID_SLOTS = 1..2
        const val MAX_NAME_LENGTH = 32
        const val MAX_DEVICE_ID_LENGTH = OfficialProtocol.MAX_IDENTIFIER_LENGTH
    }
}

data class PersistedSession(
    val credentialId: String,
    val token: String,
    val showers: List<ConfiguredShower>,
    val watchBound: Boolean,
) {
    init {
        require(runCatching { UUID.fromString(credentialId) }.isSuccess)
        require(token.length > MIN_TOKEN_LENGTH_EXCLUSIVE && token.length <= MAX_TOKEN_LENGTH)
        require(showers.size in 1..2)
        require(showers.map(ConfiguredShower::slot).distinct().size == showers.size)
        // The official switch POST carries deviceId only, so it must be unique across slots even
        // when a malformed setup claims different stadium metadata for the same target.
        require(showers.map { it.route.deviceId }.distinct().size == showers.size)
    }

    fun room(slot: Int): ConfiguredShower? = showers.firstOrNull { it.slot == slot }

    companion object {
        const val MIN_TOKEN_LENGTH_EXCLUSIVE = 100
        const val MAX_TOKEN_LENGTH = 8_192
    }
}

class SessionStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Phone-side durable session storage.
 *
 * The official service exposes no refresh token, so the same server token remains usable until the
 * service returns 401 or the user logs out. At rest, the token and both device routes are encrypted
 * with a non-exportable AndroidKeyStore AES-256/GCM key. Backup and device transfer are disabled by
 * the application manifest/data-extraction rules.
 */
class SecureSessionStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(loadOrClearCorruptSession())

    val state: StateFlow<PersistedSession?> = _state.asStateFlow()

    @Synchronized
    fun snapshotOrNull(): PersistedSession? {
        val session = _state.value ?: return null
        return session.copy(showers = session.showers.map { it.copy(route = it.route.copy()) })
    }

    @Synchronized
    fun tokenOrNull(): String? = _state.value?.token

    @Synchronized
    fun hasUsableSession(): Boolean = _state.value != null

    @Synchronized
    @Throws(SessionStorageException::class)
    fun replace(
        credentialId: String,
        token: String,
        showers: List<ConfiguredShower>,
        watchBound: Boolean = false,
    ) {
        persist(PersistedSession(credentialId, token, normalize(showers), watchBound))
    }

    /**
     * Changes the route generation while retaining the same official token.
     *
     * The old id is returned for a best-effort watch clear. A delayed clear cannot erase a later
     * provisioning because that provisioning carries the newly generated id.
     */
    @Synchronized
    fun updateShowersAndRotateCredential(showers: List<ConfiguredShower>): String {
        val current = _state.value ?: throw SessionStorageException("No active phone session")
        val oldCredentialId = current.credentialId
        persist(
            current.copy(
                credentialId = UUID.randomUUID().toString(),
                showers = normalize(showers),
                watchBound = false,
            ),
        )
        return oldCredentialId
    }

    @Synchronized
    @Throws(SessionStorageException::class)
    fun markWatchBound(bound: Boolean) {
        val current = _state.value ?: return
        if (current.watchBound == bound) return
        persist(current.copy(watchBound = bound))
    }

    @Synchronized
    fun markWatchBoundIfMatches(
        credentialId: String,
        showers: List<ConfiguredShower>,
        bound: Boolean,
    ): Boolean {
        val current = _state.value ?: return false
        val expected = normalize(showers)
        if (current.credentialId != credentialId || current.showers != expected) return false
        if (current.watchBound != bound) persist(current.copy(watchBound = bound))
        return true
    }

    @Synchronized
    fun clearIfCredentialMatches(credentialId: String): Boolean {
        if (_state.value?.credentialId != credentialId) return false
        clear()
        return true
    }

    @Synchronized
    fun clear() {
        // Drop the in-memory reference first, then remove both ciphertext and the key that could
        // decrypt any filesystem remnant. Either successful deletion is fail-closed on next load.
        _state.value = null
        val preferencesCleared = preferences.edit().clear().commit()
        val keyDeleted = runCatching {
            val store = keyStore()
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        }.isSuccess
        if (!preferencesCleared && !keyDeleted) {
            throw SessionStorageException("Unable to clear encrypted phone session")
        }
    }

    private fun persist(session: PersistedSession) {
        val plaintext = PhoneSessionCodec.encode(session)
        val aad = AAD.toByteArray(StandardCharsets.US_ASCII)
        var iv: ByteArray? = null
        var ciphertext: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(aad)
            iv = cipher.iv.clone()
            ciphertext = cipher.doFinal(plaintext)
            require(iv.size == GCM_IV_BYTES)
            require(ciphertext.size <= MAX_CIPHERTEXT_BYTES)
            val saved = preferences.edit()
                .putInt(KEY_FORMAT_VERSION, STORAGE_VERSION)
                .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit()
            if (!saved) throw SessionStorageException("Unable to save encrypted phone session")
            _state.value = session
        } catch (storage: SessionStorageException) {
            throw storage
        } catch (exception: Exception) {
            throw SessionStorageException("Unable to encrypt phone session", exception)
        } finally {
            plaintext.fill(0)
            aad.fill(0)
            iv?.fill(0)
            ciphertext?.fill(0)
        }
    }

    private fun loadOrClearCorruptSession(): PersistedSession? {
        if (!preferences.contains(KEY_CIPHERTEXT)) return null
        var iv: ByteArray? = null
        var ciphertext: ByteArray? = null
        var plaintext: ByteArray? = null
        val aad = AAD.toByteArray(StandardCharsets.US_ASCII)
        return try {
            require(preferences.getInt(KEY_FORMAT_VERSION, 0) == STORAGE_VERSION)
            iv = Base64.decode(preferences.getString(KEY_IV, null), Base64.NO_WRAP)
            ciphertext = Base64.decode(preferences.getString(KEY_CIPHERTEXT, null), Base64.NO_WRAP)
            require(iv.size == GCM_IV_BYTES)
            require(ciphertext.size in 16..MAX_CIPHERTEXT_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad)
            plaintext = cipher.doFinal(ciphertext)
            PhoneSessionCodec.decode(plaintext)
        } catch (_: Exception) {
            // A missing/invalidated key or damaged authenticated ciphertext cannot be recovered.
            // Remove only this app's encrypted session and require an explicit OTP login.
            preferences.edit().clear().commit()
            runCatching { keyStore().deleteEntry(KEY_ALIAS) }
            null
        } finally {
            aad.fill(0)
            iv?.fill(0)
            ciphertext?.fill(0)
            plaintext?.fill(0)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun normalize(showers: List<ConfiguredShower>): List<ConfiguredShower> =
        showers.sortedBy(ConfiguredShower::slot).also { normalized ->
            require(normalized.size in 1..2)
            require(normalized.map(ConfiguredShower::slot).distinct().size == normalized.size)
            require(normalized.map { it.route.deviceId }.distinct().size == normalized.size)
        }

    private companion object {
        const val PREFERENCES_NAME = "phone_session_encrypted"
        const val KEY_FORMAT_VERSION = "format_version"
        const val KEY_IV = "iv"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val STORAGE_VERSION = 1
        const val KEY_ALIAS = "MetalDogPhoneSessionAesV1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val MAX_CIPHERTEXT_BYTES = 16 * 1024
        const val AAD = "MetalDogPhoneSession:v1"
    }
}

internal object PhoneSessionCodec {
    private const val MAGIC = 0x4D445332 // MDS2
    private const val VERSION = 1

    fun encode(session: PersistedSession): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeBoolean(session.watchBound)
            data.writeSizedUtf8(session.credentialId, 36)
            data.writeSizedUtf8(session.token, PersistedSession.MAX_TOKEN_LENGTH)
            data.writeInt(session.showers.size)
            session.showers.sortedBy(ConfiguredShower::slot).forEach { shower ->
                data.writeInt(shower.slot)
                data.writeSizedUtf8(shower.name, ConfiguredShower.MAX_NAME_LENGTH * 4)
                data.writeLong(shower.route.brandId)
                data.writeLong(shower.route.stadiumId)
                data.writeSizedUtf8(shower.route.deviceId, ConfiguredShower.MAX_DEVICE_ID_LENGTH * 4)
            }
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): PersistedSession {
        require(bytes.size in 1..MAX_PLAINTEXT_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == MAGIC)
            require(data.readInt() == VERSION)
            val watchBound = data.readBoolean()
            val credentialId = data.readSizedUtf8(36)
            val token = data.readSizedUtf8(PersistedSession.MAX_TOKEN_LENGTH)
            val count = data.readInt()
            require(count in 1..2)
            val showers = buildList(count) {
                repeat(count) {
                    val slot = data.readInt()
                    val name = data.readSizedUtf8(ConfiguredShower.MAX_NAME_LENGTH * 4)
                    val brandId = data.readLong()
                    val stadiumId = data.readLong()
                    val deviceId = data.readSizedUtf8(ConfiguredShower.MAX_DEVICE_ID_LENGTH * 4)
                    add(
                        ConfiguredShower(
                            slot = slot,
                            name = name,
                            route = DeviceRoute(brandId, stadiumId, deviceId),
                        ),
                    )
                }
            }
            require(data.read() == -1)
            PersistedSession(credentialId, token, showers.sortedBy(ConfiguredShower::slot), watchBound)
        }
    }

    private fun DataOutputStream.writeSizedUtf8(value: String, maxBytes: Int) {
        val utf8 = value.toByteArray(StandardCharsets.UTF_8)
        try {
            require(utf8.size in 1..maxBytes)
            writeInt(utf8.size)
            write(utf8)
        } finally {
            utf8.fill(0)
        }
    }

    private fun DataInputStream.readSizedUtf8(maxBytes: Int): String {
        val length = readInt()
        require(length in 1..maxBytes)
        val value = ByteArray(length)
        return try {
            readFully(value)
            String(value, StandardCharsets.UTF_8)
        } finally {
            value.fill(0)
        }
    }

    private const val MAX_PLAINTEXT_BYTES = 12 * 1024
}
