package com.myprojects.audionotes.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.work.WorkManager
import com.myprojects.audionotes.data.local.entity.Note
import com.myprojects.audionotes.data.local.entity.NoteCategory
import com.myprojects.audionotes.data.repository.NoteRepository
import com.myprojects.audionotes.util.AUDIO_PLACEHOLDER_PREFIX
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// --- Модели сортировки ---
enum class SortOrder {
    ASCENDING,
    DESCENDING
}

enum class SortBy(val displayName: String) {
    UPDATED_AT("Date Modified"),
    CREATED_AT("Date Created"),
    TITLE("Title")
}

data class SortOption(
    val sortBy: SortBy,
    val sortOrder: SortOrder
) {
    val displayName: String
        get() {
            val orderString =
                if (sortOrder == SortOrder.DESCENDING) "Newest first" else "Oldest first"
            return when (sortBy) {
                SortBy.UPDATED_AT -> "Modified ($orderString)"
                SortBy.CREATED_AT -> "Created ($orderString)"
                SortBy.TITLE -> if (sortOrder == SortOrder.ASCENDING) "Title (A-Z)" else "Title (Z-A)"
            }
        }

    companion object {
        val DEFAULT = SortOption(SortBy.UPDATED_AT, SortOrder.DESCENDING)
        val allOptions = listOf(
            SortOption(SortBy.UPDATED_AT, SortOrder.DESCENDING),
            SortOption(SortBy.UPDATED_AT, SortOrder.ASCENDING),
            SortOption(SortBy.CREATED_AT, SortOrder.DESCENDING),
            SortOption(SortBy.CREATED_AT, SortOrder.ASCENDING),
            SortOption(SortBy.TITLE, SortOrder.ASCENDING),
            SortOption(SortBy.TITLE, SortOrder.DESCENDING),
        )
    }
}

// --- Модели фильтров ---
enum class TriStateFilterSelection(val displayName: String) {
    ANY("Any"),
    YES("Yes"),
    NO("No")
}

data class ActiveFilters(
    val selectedCategories: Set<String> = emptySet(),
    val reminderFilter: TriStateFilterSelection = TriStateFilterSelection.ANY,
    val audioFilter: TriStateFilterSelection = TriStateFilterSelection.ANY
) {
    companion object {
        val NONE = ActiveFilters()
    }

    val isAnyFilterApplied: Boolean
        get() = selectedCategories.isNotEmpty() ||
                reminderFilter != TriStateFilterSelection.ANY ||
                audioFilter != TriStateFilterSelection.ANY
}

