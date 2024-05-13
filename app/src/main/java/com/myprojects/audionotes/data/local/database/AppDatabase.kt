package com.myprojects.audionotes.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.myprojects.audionotes.data.local.dao.NoteDao
import com.myprojects.audionotes.data.local.entity.Note
import com.myprojects.audionotes.data.local.entity.NoteBlock
import com.myprojects.audionotes.util.Converters // Импортируем наш конвертер

@Database(
    entities = [Note::class, NoteBlock::class],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class) // Регистрируем конвертер
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        const val DATABASE_NAME = "audio_notes_db"

        // Паттерн Singleton для базы данных (не строго нужен с Hilt, но полезен)
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}