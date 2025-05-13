package com.myprojects.audionotes.data.repository

import androidx.sqlite.db.SupportSQLiteQuery
import com.myprojects.audionotes.data.local.entity.Note
import com.myprojects.audionotes.data.local.entity.NoteBlock
import com.myprojects.audionotes.data.local.entity.NoteWithContentAndAudioBlocks
import kotlinx.coroutines.flow.Flow

interface NoteRepository {
    // Получение НЕархивированных заметок с гибким запросом
    fun getNotes(query: SupportSQLiteQuery): Flow<List<Note>>

    // Получение АРХИВИРОВАННЫХ заметок с гибким запросом
    fun getArchivedNotes(query: SupportSQLiteQuery): Flow<List<Note>>

    // Установка статуса архивации для заметки
    suspend fun setArchivedStatus(noteId: Long, isArchived: Boolean)

    // Получение всех заметок (не Flow) для процесса бэкапа
    suspend fun getAllNotesForBackup(): List<Note>

    // Замена всех локальных заметок данными из бэкапа (при восстановлении)
    suspend fun replaceAllNotesFromBackup(notes: List<Note>)

    // Остальные методы без изменений
    fun getNoteWithContentAndAudioBlocks(noteId: Long): Flow<NoteWithContentAndAudioBlocks?>
    suspend fun saveNote(note: Note, audioBlocks: List<NoteBlock>): Long
    suspend fun createNewNote(): Long
    suspend fun deleteNoteById(noteId: Long)
    suspend fun getNoteById(noteId: Long): Note?
    suspend fun getAudioBlocksForNote(noteId: Long): List<NoteBlock>
}