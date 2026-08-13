package com.panzhikun.metaldogshower.provision

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class ProvisioningPayloadTest {
    @Test
    fun emitsCredentialScopedVersionTwoPayloadWithTwoDistinctRooms() {
        val credentialId = UUID.randomUUID().toString()
        val token = "t".repeat(128).toByteArray()
        val payload = SensitiveProvisioningJson.build(
            token,
            ProvisioningConfig(
                credentialId = credentialId,
                devices = listOf(
                    ProvisioningDevice(2, 1041, 42, "DEVICE-2", "浴室2"),
                    ProvisioningDevice(1, 1041, 42, "DEVICE-1", "浴室1"),
                ),
            ),
        )
        try {
            assertEquals(
                "{\"version\":2,\"token\":\"${"t".repeat(128)}\",\"credentialId\":\"$credentialId\"," +
                    "\"devices\":[{\"slot\":1,\"brandId\":1041,\"stadiumId\":42," +
                    "\"deviceId\":\"DEVICE-1\",\"deviceName\":\"浴室1\"}," +
                    "{\"slot\":2,\"brandId\":1041,\"stadiumId\":42," +
                    "\"deviceId\":\"DEVICE-2\",\"deviceName\":\"浴室2\"}]}",
                String(payload, Charsets.UTF_8),
            )
        } finally {
            token.fill(0)
            payload.fill(0)
        }
    }
}
