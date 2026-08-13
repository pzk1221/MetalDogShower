package com.panzhikun.metaldogshower

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Schedules one persisted, network-constrained job at a time. One-shot jobs allow a user-selected
 * active window and interval without keeping a foreground service or a permanent notification.
 * Android may batch the job under Doze; every execution remains GET-only.
 */
object BackgroundPollingScheduler {
    private const val JOB_ID = 0x4D4453
    private const val MINIMUM_DELAY_MILLIS = 1_000L

    fun reconcile(context: Context, immediateIfActive: Boolean = false) {
        val appContext = context.applicationContext
        val scheduler = appContext.getSystemService(JobScheduler::class.java)
        val app = appContext as? MetalDogApplication
        val settings = PollingSettingsStore(appContext).read().background
        if (!settings.enabled || app?.sessionStore?.snapshotOrNull() == null) {
            scheduler.cancel(JOB_ID)
            return
        }

        val now = System.currentTimeMillis()
        val triggerAt = BackgroundPollingPlanner.nextTriggerAt(
            settings = settings,
            nowMillis = now,
            immediateIfActive = immediateIfActive,
        ) ?: run {
            scheduler.cancel(JOB_ID)
            return
        }
        val delayMillis = (triggerAt - now).coerceAtLeast(MINIMUM_DELAY_MILLIS)
        val job = JobInfo.Builder(
            JOB_ID,
            ComponentName(appContext, BackgroundPollingJobService::class.java),
        )
            .setMinimumLatency(delayMillis)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        scheduler.schedule(job)
    }

    fun cancel(context: Context) {
        context.applicationContext.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}

class BackgroundPollingJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runningJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (runningJob?.isActive == true) return false
        runningJob = serviceScope.launch {
            try {
                val app = application as MetalDogApplication
                val settings = PollingSettingsStore(app).read().background
                if (
                    settings.enabled &&
                    BackgroundPollingPlanner.isActive(settings, System.currentTimeMillis()) &&
                    app.sessionStore.snapshotOrNull() != null
                ) {
                    app.refreshConfiguredRoomsForSchedule()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed GET cycle is not retried immediately; the next planned run remains safe.
            }
            if (isActive) {
                jobFinished(params, false)
                BackgroundPollingScheduler.reconcile(applicationContext, immediateIfActive = false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningJob?.cancel()
        runningJob = null
        // GET requests are safe to retry, and the next attempt still checks the selected window.
        return true
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
