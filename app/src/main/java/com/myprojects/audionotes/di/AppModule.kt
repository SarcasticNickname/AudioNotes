package com.myprojects.audionotes.di

import com.myprojects.audionotes.data.repository.NoteRepository
import com.myprojects.audionotes.data.repository.NoteRepositoryImpl
import com.myprojects.audionotes.util.AudioPlayer
import com.myprojects.audionotes.util.AudioRecorder
import com.myprojects.audionotes.util.IAudioPlayer
import com.myprojects.audionotes.util.IAudioRecorder
import com.myprojects.audionotes.util.ISpeechToTextProcessor
import com.myprojects.audionotes.util.SpeechToTextProcessorAndroid
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindNoteRepository(
        noteRepositoryImpl: NoteRepositoryImpl
    ): NoteRepository

    @Binds
    @Singleton
    abstract fun bindAudioRecorder(audioRecorder: AudioRecorder): IAudioRecorder

    @Binds
    @Singleton
    abstract fun bindAudioPlayer(audioPlayer: AudioPlayer): IAudioPlayer

    @Binds
    @Singleton
    abstract fun bindSpeechToTextProcessor(
        speechToTextProcessorAndroid: SpeechToTextProcessorAndroid
    ): ISpeechToTextProcessor
}