package com.example.audiorecorder

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AudioCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioCaptureManager: AudioCaptureManager? = null
    private var notificationHelper: NotificationHelper? = null
    private var notificationManager: NotificationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var recordingJob: Job? = null
    private var elapsedSeconds = 0
    private var outputFile: File? = null
    private var currentRecordingId: Long = -1
    private val bookmarks = mutableListOf<Long>()
    private var startTimeMs = 0L
    private var pausedAccumulatedMs = 0L
    private var pauseStartedAtMs = 0L

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped by the system")
            mainHandler.post { stopRecording(fromSystem = true) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        notificationManager = getSystemService(NotificationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (_isRecording.value) return START_NOT_STICKY
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (resultCode != -1 && data != null) {
                    startRecordingSafely(resultCode, data)
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> stopRecording(fromSystem = false)
            ACTION_BOOKMARK -> addBookmark()
            ACTION_PAUSE_RESUME -> togglePauseResume()
        }
        return START_NOT_STICKY
    }

    private fun hasEnoughStorage(): Boolean {
        return try {
            val dir = getExternalFilesDir(null) ?: return true
            val stat = StatFs(dir.path)
            stat.availableBytes > MIN_FREE_SPACE_BYTES
        } catch (e: Exception) {
            true // don't block recording just because the check itself failed
        }
    }

    private fun startRecordingSafely(resultCode: Int, data: Intent) {
        try {
            if (!hasEnoughStorage()) {
                throw IllegalStateException("فضای ذخیره‌سازی کافی نیست (حداقل ۵۰ مگابایت لازم است)")
            }

            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection در دسترس نیست")
            mediaProjection = projection
            projection.registerCallback(projectionCallback, mainHandler)

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "recording_$timeStamp.aac"
            val dir = File(getExternalFilesDir(null), "Recordings").apply { mkdirs() }
            outputFile = File(dir, fileName)

            val quality = (application as AudioRecorderApp).preferences.quality

            val manager = AudioCaptureManager(projection, quality)
            manager.onAmplitude = { level -> _amplitude.value = level }
            manager.onError = { e ->
                mainHandler.post {
                    Toast.makeText(this, "خطا در حین ضبط: ${e.message}", Toast.LENGTH_LONG).show()
                    stopRecording(fromSystem = false)
                }
            }
            manager.startRecording(outputFile!!)
            audioCaptureManager = manager

            acquireWakeLock()

            startTimeMs = System.currentTimeMillis()
            pausedAccumulatedMs = 0
            elapsedSeconds = 0
            bookmarks.clear()
            currentRecordingId = -1

            serviceScope.launch {
                val app = application as AudioRecorderApp
                val entity = RecordingEntity(
                    fileName = fileName,
                    filePath = outputFile!!.absolutePath,
                    quality = quality.name
                )
                currentRecordingId = app.repository.insert(entity)
            }

            val initialNotification = notificationHelper!!.buildRecordingNotification(0)
            startForeground(NotificationHelper.NOTIFICATION_ID, initialNotification)

            _isRecording.value = true
            _isPaused.value = false
            _elapsedSeconds.value = 0

            recordingJob = serviceScope.launch {
                while (isActive) {
                    delay(1000)
                    if (!_isPaused.value) {
                        elapsedSeconds++
                        _elapsedSeconds.value = elapsedSeconds
                    }
                    val notification = notificationHelper!!.buildRecordingNotification(
                        elapsedSeconds, bookmarks.size, _isPaused.value
                    )
                    notificationManager?.notify(NotificationHelper.NOTIFICATION_ID, notification)
                }
            }

            Log.d(TAG, "Service started recording (${quality.name})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            Toast.makeText(this, "خطا در شروع ضبط: ${e.message}", Toast.LENGTH_LONG).show()
            cleanupAfterFailure()
            _isRecording.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun togglePauseResume() {
        val manager = audioCaptureManager ?: return
        if (_isPaused.value) {
            manager.resume()
            pausedAccumulatedMs += System.currentTimeMillis() - pauseStartedAtMs
            _isPaused.value = false
            Toast.makeText(this, "ضبط ادامه یافت", Toast.LENGTH_SHORT).show()
        } else {
            manager.pause()
            pauseStartedAtMs = System.currentTimeMillis()
            _isPaused.value = true
            Toast.makeText(this, "ضبط موقتاً متوقف شد", Toast.LENGTH_SHORT).show()
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioRecorder::RecordingWakeLock").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // safety cap: 4 hours max
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun cleanupAfterFailure() {
        try { audioCaptureManager?.stopRecording() } catch (_: Exception) {}
        audioCaptureManager = null
        try { mediaProjection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        releaseWakeLock()
        outputFile?.takeIf { it.exists() && it.length() == 0L }?.delete()
    }

    private fun addBookmark() {
        if (startTimeMs > 0 && _isRecording.value && !_isPaused.value) {
            val mark = System.currentTimeMillis() - startTimeMs - pausedAccumulatedMs
            bookmarks.add(mark)
            Toast.makeText(this, "🔖 علامت اضافه شد (${bookmarks.size})", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Bookmark added at $mark ms")
        }
    }

    private fun stopRecording(fromSystem: Boolean) {
        if (!_isRecording.value && audioCaptureManager == null) return

        recordingJob?.cancel()
        audioCaptureManager?.stopRecording()

        val duration = if (startTimeMs > 0) {
            (System.currentTimeMillis() - startTimeMs - pausedAccumulatedMs).coerceAtLeast(0)
        } else 0
        val size = outputFile?.length() ?: 0
        val bookmarksCsv = bookmarks.joinToString(",")
        val recordingId = currentRecordingId

        serviceScope.launch {
            if (recordingId != -1L) {
                val app = application as AudioRecorderApp
                app.repository.updateMetadata(recordingId, duration, size)
                app.repository.updateBookmarks(recordingId, bookmarksCsv)
            }
        }

        try { mediaProjection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        audioCaptureManager = null
        releaseWakeLock()

        _isRecording.value = false
        _isPaused.value = false
        _elapsedSeconds.value = 0
        _amplitude.value = 0

        if (fromSystem) {
            Toast.makeText(this, "ضبط صفحه توسط سیستم متوقف شد", Toast.LENGTH_SHORT).show()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.d(TAG, "Service stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try { audioCaptureManager?.stopRecording() } catch (_: Exception) {}
        try { mediaProjection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        releaseWakeLock()
        _isRecording.value = false
    }

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_BOOKMARK = "ACTION_BOOKMARK"
        const val ACTION_PAUSE_RESUME = "ACTION_PAUSE_RESUME"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"
        private const val TAG = "AudioCaptureService"
        private const val MIN_FREE_SPACE_BYTES = 50L * 1024 * 1024 // 50 MB

        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording

        private val _isPaused = MutableStateFlow(false)
        val isPaused: StateFlow<Boolean> = _isPaused

        private val _elapsedSeconds = MutableStateFlow(0)
        val elapsedSecondsFlow: StateFlow<Int> = _elapsedSeconds

        private val _amplitude = MutableStateFlow(0)
        val amplitudeFlow: StateFlow<Int> = _amplitude
    }
}
