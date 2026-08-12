package com.example.audiorecorder

import kotlinx.coroutines.flow.Flow
import java.io.File

class RecordingRepository(private val recordingDao: RecordingDao) {
    val allRecordings: Flow<List<RecordingEntity>> = recordingDao.getAll()

    suspend fun insert(recording: RecordingEntity): Long = recordingDao.insert(recording)
    suspend fun getById(id: Long): RecordingEntity? = recordingDao.getById(id)
    suspend fun getByUuid(uuid: String): RecordingEntity? = recordingDao.getByUuid(uuid)

    suspend fun updateMetadata(id: Long, duration: Long, size: Long, status: RecordingStatus) =
        recordingDao.updateMetadata(id, duration, size, status.name)

    suspend fun updateBookmarks(id: Long, bookmarks: String) =
        recordingDao.updateBookmarks(id, bookmarks)

    suspend fun updateStatus(id: Long, status: RecordingStatus) =
        recordingDao.updateStatus(id, status.name)

    suspend fun rename(id: Long, title: String) = recordingDao.rename(id, title)

    suspend fun deleteAtomic(item: RecordingEntity): Boolean {
        return try {
            recordingDao.delete(item)
            val file = File(item.filePath)
            if (file.exists()) file.delete() else true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getIncompleteRecordings(): List<RecordingEntity> = recordingDao.getIncomplete()

    suspend fun cleanupOldFailed(cutoffMillis: Long): Int =
        recordingDao.deleteOldFailed(cutoffMillis)
}
