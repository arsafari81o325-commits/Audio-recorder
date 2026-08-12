package com.example.audiorecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "audio_recorder_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_BOOKMARK = "ACTION_BOOKMARK"
        const val ACTION_PAUSE_RESUME = "ACTION_PAUSE_RESUME"
    }

    init {
        createChannel()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ضبط صدای داخلی",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "نوتیفیکیشن کنترل ضبط صدا"
            setSound(null, null)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, AudioCaptureService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun buildInitializingNotification(): Notification {
        val contentIntent = Intent(context, MainActivity::class.java)
        val contentPending = PendingIntent.getActivity(
            context, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("در حال آماده‌سازی ضبط...")
            .setContentText("لطفاً چند لحظه صبر کنید")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(contentPending)
            .build()
    }

    fun buildRecordingNotification(
        elapsedSeconds: Int,
        bookmarkCount: Int = 0,
        isPaused: Boolean = false
    ): Notification {
        val contentIntent = Intent(context, MainActivity::class.java)
        val contentPending = PendingIntent.getActivity(
            context, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val time = String.format("%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60)
        val subtitle = buildString {
            append(if (isPaused) "⏸ متوقف موقت  •  " else "")
            append("زمان: $time")
            if (bookmarkCount > 0) append("  •  علامت‌ها: $bookmarkCount")
        }

        val pauseResumeLabel = if (isPaused) "ادامه" else "توقف موقت"
        val pauseResumeIcon = if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(
                if (isPaused) "ضبط صدای داخلی متوقف شده"
                else "در حال ضبط صدای داخلی"
            )
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(contentPending)
            .addAction(pauseResumeIcon, pauseResumeLabel, servicePendingIntent(ACTION_PAUSE_RESUME, 2))
            .addAction(android.R.drawable.ic_menu_save, "علامت", servicePendingIntent(ACTION_BOOKMARK, 1))
            .addAction(android.R.drawable.ic_delete, "توقف", servicePendingIntent(ACTION_STOP, 0))
            .build()
    }
}
