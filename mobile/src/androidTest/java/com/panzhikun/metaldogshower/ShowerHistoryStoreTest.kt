package com.panzhikun.metaldogshower

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShowerHistoryStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun clearHistory() {
        context.getSharedPreferences("shower_history", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun shortOpenCloseIsFilteredOut() {
        val store = ShowerHistoryStore(context)

        store.observeConfirmedStatus(1, "浴室1", isOpen = true, observedAtEpochMillis = 1_000L)
        store.observeConfirmedStatus(1, "浴室1", isOpen = false, observedAtEpochMillis = 60_999L)

        assertTrue(store.snapshot().records.isEmpty())
        assertTrue(store.snapshot().activeSessions.isEmpty())
    }

    @Test
    fun completedSessionStoresStartAndEndTimes() {
        val store = ShowerHistoryStore(context)

        store.observeConfirmedStatus(2, "浴室2", isOpen = true, observedAtEpochMillis = 2_000L)
        store.observeConfirmedStatus(2, "浴室2", isOpen = false, observedAtEpochMillis = 122_000L)

        val record = store.snapshot().records.single()
        assertEquals(2, record.roomSlot)
        assertEquals(2_000L, record.startedAtEpochMillis)
        assertEquals(122_000L, record.endedAtEpochMillis)
        assertEquals(120_000L, record.durationMillis)
    }
}
