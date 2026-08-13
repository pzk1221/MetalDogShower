package com.panzhikun.metaldogshower.wear.tile

import android.content.ComponentName
import androidx.wear.protolayout.ActionBuilders.launchAction
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.material3.Typography.BODY_LARGE
import androidx.wear.protolayout.material3.Typography.BODY_MEDIUM
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.RequestBuilders.ResourcesRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.panzhikun.metaldogshower.wear.MainActivity
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The tile deliberately contains no switch action. A tap only opens the app,
 * where a second, explicit confirmation is required before any control request.
 */
class ShowerTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest) =
        immediateFuture(
            Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(
                    Timeline.fromLayoutElement(
                        materialScope(this, requestParams.deviceConfiguration) {
                            primaryLayout(
                                titleSlot = {
                                    text("淋浴控制".layoutString, typography = BODY_MEDIUM)
                                },
                                mainSlot = {
                                    text("打开应用后确认".layoutString, typography = BODY_LARGE)
                                },
                                bottomSlot = {
                                    textEdgeButton(
                                        labelContent = { text("打开".layoutString) },
                                        onClick = clickable(
                                            action = launchAction(
                                                ComponentName(
                                                    this@ShowerTileService,
                                                    MainActivity::class.java,
                                                ),
                                            ),
                                        ),
                                    )
                                },
                            )
                        },
                    ),
                )
                .build(),
        )

    override fun onTileResourcesRequest(requestParams: ResourcesRequest) =
        immediateFuture(Resources.Builder().setVersion(RESOURCES_VERSION).build())

    private companion object {
        const val RESOURCES_VERSION = "1"
    }
}

/** Avoids pulling the full Guava implementation into a low-RAM watch APK. */
private fun <T> immediateFuture(value: T): ListenableFuture<T> =
    object : ListenableFuture<T> {
        override fun addListener(listener: Runnable, executor: Executor) {
            executor.execute(listener)
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        override fun isCancelled(): Boolean = false
        override fun isDone(): Boolean = true
        override fun get(): T = value
        override fun get(timeout: Long, unit: TimeUnit): T = value
    }
