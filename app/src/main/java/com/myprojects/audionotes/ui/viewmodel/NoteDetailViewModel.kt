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
import com.myprojects.audionotes.data.local.dao.cleanAudioPlaceholdersForRegex
import com.myprojects.audionotes.data.local.entity.BlockType
import com.myprojects.audionotes.data.local.entity.Note
import com.myprojects.audionotes.data.local.entity.NoteBlock
import com.myprojects.audionotes.data.local.entity.NoteCategory
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
    private val workManager: WorkManager, // НОВОЕ: для напоминаний
    @ApplicationContext private val appContext: Context, // НОВОЕ: для проверки разрешений
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val currentNoteIdFromArgs: Long = savedStateHandle.get<Long>("noteId") ?: -1L
    private var originalNoteTitle: String = ""
    private var originalHtmlContent: String = "<p><br></p>" // Начальное значение для HTML
    private var initialNoteCreatedAt: Long? = null
    private var originalReminderAt: Long? = null // НОВОЕ: для отслеживания изменений напоминания

    val richTextState = RichTextState()

    private val _uiState =
        MutableStateFlow(NoteDetailUiState(initialHtmlContentForEditor = originalHtmlContent))
    val uiState: StateFlow<NoteDetailUiState> = _uiState.asStateFlow()

    // _pendingAudioBlocks используется для временного хранения аудио-блоков до их сохранения в БД
    // и для корректного отображения плееров в режиме редактирования.
    private val _pendingAudioBlocks = mutableStateListOf<NoteBlock>()
    private var audioPositionJob: Job? = null
    private var audioPlayerStateJob: Job? = null

    companion object {
        private const val TAG = "NoteDetailVM_Rich"
        private val PLACEHOLDER_REGEX = Regex("\\[AUDIO_ID\\s*:\\s*(\\d+)\\s*]")
    }

    init {
        Log.d(TAG, "ViewModel init. Received noteId: $currentNoteIdFromArgs")
        val isTrulyNewNoteScenario = currentNoteIdFromArgs == -1L || currentNoteIdFromArgs == 0L
        _uiState.update {
            it.copy(
                isEditing = isTrulyNewNoteScenario,
                noteId = if (isTrulyNewNoteScenario) 0L else currentNoteIdFromArgs,
                isLoading = !isTrulyNewNoteScenario // Загрузка, если это существующая заметка
            )
        }

        if (isTrulyNewNoteScenario) {
            richTextState.setHtml(originalHtmlContent) // Устанавливаем пустой HTML
            _uiState.update { it.copy(isLoading = false, noteTitle = "New Note") }
            // Для новой заметки displayedAudioBlocks должен быть пуст, т.к. _pendingAudioBlocks пуст
            parseTextAndUpdateDisplayedAudio()
        } else {
            loadNoteData() // Загружаем существующую заметку
        }

        observeAudioPlayer()
        observeSpeechProcessor()

        // Отслеживание изменений в тексте редактора для обновления списка аудио-блоков
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
                    // Добавляем пробелы вокруг вставляемого текста, если это необходимо,
                    // чтобы слова не слипались.
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
                    _uiState.update { it.copy(error = null) } // Сбрасываем ошибку, если была
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
                            isEditing = true
                        )
                    }
                    setAsNewNote()
                }
                .collect { noteWithContentAndAudioBlocks ->
                    if (noteWithContentAndAudioBlocks != null) {
                        val note = noteWithContentAndAudioBlocks.note
                        val audioBlocks = noteWithContentAndAudioBlocks.audioBlocks
                        Log.d(
                            TAG,
                            "loadNoteData: Note loaded: ${note.title}, AudioBlock count: ${audioBlocks.size}, ReminderAt: ${note.reminderAt}"
                        )

                        originalNoteTitle = note.title
                        // HTML из БД УЖЕ ДОЛЖЕН БЫТЬ с прямыми кириллическими символами,
                        // так как мы его декодировали при сохранении с помощью Jsoup.
                        originalHtmlContent = note.content.ifBlank { "<p><br></p>" }
                        initialNoteCreatedAt = note.createdAt
                        originalReminderAt = note.reminderAt

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
                                reminderAt = note.reminderAt
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
                error = null
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
                    // Сбрасываем состояние воспроизведения, если плеер остановился или завершил
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
                if (blockId != _uiState.value.currentPlayingAudioBlockId) { // Если ID сменился, сбросить позицию
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
                if (_uiState.value.currentPlayingAudioBlockId != null) { // Обновлять только если что-то играет
                    _uiState.update {
                        it.copy(
                            currentAudioPositionMs = pos,
                            currentAudioTotalDurationMs = dur
                        )
                    }
                } else { // Если ничего не играет, сбросить
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

    fun onPermissionResult(isGranted: Boolean) { // Для микрофона
        _uiState.update {
            it.copy(
                permissionGranted = isGranted,
                showPermissionRationaleDialog = false // Скрываем диалог объяснения
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
            // Если разрешение было только что дано, и пользователь уже пытался открыть диалог напоминаний,
            // то открываем его.
            if (_uiState.value.showReminderDialog && newStatus == NotificationPermissionStatus.GRANTED) {
                // Диалог уже должен быть открыт или откроется по флагу showReminderDialog
            }
            _uiState.update { it.copy(error = null) } // Убираем возможную ошибку о разрешении
        } else {
            _uiState.update { it.copy(error = "Notification permission denied. Reminders may not work.") }
        }
    }

    fun onPermissionRationaleDismissed() { // Для микрофона
        _uiState.update { it.copy(showPermissionRationaleDialog = false) }
    }

    fun updateNoteTitle(newTitle: String) {
        _uiState.update { it.copy(noteTitle = newTitle) }
    }

    private fun parseTextAndUpdateDisplayedAudio() {
        // Extract the raw HTML from the editor, undoing any placeholder-escaping
        val htmlForSearch = richTextState
            .toHtml()
            .cleanAudioPlaceholdersForRegex()

        // Find all [AUDIO_ID:xxx] in the HTML
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

        // Filter your pending blocks down to just those referenced in the editor,
        // preserving their original orderIndex.
        val newDisplayedAudioBlocks = _pendingAudioBlocks
            .filter { foundAudioIdsInText.contains(it.id) }
            .sortedBy { it.orderIndex }

        // Only update UI state if IDs/order actually changed
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
        if (currentIsEditing) { // Если выходим из режима редактирования
            if (hasUnsavedChanges()) {
                saveNote(switchToViewModeAfterSave = true) // Сохраняем и переключаем
            } else {
                _uiState.update { it.copy(isEditing = false) } // Просто переключаем
            }
        } else { // Если входим в режим редактирования
            Log.d(
                TAG,
                "toggleEditMode: entering edit, text='${richTextState.annotatedString.text}'"
            )
            _uiState.update { it.copy(isEditing = true) }
        }
    }

    fun handleRecordButtonPress() {
        if (_uiState.value.isRecording) {
            stopRecordingAudio()
        } else if (_uiState.value.permissionGranted) { // Проверяем разрешение на микрофон
            if (!_uiState.value.isSpeechToTextActive) { // Не начинаем запись, если STT активен
                startRecordingAudioInternal()
            }
        } else {
            // Запрашиваем разрешение на микрофон
            _uiState.update { it.copy(showPermissionRationaleDialog = true) }
        }
    }

    private fun startRecordingAudioInternal() {
        if (!_uiState.value.permissionGranted) {
            _uiState.update { it.copy(error = "Microphone permission not granted.") }; return
        }
        if (audioRecorder.isRecording()) return // Уже записываем
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
                // Создаем новый NoteBlock с временным ID
                val newAudioBlock = NoteBlock(
                    id = System.currentTimeMillis(), // Временный ID, будет заменен при сохранении в БД
                    noteId = _uiState.value.noteId
                        ?: 0L, // Временный noteId, будет обновлен при сохранении
                    type = BlockType.AUDIO,
                    audioFilePath = filePath,
                    audioDurationMs = duration,
                    orderIndex = _pendingAudioBlocks.size // Порядок для новых блоков
                )
                _pendingAudioBlocks.add(newAudioBlock)
                Log.d(
                    TAG,
                    "stopRecordingAudio: Added new block to _pendingAudioBlocks: ID=${newAudioBlock.id}, path=${newAudioBlock.audioFilePath}"
                )
                val placeholder = createAudioPlaceholder(newAudioBlock.id)
                // Вставляем плейсхолдер в текущую позицию курсора или заменяем выделение
                richTextState.replaceSelectedText(placeholder)
                Log.d(TAG, "stopRecordingAudio: Inserted placeholder '$placeholder'.")
                // parseTextAndUpdateDisplayedAudio() будет вызван автоматически из-за изменения richTextState
            } else {
                _uiState.update { it.copy(error = "Failed to save recording (file path is null).") }
            }
        }
    }

    fun handleSpeechToTextButtonPress() {
        viewModelScope.launch {
            if (_uiState.value.isSpeechToTextActive) {
                Log.d(TAG, "STT button pressed: stopping speech processor.")
                speechProcessor.stopListening()
                return@launch
            }

            if (_uiState.value.isRecording) { // Не запускаем STT, если идет обычная запись
                _uiState.update { it.copy(error = "Stop audio recording before using Speech-to-Text.") }
                return@launch
            }
            if (!_uiState.value.permissionGranted) { // Проверяем разрешение на микрофон
                _uiState.update {
                    it.copy(
                        showPermissionRationaleDialog = true, // Показываем диалог объяснения
                        error = "Microphone permission needed for speech-to-text."
                    )
                }
                return@launch
            }
            if (!speechProcessor.isRecognitionAvailable()) {
                _uiState.update { it.copy(error = "Speech recognition service is not available on this device.") }
                return@launch
            }

            val sttLanguage = "ru-RU" // Можно сделать настраиваемым
            Log.d(TAG, "STT button pressed: starting speech processor with lang $sttLanguage.")
            speechProcessor.startListening(sttLanguage)
            _uiState.update { it.copy(error = null) } // Очищаем предыдущие ошибки
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
        // Ищем сначала среди отображаемых (уже сохраненных или отфильтрованных), потом среди всех pending
        val blockToPlay = _uiState.value.displayedAudioBlocks.find { it.id == blockId }
            ?: _pendingAudioBlocks.find { it.id == blockId }

        blockToPlay?.audioFilePath?.let { path ->
            val currentState = _uiState.value
            if (currentState.currentPlayingAudioBlockId == blockId && currentState.audioPlayerState == PlayerState.PLAYING) {
                audioPlayer.pause()
            } else if (currentState.currentPlayingAudioBlockId == blockId && currentState.audioPlayerState == PlayerState.PAUSED) {
                audioPlayer.resume()
            } else {
                // Останавливаем предыдущее воспроизведение, если играл другой блок
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
            // Для RichTextEditor, если плейсхолдер это просто текст, то replace должен сработать
            val currentHtml = richTextState.toHtml()
            var htmlChanged = false

            if (currentHtml.contains(plainPlaceholder)) {
                richTextState.setHtml(currentHtml.replace(plainPlaceholder, ""))
                htmlChanged = true
                Log.d(TAG, "Removed placeholder for block $blockId from HTML via replace.")
            } else {
                // Если в HTML не нашли (например, из-за [), пробуем из raw text,
                // но это может не обновить HTML редактора корректно без setHtml.
                val currentRawText = richTextState.annotatedString.text
                if (currentRawText.contains(plainPlaceholder)) {
                    // Это сложнее, т.к. нужно удалить Span из AnnotatedString или пересоздать HTML
                    // Простейший вариант - если HTML генерируется из AnnotatedString, то это может сработать
                    // richTextState.setText(currentRawText.replace(plainPlaceholder, "")) // Если есть такой метод
                    // richTextState.setHtml(currentRawText.replace(plainPlaceholder, "")) // Генерируем HTML из измененного текста
                    // Это может быть неидеально, но для простого плейсхолдера может сработать
                    Log.w(
                        TAG,
                        "Placeholder for block $blockId found in raw text but not directly in HTML. Attempting removal (may need review)."
                    )
                    // Если RichTextEditor имеет метод для удаления текста по совпадению, это было бы лучше.
                    // Пока оставляем как есть, но это место может требовать доработки для сложных HTML.
                } else {
                    Log.w(
                        TAG,
                        "Placeholder for audio block $blockId not found in HTML or raw text for deletion."
                    )
                }
            }

            val blockToRemove = _pendingAudioBlocks.find { it.id == blockId }
            if (blockToRemove != null) {
                blockToRemove.audioFilePath?.let { filePath ->
                    try {
                        File(filePath).delete()
                        Log.i(TAG, "Deleted audio file: $filePath")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deleting file for block $blockId: $filePath", e)
                    }
                }
                _pendingAudioBlocks.remove(blockToRemove)
                Log.d(TAG, "Removed block from _pendingAudioBlocks: ID=$blockId")
                // Если HTML не изменился через setHtml, но блок удален из pending, нужно обновить displayedAudioBlocks
                if (!htmlChanged) {
                    parseTextAndUpdateDisplayedAudio()
                }
                // Если HTML изменился через setHtml, parseTextAndUpdateDisplayedAudio вызовется через snapshotFlow
            }
        }
    }

    fun onSeekAudio(blockId: Long, positionMs: Int) {
        if (_uiState.value.currentPlayingAudioBlockId == blockId) {
            audioPlayer.seekTo(positionMs)
        }
    }

    fun saveNote(switchToViewModeAfterSave: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                // 1) Gather basic fields
                val currentNoteId = _uiState.value.noteId?.takeIf { it != 0L } ?: 0L
                val title = _uiState.value.noteTitle.ifBlank { "Untitled Note" }
                val reminderTime = _uiState.value.reminderAt

                // 2) Get HTML from editor (with entities)
                val htmlWithEntities = richTextState.toHtml()
                Log.d(TAG, "saveNote: HTML from editor: '$htmlWithEntities'")

                // 3) Decode entities to direct chars using Jsoup
                val htmlDecoded = Parser.unescapeEntities(htmlWithEntities, false)
                Log.d(TAG, "saveNote: Decoded HTML: '$htmlDecoded'")

                // 4) Extract temp audio IDs from the raw annotated text
                val rawText = richTextState.annotatedString.text
                val idRegex = Regex(
                    Regex.escape(AUDIO_PLACEHOLDER_PREFIX) +
                            "\\s*(\\d+)\\s*" +
                            Regex.escape(AUDIO_PLACEHOLDER_SUFFIX)
                )
                val tempIds = idRegex
                    .findAll(rawText)
                    .mapNotNull { it.groupValues[1].toLongOrNull() }
                    .toSet()
                Log.d(TAG, "saveNote: temp audio IDs: $tempIds")

                // 5) Filter & re‐index pending blocks for persistence
                val audioToSave = _pendingAudioBlocks
                    .filter { tempIds.contains(it.id) }
                    .mapIndexed { idx, block -> block.copy(noteId = 0L, orderIndex = idx) }

                // 6) Build the Note entity
                val note = Note(
                    id = currentNoteId,
                    title = title,
                    content = htmlDecoded,  // unescaped HTML
                    createdAt = if (currentNoteId == 0L) System.currentTimeMillis()
                    else (initialNoteCreatedAt ?: System.currentTimeMillis()),
                    updatedAt = System.currentTimeMillis(),
                    reminderAt = reminderTime
                )

                // 7) Save via repository (DAO will do ID sync for you)
                val savedNoteId = noteRepository.saveNote(note, audioToSave)
                Log.i(TAG, "Note saved, id: $savedNoteId")

                if (savedNoteId > 0L) {
                    if (currentNoteId == 0L) initialNoteCreatedAt = note.createdAt
                    savedStateHandle["noteId"] = savedNoteId

                    // 8) (Re-)schedule or cancel reminders
                    scheduleOrCancelReminderWorker(savedNoteId, note.title, reminderTime)
                    originalReminderAt = reminderTime

                    // 9) Update UI state & reload
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            noteId = savedNoteId,
                            isEditing = !switchToViewModeAfterSave
                        )
                    }
                    loadNoteData()
                } else {
                    _uiState.update { it.copy(isSaving = false, error = "Failed to save note") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving note", e)
                _uiState.update {
                    it.copy(isSaving = false, error = e.message ?: "Failed to save note")
                }
            }
        }
    }


    fun hasUnsavedChanges(): Boolean {
        val titleChanged = _uiState.value.noteTitle != originalNoteTitle

        // Получаем текущий HTML из редактора (вероятно, с сущностями)
        val currentHtmlFromEditorWithEntities = richTextState.toHtml()
        // Декодируем его с помощью Jsoup для сравнения
        val currentHtmlDecoded = Parser.unescapeEntities(currentHtmlFromEditorWithEntities, false)

        // originalHtmlContent уже хранит HTML с прямыми символами
        val contentChanged = currentHtmlDecoded != originalHtmlContent
        val reminderChanged = _uiState.value.reminderAt != originalReminderAt

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

        return titleChanged || contentChanged || reminderChanged
    }


    // --- Text Formatting ---
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
        if (selection.collapsed) return true // Если нет выделения, стиль применяется к следующему вводимому тексту

        val regex = Regex(
            Regex.escape(AUDIO_PLACEHOLDER_PREFIX) + "\\s*\\d+\\s*" + Regex.escape(
                AUDIO_PLACEHOLDER_SUFFIX
            )
        )
        regex.findAll(text).forEach { matchResult ->
            // Проверяем пересечение выделения и плейсхолдера
            if (selection.start < matchResult.range.last + 1 && matchResult.range.first < selection.end) {
                Log.d(
                    TAG,
                    "Style application denied: selection intersects with audio placeholder '${matchResult.value}'"
                )
                _uiState.update { it.copy(error = "Cannot apply style to audio placeholder.") } // Сообщаем пользователю
                return false
            }
        }
        _uiState.update { it.copy(error = null) } // Сбрасываем ошибку, если стиль можно применить
        return true
    }

    fun onReminderIconClick() {
        // Сначала проверяем разрешение на уведомления (для Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            )) {
                PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "Notification permission already granted.")
                    // Разрешение есть, показываем диалог
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
                    // Разрешение не дано, нужно его запросить. Устанавливаем статус для UI, чтобы он инициировал запрос.
                    _uiState.update { it.copy(notificationPermissionStatus = NotificationPermissionStatus.DENIED) }
                }
            }
        } else {
            // На версиях < Android 13 разрешение не требуется
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
        // Проверяем, что выбранное время в будущем
        if (dateTimeMillis <= System.currentTimeMillis()) {
            _uiState.update { it.copy(error = "Please select a future time for the reminder.") }
            return
        }
        _uiState.update {
            it.copy(
                reminderAt = dateTimeMillis,
                showReminderDialog = false, // Закрываем диалог после установки
                error = null // Сбрасываем ошибку
            )
        }
        Log.d(TAG, "Reminder set in ViewModel to: $dateTimeMillis")
        // Сохранение и планирование Worker'а произойдет при вызове saveNote()
    }

    fun onClearReminder() {
        _uiState.update {
            it.copy(
                reminderAt = null,
                showReminderDialog = false // Закрываем диалог после очистки
            )
        }
        Log.d(TAG, "Reminder cleared in ViewModel.")
        // Отмена Worker'а произойдет при вызове saveNote()
    }

    private fun scheduleOrCancelReminderWorker(
        noteId: Long,
        noteTitle: String,
        reminderTimeMillis: Long?
    ) {
        val workTag = "reminder_note_$noteId" // Уникальный тег для работы этой заметки

        if (reminderTimeMillis != null && reminderTimeMillis > System.currentTimeMillis()) {
            // Если есть время напоминания и оно в будущем, планируем Worker
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
                .addTag(workTag) // Добавляем тег для возможности отмены по нему
                .build()

            workManager.enqueueUniqueWork(
                workTag, // Уникальное имя для этой работы
                ExistingWorkPolicy.REPLACE, // Заменить существующую работу, если она есть
                reminderWorkRequest
            )
            Log.i(TAG, "Work request enqueued/replaced for tag: $workTag")
        } else {
            // Если время напоминания null или в прошлом, отменяем существующий Worker
            Log.i(
                TAG,
                "Cancelling any existing reminder for note $noteId. Tag: $workTag. ReminderTime: $reminderTimeMillis"
            )
            workManager.cancelUniqueWork(workTag) // Отменяем работу по уникальному имени (тегу)
            Log.i(TAG, "Work request cancelled for tag: $workTag")
        }
    }

    fun resetNotificationPermissionStatusRequest() {
        // Сбрасываем статус запроса, чтобы UI не запрашивал разрешение повторно без явного действия пользователя
        // Это важно, если пользователь отклонил запрос, а потом снова кликнул на иконку напоминания.
        if (_uiState.value.notificationPermissionStatus == NotificationPermissionStatus.DENIED) {
            _uiState.update { it.copy(notificationPermissionStatus = NotificationPermissionStatus.UNDETERMINED) }
        }
    }

    fun onCategorySelected(category: NoteCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    override fun onCleared() {
        super.onCleared()
        // Освобождаем ресурсы
        if (audioRecorder.isRecording()) {
            audioRecorder.stopRecording()
                ?.let { File(it).delete() } // Остановить и удалить незаконченный файл
        }
        audioPlayer.release()
        speechProcessor.release()
        audioPlayerStateJob?.cancel()
        audioPositionJob?.cancel()
        Log.d(TAG, "ViewModel cleared.")
    }
}