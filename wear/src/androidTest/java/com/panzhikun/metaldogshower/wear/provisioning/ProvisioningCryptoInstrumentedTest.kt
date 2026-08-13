package com.panzhikun.metaldogshower.wear.provisioning

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on the actual watch. It uses only random/fake values and never opens a network socket. */
@RunWith(AndroidJUnit4::class)
class ProvisioningCryptoInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun cleanFakeState() {
        ProvisionedConfigStore(context).clear()
        context.getSharedPreferences("provisioning_replay_v1", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun watchKeystoreOaepAndDynamicAadRoundTrip() {
        val requestId = "instrumented-request-0001"
        val challengeBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val challenge = Base64.encodeToString(challengeBytes, Base64.NO_WRAP)
        val aesKey = ByteArray(32).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val plaintext = "fake-provisioning-payload".toByteArray()
        var encryptedKey = ByteArray(0)
        var ciphertext = ByteArray(0)
        var recoveredKey = ByteArray(0)
        var recoveredPlaintext = ByteArray(0)
        try {
            val publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(ProvisioningCrypto.publicKeyX509())) as RSAPublicKey
            val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            rsa.init(
                Cipher.ENCRYPT_MODE,
                publicKey,
                OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA1,
                    PSource.PSpecified.DEFAULT,
                ),
            )
            encryptedKey = rsa.doFinal(aesKey)
            recoveredKey = ProvisioningCrypto.decryptEnvelopeKey(encryptedKey)
            assertArrayEquals(aesKey, recoveredKey)

            val gcm = Cipher.getInstance("AES/GCM/NoPadding")
            gcm.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
            gcm.updateAAD(ProvisioningProtocol.envelopeAad(requestId, challenge))
            ciphertext = gcm.doFinal(plaintext)
            recoveredPlaintext = ProvisioningCrypto.decryptPayload(
                aesKey = recoveredKey,
                iv = iv,
                ciphertext = ciphertext,
                requestId = requestId,
                challengeBase64 = challenge,
            )
            assertArrayEquals(plaintext, recoveredPlaintext)

            val tampered = ciphertext.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            try {
                ProvisioningCrypto.decryptPayload(
                    aesKey = recoveredKey,
                    iv = iv,
                    ciphertext = tampered,
                    requestId = requestId,
                    challengeBase64 = challenge,
                )
                throw AssertionError("Tampered GCM payload was accepted")
            } catch (_: AEADBadTagException) {
                // Expected authentication failure.
            } finally {
                tampered.fill(0)
            }
        } finally {
            challengeBytes.fill(0)
            aesKey.fill(0)
            iv.fill(0)
            plaintext.fill(0)
            encryptedKey.fill(0)
            ciphertext.fill(0)
            recoveredKey.fill(0)
            recoveredPlaintext.fill(0)
        }
    }

    @Test
    fun encryptedConfigAndReplayGuardsRoundTrip() {
        val fakeJson = JSONObject()
            .put("version", 1)
            .put("token", "t".repeat(128))
            .put("brandId", 7)
            .put("stadiumId", 42)
            .put("deviceId", "FAKE-DEVICE")
            .put("deviceName", "模拟淋浴")
            .toString()
            .toByteArray()
        val store = ProvisionedConfigStore(context)
        var loaded = ByteArray(0)
        try {
            assertTrue(store.save(fakeJson))
            loaded = requireNotNull(store.load())
            assertArrayEquals(fakeJson, loaded)
        } finally {
            fakeJson.fill(0)
            loaded.fill(0)
            store.clear()
        }

        val node = "fake-node"
        val request = "instrumented-request-0002"
        val challenge = Base64.encodeToString(ByteArray(32) { it.toByte() }, Base64.NO_WRAP)
        assertTrue(ProvisioningSessionStore.begin(node, request, challenge))
        assertTrue(ProvisioningSessionStore.consume(node, request, challenge))
        assertFalse(ProvisioningSessionStore.consume(node, request, challenge))

        val replay = ProvisioningReplayStore(context)
        val wrappedKey = ByteArray(256) { 1 }
        val iv = ByteArray(12) { 2 }
        val ciphertext = ByteArray(48) { 3 }
        try {
            assertTrue(replay.markConsumed(node, request, wrappedKey, iv, ciphertext))
            assertFalse(replay.markConsumed(node, request, wrappedKey, iv, ciphertext))
            assertTrue(replay.wasRequestConsumed(node, request))
        } finally {
            wrappedKey.fill(0)
            iv.fill(0)
            ciphertext.fill(0)
        }
    }
}
