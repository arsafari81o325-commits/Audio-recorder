package com.example.audiorecorder

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * @param title       optional custom user-given name (renamed). If blank, [fileName] is shown.
 * @param bookmarks   comma-separated list of bookmark offsets in ms, relative to recording start.
 * @param quality     the RecordingQuality name used when this file was recorded (for display only).
 */
@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileName: String,
    val filePath: String,
    val durationMs: Long = 0,
    val fileSizeBytes: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val bookmarks: String = "",
    val title: String = "",
    val quality: String = RecordingQuality.HIGH.name
) {
    val displayName: String get() = title.ifBlank { fileName }
}