// --- UiState для экрана списка заметок ---
data class NoteListUiState(
    val notes: List<Note> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val currentSortOption: SortOption = SortOption.DEFAULT,
    val searchQuery: String = "",
    val activeFilters: ActiveFilters = ActiveFilters.NONE,
    val availableCategories: List<NoteCategory> = NoteCategory.entries.filter { it != NoteCategory.NONE }
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NoteListViewModel @Inject constructor(
    private val noteRepository: NoteRepository,
    private val workManager: WorkManager
) : ViewModel() {

    // MutableStateFlow для управления параметрами из UI
    private val _currentSortOption = MutableStateFlow(SortOption.DEFAULT)
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> =
        _searchQuery.asStateFlow() // Публичный для UI, чтобы TextField мог его читать
    private val _activeFilters = MutableStateFlow(ActiveFilters.NONE)

    // Внутренний MutableStateFlow для управления состоянием загрузки и ошибок,
    // чтобы эти состояния не сбрасывались преждевременно при обновлении данных в основном uiState.
    private val _internalUiState = MutableStateFlow(NoteListUiState(isLoading = true))


    // --- Основной поток данных из БД для НЕАРХИВИРОВАННЫХ заметок ---
    private val _notesFromDbFlow: Flow<List<Note>> = combine(
        _currentSortOption,
        _searchQuery.debounce(300L), // Применяем debounce, чтобы не делать запрос на каждое нажатие
        _activeFilters
    ) { sortOption, searchQueryValue, currentFilters ->
        // Этот блок просто собирает параметры для flatMapLatest
        Triple(sortOption, searchQueryValue, currentFilters)
    }.flatMapLatest { (sortOption, searchQueryValue, currentFilters) ->
        // Формируем SQL-запрос на основе текущих параметров
        val sb = StringBuilder("SELECT * FROM notes")
        val args = mutableListOf<Any>()
        val conditions = mutableListOf<String>()

        conditions.add("isArchived = 0") // Ключевое условие: только неархивированные заметки

        // Условие для поиска
        if (searchQueryValue.isNotBlank()) {
            conditions.add("(LOWER(title) LIKE LOWER(?) OR LOWER(content) LIKE LOWER(?))")
            args.add("%$searchQueryValue%")
            args.add("%$searchQueryValue%")
        }

        // Условие для фильтра по категориям
        if (currentFilters.selectedCategories.isNotEmpty()) {
            val categoryPlaceholders = currentFilters.selectedCategories.joinToString { "?" }
            conditions.add("category IN ($categoryPlaceholders)")
            args.addAll(currentFilters.selectedCategories)
        }

        // Условие для фильтра по напоминаниям
        when (currentFilters.reminderFilter) {
            TriStateFilterSelection.YES -> {
                conditions.add("(reminderAt IS NOT NULL AND reminderAt > ?)")
                args.add(System.currentTimeMillis())
            }

            TriStateFilterSelection.NO -> {
                conditions.add("(reminderAt IS NULL OR reminderAt <= ?)")
                args.add(System.currentTimeMillis())
            }

            TriStateFilterSelection.ANY -> { /* No condition */
            }
        }

        // Собираем все WHERE условия
        if (conditions.isNotEmpty()) {
            sb.append(" WHERE ").append(conditions.joinToString(" AND "))
        }

        // Добавляем сортировку
        sb.append(" ORDER BY ")
            .append(
                when (sortOption.sortBy) {
                    SortBy.UPDATED_AT -> "updatedAt"
                    SortBy.CREATED_AT -> "createdAt"
                    SortBy.TITLE -> "LOWER(title)"
                }
            )
            .append(if (sortOption.sortOrder == SortOrder.DESCENDING) " DESC" else " ASC")

        Log.d(TAG, "Notes query: ${sb.toString()} with args: $args")
        noteRepository.getNotes(SimpleSQLiteQuery(sb.toString(), args.toTypedArray()))
            .catch { e ->
                Log.e(TAG, "Error in _notesFromDbFlow flatMapLatest", e)
                _internalUiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "DB query failed"
                    )
                }
                emit(emptyList()) // Возвращаем пустой список, чтобы Flow не прервался
            }
    }

    // --- Поток данных из БД для АРХИВИРОВАННЫХ заметок ---
    // Для экрана архива можно будет создать аналогичный _archivedNotesFromDbFlow
    // с isArchived = 1 и, возможно, своими параметрами сортировки/фильтра.
    // Пока оставим заглушку, так как экран архива еще не реализован полностью.
    val archivedNotesFlow: Flow<List<Note>> = flow {
        // TODO: Реализовать загрузку архивированных заметок с нужными параметрами
        // Например, можно использовать _currentSortOption и т.д., если они применимы к архиву
        val query =
            SimpleSQLiteQuery("SELECT * FROM notes WHERE isArchived = 1 ORDER BY updatedAt DESC")
        noteRepository.getArchivedNotes(query).collect { emit(it) }
    }.catch { e ->
        Log.e(TAG, "Error fetching archived notes", e)
        emit(emptyList())
    }

    // --- Поток, который применяет Kotlin-фильтры (например, по наличию аудио) ---
    private val _processedNotesFlow: Flow<List<Note>> = _notesFromDbFlow
        .map { notesFromDb ->
            val audioFilter = _activeFilters.value.audioFilter
            if (audioFilter == TriStateFilterSelection.ANY) {
                notesFromDb
            } else {
                notesFromDb.filter { note ->
                    val hasAudioInContent =
                        note.content.contains(AUDIO_PLACEHOLDER_PREFIX, ignoreCase = false)
                    if (audioFilter == TriStateFilterSelection.YES) hasAudioInContent else !hasAudioInContent
                }
            }
        }
        .catch { e ->
            Log.e(TAG, "Error in _processedNotesFlow map", e)
            _internalUiState.update {
                it.copy(
                    isLoading = false,
                    error = e.message ?: "Processing notes failed"
                )
            }
            emit(emptyList())
        }

    // --- Конечный UiState для экрана ---
    val uiState: StateFlow<NoteListUiState> = combine(
        _processedNotesFlow,
        _currentSortOption,
        this.searchQuery, // Используем публичный searchQuery StateFlow
        _activeFilters
    ) { notes, sortOption, currentSearchVal, currentFilters ->
        // Обновляем _internalUiState здесь, чтобы isLoading сбросился после того, как все данные пришли
        // Это предотвращает преждевременное скрытие индикатора загрузки.
        _internalUiState.update {
            it.copy(
                isLoading = false, // Данные получены и обработаны
                error = if (it.error != null && notes.isNotEmpty()) null else it.error // Сбрасываем ошибку, если данные пришли
            )
        }
        NoteListUiState(
            notes = notes,
            isLoading = _internalUiState.value.isLoading, // Берем актуальный isLoading
            error = _internalUiState.value.error,       // Берем актуальную ошибку
            currentSortOption = sortOption,
            searchQuery = currentSearchVal,
            activeFilters = currentFilters,
            availableCategories = NoteCategory.entries.filter { it != NoteCategory.NONE }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = _internalUiState.value // Начальное состояние с isLoading = true
    )

    companion object {
        private const val TAG = "NoteListViewModel"
    }

    // init блок не нужен для подписки на _processedNotesFlow для обновления _internalUiState,
    // так как это теперь делается внутри combine для uiState.
    // isLoading управляется при вызове методов и при получении данных.

    fun createNewNote(onNoteCreated: (Long) -> Unit) {
        viewModelScope.launch {
            _internalUiState.update { it.copy(isLoading = true) }
            try {
                val newNoteId = noteRepository.createNewNote()
                if (newNoteId != -1L) {
                    onNoteCreated(newNoteId)
                    _internalUiState.update { it.copy(isLoading = false, error = null) }
                } else {
                    _internalUiState.update {
                        it.copy(
                            error = "Failed to create new note (invalid ID).",
                            isLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating new note", e)
                _internalUiState.update {
                    it.copy(
                        error = e.localizedMessage ?: "Failed to create note", isLoading = false
                    )
                }
            }
        }
    }

    fun deleteNote(noteId: Long) {
        viewModelScope.launch {
            _internalUiState.update { it.copy(isLoading = true) }
            try {
                val workTag = "reminder_note_$noteId"
                workManager.cancelUniqueWork(workTag)
                Log.i(TAG, "Cancelled reminder work for note $noteId before deletion.")
                noteRepository.deleteNoteById(noteId)
                // isLoading будет сброшен, когда _processedNotesFlow обновит uiState
                _internalUiState.update { it.copy(error = null) } // Сбрасываем ошибку
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting note $noteId", e)
                _internalUiState.update {
                    it.copy(
                        error = e.localizedMessage ?: "Failed to delete note", isLoading = false
                    )
                }
            }
        }
    }

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
            } else {
                // Если запрос очищен, но не было активного поиска, isLoading не меняем агрессивно
                // Но если был активный поиск, а теперь он пуст, isLoading все равно должен был установиться
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

    fun archiveNote(noteId: Long) {
        viewModelScope.launch {
            _internalUiState.update { it.copy(isLoading = true) }
            try {
                noteRepository.setArchivedStatus(noteId, true)
                val workTag = "reminder_note_$noteId"
                workManager.cancelUniqueWork(workTag)
                Log.i(TAG, "Cancelled reminder work for archived note $noteId.")
                // isLoading сбросится, когда uiState обновится после фильтрации
            } catch (e: Exception) {
                Log.e(TAG, "Error archiving note $noteId", e)
                _internalUiState.update {
                    it.copy(
                        error = "Failed to archive note.",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun unarchiveNote(noteId: Long) { // Для будущего экрана архива
        viewModelScope.launch {
            _internalUiState.update { it.copy(isLoading = true) }
            try {
                noteRepository.setArchivedStatus(noteId, false)
                // isLoading сбросится, когда uiState (для экрана архива) обновится
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

    fun clearError() {
        _internalUiState.update { it.copy(error = null) }
    }
}