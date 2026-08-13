package com.panzhikun.metaldogshower.wear.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisionedConfigParserTest {
    @Test
    fun legacyV1_normalizesToSlotOneWithoutChangingCredential() {
        val credentialId = "123e4567-e89b-12d3-a456-426614174000"

        val config = ProvisionedConfigParser.parse(
            payload = legacyPayload(),
            legacyCredentialId = credentialId,
        )

        assertTrue(config.migratedFromLegacy)
        assertEquals(credentialId, config.credentialId)
        assertEquals("t".repeat(128), config.token)
        assertEquals(1, config.devices.size)
        assertEquals(1, config.devices.single().slot)
        assertEquals("旧浴室", config.devices.single().deviceName)
    }

    @Test
    fun versionTwo_readsTwoSlotsSharingOneTokenAndSortsSlots() {
        val payload = """
            {
              "version": 2,
              "credentialId": "123e4567-e89b-12d3-a456-426614174000",
              "token": "${"s".repeat(128)}",
              "devices": [
                {"slot": 2, "brandId": 8, "stadiumId": 12, "deviceId": "route-2", "deviceName": "东侧"},
                {"slot": 1, "brandId": 8, "stadiumId": 12, "deviceId": "route-1", "deviceName": "西侧"}
              ]
            }
        """.trimIndent().toByteArray()

        val config = ProvisionedConfigParser.parse(payload)

        assertFalse(config.migratedFromLegacy)
        assertEquals(listOf(1, 2), config.devices.map(ProvisionedDevice::slot))
        assertEquals(listOf("西侧", "东侧"), config.devices.map(ProvisionedDevice::deviceName))
        assertEquals(1, config.devices.map { config.token }.distinct().size)
    }

    @Test
    fun versionTwo_acceptsOneBoundSlot() {
        val config = ProvisionedConfigParser.parse(
            versionTwoPayload(
                devices = """
                    {"slot": 1, "brandId": 8, "stadiumId": 12, "deviceId": "route-1", "deviceName": "仅有浴室"}
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(1), config.devices.map(ProvisionedDevice::slot))
    }

    @Test
    fun versionTwo_rejectsDuplicateSlot() {
        val payload = versionTwoPayload(
            devices = """
                {"slot": 1, "brandId": 8, "stadiumId": 12, "deviceId": "route-1", "deviceName": "一"},
                {"slot": 1, "brandId": 8, "stadiumId": 12, "deviceId": "route-2", "deviceName": "二"}
            """.trimIndent(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ProvisionedConfigParser.parse(payload)
        }
    }

    @Test
    fun versionTwo_rejectsSameDeviceIdAcrossDifferentVenues() {
        val payload = versionTwoPayload(
            devices = """
                {"slot": 1, "brandId": 8, "stadiumId": 12, "deviceId": "same-route", "deviceName": "一"},
                {"slot": 2, "brandId": 9, "stadiumId": 99, "deviceId": "same-route", "deviceName": "二"}
            """.trimIndent(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ProvisionedConfigParser.parse(payload)
        }
    }

    @Test
    fun versionTwo_rejectsNonCanonicalCredentialId() {
        val payload = versionTwoPayload(
            credentialId = "123E4567-E89B-12D3-A456-426614174000",
            devices = """
                {"slot": 1, "brandId": 8, "stadiumId": 12, "deviceId": "route-1", "deviceName": "一"}
            """.trimIndent(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ProvisionedConfigParser.parse(payload)
        }
    }

    private fun legacyPayload(): ByteArray = """
        {
          "version": 1,
          "token": "${"t".repeat(128)}",
          "brandId": 7,
          "stadiumId": 42,
          "deviceId": "legacy-route",
          "deviceName": "旧浴室"
        }
    """.trimIndent().toByteArray()

    private fun versionTwoPayload(
        credentialId: String = "123e4567-e89b-12d3-a456-426614174000",
        devices: String,
    ): ByteArray = """
        {
          "version": 2,
          "credentialId": "$credentialId",
          "token": "${"t".repeat(128)}",
          "devices": [$devices]
        }
    """.trimIndent().toByteArray()
}
