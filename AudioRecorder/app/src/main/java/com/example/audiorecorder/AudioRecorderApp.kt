package com.example.audiorecorder

import android.app.Application

class AudioRecorderApp : Application() {
    val database by lazy { RecordingDatabase.getDatabase(this) }
    val repository by lazy { RecordingRepository(database.recordingDao()) }
    val preferences by lazy { PreferencesManager(this) }
}
