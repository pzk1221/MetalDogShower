package com.panzhikun.metaldogshower.session

import com.panzhikun.metaldogshower.core.DeviceRoute
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PhoneSessionCodecTest {
    @Test
    fun roundTripsTwoRoomsAndCredentialGeneration() {
        val original = PersistedSession(
            credentialId = UUID.randomUUID().toString(),
            token = "t".repeat(128),
            showers = listOf(
                ConfiguredShower(1, "浴室1", DeviceRoute(1041, 10, "DEVICE-1")),
                ConfiguredShower(2, "浴室2", DeviceRoute(1041, 10, "DEVICE-2")),
            ),
            watchBound = true,
        )

        val encoded = PhoneSessionCodec.encode(original)
        val decoded = PhoneSessionCodec.decode(encoded)

        assertEquals(original, decoded)
        encoded.fill(0)
    }

    @Test
    fun rejectsTrailingOrDamagedPlaintext() {
        val original = PersistedSession(
            credentialId = UUID.randomUUID().toString(),
            token = "x".repeat(128),
            showers = listOf(
                ConfiguredShower(1, "浴室1", DeviceRoute(1041, 10, "DEVICE-1")),
            ),
            watchBound = false,
        )
        val encoded = PhoneSessionCodec.encode(original)
        val trailing = encoded + byteArrayOf(1)

        try {
            PhoneSessionCodec.decode(trailing)
            fail("Expected trailing data to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        } finally {
            encoded.fill(0)
            trailing.fill(0)
        }
    }

    @Test
    fun rejectsSameSwitchDeviceAcrossSlotsEvenWhenStadiumDiffers() {
        try {
            PersistedSession(
                credentialId = UUID.randomUUID().toString(),
                token = "z".repeat(128),
                showers = listOf(
                    ConfiguredShower(1, "浴室1", DeviceRoute(1041, 10, "SAME-DEVICE")),
                    ConfiguredShower(2, "浴室2", DeviceRoute(1041, 11, "SAME-DEVICE")),
                ),
                watchBound = false,
            )
            fail("Expected duplicate route to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
