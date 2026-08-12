package com.example.audiorecorder

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class RecordingListViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as AudioRecorderApp).repository
    private val preferences = (application as AudioRecorderApp).preferences

    private val _searchQuery = MutableStateFlow("")
    private val _sortMode = MutableStateFlow(preferences.sortMode)
    val sortMode: StateFlow<SortMode> = _sortMode

    val recordings: StateFlow<List<RecordingEntity>> =
        combine(repository.allRecordings, _searchQuery, _sortMode) { list, query, sort ->
            val filtered = if (query.isBlank()) {
                list
            } else {
                list.filter { it.displayName.contains(query, ignoreCase = true) }
            }
            when (sort) {
                SortMode.DATE_DESC -> filtered.sortedByDescending { it.createdAt }
                SortMode.DATE_ASC -> filtered.sortedBy { it.createdAt }
                SortMode.NAME_ASC -> filtered.sortedBy { it.displayName.lowercase() }
                SortMode.DURATION_DESC -> filtered.sortedByDescending { it.durationMs }
                SortMode.SIZE_DESC -> filtered.sortedByDescending { it.fileSizeBytes }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        preferences.sortMode = mode
    }

    fun rename(item: RecordingEntity, newTitle: String) = viewModelScope.launch {
        repository.rename(item.id, newTitle.trim())
    }

    fun delete(item: RecordingEntity) = viewModelScope.launch {
        repository.deleteAtomic(item)
    }
}
