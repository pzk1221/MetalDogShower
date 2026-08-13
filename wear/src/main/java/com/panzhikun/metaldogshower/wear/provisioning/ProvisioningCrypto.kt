package com.panzhikun.metaldogshower.wear.provisioning

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

internal object ProvisioningCrypto {
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val RSA_ALIAS = "metal_dog_provisioning_rsa_v1"

    fun publicKeyX509(): ByteArray {
        ensureRsaKeyPair()
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return keyStore.getCertificate(RSA_ALIAS).publicKey.encoded
    }

    fun decryptEnvelopeKey(encryptedKey: ByteArray): ByteArray {
        ensureRsaKeyPair()
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val privateKey = keyStore.getKey(RSA_ALIAS, null)
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            privateKey,
            OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                // Android Keystore authorizes MGF1 SHA-1 by default on API
                // 30-34. Keep both ends explicit and compatible.
                MGF1ParameterSpec.SHA1,
                PSource.PSpecified.DEFAULT,
            ),
        )
        return cipher.doFinal(encryptedKey)
    }

    fun decryptPayload(
        aesKey: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        requestId: String,
        challengeBase64: String,
    ): ByteArray {
        require(aesKey.size == 32)
        require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(aesKey, "AES"),
            GCMParameterSpec(128, iv),
        )
        val aad = ProvisioningProtocol.envelopeAad(requestId, challengeBase64)
        try {
            cipher.updateAAD(aad)
        } finally {
            aad.fill(0)
        }
        return cipher.doFinal(ciphertext)
    }

    private fun ensureRsaKeyPair() {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (keyStore.containsAlias(RSA_ALIAS)) return

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEY_STORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(
                    RSA_ALIAS,
                    KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(2_048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKeyPair()
        }
    }
}
