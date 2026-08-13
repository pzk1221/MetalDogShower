package com.panzhikun.metaldogshower.widget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Executes one widget tap without bringing an Activity to the foreground. The job is deliberately
 * one-shot and never asks JobScheduler to retry: a control POST can have an unknown outcome.
 */
class WidgetActionJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runningJob: Job? = null
    private var runningAction: WidgetPendingAction? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (runningJob?.isActive == true) return false
        val room = WidgetRoom.fromStorageId(params.extras.getInt(EXTRA_ROOM, 0))
        val kind = runCatching {
            WidgetActionKind.valueOf(params.extras.getString(EXTRA_KIND).orEmpty())
        }.getOrNull()
        if (room == null || kind == null) {
            if (room != null) ShowerWidgetStateStore.clearPending(this)
            jobFinished(params, false)
            return false
        }
        val startedAt = params.extras.getLong(EXTRA_STARTED_AT)
        val pending = ShowerWidgetStateStore.pendingAction(applicationContext)
        if (
            pending == null ||
            pending.room != room ||
            pending.kind != kind ||
            pending.startedAtEpochMillis != startedAt
        ) {
            // Ignore a stale JobScheduler callback after the visual action was already cleared or
            // replaced. This also prevents an old queued job from acting on a later widget tap.
            jobFinished(params, false)
            return false
        }
        runningAction = pending
        runningJob = serviceScope.launch {
            try {
                val result = when (kind) {
                    WidgetActionKind.OPEN -> ShowerWidgetBridge.executeOnce(
                        applicationContext,
                        WidgetCommand(room, WidgetShowerState.OPEN),
                    )
                    WidgetActionKind.CLOSE -> ShowerWidgetBridge.executeOnce(
                        applicationContext,
                        WidgetCommand(room, WidgetShowerState.CLOSED),
                    )
                    WidgetActionKind.REFRESH -> null
                }
                when (kind) {
                    WidgetActionKind.OPEN,
                    WidgetActionKind.CLOSE,
                    -> applyCommandResult(room, result as WidgetCommandResult)
                    WidgetActionKind.REFRESH -> applyRefreshResult(room)
                }
            } catch (cancelled: CancellationException) {
                // Cancellation after a control request is indistinguishable from an unknown
                // network outcome. Never return true to JobScheduler, otherwise it may repeat a
                // POST. A refresh has no POST and can simply clear its visual pending state.
                if (kind != WidgetActionKind.REFRESH) {
                    ShowerWidgetStateStore.publishAmbiguous(applicationContext, room, false)
                }
                throw cancelled
            } catch (_: Exception) {
                if (kind != WidgetActionKind.REFRESH) {
                    ShowerWidgetStateStore.publishAmbiguous(applicationContext, room, false)
                }
            } finally {
                ShowerWidgetStateStore.clearPending(applicationContext)
                runningAction = null
                runningJob = null
                jobFinished(params, false)
            }
        }
        return true
    }

    private suspend fun applyCommandResult(room: WidgetRoom, result: WidgetCommandResult) {
        when (result) {
            is WidgetCommandResult.Applied -> ShowerWidgetStateStore.publishRoomStatus(
                applicationContext,
                room,
                result.confirmedState,
                result.confirmedAtEpochMillis,
            )
            is WidgetCommandResult.Ambiguous -> ShowerWidgetStateStore.publishAmbiguous(
                applicationContext,
                room,
                result.authenticationRequired,
            )
            WidgetCommandResult.SessionUnavailable -> {
                val current = ShowerWidgetStateStore.snapshot(applicationContext)
                ShowerWidgetStateStore.publishAvailability(
                    applicationContext,
                    isLoggedIn = false,
                    isWatchBound = current.isWatchBound,
                )
                ShowerWidgetStateStore.publishAmbiguous(applicationContext, room, true)
            }
            is WidgetCommandResult.Failed -> {
                // Failed means the controller proved no unknown control POST outcome. Keep the
                // cached state only for an explicit pre-POST failure; it will naturally expire.
            }
        }
    }

    private suspend fun applyRefreshResult(room: WidgetRoom) {
        when (val result = ShowerWidgetBridge.refreshOnce(applicationContext, room)) {
            is WidgetRefreshResult.Updated -> ShowerWidgetStateStore.publishRoomStatus(
                applicationContext,
                room,
                result.state,
                result.confirmedAtEpochMillis,
            )
            WidgetRefreshResult.SessionUnavailable -> {
                val current = ShowerWidgetStateStore.snapshot(applicationContext)
                ShowerWidgetStateStore.publishAvailability(
                    applicationContext,
                    isLoggedIn = false,
                    isWatchBound = current.isWatchBound,
                )
            }
            is WidgetRefreshResult.Failed -> Unit
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningJob?.cancel()
        runningJob = null
        // Returning false is intentional: JobScheduler must never replay a widget control POST.
        return false
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 0x4D5741
        private const val EXTRA_ROOM = "job_room"
        private const val EXTRA_KIND = "job_kind"
        private const val EXTRA_STARTED_AT = "job_started_at"

        internal fun schedule(
            context: Context,
            room: WidgetRoom,
            kind: WidgetActionKind,
        ): Boolean {
            val appContext = context.applicationContext
            val scheduler = appContext.getSystemService(JobScheduler::class.java) ?: return false
            val pending = ShowerWidgetStateStore.pendingAction(appContext) ?: return false
            val extras = PersistableBundle().apply {
                putInt(EXTRA_ROOM, room.storageId)
                putString(EXTRA_KIND, kind.name)
                putLong(EXTRA_STARTED_AT, pending.startedAtEpochMillis)
            }
            val job = JobInfo.Builder(
                JOB_ID,
                ComponentName(appContext, WidgetActionJobService::class.java),
            )
                .setMinimumLatency(0L)
                .setOverrideDeadline(10_000L)
                .setExtras(extras)
                .build()
            return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        }
    }
}
