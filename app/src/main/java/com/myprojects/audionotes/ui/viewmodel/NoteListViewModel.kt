package com.myprojects.audionotes.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myprojects.audionotes.data.local.entity.Note
import com.myprojects.audionotes.data.repository.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// Состояние UI для экрана списка заметок
data class NoteListUiState(
    val notes: List<Note> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class NoteListViewModel @Inject constructor(
    private val noteRepository: NoteRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NoteListUiState())
    val uiState: StateFlow<NoteListUiState> = _uiState.asStateFlow()

    init {
        loadNotes()
    }

    private fun loadNotes() {
        viewModelScope.launch {
            noteRepository.getAllNotes()
                .onStart { _uiState.update { it.copy(isLoading = true) } }
                .catch { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: "Failed to load notes") }
                    // Можно добавить логирование ошибки
                }
                .collect { notesList ->
                    _uiState.update {
                        it.copy(
                            notes = notesList,
                            isLoading = false,
                            error = null // Сбрасываем ошибку при успешной загрузке
                        )
                    }
                }
        }
    }

    // Функция для создания новой заметки
    // Возвращает ID новой заметки, чтобы экран мог на нее перейти
    fun createNewNote(onNoteCreated: (Long) -> Unit) {
        viewModelScope.launch {
            try {
                val newNoteId = noteRepository.createNewNote()
                onNoteCreated(newNoteId) // Передаем ID для навигации
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.localizedMessage ?: "Failed to create note") }
                // Логирование ошибки
            }
        }
    }

    // Функция для удаления заметки
    fun deleteNote(noteId: Long) {
        viewModelScope.launch {
            try {
                // Перед удалением получим объект Note по ID, так как DAO требует сам объект
                val noteToDelete = noteRepository.getNoteById(noteId)
                if (noteToDelete != null) {
                    // TODO: Перед удалением из БД нужно будет удалить связанные аудиофайлы!
                    // Логика удаления файлов будет в репозитории или специальном менеджере
                    noteRepository.deleteNoteById(noteId) // Используем новый метод репозитория
                } else {
                    _uiState.update { it.copy(error = "Note not found for deletion") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.localizedMessage ?: "Failed to delete note") }
                // Логирование ошибки
            }
        }
    }
}