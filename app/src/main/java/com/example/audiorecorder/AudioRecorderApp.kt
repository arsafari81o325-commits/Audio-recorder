package com.example.audiorecorder

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class AudioRecorderApp : Application() {
    val database by lazy { RecordingDatabase.getDatabase(this) }
    val repository by lazy { RecordingRepository(database.recordingDao()) }
    val preferences by lazy { PreferencesManager(this) }
    val recordingSession by lazy { RecordingSession() }

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val crashFile = File(getExternalFilesDir(null), "last_crash.txt")
                crashFile.writeText(sw.toString())
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
