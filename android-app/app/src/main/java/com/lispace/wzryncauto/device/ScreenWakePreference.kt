package com.lispace.wzryncauto.device

import android.content.Context

object ScreenWakePreference {
    private const val PREFERENCES = "run_preferences"
    private const val KEY_KEEP_AWAKE = "keep_screen_awake"

    // The previous implementation kept the screen awake during each active
    // round, so preserve that behavior as the default while making it an
    // explicit user setting for the whole task.
    fun load(context: Context): Boolean = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(KEY_KEEP_AWAKE, true)

    fun save(context: Context, keepAwake: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_KEEP_AWAKE, keepAwake)
            .apply()
    }
}
