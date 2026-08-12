package com.example.audiorecorder

import kotlinx.coroutines.flow.Flow

class RecordingRepository(private val recordingDao: RecordingDao) {
    val allRecordings: Flow<List<RecordingEntity>> = recordingDao.getAll()

    suspend fun insert(recording: RecordingEntity): Long = recordingDao.insert(recording)
    suspend fun delete(recording: RecordingEntity) = recordingDao.delete(recording)
    suspend fun getById(id: Long): RecordingEntity? = recordingDao.getById(id)
    suspend fun updateMetadata(id: Long, duration: Long, size: Long) =
        recordingDao.updateMetadata(id, duration, size)
    suspend fun updateBookmarks(id: Long, bookmarks: String) =
        recordingDao.updateBookmarks(id, bookmarks)
    suspend fun rename(id: Long, title: String) = recordingDao.rename(id, title)
}
