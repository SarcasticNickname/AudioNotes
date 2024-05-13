// NoteRepository.kt (интерфейс)
package com.myprojects.audionotes.data.repository

import com.myprojects.audionotes.data.local.entity.Note
import com.myprojects.audionotes.data.local.entity.NoteBlock // Используется для аудио
import com.myprojects.audionotes.data.local.entity.NoteWithContentAndAudioBlocks
import kotlinx.coroutines.flow.Flow

interface NoteRepository {
    fun getAllNotes(): Flow<List<Note>>
    fun getNoteWithContentAndAudioBlocks(noteId: Long): Flow<NoteWithContentAndAudioBlocks?>
    suspend fun saveNote(
        note: Note,
        audioBlocks: List<NoteBlock>
    ): Long // Сохраняет заметку и ее аудио блоки

    suspend fun createNewNote(): Long // Создает заметку с пустым контентом
    suspend fun deleteNoteById(noteId: Long)
    suspend fun getNoteById(noteId: Long): Note?
    suspend fun getAudioBlocksForNote(noteId: Long): List<NoteBlock>
}