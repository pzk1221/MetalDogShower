package com.panzhikun.metaldogshower.wear.provisioning

import com.panzhikun.metaldogshower.core.OfficialProtocol
import org.json.JSONObject
import java.util.UUID

internal data class ProvisionedDevice(
    val slot: Int,
    val brandId: Long,
    val stadiumId: Long,
    val deviceId: String,
    val deviceName: String,
)

internal data class ProvisionedConfig(
    val credentialId: String,
    val token: String,
    val devices: List<ProvisionedDevice>,
    val migratedFromLegacy: Boolean,
)

/**
 * Pure parser for the encrypted provisioning plaintext.
 *
 * Version 1 is the shipped single-device schema. It is normalized in memory
 * to slot 1 without rewriting the ciphertext. Version 2 shares one token
 * across one or two explicitly numbered device routes.
 */
internal object ProvisionedConfigParser {
    const val LEGACY_SINGLE_DEVICE_VERSION = 1
    const val DUAL_DEVICE_VERSION = 2

    fun parse(
        payload: ByteArray,
        legacyCredentialId: String = UUID.randomUUID().toString(),
    ): ProvisionedConfig {
        val json = JSONObject(payload.toString(Charsets.UTF_8))
        val token = json.requiredString("token", 101, 16_384)
        return when (json.optInt("version", 0)) {
            LEGACY_SINGLE_DEVICE_VERSION -> ProvisionedConfig(
                credentialId = requireCanonicalCredentialId(legacyCredentialId),
                token = token,
                devices = listOf(parseDevice(json, slot = 1)),
                migratedFromLegacy = true,
            )

            DUAL_DEVICE_VERSION -> {
                val array = json.getJSONArray("devices")
                require(array.length() in 1..2)
                val devices = buildList(array.length()) {
                    repeat(array.length()) { index ->
                        val device = array.getJSONObject(index)
                        add(parseDevice(device, slot = device.optInt("slot", 0)))
                    }
                }
                require(devices.map(ProvisionedDevice::slot).distinct().size == devices.size)
                // The official switch POST contains only deviceId, so two slots
                // may never reuse it even when their brand/stadium fields differ.
                require(devices.map(ProvisionedDevice::deviceId).distinct().size == devices.size)
                ProvisionedConfig(
                    credentialId = requireCanonicalCredentialId(
                        json.requiredString("credentialId", 36, 36),
                    ),
                    token = token,
                    devices = devices.sortedBy(ProvisionedDevice::slot),
                    migratedFromLegacy = false,
                )
            }

            else -> throw IllegalArgumentException("Unsupported provisioning payload")
        }
    }

    private fun parseDevice(json: JSONObject, slot: Int): ProvisionedDevice {
        require(slot in 1..2)
        val brandId = json.optLong("brandId", 0L)
        val stadiumId = json.optLong("stadiumId", 0L)
        require(brandId > 0L && stadiumId > 0L)
        return ProvisionedDevice(
            slot = slot,
            brandId = brandId,
            stadiumId = stadiumId,
            deviceId = json.requiredString(
                "deviceId",
                1,
                OfficialProtocol.MAX_IDENTIFIER_LENGTH,
            ),
            deviceName = json.requiredString("deviceName", 1, 128),
        )
    }

    private fun JSONObject.requiredString(
        name: String,
        minLength: Int,
        maxLength: Int,
    ): String = getString(name).also { require(it.length in minLength..maxLength) }

    fun isCanonicalCredentialId(value: String): Boolean = runCatching {
        UUID.fromString(value).toString() == value
    }.getOrDefault(false)

    private fun requireCanonicalCredentialId(value: String): String =
        value.also { require(isCanonicalCredentialId(it)) }
}
