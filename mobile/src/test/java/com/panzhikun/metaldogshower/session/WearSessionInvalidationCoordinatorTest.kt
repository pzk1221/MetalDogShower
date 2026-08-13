package com.panzhikun.metaldogshower.session

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearSessionInvalidationCoordinatorTest {
    @Test
    fun invalidationWaitsForPhoneOperationAndPublishesOnceAfterMatchingClear() = runBlocking {
        val mutex = Mutex(locked = true)
        val attempted = CompletableDeferred<Unit>()
        val clearCalled = AtomicBoolean(false)
        val publishCount = AtomicInteger(0)
        val result = async(Dispatchers.Default) {
            attempted.complete(Unit)
            invalidateCredentialUnderLock(
                mutex = mutex,
                clearIfMatches = {
                    clearCalled.set(true)
                    true
                },
                publishClearedSession = { publishCount.incrementAndGet() },
            )
        }

        attempted.await()
        yield()
        assertFalse(clearCalled.get())
        mutex.unlock()

        assertTrue(withTimeout(2_000L) { result.await() })
        assertTrue(clearCalled.get())
        assertEquals(1, publishCount.get())
    }

    @Test
    fun nonMatchingInvalidationDoesNotPublish() = runBlocking {
        val publishCount = AtomicInteger(0)

        val cleared = invalidateCredentialUnderLock(
            mutex = Mutex(),
            clearIfMatches = { false },
            publishClearedSession = { publishCount.incrementAndGet() },
        )

        assertFalse(cleared)
        assertEquals(0, publishCount.get())
    }
}
