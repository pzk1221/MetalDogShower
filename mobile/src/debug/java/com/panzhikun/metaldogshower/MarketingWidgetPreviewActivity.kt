package com.panzhikun.metaldogshower

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView

/** Renders the real expanded RemoteViews layout for marketing screenshot QA. */
class MarketingWidgetPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(225, 229, 238))
        }
        val title = TextView(this).apply {
            text = "桌面小组件"
            textSize = 22f
            setTextColor(Color.rgb(17, 22, 56))
            gravity = Gravity.CENTER
        }
        root.addView(
            title,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(64)).apply {
                gravity = Gravity.TOP
                topMargin = dp(48)
                marginStart = dp(24)
                marginEnd = dp(24)
            },
        )

        val remoteViews = RemoteViews(packageName, R.layout.shower_control_widget_expanded).apply {
            setTextViewText(R.id.widget_status, "已关闭")
            setViewVisibility(R.id.widget_live_dot, View.VISIBLE)
        }
        val widget = remoteViews.apply(this, root)
        root.addView(
            widget,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(190)).apply {
                gravity = Gravity.CENTER
                marginStart = dp(22)
                marginEnd = dp(22)
            },
        )
        setContentView(root)
    }
}
