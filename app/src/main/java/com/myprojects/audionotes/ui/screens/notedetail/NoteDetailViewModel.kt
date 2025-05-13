package com.myprojects.audionotes.ui.screens.notedetail

import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myprojects.audionotes.data.local.entity.BlockType
import com.myprojects.audionotes.data.local.entity.Note
import com.myprojects.audionotes.data.local.entity.NoteBlock
import com.myprojects.audionotes.data.repository.NoteRepository
import com.myprojects.audionotes.util.AUDIO_PLACEHOLDER_PREFIX
import com.myprojects.audionotes.util.AUDIO_PLACEHOLDER_SUFFIX
import com.myprojects.audionotes.util.IAudioPlayer
import com.myprojects.audionotes.util.IAudioRecorder
import com.myprojects.audionotes.util.PlayerState
import com.myprojects.audionotes.util.createAudioPlaceholder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged

@HiltViewModel
class NoteDetailViewModel @Inject constructor(
    private val noteRepository: NoteRepository,
    private val audioRecorder: IAudioRecorder,
    private val audioPlayer: IAudioPlayer,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // ID заметки, полученный из аргументов навигации
    private val currentNoteIdFromArgs: Long = savedStateHandle.get<Long>("noteId") ?: -1L

    // Исходные значения для отслеживания изменений
    private var originalNoteContent: String = ""
    private var originalNoteTitle: String = ""
    private var initialNoteCreatedAt: Long? =
        null // Для сохранения createdAt при обновлении существующей заметки

    // Состояние UI
    private val _uiState = MutableStateFlow(NoteDetailUiState())
    val uiState: StateFlow<NoteDetailUiState> = _uiState.asStateFlow()

    // Локальный список аудио блоков, которые были созданы/загружены в текущей сессии ViewModel
    // Этот список является "мастер-списком" аудио, из которого формируется displayedAudioBlocks
    private val _pendingAudioBlocks = mutableStateListOf<NoteBlock>()
    private var audioPositionJob: Job? = null
    private var audioPlayerStateJob: Job? = null

    init {
        // ... (логика isNewNoteInitially и loadNoteData() как раньше) ...
        Log.d("NoteDetailVM", "ViewModel init. Received noteId from args: $currentNoteIdFromArgs")
        val isTrulyNewNoteScenario = currentNoteIdFromArgs == -1L
        _uiState.update { it.copy(isEditing = isTrulyNewNoteScenario, noteId = if(isTrulyNewNoteScenario) 0L else currentNoteIdFromArgs) }
        loadNoteData(isTrulyNewNoteScenario)
        observeAudioPlayer() // Переименовал для ясности
    }

    private fun loadNoteData(isNewNoteFlow: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val targetNoteId = _uiState.value.noteId // Берем ID из стейта, он уже инициализирован

            Log.d(
                "NoteDetailVM",
                "loadNoteData called for noteId: $targetNoteId. Is new flow: $isNewNoteFlow"
            )

            if (!isNewNoteFlow && targetNoteId != null && targetNoteId != 0L) {
                noteRepository.getNoteWithContentAndAudioBlocks(targetNoteId)
                    .catch { e ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = e.localizedMessage ?: "Failed to load note",
                                isEditing = true
                            )
                        } // Если ошибка загрузки, лучше разрешить редактирование
                        Log.e("NoteDetailVM", "Error loading note $targetNoteId", e)
                    }
                    .collect { noteWithContentAndAudioBlocks ->
                        if (noteWithContentAndAudioBlocks != null) {
                            Log.i(
                                "NoteDetailVM",
                                "Note loaded: ${noteWithContentAndAudioBlocks.note.title}, content length: ${noteWithContentAndAudioBlocks.note.content.length}"
                            )
                            originalNoteTitle = noteWithContentAndAudioBlocks.note.title
                            originalNoteContent = noteWithContentAndAudioBlocks.note.content
                            initialNoteCreatedAt = noteWithContentAndAudioBlocks.note.createdAt
                            _pendingAudioBlocks.clear()
                            _pendingAudioBlocks.addAll(noteWithContentAndAudioBlocks.audioBlocks)

                            _uiState.update {
                                it.copy(
                                    // noteId уже должен быть установлен
                                    noteTitle = noteWithContentAndAudioBlocks.note.title,
                                    textFieldValue = TextFieldValue(
                                        noteWithContentAndAudioBlocks.note.content,
                                        selection = TextRange(noteWithContentAndAudioBlocks.note.content.length)
                                    ),
                                    isLoading = false,
                                    // isEditing не меняем здесь, он управляется отдельно или при инициализации
                                    error = null
                                )
                            }
                            parseTextAndUpdateDisplayedAudio()
                        } else {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    error = "Note not found (ID: $targetNoteId). Defaulting to edit mode.",
                                    isEditing = true
                                )
                            }
                            Log.e("NoteDetailVM", "Note with ID $targetNoteId not found.")
                        }
                    }
            } else { // Сценарий новой заметки (isNewNoteFlow = true или targetNoteId = 0L)
                Log.i("NoteDetailVM", "Setting up UI for a new note or if targetId was 0L.")
                originalNoteTitle = "New Note" // Стартовое значение из uiState
                originalNoteContent = ""
                initialNoteCreatedAt = System.currentTimeMillis() // Для новой заметки
                _pendingAudioBlocks.clear()
                _uiState.update {
                    it.copy(
                        noteId = 0L, // Убеждаемся, что ID для новой заметки 0L
                        noteTitle = originalNoteTitle, // Берем из _uiState, если уже было изменено
                        textFieldValue = TextFieldValue(originalNoteContent),
                        isLoading = false,
                        isEditing = true // Новые заметки всегда в режиме редактирования
                    )
                }
                parseTextAndUpdateDisplayedAudio()
            }
        }
    }

    private fun observeAudioPlayer() {
        audioPlayerStateJob?.cancel()
        audioPositionJob?.cancel()

        audioPlayerStateJob = viewModelScope.launch {
            audioPlayer.playerState
                // Собираем только при реальном изменении состояния
                .collect { state ->
                    _uiState.update { it.copy(audioPlayerState = state) }
                    if (state == PlayerState.COMPLETED || state == PlayerState.IDLE || state == PlayerState.ERROR) {
                        // Сбрасываем ID и позиции, если воспроизведение завершено/остановлено/ошибка
                        _uiState.update {
                            it.copy(
                                currentPlayingAudioBlockId = null,
                                currentAudioPositionMs = 0,
                                // totalDuration можно оставить, если он не сбросился в плеере
                            )
                        }
                    }
                }
        }

        viewModelScope.launch {
            audioPlayer.currentPlayingBlockId
                .collect { blockId ->
                    _uiState.update { it.copy(currentPlayingAudioBlockId = blockId) }
                    // Если сменился играющий блок, сбрасываем позиции для нового блока
                    if (blockId != _uiState.value.currentPlayingAudioBlockId) {
                        _uiState.update { it.copy(currentAudioPositionMs = 0, currentAudioTotalDurationMs = 0) }
                    }
                }
        }

        // Подписываемся на позицию и длительность
        audioPositionJob = viewModelScope.launch {
            combine(
                audioPlayer.currentPositionMs,
                audioPlayer.totalDurationMs
            ) { position, duration -> Pair(position, duration) }
                .distinctUntilChanged()
                .collect { (position, duration) ->
                    // Обновляем UI только если текущий играющий блок совпадает
                    if (_uiState.value.currentPlayingAudioBlockId != null) {
                        _uiState.update {
                            it.copy(
                                currentAudioPositionMs = position,
                                currentAudioTotalDurationMs = duration
                            )
                        }
                    } else {
                        // Если нет играющего блока, но приходят обновления (например, старые), сбрасываем
                        _uiState.update {
                            it.copy(currentAudioPositionMs = 0, currentAudioTotalDurationMs = 0)
                        }
                    }
                }
        }
    }

    fun onPermissionResult(isGranted: Boolean) {
        Log.d("NoteDetailVM", "onPermissionResult: isGranted = $isGranted")
        _uiState.update {
            it.copy(
                permissionGranted = isGranted,
                showPermissionRationaleDialog = false
            )
        }
        if (isGranted) {
            startRecordingAudioInternal() // Начинаем запись, если разрешение дали
        } else {
            _uiState.update { it.copy(error = "Microphone permission denied. Cannot record audio.") }
        }
    }

    fun onPermissionRationaleDismissed() {
        _uiState.update { it.copy(showPermissionRationaleDialog = false) }
    }

    fun updateNoteTitle(newTitle: String) {
        _uiState.update { it.copy(noteTitle = newTitle) }
    }

    fun onContentChange(newTextFieldValue: TextFieldValue) {
        _uiState.update { it.copy(textFieldValue = newTextFieldValue) }
        parseTextAndUpdateDisplayedAudio()
    }

    private fun parseTextAndUpdateDisplayedAudio() {
        val text = _uiState.value.textFieldValue.text
        val regex = Regex(
            Regex.escape(AUDIO_PLACEHOLDER_PREFIX) + "(\\d+)" + Regex.escape(
                AUDIO_PLACEHOLDER_SUFFIX
            )
        )
        val foundAudioIdsInText =
            regex.findAll(text).mapNotNull { it.groupValues[1].toLongOrNull() }
                .toSet() // Используем Set для эффективности

        val currentDisplayed = _pendingAudioBlocks.filter { block ->
            foundAudioIdsInText.contains(block.id)
        }
            .sortedWith(compareBy { block -> foundAudioIdsInText.indexOf(block.id) }) // Сортируем по порядку в тексте

        _uiState.update { it.copy(displayedAudioBlocks = currentDisplayed) }
        Log.d("NoteDetailVM", "Parsed text. Displayed audio blocks: ${currentDisplayed.size}")
    }

    fun toggleEditMode() {
        val currentIsEditing = _uiState.value.isEditing
        if (currentIsEditing && hasUnsavedChanges()) {
            saveNote(switchToViewModeAfterSave = true) // Сохраняем и указываем переключить режим
        } else {
            _uiState.update { it.copy(isEditing = !currentIsEditing) }
        }
    }

    // Вызывается из UI, когда пользователь нажимает кнопку записи/остановки
    fun handleRecordButtonPress() {
        if (_uiState.value.isRecording) {
            stopRecordingAudio()
        } else {
            if (_uiState.value.permissionGranted) {
                startRecordingAudioInternal()
            } else {
                // Если разрешение не дано, сообщаем UI, чтобы он показал диалог Rationale.
                // UI (NoteDetailScreen) при нажатии на "Grant" в диалоге сам вызовет permissionLauncher.
                Log.d(
                    "NoteDetailVM",
                    "Record button pressed, permission not granted. Showing rationale."
                )
                _uiState.update { it.copy(showPermissionRationaleDialog = true) }
            }
        }
    }

    private fun startRecordingAudioInternal() {
        if (audioRecorder.isRecording()) {
            Log.w("NoteDetailVM", "Attempted to start recording while already recording.")
            return
        }
        viewModelScope.launch {
            val audioFile = audioRecorder.createAudioFile()
            if (audioFile != null) {
                Log.d("NoteDetailVM", "Attempting to start recording to: ${audioFile.absolutePath}")
                if (audioRecorder.startRecording(audioFile)) {
                    _uiState.update { it.copy(isRecording = true, error = null) }
                    Log.i("NoteDetailVM", "Recording started successfully: ${audioFile.name}")
                } else {
                    _uiState.update { it.copy(error = "Failed to start audio recording.") }
                    Log.e("NoteDetailVM", "audioRecorder.startRecording returned false.")
                    audioFile.delete() // Удаляем пустой файл, если старт не удался
                }
            } else {
                _uiState.update { it.copy(error = "Failed to create audio file.") }
                Log.e("NoteDetailVM", "audioRecorder.createAudioFile() returned null.")
            }
        }
    }

    fun stopRecordingAudio() {
        if (!audioRecorder.isRecording()) {
            Log.w("NoteDetailVM", "Attempted to stop recording when not recording.")
            return
        }
        viewModelScope.launch {
            val filePath = audioRecorder.stopRecording()
            _uiState.update { it.copy(isRecording = false) }

            if (filePath != null) {
                Log.i("NoteDetailVM", "Recording stopped. File: $filePath")
                val duration = getAudioFileDuration(filePath)
                val newAudioBlock = NoteBlock(
                    id = System.currentTimeMillis(), // Временный уникальный ID
                    noteId = _uiState.value.noteId ?: 0L,
                    type = BlockType.AUDIO,
                    audioFilePath = filePath,
                    audioDurationMs = duration,
                    orderIndex = _pendingAudioBlocks.size
                )
                _pendingAudioBlocks.add(newAudioBlock)
                Log.d("NoteDetailVM", "New audio block created (pending): ID=${newAudioBlock.id}")

                val placeholder = createAudioPlaceholder(newAudioBlock.id)
                val currentTfv = _uiState.value.textFieldValue
                val selection = currentTfv.selection
                val newText =
                    currentTfv.text.replaceRange(selection.min, selection.max, placeholder)
                val newCursorPosition = selection.min + placeholder.length

                _uiState.update {
                    it.copy(textFieldValue = TextFieldValue(newText, TextRange(newCursorPosition)))
                }
                parseTextAndUpdateDisplayedAudio() // Обновить отображаемые плееры
            } else {
                _uiState.update { it.copy(error = "Failed to save recording (file path is null).") }
                Log.e("NoteDetailVM", "stopRecording returned null file path.")
            }
        }
    }

    private fun getAudioFileDuration(filePath: String): Long {
        return try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(filePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            Log.e("NoteDetailVM", "Error getting audio duration for $filePath", e)
            0L
        }
    }

    fun playAudio(blockId: Long) {
        val blockToPlay = _uiState.value.displayedAudioBlocks.find { it.id == blockId }
            ?: _pendingAudioBlocks.find { it.id == blockId }

        blockToPlay?.audioFilePath?.let { path ->
            val currentState = _uiState.value
            if (currentState.currentPlayingAudioBlockId == blockId && currentState.audioPlayerState == PlayerState.PLAYING) {
                audioPlayer.pause()
            } else if (currentState.currentPlayingAudioBlockId == blockId && currentState.audioPlayerState == PlayerState.PAUSED) {
                audioPlayer.resume()
            } else {
                audioPlayer.play(path, blockId)
            }
        } ?: Log.w("NoteDetailVM", "Audio block $blockId not found to play.")
    }

    fun deleteAudioBlockFromPlayer(blockId: Long) {
        viewModelScope.launch {
            val placeholderToRemove = createAudioPlaceholder(blockId)
            val currentTfv = _uiState.value.textFieldValue
            var newText = currentTfv.text.replace(placeholderToRemove, "")
            newText =
                newText.replace(Regex("\\s*${Regex.escape(placeholderToRemove)}\\s*"), " ").trim()
                    .replace(Regex("\\s{2,}"), " ")

            val blockToRemove = _pendingAudioBlocks.find { it.id == blockId }
            if (blockToRemove != null) {
                blockToRemove.audioFilePath?.let { filePath ->
                    try {
                        File(filePath).delete()
                    } catch (e: Exception) {
                        Log.e("NoteDetailVM", "Error deleting audio file $filePath", e)
                    }
                }
                _pendingAudioBlocks.remove(blockToRemove)
            }

            val removedAtIndex = currentTfv.text.indexOf(placeholderToRemove)
            val newCursorPosition =
                if (removedAtIndex != -1) removedAtIndex else currentTfv.selection.start.coerceAtMost(
                    newText.length
                )

            _uiState.update {
                it.copy(
                    textFieldValue = TextFieldValue(
                        newText,
                        TextRange(newCursorPosition)
                    )
                )
            }
            parseTextAndUpdateDisplayedAudio()
            Log.d("NoteDetailVM", "Audio block $blockId (placeholder and pending) deleted.")
        }
    }

    fun onSeekAudio(blockId: Long, positionMs: Int) {
        // Убедимся, что мы перематываем текущий активный трек
        if (_uiState.value.currentPlayingAudioBlockId == blockId) {
            audioPlayer.seekTo(positionMs)
            // Обновление uiState.currentAudioPositionMs произойдет через StateFlow из плеера
        } else {
            // Если пытаемся перемотать неактивный трек, можно сначала его запустить
            // или просто проигнорировать. Для простоты пока игнорируем.
            Log.w("NoteDetailVM", "Seek attempted on non-active or non-matching audio block: $blockId")
        }
    }

    fun saveNote(switchToViewModeAfterSave: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                val idForDb = _uiState.value.noteId.takeIf { it != null && it != 0L } ?: 0L

                val noteToSave = Note(
                    id = idForDb,
                    title = _uiState.value.noteTitle.ifBlank { "Untitled Note" },
                    content = _uiState.value.textFieldValue.text,
                    createdAt = if (idForDb == 0L) System.currentTimeMillis() else (initialNoteCreatedAt
                        ?: System.currentTimeMillis()),
                    updatedAt = System.currentTimeMillis()
                )

                val textContent = _uiState.value.textFieldValue.text
                val regex = Regex(
                    Regex.escape(AUDIO_PLACEHOLDER_PREFIX) + "(\\d+)" + Regex.escape(
                        AUDIO_PLACEHOLDER_SUFFIX
                    )
                )
                val idsInText =
                    regex.findAll(textContent).mapNotNull { it.groupValues[1].toLongOrNull() }
                        .toSet()

                val audioBlocksToPersist = _pendingAudioBlocks.filter { idsInText.contains(it.id) }
                    .mapIndexed { index, block ->
                        block.copy(
                            // noteId для аудио блоков будет установлен репозиторием/DAO при сохранении, если он 0L
                            // или если id заметки изменился (была новая, стала существующая)
                            noteId = if (idForDb == 0L) 0L else (_uiState.value.noteId ?: 0L),
                            orderIndex = index
                        )
                    }

                Log.d(
                    "NoteDetailVM",
                    "Saving note. ID for DB: $idForDb. Title: ${noteToSave.title}. Audio blocks to persist: ${audioBlocksToPersist.size}"
                )

                val savedOrUpdatedNoteId = noteRepository.saveNote(noteToSave, audioBlocksToPersist)
                Log.i("NoteDetailVM", "Note saved/updated. Resulting ID: $savedOrUpdatedNoteId")

                originalNoteTitle = noteToSave.title
                originalNoteContent = noteToSave.content
                initialNoteCreatedAt = noteToSave.createdAt

                // Определяем, нужно ли перезагружать данные (если это была новая заметка и она успешно сохранилась с новым ID)
                val shouldReload =
                    (idForDb == 0L && savedOrUpdatedNoteId != 0L && savedOrUpdatedNoteId != idForDb)

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        noteId = savedOrUpdatedNoteId,
                        isEditing = if (switchToViewModeAfterSave) false else it.isEditing
                    )
                }

                if (shouldReload) {
                    // isNewNoteInitially = false // <--- УДАЛЯЕМ ЭТУ СТРОКУ
                    savedStateHandle["noteId"] = savedOrUpdatedNoteId
                    loadNoteData(false) // Перезагружаем с флагом, что это уже не "новый поток"
                } else {
                    parseTextAndUpdateDisplayedAudio()
                }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        error = e.localizedMessage ?: "Failed to save note"
                    )
                }
                Log.e("NoteDetailVM", "Error saving note", e)
            }
        }
    }

    fun hasUnsavedChanges(): Boolean {
        val titleChanged = _uiState.value.noteTitle != originalNoteTitle
        val contentChanged = _uiState.value.textFieldValue.text != originalNoteContent
        // TODO: Более точная проверка изменений в списке аудио блоков
        // (сравнение _pendingAudioBlocks с начальным списком аудио блоков по составу и файлам)
        return titleChanged || contentChanged
    }

    override fun onCleared() {
        super.onCleared()
        if (audioRecorder.isRecording()) {
            audioRecorder.stopRecording()?.let { path -> File(path).delete() }
        }
        audioPlayer.release() // Вызываем release для плеера
        audioPlayerStateJob?.cancel()
        audioPositionJob?.cancel() // Отменяем подписку на позицию
        Log.d("NoteDetailVM", "ViewModel cleared.")
    }
}