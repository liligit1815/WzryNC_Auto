package com.lili.wzryfarm.device

import android.content.Context

enum class RunBrightnessMode(val label: String) {
    KEEP("保持当前亮度"),
    SYSTEM_LOW("系统最低亮度"),
    ROOT_LOW("ROOT 极低亮度"),
}

object BrightnessPreference {
    private const val PREFERENCES = "run_preferences"
    private const val KEY_MODE = "brightness_mode"

    fun load(context: Context): RunBrightnessMode {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null)
        return RunBrightnessMode.entries.firstOrNull { it.name == stored }
            ?: RunBrightnessMode.KEEP
    }

    fun save(context: Context, mode: RunBrightnessMode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
    }
}
