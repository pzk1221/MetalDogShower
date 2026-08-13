package com.panzhikun.metaldogshower

import android.content.Context
import androidx.core.content.edit

class OnboardingStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun shouldShow(): Boolean = !preferences.getBoolean(KEY_COMPLETED, false)

    fun markCompleted() {
        preferences.edit { putBoolean(KEY_COMPLETED, true) }
    }

    private companion object {
        const val PREFERENCES_NAME = "onboarding_preferences"
        const val KEY_COMPLETED = "guide_v1_completed"
    }
}
