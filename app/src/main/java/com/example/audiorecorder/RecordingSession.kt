package com.example.audiorecorder

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class RecordingSessionState {
    object Idle : RecordingSessionState()
    object Initializing : RecordingSessionState()
    data class Recording(
        val elapsedSeconds: Int,
        val amplitude: Int,
        val bookmarkCount: Int,
        val quality: RecordingQuality
    ) : RecordingSessionState()
    data class Paused(
        val elapsedSeconds: Int,
        val bookmarkCount: Int,
        val quality: RecordingQuality
    ) : RecordingSessionState()
    object Stopping : RecordingSessionState()
    data class Error(val error: RecordingError) : RecordingSessionState()
}

sealed class RecordingError(val userMessage: String) {
    object StorageFull : RecordingError("فضای ذخیره‌سازی کافی نیست. لطفاً فضای خالی ایجاد کنید.")
    object ProjectionDenied : RecordingError("مجوز ضبط صفحه لغو یا داده نشد.")
    object CodecUnavailable : RecordingError("کدک صوتی در دسترس نیست. لطفاً دستگاه را راه‌اندازی مجدد کنید.")
    object AudioCaptureUnavailable : RecordingError("ضبط صدا روی این دستگاه پشتیبانی نمی‌شود.")
    object Unknown : RecordingError("خطایی در ضبط رخ داد. لطفاً دوباره تلاش کنید.")
}

class RecordingSession {
    private val _state = MutableStateFlow<RecordingSessionState>(RecordingSessionState.Idle)
    val state: StateFlow<RecordingSessionState> = _state.asStateFlow()

    internal fun setInitializing() { _state.value = RecordingSessionState.Initializing }
    internal fun setRecording(elapsed: Int, amp: Int, marks: Int, q: RecordingQuality) {
        _state.value = RecordingSessionState.Recording(elapsed, amp, marks, q)
    }
    internal fun setPaused(elapsed: Int, marks: Int, q: RecordingQuality) {
        _state.value = RecordingSessionState.Paused(elapsed, marks, q)
    }
    internal fun setStopping() { _state.value = RecordingSessionState.Stopping }
    internal fun setIdle() { _state.value = RecordingSessionState.Idle }
    internal fun setError(error: RecordingError) { _state.value = RecordingSessionState.Error(error) }
}
