package com.myprojects.audionotes.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

interface ISpeechToTextProcessor {
    val isListening: StateFlow<Boolean>
    val recognitionResult: SharedFlow<String>
    val recognitionError: SharedFlow<String>

    fun startListening(languageCode: String = Locale.getDefault().toLanguageTag())
    fun stopListening()

    // processActivityResult не используется с RecognitionListener, поэтому его можно удалить из интерфейса,
    // но оставлю, если он где-то ожидается, хотя и с пустой реализацией.
    fun processActivityResult(resultCode: Int, data: Intent?)
    fun release()
    fun isRecognitionAvailable(): Boolean
}

@Singleton
class SpeechToTextProcessorAndroid @Inject constructor(
    @ApplicationContext private val appContext: Context
) : ISpeechToTextProcessor, RecognitionListener {

    private var recognizerBusy = false
    private var speechRecognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _recognitionResult = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    override val recognitionResult: SharedFlow<String> = _recognitionResult.asSharedFlow()

    private val _recognitionError = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    override val recognitionError: SharedFlow<String> = _recognitionError.asSharedFlow()

    private var currentLanguage: String = Locale.getDefault().toLanguageTag()

    companion object {
        private const val TAG = "SttProcessorAndroid"
    }

    // ИЗМЕНЕНИЕ: Убрана инициализация SpeechRecognizer из init.
    // Он будет создаваться "лениво" при первом вызове startListening через ensureRecognizerInitialized.
    init {
        Log.d(TAG, "SpeechToTextProcessorAndroid initialized (Singleton instance).")
    }

    // ИЗМЕНЕНИЕ: Новая вспомогательная функция
    private fun ensureRecognizerInitialized() {
        if (!isRecognitionAvailableInternal()) {
            Log.w(
                TAG,
                "ensureRecognizerInitialized: Speech recognition not available on this device."
            )
            return // Не можем инициализировать, если сервис недоступен
        }

        if (speechRecognizer == null) {
            Log.d(
                TAG,
                "ensureRecognizerInitialized: SpeechRecognizer is null, creating new instance."
            )
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            speechRecognizer?.setRecognitionListener(this)
            recognizerBusy = false // Сбрасываем флаг занятости при новой инициализации
        } else if (recognizerBusy) {
            Log.d(
                TAG,
                "ensureRecognizerInitialized: SpeechRecognizer was busy, re-creating instance."
            )
            speechRecognizer?.destroy() // Уничтожаем старый "занятый" экземпляр
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            speechRecognizer?.setRecognitionListener(this)
            recognizerBusy = false
        }
        // Если speechRecognizer не null и не busy, ничего не делаем
    }

    override fun isRecognitionAvailable(): Boolean {
        return isRecognitionAvailableInternal()
    }

    private fun isRecognitionAvailableInternal(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(appContext)
    }

    override fun startListening(languageCode: String) {
        if (!isRecognitionAvailableInternal()) {
            _recognitionError.tryEmit("Speech recognition not available")
            Log.w(TAG, "startListening: Speech recognition not available.")
            return
        }

        // ИЗМЕНЕНИЕ: Вызов новой функции для гарантии инициализации
        ensureRecognizerInitialized()

        if (speechRecognizer == null) {
            _recognitionError.tryEmit("Speech recognizer could not be initialized.")
            Log.e(
                TAG,
                "startListening: speechRecognizer is null even after ensureRecognizerInitialized."
            )
            return
        }

        if (_isListening.value) {
            Log.d(TAG, "startListening: Already listening.")
            return // уже слушаем
        }

        currentLanguage = languageCode
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
            // Можно добавить другие флаги, если нужно, например:
            // putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500) //ms
            // putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500) //ms
            // putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000) //ms
        }
        try {
            Log.d(TAG, "Attempting to call speechRecognizer.startListening() with intent: $intent")
            speechRecognizer?.startListening(intent)
            // _isListening.value будет установлено в true в onReadyForSpeech
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recognition in startListening", e)
            _recognitionError.tryEmit("Error starting recognition: ${e.message}")
            _isListening.value = false // Убедимся, что состояние сброшено
        }
    }

    override fun stopListening() {
        // ИЗМЕНЕНИЕ: Проверка на null для speechRecognizer
        if (speechRecognizer != null && _isListening.value) {
            speechRecognizer?.stopListening()
            Log.d(TAG, "stopListening called on recognizer")
        } else {
            Log.d(TAG, "stopListening called but recognizer is null or not actively listening.")
        }
        _isListening.value =
            false // Принудительно, если stopListening вызван до onEndOfSpeech или если recognizer null
    }

    // Этот метод не нужен для SpeechRecognizer, так как он использует Listener
    override fun processActivityResult(resultCode: Int, data: Intent?) {
        // No-op: Results are handled by RecognitionListener methods
        Log.d(TAG, "processActivityResult called (should not happen with RecognitionListener)")
    }

    override fun release() {
        Log.d(TAG, "SpeechToTextProcessorAndroid release called. Destroying recognizer.")
        speechRecognizer?.destroy() // Безопасно вызывать destroy, даже если он уже был вызван
        speechRecognizer = null
        _isListening.value = false // Убедимся, что состояние сброшено
        recognizerBusy = false     // Сбрасываем флаг занятости
    }

    // --- RecognitionListener Implementation ---
    override fun onReadyForSpeech(params: Bundle?) {
        Log.d(TAG, "onReadyForSpeech. Params: $params")
        _isListening.value = true
        _recognitionError.tryEmit("") // Clear previous errors
    }

    override fun onBeginningOfSpeech() {
        Log.d(TAG, "onBeginningOfSpeech")
    }

    override fun onRmsChanged(rmsdB: Float) {
        // Log.v(TAG, "onRmsChanged: $rmsdB") // Можнет быть слишком частым для логов уровня D
    }

    override fun onBufferReceived(buffer: ByteArray?) {
        Log.d(TAG, "onBufferReceived")
    }

    override fun onEndOfSpeech() {
        Log.d(TAG, "onEndOfSpeech")
        // SpeechRecognizer сам переходит в состояние "не слушаю".
        // _isListening.value будет установлено в false в onResults или onError.
    }

    override fun onError(error: Int) {
        val msg = getErrorText(error)
        Log.e(TAG, "onError: $msg (code: $error)")
        _recognitionError.tryEmit(msg)
        _isListening.value = false
        recognizerBusy = when (error) {
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_CLIENT
                -> true

            else -> false
        }
        if (recognizerBusy) {
            Log.w(
                TAG,
                "Recognizer is busy or encountered a critical error. Will attempt re-initialization on next startListening call."
            )
            // release() // Раскомментировать, если хотим агрессивное пересоздание
        }
    }

    override fun onResults(results: Bundle?) {
        Log.d(TAG, "onResults")
        _isListening.value = false // Убеждаемся, что сброшено
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val bestResult = matches[0]
            Log.d(TAG, "Recognition results: \"$bestResult\" (all: $matches)")
            _recognitionResult.tryEmit(bestResult)
        } else {
            Log.d(TAG, "No recognition results found in bundle.")
            _recognitionError.tryEmit("No speech results found.")
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            Log.d(TAG, "onPartialResults: ${matches[0]}")
            // _recognitionPartialResult.tryEmit(matches[0])
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {
        Log.d(TAG, "onEvent - type: $eventType, params: $params")
    }

    private fun getErrorText(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input / timeout"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Выбранный язык не поддерживается для распознавания на этом устройстве."
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Языковой пакет для выбранного языка не установлен или недоступен. Проверьте настройки голосового ввода Google."
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Server disconnected"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests to the server"
            else -> "Unknown speech recognition error ($errorCode)"
        }
    }
}