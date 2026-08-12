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

    @Query("UPDATE recordings SET durationMs = :duration, fileSizeBytes = :size, status = :status WHERE id = :id")
    suspend fun updateMetadata(id: Long, duration: Long, size: Long, status: String)

    @Query("UPDATE recordings SET bookmarks = :bookmarks WHERE id = :id")
    suspend fun updateBookmarks(id: Long, bookmarks: String)

    @Query("UPDATE recordings SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE recordings SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("SELECT * FROM recordings WHERE status IN ('PENDING', 'INTERRUPTED')")
    suspend fun getIncomplete(): List<RecordingEntity>

    @Query("DELETE FROM recordings WHERE status = 'FAILED' AND createdAt < :olderThanTimestamp")
    suspend fun deleteOldFailed(olderThanTimestamp: Long): Int

    @Query("SELECT * FROM recordings WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): RecordingEntity?
}
