package com.myprojects.audionotes.util // или com.myprojects.audionotes.service

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject


interface IAudioRecorder {
    fun startRecording(outputFile: File): Boolean
    fun stopRecording(): String?
    fun getRecordingFilePath(): String?
    fun isRecording(): Boolean
    fun createAudioFile(): File?
}

class AudioRecorder @Inject constructor(
    @ApplicationContext private val appContext: Context // Используй @ApplicationContext от Hilt, если внедряешь через Hilt
) : IAudioRecorder {

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var _isRecording = false

    companion object {
        private const val AUDIO_FILE_PREFIX = "AudioNote_"
        private const val AUDIO_FILE_SUFFIX = ".mp3" // или .3gp, .aac, .m4a в зависимости от кодека
    }

    override fun startRecording(outputFile: File): Boolean {
        if (_isRecording) {
            // Уже идет запись
            return false
        }

        currentOutputFile = outputFile
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4) // Или THREE_GPP, AAC_ADTS
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC) // Или AMR_NB, AMR_WB
                setAudioSamplingRate(44100) // Стандартная частота дискретизации
                setAudioEncodingBitRate(96000) // Битрейт
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            _isRecording = true
            true
        } catch (e: IOException) {
            // Логирование ошибки
            e.printStackTrace()
            releaseRecorder() // Освободить ресурсы при ошибке
            _isRecording = false
            false
        } catch (e: IllegalStateException) {
            // Логирование ошибки
            e.printStackTrace()
            releaseRecorder()
            _isRecording = false
            false
        }
    }

    override fun stopRecording(): String? {
        if (!_isRecording) {
            return null // Запись не шла
        }
        try {
            mediaRecorder?.stop()
        } catch (e: RuntimeException) { // MediaRecorder может бросить RuntimeException при stop, если start не был удачным
            e.printStackTrace()
            releaseRecorder()
            _isRecording = false
            // Возможно, стоит удалить currentOutputFile, если он был создан, но запись не удалась
            currentOutputFile?.delete()
            return null
        } finally {
            releaseRecorder()
            _isRecording = false
        }
        return currentOutputFile?.absolutePath
    }

    override fun getRecordingFilePath(): String? {
        return currentOutputFile?.absolutePath
    }

    override fun isRecording(): Boolean {
        return _isRecording
    }


    private fun releaseRecorder() {
        mediaRecorder?.reset()
        mediaRecorder?.release()
        mediaRecorder = null
        // currentOutputFile = null // Не сбрасываем, чтобы можно было получить путь после остановки
    }

    override fun createAudioFile(): File? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val storageDir: File = appContext.filesDir // Используем внутреннее хранилище
            if (!storageDir.exists()) {
                storageDir.mkdirs()
            }
            File(storageDir, "$AUDIO_FILE_PREFIX${timeStamp}$AUDIO_FILE_SUFFIX")
        } catch (e: Exception) {
            // Логирование ошибки создания файла
            e.printStackTrace()
            null
        }
    }
}