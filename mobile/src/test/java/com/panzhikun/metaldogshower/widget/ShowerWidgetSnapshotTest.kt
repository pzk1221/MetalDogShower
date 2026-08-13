package com.panzhikun.metaldogshower.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowerWidgetSnapshotTest {
    @Test
    fun unknownOrStaleDisplayStateDoesNotBlockConfirmedControlFlow() {
        val unknown = ShowerWidgetSnapshot(
            isLoggedIn = true,
            roomOne = WidgetRoomSnapshot(
                isConfigured = true,
                state = WidgetShowerState.UNKNOWN,
                confirmedAtEpochMillis = 0L,
            ),
        )
        val stale = unknown.copy(
            roomOne = WidgetRoomSnapshot(
                isConfigured = true,
                state = WidgetShowerState.CLOSED,
                confirmedAtEpochMillis = 1L,
            ),
        )

        assertTrue(unknown.canRequestControl(WidgetRoom.ROOM_ONE))
        assertTrue(stale.canRequestControl(WidgetRoom.ROOM_ONE))
    }

    @Test
    fun loginAndSelectedRoomConfigurationRemainRequired() {
        val loggedOut = ShowerWidgetSnapshot(
            isLoggedIn = false,
            roomOne = WidgetRoomSnapshot(isConfigured = true),
        )
        val roomMissing = ShowerWidgetSnapshot(
            isLoggedIn = true,
            roomOne = WidgetRoomSnapshot(isConfigured = false),
        )

        assertFalse(loggedOut.canRequestControl(WidgetRoom.ROOM_ONE))
        assertFalse(roomMissing.canRequestControl(WidgetRoom.ROOM_ONE))
    }

    @Test
    fun knownDisplayStateExpiresAfterTwoMinutesWithoutBlockingControl() {
        val confirmedAt = 1_000_000L
        val snapshot = ShowerWidgetSnapshot(
            isLoggedIn = true,
            roomOne = WidgetRoomSnapshot(
                isConfigured = true,
                state = WidgetShowerState.OPEN,
                confirmedAtEpochMillis = confirmedAt,
            ),
        )

        assertEquals(
            WidgetShowerState.OPEN,
            snapshot.roomOne.freshDisplayState(confirmedAt + WIDGET_STATUS_FRESHNESS_MILLIS),
        )
        assertEquals(
            WidgetShowerState.UNKNOWN,
            snapshot.roomOne.freshDisplayState(confirmedAt + WIDGET_STATUS_FRESHNESS_MILLIS + 1L),
        )
        assertTrue(snapshot.canRequestControl(WidgetRoom.ROOM_ONE))
    }

    @Test
    fun missingOrFutureConfirmationTimeIsNotPresentedAsLive() {
        val missing = WidgetRoomSnapshot(
            isConfigured = true,
            state = WidgetShowerState.CLOSED,
            confirmedAtEpochMillis = 0L,
        )
        val future = missing.copy(confirmedAtEpochMillis = 20_000L)

        assertEquals(WidgetShowerState.UNKNOWN, missing.freshDisplayState(10_000L))
        assertEquals(WidgetShowerState.UNKNOWN, future.freshDisplayState(10_000L))
    }
}
