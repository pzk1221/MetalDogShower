package com.panzhikun.metaldogshower.wear.provisioning

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Sends one secret-free request to the paired phone when this watch has no local binding. */
internal object WatchSyncRequester {
    fun requestIfMissing(context: Context): Boolean {
        val appContext = context.applicationContext
        if (ProvisionedConfigStore(appContext).hasConfig()) return false
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - preferences.getLong(KEY_LAST_REQUEST_AT, 0L) < REQUEST_COOLDOWN_MILLIS) return false

        val nodes = runCatching {
            Tasks.await(
                Wearable.getNodeClient(appContext).connectedNodes,
                TASK_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
        }.getOrNull().orEmpty()
        if (nodes.isEmpty()) return false

        preferences.edit().putLong(KEY_LAST_REQUEST_AT, now).apply()
        val payload = JSONObject()
            .put("version", ProvisioningProtocol.VERSION)
            .toString()
            .toByteArray(Charsets.UTF_8)
        return try {
            nodes.any { node ->
                runCatching {
                    Tasks.await(
                        Wearable.getMessageClient(appContext).sendMessage(
                            node.id,
                            ProvisioningProtocol.SESSION_SYNC_REQUEST_PATH,
                            payload,
                        ),
                        TASK_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS,
                    )
                    true
                }.getOrDefault(false)
            }
        } finally {
            payload.fill(0)
        }
    }

    private const val PREFERENCES_NAME = "watch_sync_request_v1"
    private const val KEY_LAST_REQUEST_AT = "last_request_at"
    private const val REQUEST_COOLDOWN_MILLIS = 30_000L
    private const val TASK_TIMEOUT_SECONDS = 4L
}
