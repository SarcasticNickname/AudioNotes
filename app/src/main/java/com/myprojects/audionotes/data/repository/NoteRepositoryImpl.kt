package com.myprojects.audionotes.data.repository

import android.content.Context
import android.util.Log
import com.myprojects.audionotes.data.local.dao.NoteDao
import com.myprojects.audionotes.data.local.entity.BlockType
import com.myprojects.audionotes.data.local.entity.Note
import com.myprojects.audionotes.data.local.entity.NoteBlock
import com.myprojects.audionotes.data.local.entity.NoteWithContentAndAudioBlocks
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoteRepositoryImpl @Inject constructor(
    private val noteDao: NoteDao,
    @ApplicationContext private val appContext: Context
) : NoteRepository {

    // ... (существующие методы ...getAllNotes, getNoteWithContentAndAudioBlocks, saveNote, createNewNote, deleteNoteById, getNoteById)

    override fun getAllNotes(): Flow<List<Note>> = noteDao.getAllNotes()

    override fun getNoteWithContentAndAudioBlocks(noteId: Long): Flow<NoteWithContentAndAudioBlocks?> =
        noteDao.getNoteWithContentAndAudioBlocksById(noteId)

    override suspend fun saveNote(note: Note, audioBlocks: List<NoteBlock>): Long {
        return withContext(Dispatchers.IO) {
            try {
                // DAO теперь сам заботится о синхронизации ID в note.content
                noteDao.saveNoteAndAudioBlocks(note, audioBlocks)
            } catch (e: Exception) {
                Log.e("NoteRepository", "Error saving note and audio blocks", e)
                if (note.id != 0L) note.id else -1L // Возвращаем ID заметки или -1 при ошибке
            }
        }
    }

    override suspend fun createNewNote(): Long {
        return withContext(Dispatchers.IO) {
            val newNote = Note(title = "New Note", content = "")
            try {
                noteDao.saveNoteAndAudioBlocks(newNote, emptyList())
            } catch (e: Exception) {
                Log.e("NoteRepository", "Error creating new note", e)
                -1L
            }
        }
    }

    override suspend fun deleteNoteById(noteId: Long) {
        withContext(Dispatchers.IO) {
            try {
                val audioBlocksToDelete = noteDao.getAudioBlocksForNote(noteId)
                audioBlocksToDelete.forEach { block ->
                    if (!block.audioFilePath.isNullOrEmpty()) {
                        try {
                            File(block.audioFilePath).delete()
                            Log.i("NoteRepository", "Deleted audio file: ${block.audioFilePath}")
                        } catch (e: Exception) {
                            Log.e(
                                "NoteRepository",
                                "Error deleting audio file: ${block.audioFilePath}",
                                e
                            )
                        }
                    }
                }
                val noteToDelete = noteDao.getNoteById(noteId)
                if (noteToDelete != null) {
                    noteDao.deleteNote(noteToDelete)
                } else {
                }
            } catch (e: Exception) {
                Log.e("NoteRepository", "Error deleting note by id: $noteId", e)
            }
        }
    }

    override suspend fun getNoteById(noteId: Long): Note? {
        return withContext(Dispatchers.IO) {
            noteDao.getNoteById(noteId)
        }
    }

    override suspend fun getAudioBlocksForNote(noteId: Long): List<NoteBlock> {
        return withContext(Dispatchers.IO) {
            noteDao.getAudioBlocksForNote(noteId, BlockType.AUDIO)
        }
    }
}