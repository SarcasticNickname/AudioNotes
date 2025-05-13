package com.myprojects.audionotes.di

import android.content.Context
import androidx.room.Room
import com.myprojects.audionotes.data.local.dao.NoteDao
import com.myprojects.audionotes.data.local.database.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class) // Зависимости будут жить пока живо приложение
object DatabaseModule {

    @Provides
    @Singleton // Создаем единственный экземпляр базы данных
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration() // Временная мера для разработки
            .build()
        // Или используем Singleton паттерн из AppDatabase:
        // return AppDatabase.getInstance(context)
    }

    @Provides
    @Singleton // DAO тоже будет синглтоном, так как зависит от синглтона AppDatabase
    fun provideNoteDao(appDatabase: AppDatabase): NoteDao {
        return appDatabase.noteDao()
    }
}