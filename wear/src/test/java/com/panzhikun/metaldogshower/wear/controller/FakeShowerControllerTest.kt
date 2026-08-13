package com.panzhikun.metaldogshower.wear.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class FakeShowerControllerTest {
    @Test
    fun localBindingLoadDoesNotSelectOrRefreshARoom() = runBlocking {
        val controller = FakeShowerController()

        assertEquals(ControllerResult.Ready, controller.loadLocalBinding())
        assertNull(controller.selectedSlot.value)
        assertFalse(controller.status.value.isStateKnown)
    }

    @Test
    fun startsWithoutAnImplicitRoomSelection() {
        val controller = FakeShowerController()

        assertNull(controller.selectedSlot.value)
        assertFalse(controller.status.value.isStateKnown)
        assertTrue(controller.selectSlot(BathroomSlot.BATHROOM_1))
        assertEquals(BathroomSlot.BATHROOM_1, controller.selectedSlot.value)
        assertFalse(controller.status.value.isStateKnown)
    }

    @Test
    fun oneDeviceConfigurationLeavesBathroomTwoUnbound() {
        val controller = FakeShowerController(boundSlotCount = 1)

        assertEquals(
            BindingState.UNBOUND,
            controller.bindings.value.single { it.slot == BathroomSlot.BATHROOM_2 }.state,
        )
        assertFalse(controller.selectSlot(BathroomSlot.BATHROOM_2))
        assertNull(controller.selectedSlot.value)
    }
}
