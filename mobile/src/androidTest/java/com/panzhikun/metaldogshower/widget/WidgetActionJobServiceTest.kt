package com.panzhikun.metaldogshower.widget

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetActionJobServiceTest {
    @Test
    @Suppress("DEPRECATION")
    fun widgetTapUsesNetworkConstrainedExpeditedJobOnAndroid12AndLater() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pending = WidgetPendingAction(
            room = WidgetRoom.ROOM_ONE,
            kind = WidgetActionKind.OPEN,
            startedAtEpochMillis = 1_000L,
        )

        val job = WidgetActionJobService.buildJobInfo(
            context = context,
            room = pending.room,
            kind = pending.kind,
            pending = pending,
        )

        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(job.isExpedited)
        }

        val scheduler = context.getSystemService(JobScheduler::class.java)
        try {
            assertEquals(JobScheduler.RESULT_SUCCESS, scheduler.schedule(job))
        } finally {
            scheduler.cancel(job.id)
        }
    }
}
