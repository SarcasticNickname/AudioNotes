package com.myprojects.audionotes.ui.viewmodel // Помещаем в тот же пакет

// Не нужен WorkManager здесь, т.к. мы не управляем напоминаниями для архивированных
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.sqlite.db.SimpleSQLiteQuery
import com.myprojects.audionotes.data.local.entity.Note
import com.myprojects.audionotes.data.local.entity.NoteCategory
import com.myprojects.audionotes.data.repository.NoteRepository
import com.myprojects.audionotes.util.AUDIO_PLACEHOLDER_PREFIX
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// Используем те же модели SortOrder, SortBy, SortOption, TriStateFilterSelection, ActiveFilters,
// что и в NoteListViewModel. Их можно вынести в отдельный файл ui/model, если еще не сделано.
// Для этого примера я их здесь не дублирую, предполагая, что они доступны.

// UiState для экрана архива (похож на NoteListUiState)
data class ArchivedNotesUiState(
    val notes: List<Note> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val currentSortOption: SortOption = SortOption.DEFAULT, // Своя сортировка для архива
    val searchQuery: String = "", // Свой поиск для архива
    val activeFilters: ActiveFilters = ActiveFilters.NONE, // Свои фильтры для архива
    val availableCategories: List<NoteCategory> = NoteCategory.entries.filter { it != NoteCategory.NONE }
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArchivedNotesViewModel @Inject constructor(
    private val noteRepository: NoteRepository
    // WorkManager здесь не нужен для архивированных заметок
) : ViewModel() {

    private val _currentSortOption = MutableStateFlow(SortOption.DEFAULT)
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    private val _activeFilters = MutableStateFlow(ActiveFilters.NONE)

    private val _internalUiState = MutableStateFlow(ArchivedNotesUiState(isLoading = true))

    // --- Основной поток данных из БД для АРХИВИРОВАННЫХ заметок ---
    private val _archivedNotesFromDbFlow: Flow<List<Note>> = combine(
        _currentSortOption,
        _searchQuery.debounce(300L),
        _activeFilters
    ) { sortOption, searchQueryValue, currentFilters ->
        Triple(sortOption, searchQueryValue, currentFilters)
    }.flatMapLatest { (sortOption, searchQueryValue, currentFilters) ->
        val sb = StringBuilder("SELECT * FROM notes")
        val args = mutableListOf<Any>()
        val conditions = mutableListOf<String>()

        conditions.add("isArchived = 1") // <<< КЛЮЧЕВОЕ ОТЛИЧИЕ: только архивированные

        if (searchQueryValue.isNotBlank()) {
            conditions.add("(LOWER(title) LIKE LOWER(?) OR LOWER(content) LIKE LOWER(?))")
            args.add("%$searchQueryValue%"); args.add("%$searchQueryValue%")
        }
        if (currentFilters.selectedCategories.isNotEmpty()) {
            val placeholders = currentFilters.selectedCategories.joinToString { "?" }
            conditions.add("category IN ($placeholders)")
            args.addAll(currentFilters.selectedCategories)
        }
        // Фильтр по напоминаниям для архива может быть не так актуален, но оставим логику
        when (currentFilters.reminderFilter) {
            TriStateFilterSelection.YES -> {
                conditions.add("(reminderAt IS NOT NULL AND reminderAt > ?)"); args.add(System.currentTimeMillis())
            }

            TriStateFilterSelection.NO -> {
                conditions.add("(reminderAt IS NULL OR reminderAt <= ?)"); args.add(System.currentTimeMillis())
            }

            TriStateFilterSelection.ANY -> {}
        }

        if (conditions.isNotEmpty()) {
            sb.append(" WHERE ").append(conditions.joinToString(" AND "))
        }
        sb.append(" ORDER BY ")
            .append(
                when (sortOption.sortBy) {
                    SortBy.UPDATED_AT -> "updatedAt"; SortBy.CREATED_AT -> "createdAt"; SortBy.TITLE -> "LOWER(title)"
                }
            )
            .append(if (sortOption.sortOrder == SortOrder.DESCENDING) " DESC" else " ASC")

        Log.d(TAG, "Archived Notes query: ${sb.toString()} with args: $args")
        // Используем метод getArchivedNotes из репозитория
        noteRepository.getArchivedNotes(SimpleSQLiteQuery(sb.toString(), args.toTypedArray()))
            .catch { e ->
                Log.e(TAG, "Error in _archivedNotesFromDbFlow flatMapLatest", e)
                _internalUiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "DB query failed for archive"
                    )
                }
                emit(emptyList())
            }
    }

    private val _processedArchivedNotesFlow: Flow<List<Note>> = _archivedNotesFromDbFlow
        .map { notesFromDb ->
            val audioFilter = _activeFilters.value.audioFilter
            if (audioFilter == TriStateFilterSelection.ANY) notesFromDb
            else notesFromDb.filter { note ->
                val hasAudio = note.content.contains(AUDIO_PLACEHOLDER_PREFIX, false)
                if (audioFilter == TriStateFilterSelection.YES) hasAudio else !hasAudio
            }
        }
        .catch { e ->
            Log.e(TAG, "Error in _processedArchivedNotesFlow map", e)
            _internalUiState.update {
                it.copy(
                    isLoading = false,
                    error = e.message ?: "Processing archived notes failed"
                )
            }
            emit(emptyList())
        }

    val uiState: StateFlow<ArchivedNotesUiState> = combine(
        _processedArchivedNotesFlow,
        _currentSortOption,
        this.searchQuery,
        _activeFilters
    ) { notes, sortOption, currentSearchVal, currentFilters ->
        _internalUiState.update {
            it.copy(
                isLoading = false,
                error = if (it.error != null && notes.isNotEmpty()) null else it.error
            )
        }
        ArchivedNotesUiState(
            notes = notes,
            isLoading = _internalUiState.value.isLoading,
            error = _internalUiState.value.error,
            currentSortOption = sortOption,
            searchQuery = currentSearchVal,
            activeFilters = currentFilters,
            availableCategories = NoteCategory.entries.filter { it != NoteCategory.NONE }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), _internalUiState.value)


    companion object {
        const val TAG = "ArchivedNotesVM"
    }

    // Методы для управления сортировкой, поиском, фильтрами (аналогичны NoteListViewModel)
    fun setSortOption(sortOption: SortOption) {
        if (_currentSortOption.value != sortOption) {
            Log.d(TAG, "Setting sort option to: ${sortOption.displayName}")
            _internalUiState.update { it.copy(isLoading = true, error = null) }
            _currentSortOption.value = sortOption
        }
    }

    fun setSearchQuery(query: String) {
        if (_searchQuery.value != query) {
            Log.d(TAG, "Setting search query to: $query")
            if (query.isNotBlank() || _searchQuery.value.isNotBlank()) {
                _internalUiState.update { it.copy(isLoading = true, error = null) }
            }
            _searchQuery.value = query
        }
    }

    fun applyFilters(newFilters: ActiveFilters) {
        if (_activeFilters.value != newFilters) {
            Log.d(TAG, "Applying filters: $newFilters")
            _internalUiState.update { it.copy(isLoading = true, error = null) }
            _activeFilters.value = newFilters
        }
    }

    fun resetFilters() {
        if (_activeFilters.value != ActiveFilters.NONE) {
            Log.d(TAG, "Resetting filters")
            _internalUiState.update { it.copy(isLoading = true, error = null) }
            _activeFilters.value = ActiveFilters.NONE
        }
    }

    fun clearError() {
        _internalUiState.update { it.copy(error = null) }
    }

    // Ключевой метод для этого ViewModel
    fun unarchiveNote(noteId: Long) {
        viewModelScope.launch {
            _internalUiState.update { it.copy(isLoading = true) }
            try {
                noteRepository.setArchivedStatus(noteId, false)
                // Список обновится автоматически, так как заметка перестанет соответствовать isArchived = 1
                // isLoading будет сброшен, когда uiState обновится
            } catch (e: Exception) {
                Log.e(TAG, "Error unarchiving note $noteId", e)
                _internalUiState.update {
                    it.copy(
                        error = "Failed to unarchive note.",
                        isLoading = false
                    )
                }
            }
        }
    }

    // Метод deleteNote можно скопировать из NoteListViewModel, если нужно удалять из архива напрямую
    fun deleteArchivedNote(noteId: Long) {
        viewModelScope.launch {
            _internalUiState.update { it.copy(isLoading = true) }
            try {
                // Отмена напоминаний здесь не нужна, т.к. они должны были быть отменены при архивации
                noteRepository.deleteNoteById(noteId) // Используем тот же метод удаления
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting archived note $noteId", e)
                _internalUiState.update {
                    it.copy(
                        error = "Failed to delete archived note.",
                        isLoading = false
                    )
                }
            }
        }
    }
}