package com.example.audiorecorder

import android.content.Context
import android.content.SharedPreferences

enum class SortMode { DATE_DESC, DATE_ASC, NAME_ASC, DURATION_DESC, SIZE_DESC }

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var quality: RecordingQuality
        get() = RecordingQuality.fromName(prefs.getString(KEY_QUALITY, RecordingQuality.HIGH.name))
        set(value) = prefs.edit().putString(KEY_QUALITY, value.name).apply()

    var sortMode: SortMode
        get() = runCatching {
            SortMode.valueOf(prefs.getString(KEY_SORT, SortMode.DATE_DESC.name)!!)
        }.getOrDefault(SortMode.DATE_DESC)
        set(value) = prefs.edit().putString(KEY_SORT, value.name).apply()

    var hasRequestedBatteryOptIgnore: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_PROMPT_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_PROMPT_SHOWN, value).apply()

    companion object {
        private const val PREFS_NAME = "audio_recorder_prefs"
        private const val KEY_QUALITY = "recording_quality"
        private const val KEY_SORT = "sort_mode"
        private const val KEY_BATTERY_PROMPT_SHOWN = "battery_prompt_shown"
    }
}
