package com.example.audiorecorder

import android.media.AudioFormat

enum class RecordingQuality(
    val label: String,
    val sampleRate: Int,
    val channelConfig: Int,
    val channelCount: Int,
    val bitRate: Int
) {
    LOW(
        label = "کم (فایل کوچک)",
        sampleRate = 24000,
        channelConfig = AudioFormat.CHANNEL_IN_MONO,
        channelCount = 1,
        bitRate = 48000
    ),
    MEDIUM(
        label = "متوسط",
        sampleRate = 44100,
        channelConfig = AudioFormat.CHANNEL_IN_STEREO,
        channelCount = 2,
        bitRate = 96000
    ),
    HIGH(
        label = "بالا (پیش‌فرض)",
        sampleRate = 48000,
        channelConfig = AudioFormat.CHANNEL_IN_STEREO,
        channelCount = 2,
        bitRate = 128000
    );

    companion object {
        fun fromName(name: String?): RecordingQuality =
            entries.firstOrNull { it.name == name } ?: HIGH
    }
}
