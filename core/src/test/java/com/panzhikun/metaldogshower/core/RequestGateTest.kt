package com.panzhikun.metaldogshower.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestGateTest {
    @Test
    fun appliesDebounceThenCooldownWithoutExecutingRejectedBlocks() = runTest {
        val clock = MutableClock()
        val gate = RequestGate(clock)
        var executions = 0

        assertTrue(gate.run(desiredOpen = true) { ++executions } is GateResult.Executed)

        clock.now = 1_000L
        val duplicate = gate.run(desiredOpen = true) { ++executions }
        assertTrue(duplicate is GateResult.Rejected)
        duplicate as GateResult.Rejected
        assertEquals(ControlRejection.DEBOUNCED, duplicate.reason)
        assertEquals(1_000L, duplicate.retryAfterMillis)

        clock.now = 3_000L
        val cooldown = gate.run(desiredOpen = false) { ++executions }
        assertTrue(cooldown is GateResult.Rejected)
        cooldown as GateResult.Rejected
        assertEquals(ControlRejection.COOLDOWN, cooldown.reason)
        assertEquals(2_000L, cooldown.retryAfterMillis)

        clock.now = 5_000L
        assertTrue(gate.run(desiredOpen = false) { ++executions } is GateResult.Executed)
        assertEquals(2, executions)
    }

    @Test
    fun mutexSerializesAndRejectsATapCapturedWhileFirstRequestIsRunning() = runTest {
        val clock = MutableClock()
        val gate = RequestGate(clock)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var executions = 0

        val first = async {
            gate.run(desiredOpen = true) {
                executions++
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        clock.now = 500L
        val second = async {
            gate.run(desiredOpen = true) { executions++ }
        }
        releaseFirst.complete(Unit)

        assertTrue(first.await() is GateResult.Executed)
        val rejected = second.await()
        assertTrue(rejected is GateResult.Rejected)
        rejected as GateResult.Rejected
        assertEquals(ControlRejection.DEBOUNCED, rejected.reason)
        assertEquals(1, executions)
    }

    private class MutableClock(var now: Long = 0L) : MonotonicClock {
        override fun nowMillis(): Long = now
    }
}
