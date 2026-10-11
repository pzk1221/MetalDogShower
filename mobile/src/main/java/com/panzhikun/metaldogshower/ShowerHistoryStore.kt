package com.panzhikun.metaldogshower

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A completed shower session that passed the minimum-duration filter. */
data class ShowerSessionRecord(
    val id: String,
    val roomSlot: Int,
    val roomName: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
) {
    val durationMillis: Long
        get() = (endedAtEpochMillis - startedAtEpochMillis).coerceAtLeast(0L)
}

data class ActiveShowerSession(
    val roomSlot: Int,
    val roomName: String,
    val startedAtEpochMillis: Long,
)

data class ShowerHistorySnapshot(
    val records: List<ShowerSessionRecord> = emptyList(),
    val activeSessions: List<ActiveShowerSession> = emptyList(),
    val updatedAtEpochMillis: Long = 0L,
)

/**
 * Persists the small, non-credential shower history locally and turns confirmed state transitions
 * into sessions. A session shorter than [MIN_RECORD_DURATION_MILLIS] is discarded on close.
 */
class ShowerHistoryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val lock = Any()
    private val _state = MutableStateFlow(load())

    val state: StateFlow<ShowerHistorySnapshot> = _state.asStateFlow()

    fun snapshot(): ShowerHistorySnapshot = synchronized(lock) {
        _state.value.copy(
            records = _state.value.records.toList(),
            activeSessions = _state.value.activeSessions.toList(),
        )
    }

    /**
     * Consumes only a status confirmed by the official API. Returns true when persistence changed.
     */
    fun observeConfirmedStatus(
        roomSlot: Int,
        roomName: String,
        isOpen: Boolean,
        observedAtEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (roomSlot !in 1..2 || roomName.isBlank() || observedAtEpochMillis <= 0L) return false
        synchronized(lock) {
            val current = _state.value
            val active = current.activeSessions.associateBy(ActiveShowerSession::roomSlot).toMutableMap()
            val records = current.records.toMutableList()
            var changed = false
            if (isOpen) {
                if (active[roomSlot] == null) {
                    active[roomSlot] = ActiveShowerSession(roomSlot, roomName, observedAtEpochMillis)
                    changed = true
                } else if (active.getValue(roomSlot).roomName != roomName) {
                    active[roomSlot] = active.getValue(roomSlot).copy(roomName = roomName)
                    changed = true
                }
            } else {
                val started = active.remove(roomSlot)
                if (started != null) {
                    changed = true
                    val duration = observedAtEpochMillis - started.startedAtEpochMillis
                    if (duration >= MIN_RECORD_DURATION_MILLIS) {
                        records += ShowerSessionRecord(
                            id = UUID.randomUUID().toString(),
                            roomSlot = roomSlot,
                            roomName = started.roomName,
                            startedAtEpochMillis = started.startedAtEpochMillis,
                            endedAtEpochMillis = observedAtEpochMillis,
                        )
                        records.sortByDescending(ShowerSessionRecord::endedAtEpochMillis)
                        if (records.size > MAX_RECORDS) {
                            records.subList(MAX_RECORDS, records.size).clear()
                        }
                    }
                }
            }
            if (!changed) return false
            persistLocked(
                ShowerHistorySnapshot(
                    records = records,
                    activeSessions = active.values.sortedBy(ActiveShowerSession::roomSlot),
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
            return true
        }
    }

    /** Stable JSON payload used by the server backup client. It contains no login token. */
    fun backupPayloadJson(): String = synchronized(lock) {
        val snapshot = _state.value
        JSONObject().apply {
            put("schemaVersion", 1)
            put("updatedAt", snapshot.updatedAtEpochMillis)
            put("records", JSONArray().apply {
                snapshot.records.forEach { record ->
                    put(JSONObject().apply {
                        put("id", record.id)
                        put("roomSlot", record.roomSlot)
                        put("roomName", record.roomName)
                        put("startedAt", record.startedAtEpochMillis)
                        put("endedAt", record.endedAtEpochMillis)
                    })
                }
            })
            put("activeSessions", JSONArray().apply {
                snapshot.activeSessions.forEach { active ->
                    put(JSONObject().apply {
                        put("roomSlot", active.roomSlot)
                        put("roomName", active.roomName)
                        put("startedAt", active.startedAtEpochMillis)
                    })
                }
            })
        }.toString()
    }

    private fun load(): ShowerHistorySnapshot {
        val records = mutableListOf<ShowerSessionRecord>()
        val active = mutableListOf<ActiveShowerSession>()
        runCatching {
            val array = JSONArray(preferences.getString(KEY_RECORDS, "[]"))
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val record = ShowerSessionRecord(
                    id = item.optString("id").takeIf(String::isNotBlank) ?: continue,
                    roomSlot = item.optInt("roomSlot", 0),
                    roomName = item.optString("roomName").takeIf(String::isNotBlank) ?: continue,
                    startedAtEpochMillis = item.optLong("startedAt", 0L),
                    endedAtEpochMillis = item.optLong("endedAt", 0L),
                )
                if (record.roomSlot in 1..2 && record.startedAtEpochMillis > 0L &&
                    record.endedAtEpochMillis >= record.startedAtEpochMillis &&
                    record.durationMillis >= MIN_RECORD_DURATION_MILLIS
                ) records += record
            }
        }
        runCatching {
            val array = JSONArray(preferences.getString(KEY_ACTIVE, "[]"))
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val session = ActiveShowerSession(
                    roomSlot = item.optInt("roomSlot", 0),
                    roomName = item.optString("roomName").takeIf(String::isNotBlank) ?: continue,
                    startedAtEpochMillis = item.optLong("startedAt", 0L),
                )
                if (session.roomSlot in 1..2 && session.startedAtEpochMillis > 0L) active += session
            }
        }
        return ShowerHistorySnapshot(
            records = records.distinctBy(ShowerSessionRecord::id)
                .sortedByDescending(ShowerSessionRecord::endedAtEpochMillis)
                .take(MAX_RECORDS),
            activeSessions = active.distinctBy(ActiveShowerSession::roomSlot).sortedBy(ActiveShowerSession::roomSlot),
            updatedAtEpochMillis = preferences.getLong(KEY_UPDATED_AT, 0L),
        )
    }

    private fun persistLocked(snapshot: ShowerHistorySnapshot) {
        val records = JSONArray().apply {
            snapshot.records.forEach { record ->
                put(JSONObject().apply {
                    put("id", record.id)
                    put("roomSlot", record.roomSlot)
                    put("roomName", record.roomName)
                    put("startedAt", record.startedAtEpochMillis)
                    put("endedAt", record.endedAtEpochMillis)
                })
            }
        }
        val active = JSONArray().apply {
            snapshot.activeSessions.forEach { session ->
                put(JSONObject().apply {
                    put("roomSlot", session.roomSlot)
                    put("roomName", session.roomName)
                    put("startedAt", session.startedAtEpochMillis)
                })
            }
        }
        preferences.edit()
            .putString(KEY_RECORDS, records.toString())
            .putString(KEY_ACTIVE, active.toString())
            .putLong(KEY_UPDATED_AT, snapshot.updatedAtEpochMillis)
            .commit()
        _state.value = snapshot
    }

    private companion object {
        const val PREFERENCES_NAME = "shower_history"
        const val KEY_RECORDS = "records"
        const val KEY_ACTIVE = "active_sessions"
        const val KEY_UPDATED_AT = "updated_at"
        const val MAX_RECORDS = 365
        const val MIN_RECORD_DURATION_MILLIS = 60_000L
    }
}
