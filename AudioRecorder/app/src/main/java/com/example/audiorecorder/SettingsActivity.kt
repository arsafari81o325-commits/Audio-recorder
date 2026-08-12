package com.example.audiorecorder

import android.os.Bundle
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class SettingsActivity : AppCompatActivity() {

    private lateinit var preferences: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        preferences = (application as AudioRecorderApp).preferences

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val radioGroup = findViewById<RadioGroup>(R.id.radioGroupQuality)
        val radioLow = findViewById<android.widget.RadioButton>(R.id.radioLow)
        val radioMedium = findViewById<android.widget.RadioButton>(R.id.radioMedium)
        val radioHigh = findViewById<android.widget.RadioButton>(R.id.radioHigh)

        radioLow.text = RecordingQuality.LOW.label
        radioMedium.text = RecordingQuality.MEDIUM.label
        radioHigh.text = RecordingQuality.HIGH.label

        when (preferences.quality) {
            RecordingQuality.LOW -> radioGroup.check(R.id.radioLow)
            RecordingQuality.MEDIUM -> radioGroup.check(R.id.radioMedium)
            RecordingQuality.HIGH -> radioGroup.check(R.id.radioHigh)
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            // Changing quality mid-recording has no effect until the next
            // recording starts (it's read once at start), which is expected.
            val newQuality = when (checkedId) {
                R.id.radioLow -> RecordingQuality.LOW
                R.id.radioMedium -> RecordingQuality.MEDIUM
                else -> RecordingQuality.HIGH
            }
            preferences.quality = newQuality
            if (AudioCaptureService.isRecording.value) {
                Toast.makeText(this, "این تغییر از ضبط بعدی اعمال می‌شود", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
