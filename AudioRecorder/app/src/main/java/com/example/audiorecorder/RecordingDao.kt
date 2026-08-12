package com.example.audiorecorder

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun getAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): RecordingEntity?

    @Insert
    suspend fun insert(recording: RecordingEntity): Long

    @Delete
    suspend fun delete(recording: RecordingEntity)

    @Query("UPDATE recordings SET durationMs = :duration, fileSizeBytes = :size WHERE id = :id")
    suspend fun updateMetadata(id: Long, duration: Long, size: Long)

    @Query("UPDATE recordings SET bookmarks = :bookmarks WHERE id = :id")
    suspend fun updateBookmarks(id: Long, bookmarks: String)

    @Query("UPDATE recordings SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)
}
