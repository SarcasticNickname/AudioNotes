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
    val currentPlayingBlockId: StateFlow<Long?>
    val currentPositionMs: StateFlow<Int>
    val totalDurationMs: StateFlow<Int>

    fun play(filePath: String, blockId: Long)
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Int)
    fun release()
}

@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val appContext: Context // appContext здесь не используется, но оставлен, если понадобится
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
    private val playerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "AudioPlayer"
        private const val POSITION_UPDATE_INTERVAL_MS =
            50L // Можно сделать интервал поменьше для более плавного обновления
    }

    override fun play(filePath: String, blockId: Long) {
        if (_playerState.value == PlayerState.PREPARING) {
            Log.w(
                TAG,
                "Player is already preparing for block ${_currentPlayingBlockId.value}. Play request for $blockId ignored."
            )
            return
        }
        if (_playerState.value == PlayerState.PLAYING && _currentPlayingBlockId.value == blockId) {
            Log.d(TAG, "Already playing block $blockId. Ignoring.")
            return
        }

        stop() // Останавливаем и освобождаем ресурсы предыдущего воспроизведения

        _currentPlayingBlockId.value = blockId
        _playerState.value =
            PlayerState.PREPARING // Устанавливаем состояние ПЕРЕД созданием MediaPlayer
        Log.d(TAG, "play: Set state to PREPARING for block $blockId")

        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(filePath)
                setOnPreparedListener { mp ->
                    Log.i(
                        TAG,
                        "MediaPlayer prepared for block $blockId. Duration: ${mp.duration} ms"
                    )
                    _totalDurationMs.value = mp.duration
                    _currentPositionMs.value = 0
                    _playerState.value = PlayerState.PLAYING
                    Log.d(TAG, "onPrepared: Set state to PLAYING for block $blockId")
                    mp.start()
                    startPositionUpdates()
                }
                setOnCompletionListener {
                    Log.i(TAG, "MediaPlayer completion for block $blockId")
                    _playerState.value = PlayerState.COMPLETED
                    _currentPositionMs.value =
                        _totalDurationMs.value.coerceAtLeast(0) // Устанавливаем позицию в конец
                    // _currentPlayingBlockId.value = null // Не сбрасываем здесь, чтобы UI мог показать "завершено" для этого блока
                    stopPositionUpdates()
                    Log.d(TAG, "onCompletion: Set state to COMPLETED for block $blockId")
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error for block $blockId. What: $what, Extra: $extra")
                    _playerState.value = PlayerState.ERROR
                    // МОЙ КОММЕНТАРИЙ: При ошибке нужно сбрасывать все значения, относящиеся к текущему проигрыванию
                    _currentPlayingBlockId.value = null
                    _totalDurationMs.value = 0
                    _currentPositionMs.value = 0
                    stopPositionUpdates()
                    releaseMediaPlayerInternal() // Освободить ресурсы при ошибке
                    true
                }
                Log.d(TAG, "MediaPlayer preparing async for $filePath, block $blockId")
                prepareAsync()
            } catch (e: IOException) {
                Log.e(TAG, "IOException during MediaPlayer setup for $filePath", e)
                handleErrorState()
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "IllegalArgumentException during MediaPlayer setup for $filePath", e)
                handleErrorState()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "IllegalStateException during MediaPlayer setup for $filePath", e)
                handleErrorState()
            }
        }
    }

    private fun handleErrorState() {
        _playerState.value = PlayerState.ERROR
        _currentPlayingBlockId.value = null
        _totalDurationMs.value = 0
        _currentPositionMs.value = 0
        stopPositionUpdates()
        releaseMediaPlayerInternal()
    }


    override fun pause() {
        if (_playerState.value == PlayerState.PLAYING && mediaPlayer?.isPlaying == true) {
            try {
                mediaPlayer?.pause()
                _playerState.value = PlayerState.PAUSED
                stopPositionUpdates() // Останавливаем обновления, так как плеер на паузе
                Log.d(TAG, "MediaPlayer paused for block ${_currentPlayingBlockId.value}")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Error pausing MediaPlayer", e)
                handleErrorState()
            }
        }
    }

    override fun resume() {
        if (_playerState.value == PlayerState.PAUSED) {
            try {
                mediaPlayer?.start()
                _playerState.value = PlayerState.PLAYING
                startPositionUpdates() // Возобновляем обновления
                Log.d(TAG, "MediaPlayer resumed for block ${_currentPlayingBlockId.value}")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Error resuming MediaPlayer", e)
                handleErrorState()
            }
        }
    }

    override fun stop() {
        Log.d(TAG, "MediaPlayer stop called for block ${_currentPlayingBlockId.value}")
        // МОЙ КОММЕНТАРИЙ: Сначала останавливаем обновления, потом освобождаем плеер
        stopPositionUpdates()
        releaseMediaPlayerInternal() // Полное освобождение и сброс состояния
    }

    override fun seekTo(positionMs: Int) {
        // МОЙ КОММЕНТАРИЙ: Проверяем, что mediaPlayer существует и готов к перемотке
        if (mediaPlayer != null && (_playerState.value == PlayerState.PLAYING || _playerState.value == PlayerState.PAUSED || _playerState.value == PlayerState.COMPLETED)) {
            try {
                val duration = _totalDurationMs.value
                val newPosition =
                    positionMs.coerceIn(0, duration) // Убедимся, что не выходим за пределы
                mediaPlayer?.seekTo(newPosition)
                _currentPositionMs.value = newPosition // Обновляем для немедленного отклика UI
                Log.d(
                    TAG,
                    "MediaPlayer seekTo: $newPosition ms for block ${_currentPlayingBlockId.value}"
                )
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Error seeking MediaPlayer", e)
                handleErrorState()
            }
        }
    }

    override fun release() {
        Log.d(TAG, "MediaPlayer release called by external component (ViewModel.onCleared)")
        playerScope.cancel() // Отменяем все корутины этого скоупа
        stop() // Вызовет releaseMediaPlayerInternal
    }

    // МОЙ КОММЕНТАРИЙ: Переименовал в releaseMediaPlayerInternal, чтобы не путать с публичным release()
    private fun releaseMediaPlayerInternal() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.stop()
                }
                it.reset() // Сбрасывает MediaPlayer в его неинициализированное состояние.
                it.release() // Освобождает ресурсы, связанные с этим объектом MediaPlayer.
            } catch (e: IllegalStateException) {
                Log.e(TAG, "IllegalStateException during mediaPlayer release", e)
                // Если reset/release вызван в неверном состоянии, он может бросить исключение
            } catch (e: Exception) {
                Log.e(TAG, "Exception during mediaPlayer release", e)
            }
        }
        mediaPlayer = null
        // МОЙ КОММЕНТАРИЙ: Устанавливаем IDLE только если не было явной ошибки,
        // которая уже установила PlayerState.ERROR
        if (_playerState.value != PlayerState.ERROR) {
            _playerState.value = PlayerState.IDLE
        }
        // Сбрасываем значения, если плеер остановлен или произошла ошибка,
        // но только если это не состояние COMPLETED, где мы хотим сохранить длительность.
        if (_playerState.value != PlayerState.COMPLETED) {
            _currentPlayingBlockId.value = null
            _currentPositionMs.value = 0
            _totalDurationMs.value = 0
        }
        // stopPositionUpdates() вызывается перед releaseMediaPlayerInternal, поэтому здесь не нужен
        Log.d(TAG, "MediaPlayer resources released, state set to ${_playerState.value}")
    }

    private fun startPositionUpdates() {
        Log.d(TAG, "startPositionUpdates called for block ${_currentPlayingBlockId.value}")
        stopPositionUpdates() // Гарантируем, что предыдущий job остановлен
        positionUpdateJob = playerScope.launch {
            // МОЙ КОММЕНТАРИЙ: Цикл должен продолжаться, пока состояние плеера PLAYING
            while (isActive && _playerState.value == PlayerState.PLAYING) {
                try {
                    val currentPos = mediaPlayer?.currentPosition ?: 0
                    val totalDur =
                        mediaPlayer?.duration ?: 0 // На всякий случай, если длительность изменилась

                    if (currentPos != _currentPositionMs.value) {
                        _currentPositionMs.value = currentPos
                    }
                    if (totalDur != 0 && totalDur != _totalDurationMs.value) {
                        _totalDurationMs.value = totalDur
                    }

                    // Проверка, не завершилось ли воспроизведение (на случай, если onCompletion не сработал вовремя)
                    if (mediaPlayer?.isPlaying == false && currentPos >= totalDur && totalDur > 0) {
                        Log.w(
                            TAG,
                            "Position update loop detected completion for block ${_currentPlayingBlockId.value}"
                        )
                        _playerState.value = PlayerState.COMPLETED
                        _currentPositionMs.value = totalDur
                        break // Выходим из цикла
                    }
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "IllegalStateException in position update loop", e)
                    handleErrorState() // Обработка ошибки и выход из цикла
                    break
                }
                delay(POSITION_UPDATE_INTERVAL_MS)
            }
            // Если цикл завершился не из-за ошибки и не из-за COMPLETED, но плеер больше не играет
            // (например, после паузы или остановки), обновляем позицию последний раз.
            if (isActive && _playerState.value != PlayerState.COMPLETED && _playerState.value != PlayerState.ERROR) {
                mediaPlayer?.currentPosition?.let { _currentPositionMs.value = it }
            }
            Log.d(
                TAG,
                "Position update job ended for block ${_currentPlayingBlockId.value}. Current state: ${_playerState.value}"
            )
        }
    }

    private fun stopPositionUpdates() {
        if (positionUpdateJob?.isActive == true) {
            positionUpdateJob?.cancel()
            Log.d(TAG, "Position update job cancelled for block ${_currentPlayingBlockId.value}")
        }
        positionUpdateJob = null
    }
}