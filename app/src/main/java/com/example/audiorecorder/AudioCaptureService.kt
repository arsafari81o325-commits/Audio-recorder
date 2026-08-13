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
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

class AudioCaptureService : Service() {

    private lateinit var session: RecordingSession
    private lateinit var repository: RecordingRepository
    private var mediaProjection: MediaProjection? = null
    private var audioCaptureManager: AudioCaptureManager? = null
    private var notificationHelper: NotificationHelper? = null
    private var notificationManager: NotificationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var recordingJob: Job? = null
    private var storageMonitorJob: Job? = null
    private var elapsedSeconds = 0
    private var outputFile: File? = null
    private var currentRecordingId: Long = -1
    private var currentRecordingUuid: String = ""
    private val bookmarks = mutableListOf<Long>()
    private var startTimeMs = 0L
    private var pausedAccumulatedMs = 0L
    private var pauseStartedAtMs = 0L
    private var currentQuality: RecordingQuality = RecordingQuality.HIGH

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped by the system")
            mainHandler.post { handleStopRequest(fromSystem = true) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as AudioRecorderApp
        session = app.recordingSession
        repository = app.repository
        notificationHelper = NotificationHelper(this)
        notificationManager = getSystemService(NotificationManager::class.java)

        serviceScope.launch { recoverInterruptedSessions() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // CRITICAL FIX: startForeground() must be called immediately,
                // before any other logic, whenever this service is launched
                // via startForegroundService(). Doing state/validity checks
                // first (and possibly returning early) risks the system
                // killing the app with ForegroundServiceDidNotStartInTimeException
                // if 5 seconds pass without startForeground() being called.
                val initNotification = notificationHelper!!.buildInitializingNotification()
                startForeground(NotificationHelper.NOTIFICATION_ID, initNotification)

                if (session.state.value != RecordingSessionState.Idle) {
                    Log.w(TAG, "Start requested but state is ${session.state.value}, resetting and ignoring")
                    session.setIdle()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (resultCode != -1 && data != null) {
                    startRecordingSafely(resultCode, data)
                } else {
                    session.setError(RecordingError.ProjectionDenied)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_STOP -> handleStopRequest(fromSystem = false)
            ACTION_BOOKMARK -> addBookmark()
            ACTION_PAUSE_RESUME -> togglePauseResume()
        }
        return START_NOT_STICKY
    }

    private fun hasEnoughStorage(thresholdBytes: Long): Boolean {
        return try {
            val dir = getExternalFilesDir(null) ?: return true
            val stat = StatFs(dir.path)
            stat.availableBytes > thresholdBytes
        } catch (e: Exception) { true }
    }

    private fun generateUniqueOutputFile(): File {
        val dir = File(getExternalFilesDir(null), "Recordings").apply { mkdirs() }
        var attempt = 0
        var file: File
        do {
            val uuid = UUID.randomUUID().toString()
            file = File(dir, "rec_${uuid}.aac")
            attempt++
        } while (file.exists() && attempt < 10)
        if (file.exists()) {
            file = File(dir, "rec_${System.currentTimeMillis()}_${(0..9999).random()}.aac")
        }
        return file
    }

    private fun startRecordingSafely(resultCode: Int, data: Intent) {
        try {
            if (!hasEnoughStorage(MIN_FREE_SPACE_BYTES)) throw IllegalStateException("Storage full")

            session.setInitializing()

            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection unavailable")

            mediaProjection = projection
            projection.registerCallback(projectionCallback, mainHandler)

            outputFile = generateUniqueOutputFile()
            currentRecordingUuid = outputFile!!.nameWithoutExtension
            currentQuality = (application as AudioRecorderApp).preferences.quality

            val manager = AudioCaptureManager(projection, currentQuality)
            manager.onAmplitude = { level ->
                val current = session.state.value
                if (current is RecordingSessionState.Recording) {
                    session.setRecording(elapsedSeconds, level, bookmarks.size, currentQuality)
                }
            }
            manager.onError = { e ->
                mainHandler.post {
                    Log.e(TAG, "Capture error detail: ${e.message}", e)
                    session.setError(RecordingError.Unknown)
                    handleStopRequest(fromSystem = false, fromError = true)
                }
            }
            manager.onDiskFull = {
                mainHandler.post {
                    Log.e(TAG, "Disk full detected by AudioCaptureManager")
                    session.setError(RecordingError.StorageFull)
                    handleStopRequest(fromSystem = false, fromError = true)
                }
            }
            manager.startRecording(outputFile!!)
            audioCaptureManager = manager

            acquireWakeLock()

            startTimeMs = SystemClock.elapsedRealtime()
            pausedAccumulatedMs = 0
            elapsedSeconds = 0
            bookmarks.clear()
            currentRecordingId = -1

            val entity = RecordingEntity(
                fileName = outputFile!!.name,
                filePath = outputFile!!.absolutePath,
                quality = currentQuality.name,
                status = RecordingStatus.PENDING.name,
                uuid = currentRecordingUuid
            )
            currentRecordingId = runBlocking(Dispatchers.IO) {
                repository.insert(entity)
            }

            session.setRecording(0, 0, 0, currentQuality)

            recordingJob = serviceScope.launch {
                while (isActive) {
                    delay(1000)
                    when (val current = session.state.value) {
                        is RecordingSessionState.Recording -> {
                            elapsedSeconds++
                            session.setRecording(elapsedSeconds, current.amplitude, bookmarks.size, currentQuality)
                            val n = notificationHelper!!.buildRecordingNotification(
                                elapsedSeconds, bookmarks.size, isPaused = false
                            )
                            notificationManager?.notify(NotificationHelper.NOTIFICATION_ID, n)
                        }
                        is RecordingSessionState.Paused -> {
                            val n = notificationHelper!!.buildRecordingNotification(
                                elapsedSeconds, bookmarks.size, isPaused = true
                            )
                            notificationManager?.notify(NotificationHelper.NOTIFICATION_ID, n)
                        }
                        else -> {}
                    }
                }
            }

            startStorageMonitor()

            Log.d(TAG, "Recording started (${currentQuality.name}): ${outputFile!!.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            val error = when {
                e.message?.contains("Storage full", ignoreCase = true) == true -> RecordingError.StorageFull
                e.message?.contains("MediaProjection", ignoreCase = true) == true -> RecordingError.ProjectionDenied
                e.message?.contains("AudioRecord", ignoreCase = true) == true -> RecordingError.AudioCaptureUnavailable
                else -> RecordingError.Unknown
            }
            session.setError(error)
            if (currentRecordingId != -1L) {
                serviceScope.launch {
                    repository.updateStatus(currentRecordingId, RecordingStatus.FAILED)
                }
            }
            cleanupAfterFailure()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startStorageMonitor() {
        storageMonitorJob = serviceScope.launch {
            while (isActive) {
                delay(10_000)
                val manager = audioCaptureManager ?: break
                val fileSize = manager.getOutputFileSize()

                when {
                    !hasEnoughStorage(CRITICAL_FREE_SPACE_BYTES) -> {
                        Log.w(TAG, "Storage critical (<5MB). Stopping recording.")
                        mainHandler.post {
                            session.setError(RecordingError.StorageFull)
                            handleStopRequest(fromSystem = false, fromError = true)
                        }
                        break
                    }
                    !hasEnoughStorage(WARNING_FREE_SPACE_BYTES) -> {
                        Log.w(TAG, "Storage warning (<25MB). File size so far: $fileSize")
                        val n = notificationHelper!!.buildRecordingNotification(
                            elapsedSeconds, bookmarks.size,
                            isPaused = session.state.value is RecordingSessionState.Paused
                        )
                        notificationManager?.notify(NotificationHelper.NOTIFICATION_ID, n)
                    }
                }
            }
        }
    }

    private fun togglePauseResume() {
        val manager = audioCaptureManager ?: return
        when (val current = session.state.value) {
            is RecordingSessionState.Recording -> {
                manager.pause()
                pauseStartedAtMs = SystemClock.elapsedRealtime()
                session.setPaused(elapsedSeconds, bookmarks.size, currentQuality)
            }
            is RecordingSessionState.Paused -> {
                manager.resume()
                pausedAccumulatedMs += SystemClock.elapsedRealtime() - pauseStartedAtMs
                session.setRecording(elapsedSeconds, 0, bookmarks.size, currentQuality)
            }
            else -> {}
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioRecorder::RecordingWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun cleanupAfterFailure() {
        try { audioCaptureManager?.stopRecording() } catch (_: Exception) {}
        audioCaptureManager = null
        try { mediaProjection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        releaseWakeLock()
        storageMonitorJob?.cancel()
        outputFile?.takeIf { it.exists() && it.length() < 1024 }?.delete()
    }

    private fun addBookmark() {
        val current = session.state.value
        if (current is RecordingSessionState.Recording && startTimeMs > 0) {
            val mark = SystemClock.elapsedRealtime() - startTimeMs - pausedAccumulatedMs
            bookmarks.add(mark)
            session.setRecording(elapsedSeconds, current.amplitude, bookmarks.size, currentQuality)
            Log.d(TAG, "Bookmark added at $mark ms")
        }
    }

    private fun handleStopRequest(fromSystem: Boolean, fromError: Boolean = false) {
        val current = session.state.value
        if (current is RecordingSessionState.Stopping || current is RecordingSessionState.Idle) {
            Log.d(TAG, "Stop requested but state is $current, ignoring")
            return
        }
        stopRecording(fromSystem, fromError)
    }

    private fun stopRecording(fromSystem: Boolean, fromError: Boolean = false) {
        if (session.state.value is RecordingSessionState.Stopping) return
        session.setStopping()

        recordingJob?.cancel()
        storageMonitorJob?.cancel()
        audioCaptureManager?.stopRecording()

        val managerPauseMs = audioCaptureManager?.totalPausedDurationMs ?: 0
        val servicePauseMs = pausedAccumulatedMs
        val effectivePauseMs = maxOf(managerPauseMs, servicePauseMs)

        val rawDuration = if (startTimeMs > 0) SystemClock.elapsedRealtime() - startTimeMs else 0
        val duration = (rawDuration - effectivePauseMs).coerceAtLeast(0)
        val size = outputFile?.length() ?: 0
        val bookmarksCsv = bookmarks.joinToString(",")
        val recordingId = currentRecordingId

        val finalStatus = when {
            fromError -> {
                if (size > 1024 && outputFile?.exists() == true) RecordingStatus.INTERRUPTED
                else RecordingStatus.FAILED
            }
            fromSystem -> RecordingStatus.INTERRUPTED
            size < 1024 -> RecordingStatus.FAILED
            else -> RecordingStatus.COMPLETED
        }

        serviceScope.launch {
            if (recordingId != -1L) {
                repository.updateMetadata(recordingId, duration, size, finalStatus)
                repository.updateBookmarks(recordingId, bookmarksCsv)
            }
        }

        try { mediaProjection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        audioCaptureManager = null
        releaseWakeLock()

        if (finalStatus == RecordingStatus.FAILED && size < 1024 && recordingId != -1L) {
            serviceScope.launch {
                delay(500)
                outputFile?.delete()
                repository.getById(recordingId)?.let { repository.deleteAtomic(it) }
            }
        }

        if (session.state.value !is RecordingSessionState.Error) {
            session.setIdle()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.d(TAG, "Stopped (status=$finalStatus, duration=$duration, size=$size)")
    }

    private suspend fun recoverInterruptedSessions() {
        try {
            val incomplete = repository.getIncompleteRecordings()
            for (record in incomplete) {
                val file = File(record.filePath)
                when {
                    !file.exists() || file.length() == 0L -> {
                        repository.deleteAtomic(record)
                        Log.d(TAG, "Recovered orphan DB record: ${record.uuid}")
                    }
                    else -> {
                        repository.updateStatus(record.id, RecordingStatus.INTERRUPTED)
                        Log.d(TAG, "Recovered interrupted recording: ${record.uuid}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recovery failed", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        storageMonitorJob?.cancel()
        try { audioCaptureManager?.stopRecording() } catch (_: Exception) {}
        try { mediaProjection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        releaseWakeLock()
        if (session.state.value !is RecordingSessionState.Error) {
            session.setIdle()
        }
    }

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_BOOKMARK = "ACTION_BOOKMARK"
        const val ACTION_PAUSE_RESUME = "ACTION_PAUSE_RESUME"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"
        private const val TAG = "AudioCaptureService"
        private const val MIN_FREE_SPACE_BYTES = 50L * 1024 * 1024
        private const val WARNING_FREE_SPACE_BYTES = 25L * 1024 * 1024
        private const val CRITICAL_FREE_SPACE_BYTES = 5L * 1024 * 1024
    }
}
