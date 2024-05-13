package com.myprojects.audionotes.util // или com.myprojects.audionotes.service

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton


// Состояние плеера
enum class PlayerState {
    IDLE, PREPARING, PLAYING, PAUSED, COMPLETED, ERROR
}

// Интерфейс для AudioPlayer
interface IAudioPlayer {
    val playerState: StateFlow<PlayerState>
    val currentPlayingBlockId: StateFlow<Long?> // Чтобы UI знал, какой блок сейчас активен
    val currentPositionMs: StateFlow<Int>
    val totalDurationMs: StateFlow<Int>

    fun play(filePath: String, blockId: Long)
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Int)
    fun release()
}

@Singleton // Плеер может быть Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val appContext: Context
) : IAudioPlayer {

    private var mediaPlayer: MediaPlayer? = null
    private val _playerState = MutableStateFlow(PlayerState.IDLE)
    override val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _currentPlayingBlockId = MutableStateFlow<Long?>(null)
    override val currentPlayingBlockId: StateFlow<Long?> = _currentPlayingBlockId.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0)
    override val currentPositionMs: StateFlow<Int> = _currentPositionMs.asStateFlow()

    private val _totalDurationMs = MutableStateFlow(0)
    override val totalDurationMs: StateFlow<Int> = _totalDurationMs.asStateFlow()

    private var positionUpdateJob: Job? = null
    private val playerScope =
        CoroutineScope(Dispatchers.Main + SupervisorJob()) // Scope для корутин плеера

    companion object {
        private const val TAG = "AudioPlayer"
        private const val POSITION_UPDATE_INTERVAL_MS = 500L
    }

    override fun play(filePath: String, blockId: Long) {
        if (_playerState.value == PlayerState.PLAYING && _currentPlayingBlockId.value == blockId) {
            Log.d(TAG, "Already playing block $blockId")
            return
        }
        stop() // Останавливаем любое предыдущее воспроизведение

        _currentPlayingBlockId.value = blockId
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(filePath)
                setOnPreparedListener { mp ->
                    Log.i(
                        TAG,
                        "MediaPlayer prepared for block $blockId. Duration: ${mp.duration} ms"
                    )
                    _playerState.value = PlayerState.PLAYING
                    _totalDurationMs.value = mp.duration
                    _currentPositionMs.value = 0 // Сбрасываем позицию перед стартом
                    mp.start()
                    startPositionUpdates()
                }
                setOnCompletionListener {
                    Log.i(TAG, "MediaPlayer completion for block $blockId")
                    _playerState.value = PlayerState.COMPLETED
                    // _currentPlayingBlockId.value = null // Не сбрасываем здесь, чтобы UI мог показать "завершено" для этого блока
                    _currentPositionMs.value =
                        _totalDurationMs.value // Устанавливаем позицию в конец
                    stopPositionUpdates()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error for block $blockId. What: $what, Extra: $extra")
                    _playerState.value = PlayerState.ERROR
                    _currentPlayingBlockId.value = null
                    _totalDurationMs.value = 0
                    _currentPositionMs.value = 0
                    stopPositionUpdates()
                    releaseMediaPlayer() // Освободить ресурсы при ошибке
                    true
                }
                _playerState.value = PlayerState.PREPARING
                Log.d(TAG, "MediaPlayer preparing for $filePath")
                prepareAsync()
            } catch (e: IOException) {
                Log.e(TAG, "IOException during MediaPlayer setup for $filePath", e)
                _playerState.value = PlayerState.ERROR
                _currentPlayingBlockId.value = null
                releaseMediaPlayer()
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "IllegalArgumentException during MediaPlayer setup for $filePath", e)
                _playerState.value = PlayerState.ERROR
                _currentPlayingBlockId.value = null
                releaseMediaPlayer()
            }
        }
    }

    override fun pause() {
        if (_playerState.value == PlayerState.PLAYING) {
            mediaPlayer?.pause()
            _playerState.value = PlayerState.PAUSED
            stopPositionUpdates()
            Log.d(TAG, "MediaPlayer paused")
        }
    }

    override fun resume() {
        if (_playerState.value == PlayerState.PAUSED) {
            mediaPlayer?.start()
            _playerState.value = PlayerState.PLAYING
            startPositionUpdates()
            Log.d(TAG, "MediaPlayer resumed")
        }
    }

    override fun stop() {
        Log.d(TAG, "MediaPlayer stop called")
        stopPositionUpdates()
        releaseMediaPlayer() // Полное освобождение и сброс состояния
    }

    override fun seekTo(positionMs: Int) {
        if (_playerState.value == PlayerState.PLAYING || _playerState.value == PlayerState.PAUSED || _playerState.value == PlayerState.COMPLETED) {
            mediaPlayer?.seekTo(positionMs)
            _currentPositionMs.value = positionMs // Обновляем для немедленного отклика UI
            Log.d(TAG, "MediaPlayer seekTo: $positionMs ms")
        }
    }

    override fun release() { // Публичный метод для полного освобождения ресурсов извне
        Log.d(TAG, "MediaPlayer release called by external component")
        playerScope.cancel() // Отменяем все корутины этого скоупа
        stop()
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.stop() // Нужно вызвать stop() перед release(), если он был в состоянии prepared/started/paused
        mediaPlayer?.reset()
        mediaPlayer?.release()
        mediaPlayer = null
        _playerState.value = PlayerState.IDLE
        _currentPlayingBlockId.value = null // Сбрасываем, так как плеер остановлен
        _currentPositionMs.value = 0
        _totalDurationMs.value = 0
        stopPositionUpdates()
        Log.d(TAG, "MediaPlayer resources released, state set to IDLE")
    }

    private fun startPositionUpdates() {
        Log.d(TAG, "startPositionUpdates called")
        stopPositionUpdates()
        positionUpdateJob = playerScope.launch {
            while (isActive && (mediaPlayer?.isPlaying == true)) {
                _currentPositionMs.value = mediaPlayer?.currentPosition ?: 0
                delay(POSITION_UPDATE_INTERVAL_MS)
            }
            _currentPositionMs.value = mediaPlayer?.currentPosition ?: 0
            Log.d(TAG, "stopPositionUpdates: job ended")
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }
}