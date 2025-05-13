package com.myprojects.audionotes.ui.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mohamedrejeb.richeditor.model.RichTextState
import com.myprojects.audionotes.data.local.entity.BlockType
import com.myprojects.audionotes.data.local.entity.Note
import com.myprojects.audionotes.data.local.entity.NoteBlock
import com.myprojects.audionotes.data.local.entity.NoteCategory
import com.myprojects.audionotes.data.preferences.UserPreferencesRepository
import com.myprojects.audionotes.data.repository.NoteRepository
import com.myprojects.audionotes.ui.screens.notedetail.NoteDetailUiState
import com.myprojects.audionotes.ui.screens.notedetail.NotificationPermissionStatus
import com.myprojects.audionotes.util.AUDIO_PLACEHOLDER_PREFIX
import com.myprojects.audionotes.util.AUDIO_PLACEHOLDER_SUFFIX
import com.myprojects.audionotes.util.IAudioPlayer
import com.myprojects.audionotes.util.IAudioRecorder
import com.myprojects.audionotes.util.ISpeechToTextProcessor
import com.myprojects.audionotes.util.PlayerState
import com.myprojects.audionotes.util.createAudioPlaceholder
import com.myprojects.audionotes.workers.ReminderWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jsoup.parser.Parser
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class NoteDetailViewModel @Inject constructor(
    private val noteRepository: NoteRepository,
    private val audioRecorder: IAudioRecorder,
    private val audioPlayer: IAudioPlayer,
    private val speechProcessor: ISpeechToTextProcessor,
    private val workManager: WorkManager,
    @ApplicationContext private val appContext: Context,
    private val savedStateHandle: SavedStateHandle,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val currentNoteIdFromArgs: Long = savedStateHandle.get<Long>("noteId") ?: -1L
    private var originalNoteTitle: String = ""
    private var originalHtmlContent: String = "<p><br></p>"
    private var initialNoteCreatedAt: Long? = null
    private var originalReminderAt: Long? = null
    private var originalCategoryName: String = NoteCategory.NONE.name // ИСХОДНАЯ КАТЕГОРИЯ


    val richTextState = RichTextState()

    private val _uiState =
        MutableStateFlow(
            NoteDetailUiState(
                initialHtmlContentForEditor = originalHtmlContent,
                // Устанавливаем начальную категорию по умолчанию для UiState
                selectedCategory = NoteCategory.fromName(originalCategoryName)
            )
        )
    val uiState: StateFlow<NoteDetailUiState> = _uiState.asStateFlow()

    private val _pendingAudioBlocks = mutableStateListOf<NoteBlock>()
    private var audioPositionJob: Job? = null
    private var audioPlayerStateJob: Job? = null

    companion object {
        private const val TAG = "NoteDetailVM_Rich"
        const val TEMP_ID_THRESHOLD = 1_000_000_000_000L
    }

    init {
        Log.d(TAG, "ViewModel init. Received noteId: $currentNoteIdFromArgs")

        //  Инициализируем текущий статус разрешения
        val micGranted = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        _uiState.update { it.copy(permissionGranted = micGranted) }

        val isTrulyNewNoteScenario = currentNoteIdFromArgs == -1L || currentNoteIdFromArgs == 0L
        _uiState.update {
            it.copy(
                isEditing = isTrulyNewNoteScenario,
                noteId = if (isTrulyNewNoteScenario) 0L else currentNoteIdFromArgs,
                isLoading = !isTrulyNewNoteScenario,
                selectedCategory = if (isTrulyNewNoteScenario) NoteCategory.NONE else it.selectedCategory // Установка для новой заметки
            )
        }

        if (isTrulyNewNoteScenario) {
            originalNoteTitle = "New Note" // Устанавливаем здесь для новой заметки
            originalHtmlContent = "<p><br></p>"
            originalCategoryName = NoteCategory.NONE.name // Инициализация для новой заметки
            originalReminderAt = null
            richTextState.setHtml(originalHtmlContent)
            _uiState.update { it.copy(isLoading = false, noteTitle = originalNoteTitle) }
            parseTextAndUpdateDisplayedAudio()
        } else {
            loadNoteData()
        }

        observeAudioPlayer()
        observeSpeechProcessor()

        viewModelScope.launch {
            snapshotFlow { richTextState.annotatedString.text }
                .distinctUntilChanged()
                .debounce(100L)
                .collect {
                    Log.d(
                        TAG,
                        "RichTextState text changed, calling parseTextAndUpdateDisplayedAudio. Length: ${it.length}"
                    )
                    parseTextAndUpdateDisplayedAudio()
                }
        }
    }

    private fun observeSpeechProcessor() {
        viewModelScope.launch {
            speechProcessor.isListening.collect { listening ->
                Log.d(TAG, "SpeechProcessor isListening changed: $listening")
                _uiState.update { it.copy(isSpeechToTextActive = listening) }
            }
        }
        viewModelScope.launch {
            speechProcessor.recognitionResult.collect { recognizedText ->
                Log.d(TAG, "ViewModel received STT result from SpeechProcessor: '$recognizedText'")
                if (recognizedText.isNotBlank()) {
                    val currentText = richTextState.annotatedString.text
                    val selection = richTextState.selection
                    val prefix =
                        if (selection.start > 0 && currentText.getOrNull(selection.start - 1)
                                ?.isWhitespace() == false && recognizedText.firstOrNull()
                                ?.isWhitespace() == false
                        ) " " else ""
                    val suffix = if (recognizedText.lastOrNull()
                            ?.isWhitespace() == false && (selection.end == currentText.length || (selection.end < currentText.length && currentText.getOrNull(
                            selection.end
                        )?.isWhitespace() == false))
                    ) " " else ""
                    richTextState.replaceSelectedText(prefix + recognizedText.trim() + suffix)
                    Log.d(TAG, "Text inserted into RichTextState via SpeechProcessor.")
                    _uiState.update { it.copy(error = null) }
                }
            }
        }
        viewModelScope.launch {
            speechProcessor.recognitionError.collect { errorMessage ->
                if (errorMessage.isNotEmpty()) {
                    Log.e(TAG, "STT Android error from SpeechProcessor: $errorMessage")
                    _uiState.update { it.copy(error = "Ошибка распознавания: $errorMessage") }
                }
            }
        }
    }

    private fun loadNoteData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val targetNoteId = _uiState.value.noteId ?: run {
                Log.e(TAG, "loadNoteData: targetNoteId is null, cannot load, setting as new note.")
                setAsNewNote()
                return@launch
            }

            if (targetNoteId == 0L) {
                Log.d(TAG, "loadNoteData: targetNoteId is 0L, setting as new note.")
                setAsNewNote()
                return@launch
            }

            Log.d(TAG, "loadNoteData: Loading note with ID: $targetNoteId")
            noteRepository.getNoteWithContentAndAudioBlocks(targetNoteId)
                .catch { e ->
                    Log.e(TAG, "Error loading note $targetNoteId", e)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = e.localizedMessage ?: "Failed to load note",
                            isEditing = true // Если ошибка, переходим в режим редактирования новой заметки
                        )
                    }
                    setAsNewNote() // В случае ошибки загрузки, инициализируем как новую заметку
                }
                .collect { noteWithContentAndAudioBlocks ->
                    if (noteWithContentAndAudioBlocks != null) {
                        val note = noteWithContentAndAudioBlocks.note
                        val audioBlocks = noteWithContentAndAudioBlocks.audioBlocks
                        Log.d(
                            TAG,
                            "loadNoteData: Note loaded: ${note.title}, AudioBlock count: ${audioBlocks.size}, ReminderAt: ${note.reminderAt}, Category: ${note.category}"
                        )

                        originalNoteTitle = note.title
                        originalHtmlContent = note.content.ifBlank { "<p><br></p>" }
                        initialNoteCreatedAt = note.createdAt
                        originalReminderAt = note.reminderAt
                        originalCategoryName = note.category // СОХРАНЯЕМ ИСХОДНУЮ КАТЕГОРИЮ

                        Log.d(
                            TAG,
                            "loadNoteData: HTML from DB (expected to be decoded by Jsoup on save): '${
                                originalHtmlContent.take(200)
                            }'"
                        )

                        _pendingAudioBlocks.clear()
                        _pendingAudioBlocks.addAll(audioBlocks)

                        richTextState.setHtml(originalHtmlContent)
                        val textAfterSetHtml = richTextState.annotatedString.text
                        Log.d(
                            TAG,
                            "loadNoteData: AnnotatedString.text after setHtml: '$textAfterSetHtml'"
                        )

                        _uiState.update {
                            it.copy(
                                noteTitle = note.title,
                                isLoading = false,
                                error = null,
                                initialHtmlContentForEditor = originalHtmlContent,
                                reminderAt = note.reminderAt,
                                selectedCategory = NoteCategory.fromName(note.category) // УСТАНАВЛИВАЕМ ЗАГРУЖЕННУЮ КАТЕГОРИЮ
                            )
                        }
                        parseTextAndUpdateDisplayedAudio()
                    } else {
                        Log.w(
                            TAG,
                            "Note with ID $targetNoteId not found in DB, setting as new note."
                        )
                        setAsNewNote()
                        _uiState.update { it.copy(error = "Note not found.") }
                    }
                }
        }
    }

    private fun setAsNewNote() {
        Log.d(TAG, "setAsNewNote called.")
        originalNoteTitle = "New Note"
        originalHtmlContent = "<p><br></p>"
        initialNoteCreatedAt = System.currentTimeMillis()
        originalReminderAt = null
        originalCategoryName = NoteCategory.NONE.name // ИНИЦИАЛИЗАЦИЯ ДЛЯ НОВОЙ ЗАМЕТКИ
        richTextState.setHtml(originalHtmlContent)
        _pendingAudioBlocks.clear()
        _uiState.update {
            it.copy(
                noteId = 0L,
                noteTitle = originalNoteTitle,
                isLoading = false,
                isEditing = true,
                initialHtmlContentForEditor = originalHtmlContent,
                reminderAt = null,
                displayedAudioBlocks = emptyList(),
                error = null,
                selectedCategory = NoteCategory.NONE // УСТАНАВЛИВАЕМ КАТЕГОРИЮ ПО УМОЛЧАНИЮ
            )
        }
        parseTextAndUpdateDisplayedAudio()
    }

    private fun observeAudioPlayer() {
        audioPlayerStateJob?.cancel(); audioPositionJob?.cancel()
        audioPlayerStateJob = viewModelScope.launch {
            audioPlayer.playerState.collect { state ->
                _uiState.update { it.copy(audioPlayerState = state) }
                if (state == PlayerState.COMPLETED || state == PlayerState.IDLE || state == PlayerState.ERROR) {
                    _uiState.update {
                        it.copy(
                            currentPlayingAudioBlockId = null,
                            currentAudioPositionMs = 0
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            audioPlayer.currentPlayingBlockId.collect { blockId ->
                _uiState.update { it.copy(currentPlayingAudioBlockId = blockId) }
                if (blockId != _uiState.value.currentPlayingAudioBlockId) {
                    _uiState.update {
                        it.copy(
                            currentAudioPositionMs = 0,
                            currentAudioTotalDurationMs = 0
                        )
                    }
                }
            }
        }
        audioPositionJob = viewModelScope.launch {
            combine(
                audioPlayer.currentPositionMs,
                audioPlayer.totalDurationMs
            ) { pos, dur -> Pair(pos, dur) }.distinctUntilChanged().collect { (pos, dur) ->
                if (_uiState.value.currentPlayingAudioBlockId != null) {
                    _uiState.update {
                        it.copy(
                            currentAudioPositionMs = pos,
                            currentAudioTotalDurationMs = dur
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            currentAudioPositionMs = 0,
                            currentAudioTotalDurationMs = 0
                        )
                    }
                }
            }
        }
    }

    fun onPermissionResult(isGranted: Boolean) {
        _uiState.update {
            it.copy(
                permissionGranted = isGranted,
                showPermissionRationaleDialog = false
            )
        }
        if (!isGranted) {
            _uiState.update { it.copy(error = "Microphone permission denied.") }
        }
    }

    fun onNotificationPermissionResult(isGranted: Boolean) {
        val newStatus =
            if (isGranted) NotificationPermissionStatus.GRANTED else NotificationPermissionStatus.DENIED
        _uiState.update { it.copy(notificationPermissionStatus = newStatus) }

        if (isGranted) {
            if (_uiState.value.showReminderDialog && newStatus == NotificationPermissionStatus.GRANTED) {
                // Диалог откроется или уже открыт
            }
            _uiState.update { it.copy(error = null) }
        } else {
            _uiState.update { it.copy(error = "Notification permission denied. Reminders may not work.") }
        }
    }

    fun onPermissionRationaleDismissed() {
        _uiState.update { it.copy(showPermissionRationaleDialog = false) }
    }

    fun updateNoteTitle(newTitle: String) {
        _uiState.update { it.copy(noteTitle = newTitle) }
    }

    private fun parseTextAndUpdateDisplayedAudio() {
        val htmlForSearch = richTextState.annotatedString.text

        val regex = Regex(
            Regex.escape(AUDIO_PLACEHOLDER_PREFIX) +
                    "\\s*(\\d+)\\s*" +
                    Regex.escape(AUDIO_PLACEHOLDER_SUFFIX)
        )
        val foundAudioIdsInText = regex
            .findAll(htmlForSearch)
            .mapNotNull { it.groupValues[1].toLongOrNull() }
            .toSet()

        Log.d(TAG, "parseTextAndUpdateDisplayedAudio: Found IDs in HTML: $foundAudioIdsInText")

        val newDisplayedAudioBlocks = _pendingAudioBlocks
            .filter { foundAudioIdsInText.contains(it.id) }
            .sortedBy { it.orderIndex }

        if (_uiState.value.displayedAudioBlocks.map { it.id }
            != newDisplayedAudioBlocks.map { it.id }
        ) {
            Log.d(TAG, "Updating displayedAudioBlocks → ${newDisplayedAudioBlocks.map { it.id }}")
            _uiState.update { it.copy(displayedAudioBlocks = newDisplayedAudioBlocks) }
        } else {
            Log.d(TAG, "displayedAudioBlocks unchanged")
        }
    }

    fun toggleEditMode() {
        val currentIsEditing = _uiState.value.isEditing
        if (currentIsEditing) {
            if (hasUnsavedChanges()) {
                saveNote(switchToViewModeAfterSave = true)
            } else {
                _uiState.update { it.copy(isEditing = false) }
            }
        } else {
            Log.d(
                TAG,
                "toggleEditMode: entering edit, text='${richTextState.annotatedString.text}'"
            )
            _uiState.update { it.copy(isEditing = true) }
        }
    }

    // Универсальная проверка разрешения на микрофон
    private fun ensureMicPermission(): Boolean {
        return if (_uiState.value.permissionGranted) {
            true
        } else {
            // показываем свой AlertDialog (rationale)
            if (!_uiState.value.showPermissionRationaleDialog) {
                _uiState.update { it.copy(showPermissionRationaleDialog = true) }
            }
            false
        }
    }

    fun handleRecordButtonPress() {
        if (_uiState.value.isRecording) {
            stopRecordingAudio(); return
        }
        // единая проверка разрешения
        if (!ensureMicPermission()) return
        if (_uiState.value.isSpeechToTextActive) return
        startRecordingAudioInternal()
    }

    private fun startRecordingAudioInternal() {
        if (!_uiState.value.permissionGranted) {
            _uiState.update { it.copy(error = "Microphone permission not granted.") }; return
        }
        if (audioRecorder.isRecording()) return
        viewModelScope.launch {
            val audioFile = audioRecorder.createAudioFile()
            if (audioFile != null) {
                if (audioRecorder.startRecording(audioFile)) {
                    _uiState.update { it.copy(isRecording = true, error = null) }
                } else {
                    _uiState.update { it.copy(error = "Failed to start audio recording.") }; audioFile.delete()
                }
            } else {
                _uiState.update { it.copy(error = "Failed to create audio file.") }
            }
        }
    }

    fun stopRecordingAudio() {
        if (!audioRecorder.isRecording()) return
        viewModelScope.launch {
            val filePath = audioRecorder.stopRecording()
            _uiState.update { it.copy(isRecording = false) }
            if (filePath != null) {
                val duration = getAudioFileDuration(filePath)
                // Генерируем уникальное временное имя или предлагаем пользователю ввести
                val defaultDisplayName = "Audio ${System.currentTimeMillis() % 10000}"
                val newAudioBlock = NoteBlock(
                    id = System.currentTimeMillis(),
                    noteId = _uiState.value.noteId ?: 0L,
                    type = BlockType.AUDIO,
                    audioFilePath = filePath,
                    audioDurationMs = duration,
                    orderIndex = _pendingAudioBlocks.size,
                    audioDisplayName = defaultDisplayName
                )
                _pendingAudioBlocks.add(newAudioBlock)
                Log.d(
                    TAG,
                    "stopRecordingAudio: Added new block: ID=${newAudioBlock.id}, Name='${newAudioBlock.audioDisplayName}', Path='${newAudioBlock.audioFilePath}'"
                )
                val placeholder = createAudioPlaceholder(newAudioBlock.id)
                richTextState.replaceSelectedText(placeholder)
                Log.d(TAG, "stopRecordingAudio: Inserted placeholder '$placeholder'.")
                parseTextAndUpdateDisplayedAudio()
            } else {
                _uiState.update { it.copy(error = "Failed to save recording (file path is null).") }
            }
        }
    }

    // Новая функция в NoteDetailViewModel.kt
    fun updateAudioBlockDisplayName(blockId: Long, newName: String) {
        val blockIndexInPending = _pendingAudioBlocks.indexOfFirst { it.id == blockId }
        if (blockIndexInPending != -1) {
            val updatedBlock =
                _pendingAudioBlocks[blockIndexInPending].copy(audioDisplayName = newName.ifBlank { null })
            _pendingAudioBlocks[blockIndexInPending] = updatedBlock
            Log.d(TAG, "Updated display name for pending block ID $blockId to '$newName'")
        }

        // Также обновить в displayedAudioBlocks, если они источник для UI в данный момент
        val currentDisplayedBlocks = _uiState.value.displayedAudioBlocks.toMutableList()
        val blockIndexInDisplayed = currentDisplayedBlocks.indexOfFirst { it.id == blockId }
        if (blockIndexInDisplayed != -1) {
            val updatedBlockInDisplayed =
                currentDisplayedBlocks[blockIndexInDisplayed].copy(audioDisplayName = newName.ifBlank { null })
            currentDisplayedBlocks[blockIndexInDisplayed] = updatedBlockInDisplayed
            _uiState.update { it.copy(displayedAudioBlocks = currentDisplayedBlocks) }
            Log.d(TAG, "Updated display name for displayed block ID $blockId to '$newName'")
        }
    }

    fun handleSpeechToTextButtonPress() {
        Log.d(
            TAG, "handleSpeechToTextButtonPress called: " +
                    "isSpeechToTextActive=${_uiState.value.isSpeechToTextActive}, " +
                    "isRecording=${_uiState.value.isRecording}, " +
                    "permissionGranted=${_uiState.value.permissionGranted}"
        )

        if (_uiState.value.isSpeechToTextActive) {
            Log.d(TAG, "Stopping speech recognition")
            speechProcessor.stopListening()
            _uiState.update { it.copy(error = null) }
            return
        }

        if (_uiState.value.isRecording) {
            Log.d(TAG, "Cannot start STT while audio recording is active")
            _uiState.update { it.copy(error = "Stop audio recording before using Speech-to-Text.") }
            return
        }

        if (!ensureMicPermission()) {
            Log.d(TAG, "Microphone permission not granted; showing rationale")
            return
        }

        viewModelScope.launch {
            if (!speechProcessor.isRecognitionAvailable()) {
                Log.d(TAG, "Speech recognition service unavailable")
                _uiState.update { it.copy(error = "Speech recognition service is not available on this device.") }
                return@launch
            }

            // Получаем текущий выбранный язык из настроек
            val selectedSpeechLanguage =
                userPreferencesRepository.speechLanguageFlow.first() // first() для однократного получения
            val sttLanguage = selectedSpeechLanguage.languageTag

            Log.d(
                TAG,
                "Starting speech recognition with language: $sttLanguage (Selected: ${selectedSpeechLanguage.displayName})"
            )
            speechProcessor.startListening(sttLanguage)
            _uiState.update { it.copy(error = null) }
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
            Log.e(TAG, "Error getting audio duration for $filePath", e); 0L
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
                if (currentState.currentPlayingAudioBlockId != null && currentState.currentPlayingAudioBlockId != blockId) {
                    audioPlayer.stop()
                }
                audioPlayer.play(path, blockId)
            }
        } ?: Log.w(TAG, "Audio block $blockId not found to play.")
    }

    fun deleteAudioBlockFromPlayer(blockId: Long) {
        viewModelScope.launch {
            val plainPlaceholder = createAudioPlaceholder(blockId)
            val currentHtmlWithEntities =
                richTextState.toHtml()
            var textContentActuallyChanged =
                false


            val currentHtmlDecoded = Parser.unescapeEntities(currentHtmlWithEntities, false)
            Log.d(
                TAG,
                "deleteAudioBlockFromPlayer: Attempting to remove placeholder '$plainPlaceholder'."
            )
            Log.d(
                TAG,
                "deleteAudioBlockFromPlayer: HTML from editor (with entities): '${
                    currentHtmlWithEntities.take(150)
                }'"
            )
            Log.d(
                TAG,
                "deleteAudioBlockFromPlayer: Decoded HTML for search: '${currentHtmlDecoded.take(150)}'"
            )


            if (currentHtmlDecoded.contains(plainPlaceholder)) {
                val newHtmlDecoded = currentHtmlDecoded.replace(plainPlaceholder, "")
                richTextState.setHtml(newHtmlDecoded)
                textContentActuallyChanged = true
                Log.d(
                    TAG,
                    "Removed placeholder for block $blockId. Called richTextState.setHtml() with updated decoded HTML."
                )
            } else {
                Log.w(
                    TAG,
                    "Placeholder '$plainPlaceholder' for audio block $blockId not found in DECODED HTML ('${
                        currentHtmlDecoded.take(
                            100
                        )
                    }...') for deletion."
                )
            }

            val blockToRemove = _pendingAudioBlocks.find { it.id == blockId }
            if (blockToRemove != null) {
                blockToRemove.audioFilePath?.let { filePath ->
                    try {
                        File(filePath).delete()
                        Log.i(TAG, "Deleted audio file: $filePath for block $blockId")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deleting file for block $blockId: $filePath", e)
                    }
                }
                val removedFromPending = _pendingAudioBlocks.remove(blockToRemove)
                Log.d(
                    TAG,
                    "Removed block from _pendingAudioBlocks: ID=$blockId, Success: $removedFromPending"
                )

                if (!textContentActuallyChanged && removedFromPending) {
                    Log.d(
                        TAG,
                        "deleteAudioBlockFromPlayer: Text not changed, but block removed from pending. Calling parseTextAndUpdateDisplayedAudio()."
                    )
                    parseTextAndUpdateDisplayedAudio()
                }
            } else {
                Log.w(
                    TAG,
                    "Block ID $blockId not found in _pendingAudioBlocks for deletion. Current pending IDs: ${_pendingAudioBlocks.map { it.id }}"
                )
            }
        }
    }

    fun onSeekAudio(blockId: Long, positionMs: Int) {
        if (_uiState.value.currentPlayingAudioBlockId == blockId) {
            audioPlayer.seekTo(positionMs)
        }
    }

    fun saveNote(
        switchToViewModeAfterSave: Boolean = false,
        fromViewModeClearReminder: Boolean = false
    ) { // МОЙ КОММЕНТАРИЙ: добавлен параметр
        viewModelScope.launch {
            // МОЙ КОММЕНТАРИЙ: Не устанавливаем isSaving = true, если это просто отмена напоминания из режима просмотра,
            // чтобы не показывать лишний индикатор сохранения на весь экран
            Log.d(TAG, "saveNote: Setting isSaving = true. Current isEditing: ${_uiState.value.isEditing}")
            if (!fromViewModeClearReminder) {
                _uiState.update { it.copy(isSaving = true, error = null) }
            } else {
                // Можно установить какой-то легкий индикатор, если очень хочется, но обычно это быстрая операция
                Log.d(TAG, "saveNote: Saving note due to reminder clear from view mode. isSaving not changed yet.")
            }

            try {
                // МОЙ КОММЕНТАРИЙ: Если это отмена напоминания из режима просмотра,
                // то заголовок и контент берем из original* значений,
                // так как пользователь не был в режиме редактирования.
                // Категория также берется из original.
                val currentNoteId = _uiState.value.noteId?.takeIf { it != 0L } ?: 0L
                val title =
                    if (fromViewModeClearReminder) originalNoteTitle else _uiState.value.noteTitle.ifBlank { "Untitled Note" }
                val reminderTime =
                    _uiState.value.reminderAt // Это значение уже будет null, если вызвано из clearReminderAndViewMode
                val selectedCategoryName =
                    if (fromViewModeClearReminder) originalCategoryName else _uiState.value.selectedCategory.name

                // МОЙ КОММЕНТАРИЙ: Контент и аудио-блоки также берем из original, если отмена из view mode.
                // Однако, если в будущем в режиме просмотра можно будет менять аудио (что маловероятно), это нужно будет учесть.
                // Пока предполагаем, что аудио не меняются в режиме просмотра.
                val htmlContentToSave: String
                val audioToSave: List<NoteBlock>

                if (fromViewModeClearReminder) {
                    htmlContentToSave = originalHtmlContent
                    // ВАЖНО: нужно взять актуальные аудио-блоки, которые были загружены с заметкой
                    // _pendingAudioBlocks может содержать изменения из предыдущего сеанса редактирования,
                    // если пользователь не сохранил их и просто вышел из режима редактирования.
                    // Лучше всего было бы иметь исходный список аудио-блоков, загруженных с заметкой.
                    // Для простоты, пока будем использовать _pendingAudioBlocks, но это место для улучшения,
                    // если нужна строгая консистентность при отмене напоминания из режима просмотра
                    // без входа в редактирование.
                    // ИДЕАЛЬНО: при loadNoteData сохранять originalAudioBlocks.
                    // Сейчас _pendingAudioBlocks перезаписываются при loadNoteData.
                    // Значит, если пользователь не входил в режим редактирования, они должны быть актуальны.
                    audioToSave = _pendingAudioBlocks.mapIndexed { idx, block ->
                        block.copy(
                            noteId = 0L,
                            orderIndex = idx
                        )
                    }

                } else {
                    val htmlWithEntities = richTextState.toHtml()
                    htmlContentToSave = Parser.unescapeEntities(htmlWithEntities, false)

                    val rawText = richTextState.annotatedString.text
                    val idRegex = Regex(
                        Regex.escape(AUDIO_PLACEHOLDER_PREFIX) +
                                "\\s*(\\d+)\\s*" +
                                Regex.escape(AUDIO_PLACEHOLDER_SUFFIX)
                    )
                    val tempIdsInText = idRegex
                        .findAll(rawText)
                        .mapNotNull { it.groupValues[1].toLongOrNull() }
                        .toSet()

                    audioToSave = _pendingAudioBlocks
                        .filter { tempIdsInText.contains(it.id) }
                        .mapIndexed { idx, block -> block.copy(noteId = 0L, orderIndex = idx) }
                }

                Log.d(TAG, "saveNote: HTML to save: '${htmlContentToSave.take(100)}'")
                Log.d(
                    TAG,
                    "saveNote: Audio blocks to save: ${audioToSave.map { "ID:${it.id}, Name:${it.audioDisplayName}" }}"
                )


                val note = Note(
                    id = currentNoteId,
                    title = title,
                    content = htmlContentToSave,
                    createdAt = if (currentNoteId == 0L) System.currentTimeMillis()
                    else (initialNoteCreatedAt ?: System.currentTimeMillis()),
                    updatedAt = System.currentTimeMillis(), // Всегда обновляем updatedAt
                    reminderAt = reminderTime,
                    category = selectedCategoryName
                )

                val savedNoteId = noteRepository.saveNote(note, audioToSave)
                Log.i(
                    TAG,
                    "Note saved, id: $savedNoteId, category: $selectedCategoryName, reminder: $reminderTime"
                )

                if (savedNoteId > 0L) {
                    if (currentNoteId == 0L) initialNoteCreatedAt = note.createdAt
                    savedStateHandle["noteId"] = savedNoteId

                    scheduleOrCancelReminderWorker(savedNoteId, note.title, reminderTime)

                    // Обновляем original* значения, чтобы hasUnsavedChanges() работал корректно
                    originalNoteTitle = title
                    originalHtmlContent = htmlContentToSave
                    originalReminderAt = reminderTime
                    originalCategoryName = selectedCategoryName
                    // МОЙ КОММЕНТАРИЙ: После сохранения, _pendingAudioBlocks должны отражать сохраненное состояние.
                    // DAO.saveNoteAndAudioBlocks возвращает новые ID для блоков.
                    // Чтобы _pendingAudioBlocks были актуальны, нужно их перезагрузить или обновить ID.
                    // Проще всего перезагрузить, если switchToViewModeAfterSave или fromViewModeClearReminder.
                    // Или, если сохранение было в режиме редактирования, оставить как есть, а при переходе в view mode произойдет loadNoteData.

                    val newUiStateAfterSave = _uiState.value.copy(
                        isSaving = if (fromViewModeClearReminder) false else _uiState.value.isSaving, // Не меняем isSaving, если это авто-сохранение
                        noteId = savedNoteId,

                        // isEditing не меняем здесь, если это авто-сохранение из-за отмены напоминания
                        isEditing = if (fromViewModeClearReminder) false else !switchToViewModeAfterSave,
                        reminderAt = reminderTime // Обновляем reminderAt в UI
                    )

                    _uiState.update { newUiStateAfterSave }

                    if (fromViewModeClearReminder || switchToViewModeAfterSave) {
                        Log.d(
                            TAG,
                            "saveNote successful, calling loadNoteData(). switchToViewMode: $switchToViewModeAfterSave, fromViewModeClear: $fromViewModeClearReminder"
                        )
                        loadNoteData()
                        _uiState.update { it.copy(isSaving = false) }
                    }


                } else {
                    Log.e(TAG, "saveNote: Failed to save, savedNoteId <= 0.")
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            error = "Failed to save note after reminder clear/save."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "saveNote: Exception during save.", e)
                _uiState.update {
                    it.copy(isSaving = false, error = e.message ?: "Failed to save note")
                }
            }
        }
    }

    fun clearReminderAndViewMode() {
        if (_uiState.value.reminderAt == null) return // Нечего отменять

        Log.d(TAG, "clearReminderAndViewMode called.")
        _uiState.update { it.copy(reminderAt = null) }
        saveNote(switchToViewModeAfterSave = false, fromViewModeClearReminder = true)
    }

    fun hasUnsavedChanges(): Boolean {
        val titleChanged = _uiState.value.noteTitle != originalNoteTitle

        val currentHtmlFromEditorWithEntities = richTextState.toHtml()
        val currentHtmlDecoded = Parser.unescapeEntities(currentHtmlFromEditorWithEntities, false)
        val contentChanged = currentHtmlDecoded != originalHtmlContent

        val reminderChanged = _uiState.value.reminderAt != originalReminderAt
        val categoryChanged =
            _uiState.value.selectedCategory.name != originalCategoryName // ПРОВЕРКА ИЗМЕНЕНИЯ КАТЕГОРИИ

        if (titleChanged) Log.d(TAG, "hasUnsavedChanges: titleChanged = true")
        if (contentChanged) Log.d(
            TAG,
            "hasUnsavedChanges: contentChanged = true " +
                    "(Original direct chars: ${originalHtmlContent.take(50)}, " +
                    "Current decoded by Jsoup: ${currentHtmlDecoded.take(50)})"
        )
        if (reminderChanged) Log.d(
            TAG,
            "hasUnsavedChanges: reminderChanged = true (Original: $originalReminderAt, Current: ${_uiState.value.reminderAt})"
        )
        if (categoryChanged) Log.d(
            TAG,
            "hasUnsavedChanges: categoryChanged = true (Original: $originalCategoryName, Current: ${_uiState.value.selectedCategory.name})"
        )

        return titleChanged || contentChanged || reminderChanged || categoryChanged // ДОБАВЛЯЕМ categoryChanged В УСЛОВИЕ
    }

    fun toggleBoldSelection() {
        if (canApplyStyleToSelection()) richTextState.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
    }

    fun toggleItalicSelection() {
        if (canApplyStyleToSelection()) richTextState.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
    }

    fun toggleUnderlineSelection() {
        if (canApplyStyleToSelection()) richTextState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline))
    }

    fun toggleStrikethroughSelection() {
        if (canApplyStyleToSelection()) richTextState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
    }

    fun changeTextColor(color: Color) {
        if (canApplyStyleToSelection()) richTextState.toggleSpanStyle(SpanStyle(color = color))
    }

    fun setFontSize(size: TextUnit) {
        if (canApplyStyleToSelection()) richTextState.toggleSpanStyle(SpanStyle(fontSize = size))
    }

    private fun canApplyStyleToSelection(): Boolean {
        val text = richTextState.annotatedString.text
        val selection = richTextState.selection
        if (selection.collapsed) return true

        val regex = Regex(
            Regex.escape(AUDIO_PLACEHOLDER_PREFIX) + "\\s*\\d+\\s*" + Regex.escape(
                AUDIO_PLACEHOLDER_SUFFIX
            )
        )
        regex.findAll(text).forEach { matchResult ->
            if (selection.start < matchResult.range.last + 1 && matchResult.range.first < selection.end) {
                Log.d(
                    TAG,
                    "Style application denied: selection intersects with audio placeholder '${matchResult.value}'"
                )
                _uiState.update { it.copy(error = "Cannot apply style to audio placeholder.") }
                return false
            }
        }
        _uiState.update { it.copy(error = null) }
        return true
    }

    fun onReminderIconClick() {
        // **Разрешаем открывать диалог только в режиме редактирования**
        if (!_uiState.value.isEditing) {
            // В режиме просмотра ничего не делаем при клике, или можно показать Snackbar "Edit note to change reminder"
            Log.d(TAG, "Reminder icon clicked in view mode. No action.")
            // _uiState.update { it.copy(error = "Edit note to set/change reminder.") } // Опционально
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            )) {
                PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "Notification permission already granted.")
                    _uiState.update {
                        it.copy(
                            notificationPermissionStatus = NotificationPermissionStatus.GRANTED,
                            showReminderDialog = true
                        )
                    }
                }

                else -> {
                    Log.d(
                        TAG,
                        "Notification permission NOT granted. Setting status to DENIED to trigger UI request."
                    )
                    _uiState.update { it.copy(notificationPermissionStatus = NotificationPermissionStatus.DENIED) }
                }
            }
        } else {
            Log.d(TAG, "Android version < TIRAMISU, no notification permission needed.")
            _uiState.update {
                it.copy(
                    notificationPermissionStatus = NotificationPermissionStatus.GRANTED,
                    showReminderDialog = true
                )
            }
        }
    }

    fun onDismissReminderDialog() {
        _uiState.update { it.copy(showReminderDialog = false) }
    }

    fun onSetReminder(dateTimeMillis: Long) {
        if (dateTimeMillis <= System.currentTimeMillis()) {
            _uiState.update { it.copy(error = "Please select a future time for the reminder.") }
            return
        }
        _uiState.update {
            it.copy(
                reminderAt = dateTimeMillis,
                showReminderDialog = false,
                error = null
            )
        }
        Log.d(TAG, "Reminder set in ViewModel to: $dateTimeMillis")
    }

    fun onClearReminder() { // Вызывается из диалога в режиме РЕДАКТИРОВАНИЯ
        _uiState.update {
            it.copy(
                reminderAt = null,
                showReminderDialog = false
            )
        }
        Log.d(TAG, "Reminder cleared in ViewModel (edit mode).")
    }

    private fun scheduleOrCancelReminderWorker(
        noteId: Long,
        noteTitle: String,
        reminderTimeMillis: Long?
    ) {
        val workTag = "reminder_note_$noteId"

        if (reminderTimeMillis != null && reminderTimeMillis > System.currentTimeMillis()) {
            val initialDelay = reminderTimeMillis - System.currentTimeMillis()
            Log.i(
                TAG,
                "Scheduling reminder for note $noteId ('$noteTitle') in $initialDelay ms. Tag: $workTag"
            )

            val data = Data.Builder()
                .putLong(ReminderWorker.KEY_NOTE_ID, noteId)
                .putString(ReminderWorker.KEY_NOTE_TITLE, noteTitle)
                .build()

            val reminderWorkRequest = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .addTag(workTag)
                .build()

            workManager.enqueueUniqueWork(
                workTag,
                ExistingWorkPolicy.REPLACE,
                reminderWorkRequest
            )
            Log.i(TAG, "Work request enqueued/replaced for tag: $workTag")
        } else {
            Log.i(
                TAG,
                "Cancelling any existing reminder for note $noteId. Tag: $workTag. ReminderTime: $reminderTimeMillis"
            )
            workManager.cancelUniqueWork(workTag)
            Log.i(TAG, "Work request cancelled for tag: $workTag")
        }
    }

    fun resetNotificationPermissionStatusRequest() {
        if (_uiState.value.notificationPermissionStatus == NotificationPermissionStatus.DENIED) {
            _uiState.update { it.copy(notificationPermissionStatus = NotificationPermissionStatus.UNDETERMINED) }
        }
    }

    fun onCategorySelected(category: NoteCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    override fun onCleared() {
        super.onCleared()
        if (audioRecorder.isRecording()) {
            audioRecorder.stopRecording()
                ?.let { File(it).delete() }
        }
        audioPlayer.release()
        speechProcessor.release()
        audioPlayerStateJob?.cancel()
        audioPositionJob?.cancel()
        Log.d(TAG, "ViewModel cleared.")
    }
}