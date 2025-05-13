// Файл: com/myprojects/audionotes/di/AppModule.kt
package com.myprojects.audionotes.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
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
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule { // Оставляем abstract, т.к. здесь только @Binds

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

@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore = Firebase.firestore
}