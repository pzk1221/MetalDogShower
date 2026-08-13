package com.panzhikun.metaldogshower.wear

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.panzhikun.metaldogshower.wear.controller.FakeShowerController
import com.panzhikun.metaldogshower.wear.controller.ControllerResult
import com.panzhikun.metaldogshower.wear.controller.RealShowerController
import com.panzhikun.metaldogshower.wear.controller.ShowerController
import com.panzhikun.metaldogshower.wear.ui.ShowerApp
import com.panzhikun.metaldogshower.wear.provisioning.WatchSyncRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var localLoadJob: Job? = null

    private val controller: ShowerController by lazy(LazyThreadSafetyMode.NONE) {
        if (BuildConfig.USE_FAKE_BACKEND) {
            FakeShowerController(
                showDemoLabel = !intent.getBooleanExtra(EXTRA_MARKETING_CAPTURE, false),
            )
        } else {
            RealShowerController(applicationContext)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShowerApp(controller = controller)
        }
        reloadLocalState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        reloadLocalState()
    }

    override fun onDestroy() {
        localLoadJob?.cancel()
        (controller as? AutoCloseable)?.close()
        super.onDestroy()
    }

    private fun reloadLocalState() {
        // A fresh app/Tile launch must never inherit an old room selection.
        // Local binding bootstrap is deliberately separate from status refresh. Opening the watch
        // app must never enter a network "checking" phase or require the phone app to be open.
        val previousLoad = localLoadJob
        localLoadJob = lifecycleScope.launch {
            previousLoad?.cancelAndJoin()
            withContext(Dispatchers.IO) {
                controller.clearSelection()
                val result = controller.loadLocalBinding()
                if (!BuildConfig.USE_FAKE_BACKEND && result == ControllerResult.Unauthorized) {
                    WatchSyncRequester.requestIfMissing(applicationContext)
                }
            }
        }
    }

    private companion object {
        const val EXTRA_MARKETING_CAPTURE = "marketing_capture"
    }
}
