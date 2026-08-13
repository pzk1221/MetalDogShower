package com.panzhikun.metaldogshower

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.panzhikun.metaldogshower.core.DeviceRoute
import com.panzhikun.metaldogshower.session.ConfiguredShower
import com.panzhikun.metaldogshower.widget.ShowerWidgetStateStore
import com.panzhikun.metaldogshower.widget.WidgetRoom
import com.panzhikun.metaldogshower.widget.WidgetShowerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug-only data seeding used to capture genuine UI screenshots without real credentials. */
class MarketingSeedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) {
            val app = application as MetalDogApplication
            app.sessionStore.replace(
                credentialId = "11111111-2222-4333-8444-555555555555",
                token = "marketing-preview-token-".padEnd(180, 'x'),
                showers = listOf(
                    ConfiguredShower(
                        slot = 1,
                        name = "浴室1",
                        route = DeviceRoute(1041L, 10001L, "marketing-room-one"),
                    ),
                    ConfiguredShower(
                        slot = 2,
                        name = "浴室2",
                        route = DeviceRoute(1041L, 10001L, "marketing-room-two"),
                    ),
                ),
                watchBound = true,
            )
            OnboardingStore(applicationContext).markCompleted()
            PollingSettingsStore(applicationContext).apply {
                setEnabled(false)
                setIntervalSeconds(300)
                setBackground(
                    BackgroundPollingSettings(
                        enabled = true,
                        mode = BackgroundPollingMode.DAILY,
                        intervalMinutes = 10,
                        dailyStartMinute = 2 * 60,
                        dailyEndMinute = 3 * 60,
                    ),
                )
            }
            app.publishWidgetSession(preserveVerifiedStatus = false)
            ShowerWidgetStateStore.publishRoomStatus(
                applicationContext,
                WidgetRoom.ROOM_ONE,
                WidgetShowerState.CLOSED,
                System.currentTimeMillis(),
            )
            ShowerWidgetStateStore.publishRoomStatus(
                applicationContext,
                WidgetRoom.ROOM_TWO,
                WidgetShowerState.OPEN,
                System.currentTimeMillis(),
            )
            withContext(Dispatchers.Main) {
                startActivity(
                    Intent(this@MarketingSeedActivity, MainActivity::class.java)
                        .setAction(Intent.ACTION_MAIN)
                        .putExtra("marketing_capture", true)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
                finish()
            }
        }
    }
}
