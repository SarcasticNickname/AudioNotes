package com.myprojects.audionotes.di

import com.myprojects.audionotes.data.repository.NoteRepository
import com.myprojects.audionotes.data.repository.NoteRepositoryImpl
import com.myprojects.audionotes.util.AudioPlayer
import com.myprojects.audionotes.util.AudioRecorder
import com.myprojects.audionotes.util.IAudioPlayer
import com.myprojects.audionotes.util.IAudioRecorder
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class) // Зависимости будут жить пока живо приложение
abstract class AppModule {

    // Hilt будет знать, что когда требуется NoteRepository, нужно предоставлять NoteRepositoryImpl
    @Binds
    @Singleton
    abstract fun bindNoteRepository(
        noteRepositoryImpl: NoteRepositoryImpl
    ): NoteRepository


    // Связываем интерфейсы с реализациями для рекордера и плеера
    // Они могут быть Singleton, если нам нужен один экземпляр на все приложение,
    // или @ViewModelScoped, если каждый ViewModel должен иметь свой (что менее вероятно для медиа).
    // Для простоты пока сделаем Singleton, но это можно изменить.
    @Binds
    @Singleton
    abstract fun bindAudioRecorder(audioRecorder: AudioRecorder): IAudioRecorder

    @Binds
    @Singleton
    abstract fun bindAudioPlayer(audioPlayer: AudioPlayer): IAudioPlayer
}