package com.panzhikun.metaldogshower.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeFirstTest {
    @Test
    fun defaultRepositoryIsFakeAndNeverNeedsProductionIdentifiers() = runTest {
        val repository = ShowerRepositories.default()

        assertEquals(RepositoryMode.FAKE, repository.mode)
        assertTrue(repository is FakeShowerRepository)
        val device = repository.resolveDevice("LOCAL-ALIAS")
        assertEquals(DeviceInfo.TYPE_SHOWER, device.type)

        val alreadyClosed = repository.control(
            route = DeviceRoute(brandId = 1L, stadiumId = 1L, deviceId = device.deviceId),
            desiredOpen = false,
        )
        assertTrue(alreadyClosed is ControlResult.Rejected)
        alreadyClosed as ControlResult.Rejected
        assertEquals(ControlRejection.ALREADY_CLOSED, alreadyClosed.reason)
        assertEquals(0L, alreadyClosed.retryAfterMillis)
    }

    @Test
    fun fakeRepositoryKeepsTwoRoomStatesIndependent() = runTest {
        val repository = FakeShowerRepository()
        val roomOne = DeviceRoute(1L, 1L, "ROOM-1")
        val roomTwo = DeviceRoute(1L, 1L, "ROOM-2")

        assertTrue(repository.control(roomOne, desiredOpen = true) is ControlResult.Confirmed)
        assertTrue(repository.status(roomOne).isOpen)
        assertFalse(repository.status(roomTwo).isOpen)
    }
}
