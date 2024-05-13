package com.myprojects.audionotes.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.myprojects.audionotes.data.local.entity.Note
import com.myprojects.audionotes.data.repository.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NoteListUiState(
    val notes: List<Note> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class NoteListViewModel @Inject constructor(
    private val noteRepository: NoteRepository,
    private val workManager: WorkManager // Внедряем WorkManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(NoteListUiState())
    val uiState: StateFlow<NoteListUiState> = _uiState.asStateFlow()

    companion object {
        private const val TAG = "NoteListViewModel"
    }

    init {
        loadNotes()
    }

    private fun loadNotes() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            noteRepository.getAllNotes()
                .catch { e ->
                    Log.e(TAG, "Error loading notes", e)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = e.localizedMessage ?: "Failed to load notes"
                        )
                    }
                }
                .collect { notesList ->
                    _uiState.update { it.copy(notes = notesList, isLoading = false, error = null) }
                }
        }
    }

    fun createNewNote(onNoteCreated: (Long) -> Unit) {
        viewModelScope.launch {
            try {
                val newNoteId = noteRepository.createNewNote()
                if (newNoteId != -1L) {
                    onNoteCreated(newNoteId)
                } else {
                    _uiState.update { it.copy(error = "Failed to create new note (invalid ID).") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating new note", e)
                _uiState.update { it.copy(error = e.localizedMessage ?: "Failed to create note") }
            }
        }
    }

    fun deleteNote(noteId: Long) {
        viewModelScope.launch {
            try {
                val workTag = "reminder_note_$noteId"
                workManager.cancelUniqueWork(workTag) // Отменяем связанную работу
                Log.i(TAG, "Cancelled reminder work for note $noteId before deletion.")

                noteRepository.deleteNoteById(noteId)
                // Список обновится автоматически через Flow
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting note $noteId", e)
                _uiState.update { it.copy(error = e.localizedMessage ?: "Failed to delete note") }
            }
        }
    }
}