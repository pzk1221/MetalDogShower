package com.panzhikun.metaldogshower

import android.content.Context
import com.panzhikun.metaldogshower.core.AuthenticationRequiredException
import com.panzhikun.metaldogshower.core.ControlResult
import com.panzhikun.metaldogshower.session.PersistedSession
import com.panzhikun.metaldogshower.widget.FailureReason
import com.panzhikun.metaldogshower.widget.ShowerWidgetController
import com.panzhikun.metaldogshower.widget.ShowerWidgetStateStore
import com.panzhikun.metaldogshower.widget.WidgetCommand
import com.panzhikun.metaldogshower.widget.WidgetCommandResult
import com.panzhikun.metaldogshower.widget.WidgetRoom
import com.panzhikun.metaldogshower.widget.WidgetRefreshResult
import com.panzhikun.metaldogshower.widget.WidgetShowerState
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Application-scoped adapter; the provider itself never owns credentials or performs control. */
class PhoneWidgetController(private val app: MetalDogApplication) : ShowerWidgetController {
    override fun canControl(context: Context, room: WidgetRoom): Boolean {
        val session = app.sessionStore.snapshotOrNull() ?: return false
        return session.room(room.slot) != null
    }

    override suspend fun execute(
        context: Context,
        command: WidgetCommand,
    ): WidgetCommandResult {
        if (!app.phoneOperationMutex.tryLock()) {
            return WidgetCommandResult.Failed(FailureReason.BUSY)
        }
        return try {
            val session = app.sessionStore.snapshotOrNull()
                ?: return WidgetCommandResult.SessionUnavailable
            val room = session.room(command.room.slot)
                ?: return WidgetCommandResult.Failed(FailureReason.REJECTED)
            var controlStarted = false

            try {
                // One fresh route-bound GET is mandatory. A failed GET proves that no widget POST was
                // attempted, so the confirmation screen can safely direct the user back to the app.
                val before = app.repository.status(room.route)
                if (!isCurrent(session, room.slot)) {
                    return WidgetCommandResult.Failed(FailureReason.REJECTED)
                }
                val beforeState = before.toWidgetState()
                ShowerWidgetStateStore.publishRoomStatus(
                    context,
                    command.room,
                    beforeState,
                    System.currentTimeMillis(),
                )
                if (beforeState == command.desiredState) {
                    return WidgetCommandResult.Applied(beforeState)
                }

                controlStarted = true
                ShowerWidgetStateStore.publishRoomStatus(
                    context,
                    command.room,
                    WidgetShowerState.UNKNOWN,
                    0L,
                )
                when (val result = app.repository.control(room.route, command.desiredState == WidgetShowerState.OPEN)) {
                    is ControlResult.Confirmed -> WidgetCommandResult.Applied(result.status.toWidgetState())
                    is ControlResult.Ambiguous -> {
                        if (result.authenticationRequired) {
                            clearMatchingSession(session)
                            WidgetCommandResult.Ambiguous(authenticationRequired = true)
                        } else {
                            result.observedStatus?.let { WidgetCommandResult.Applied(it.toWidgetState()) }
                                ?: WidgetCommandResult.Ambiguous()
                        }
                    }
                    is ControlResult.Rejected -> {
                        ShowerWidgetStateStore.publishRoomStatus(
                            context,
                            command.room,
                            beforeState,
                            System.currentTimeMillis(),
                        )
                        WidgetCommandResult.Failed(FailureReason.REJECTED)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AuthenticationRequiredException) {
                clearMatchingSession(session)
                WidgetCommandResult.SessionUnavailable
            } catch (_: IOException) {
                if (controlStarted) WidgetCommandResult.Ambiguous() else
                    WidgetCommandResult.Failed(FailureReason.NO_INTERNET)
            } catch (_: Exception) {
                if (controlStarted) WidgetCommandResult.Ambiguous() else
                    WidgetCommandResult.Failed(FailureReason.UNKNOWN)
            }
        } finally {
            app.phoneOperationMutex.unlock()
        }
    }

    override suspend fun refresh(context: Context, room: WidgetRoom): WidgetRefreshResult {
        if (!app.phoneOperationMutex.tryLock()) {
            return WidgetRefreshResult.Failed(FailureReason.BUSY)
        }
        return try {
            val session = app.sessionStore.snapshotOrNull()
                ?: return WidgetRefreshResult.SessionUnavailable
            val configured = session.room(room.slot)
                ?: return WidgetRefreshResult.Failed(FailureReason.REJECTED)
            try {
                val status = app.repository.status(configured.route)
                if (!isCurrent(session, configured.slot)) {
                    WidgetRefreshResult.Failed(FailureReason.REJECTED)
                } else {
                    WidgetRefreshResult.Updated(status.toWidgetState())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AuthenticationRequiredException) {
                clearMatchingSession(session)
                WidgetRefreshResult.SessionUnavailable
            } catch (_: IOException) {
                WidgetRefreshResult.Failed(FailureReason.NO_INTERNET)
            } catch (_: Exception) {
                WidgetRefreshResult.Failed(FailureReason.UNKNOWN)
            }
        } finally {
            app.phoneOperationMutex.unlock()
        }
    }

    /**
     * Refreshes every configured room once for the user-created background schedule. This path is
     * deliberately separate from control execution and cannot issue a switch POST.
     */
    suspend fun refreshConfiguredRooms(context: Context) {
        if (!app.phoneOperationMutex.tryLock()) return
        try {
            val session = app.sessionStore.snapshotOrNull() ?: return
            for (configured in session.showers.sortedBy { it.slot }) {
                if (!isCurrent(session, configured.slot)) return
                try {
                    val status = app.repository.status(configured.route)
                    if (!isCurrent(session, configured.slot)) return
                    val room = WidgetRoom.fromStorageId(configured.slot) ?: continue
                    ShowerWidgetStateStore.publishRoomStatus(
                        context = context.applicationContext,
                        room = room,
                        state = status.toWidgetState(),
                        confirmedAtEpochMillis = System.currentTimeMillis(),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: AuthenticationRequiredException) {
                    clearMatchingSession(session)
                    return
                } catch (_: IOException) {
                    // Keep the last verified display hint; its freshness timeout will expire it.
                } catch (_: Exception) {
                    // A scheduled GET failure is not retried immediately and never becomes a POST.
                }
            }
        } finally {
            app.phoneOperationMutex.unlock()
        }
    }

    private fun isCurrent(original: PersistedSession, slot: Int): Boolean {
        val current = app.sessionStore.snapshotOrNull() ?: return false
        return current.credentialId == original.credentialId &&
            current.room(slot)?.route == original.room(slot)?.route
    }

    private suspend fun clearMatchingSession(original: PersistedSession) {
        val cleared = app.sessionStore.clearIfCredentialMatches(original.credentialId)
        if (cleared) {
            app.publishWidgetSession(preserveVerifiedStatus = false)
        }
        app.provisioningManager.notifySessionClear(original.credentialId)
    }

    private fun com.panzhikun.metaldogshower.core.ShowerStatus.toWidgetState(): WidgetShowerState =
        if (isOpen) WidgetShowerState.OPEN else WidgetShowerState.CLOSED

    private val WidgetRoom.slot: Int
        get() = if (this == WidgetRoom.ROOM_ONE) 1 else 2
}
