package com.myprojects.audionotes.util

import android.app.Activity
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

// SttState и SttResult больше не нужны в таком виде, ViewModel будет управлять этим проще
// enum class SttState { IDLE, LISTENING, ERROR }
// sealed class SttResult { ... }

interface ISpeechToTextProcessor {
    val isListening: StateFlow<Boolean>
    val recognitionResult: SharedFlow<String> // Поток для успешных результатов
    val recognitionError: SharedFlow<String>  // Поток для ошибок

    fun startListening(languageCode: String = Locale.getDefault().toLanguageTag())
    fun stopListening()
    fun processActivityResult(
        resultCode: Int,
        data: Intent?
    ) // Этот метод не нужен, т.к. SpeechRecognizer работает через Listener

    fun release()
    fun isRecognitionAvailable(): Boolean
}


@Singleton // Оставляем Singleton, если ViewModel тоже Singleton и хелпер имеет состояние
class SpeechToTextProcessorAndroid @Inject constructor(
    @ApplicationContext private val appContext: Context
) : ISpeechToTextProcessor, RecognitionListener {

    private var recognizerBusy = false

    private var speechRecognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    // Используем SharedFlow для отправки результатов, чтобы подписчик получил их один раз
    private val _recognitionResult = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    override val recognitionResult: SharedFlow<String> = _recognitionResult.asSharedFlow()

    private val _recognitionError = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    override val recognitionError: SharedFlow<String> = _recognitionError.asSharedFlow()

    private var currentLanguage: String = Locale.getDefault().toLanguageTag()

    companion object {
        private const val TAG = "SttProcessorAndroid"
    }

    init {
        // Инициализируем отложенно: если рантайм не поддерживает STT,
        // просто фиксируем ошибку, но не падаем.
        if (isRecognitionAvailableInternal()) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            speechRecognizer?.setRecognitionListener(this)
        } else {
            Log.e(TAG, "Speech recognition not available on this device.")
        }
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
            return
        }

        // если прошлый вызов упал с BUSY, пересоздаём инстанс
        if (recognizerBusy) {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also {
                it.setRecognitionListener(this)
            }
            recognizerBusy = false
        }

        if (_isListening.value) return // уже слушаем

        currentLanguage = languageCode
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            _recognitionError.tryEmit("Error starting recognition: ${e.message}")
        }
    }

    override fun stopListening() {
        if (_isListening.value) {
            speechRecognizer?.stopListening()
            Log.d(TAG, "stopListening called")
        }
        _isListening.value = false // Принудительно, если stopListening вызван до onEndOfSpeech
    }

    // Этот метод не нужен для SpeechRecognizer, так как он использует Listener
    override fun processActivityResult(resultCode: Int, data: Intent?) {
        // No-op: Results are handled by RecognitionListener methods
    }

    override fun release() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        _isListening.value = false
        Log.d(TAG, "SpeechToTextProcessorAndroid released")
    }

    // --- RecognitionListener Implementation ---
    override fun onReadyForSpeech(params: Bundle?) {
        Log.d(TAG, "onReadyForSpeech")
        _isListening.value = true
        _recognitionError.tryEmit("") // Clear previous errors
    }

    override fun onBeginningOfSpeech() {
        Log.d(TAG, "onBeginningOfSpeech")
    }

    override fun onRmsChanged(rmsdB: Float) { /* Optional: handle RMS dB changes */
    }

    override fun onBufferReceived(buffer: ByteArray?) { /* Optional: handle audio buffer */
    }

    override fun onEndOfSpeech() {
        Log.d(TAG, "onEndOfSpeech")
        _isListening.value = false
    }

    override fun onError(error: Int) {
        val msg = getErrorText(error)
        _recognitionError.tryEmit(msg)
        _isListening.value = false
        recognizerBusy = (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
    }

    override fun onResults(results: Bundle?) {
        Log.d(TAG, "onResults")
        _isListening.value = false // Убеждаемся, что сброшено
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            Log.d(TAG, "Recognition results: ${matches[0]}")
            _recognitionResult.tryEmit(matches[0])
        } else {
            Log.d(TAG, "No recognition results.")
            _recognitionError.tryEmit("No speech results") // Или другая обработка
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        Log.d(TAG, "onPartialResults")
        // Можно обрабатывать промежуточные результаты, если нужно
        // val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        // if (!matches.isNullOrEmpty()) { _recognitionPartialResult.tryEmit(matches[0]) }
    }

    override fun onEvent(eventType: Int, params: Bundle?) { /* Optional: handle specific events */
    }

    private fun getErrorText(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error (STT might require online connection for some languages/devices)"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input / timeout"
            else -> "Unknown speech recognition error"
        }
    }
}