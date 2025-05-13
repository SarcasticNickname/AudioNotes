package com.myprojects.audionotes.data.repository

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteQuery
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

    override fun getNotes(query: SupportSQLiteQuery): Flow<List<Note>> {
        return noteDao.getNotes(query)
    }

    override fun getArchivedNotes(query: SupportSQLiteQuery): Flow<List<Note>> {
        return noteDao.getArchivedNotes(query)
    }

    override suspend fun setArchivedStatus(noteId: Long, isArchived: Boolean) {
        withContext(Dispatchers.IO) {
            // Передаем текущее время для updatedAt
            noteDao.setArchivedStatus(noteId, isArchived, System.currentTimeMillis())
        }
    }

    override suspend fun getAllNotesForBackup(): List<Note> {
        return withContext(Dispatchers.IO) {
            noteDao.getAllNotesForBackup()
        }
    }

    override suspend fun replaceAllNotesFromBackup(notes: List<Note>) {
        withContext(Dispatchers.IO) {
            // Эта операция заменит существующие заметки с такими же ID
            // или вставит новые, если ID не совпадают.
            // Локальные заметки, которых нет в бэкапе, останутся нетронутыми, если не удалить их предварительно.
            // Для полного "восстановления как было в бэкапе", возможно, потребуется сначала удалить все локальные заметки.
            // Но текущая реализация просто добавляет/обновляет.
            noteDao.insertOrReplaceNotes(notes)
            Log.i(
                "NoteRepository",
                "${notes.size} notes processed from backup (inserted/replaced)."
            )
        }
    }

    override fun getNoteWithContentAndAudioBlocks(noteId: Long): Flow<NoteWithContentAndAudioBlocks?> =
        noteDao.getNoteWithContentAndAudioBlocksById(noteId)

    override suspend fun saveNote(note: Note, audioBlocks: List<NoteBlock>): Long {
        return withContext(Dispatchers.IO) {
            try {
                noteDao.saveNoteAndAudioBlocks(note, audioBlocks)
            } catch (e: Exception) {
                Log.e("NoteRepository", "Error saving note and audio blocks", e)
                if (note.id != 0L) note.id else -1L
            }
        }
    }

    override suspend fun createNewNote(): Long {
        return withContext(Dispatchers.IO) {
            val newNote = Note(
                title = "New Note",
                content = "",
                isArchived = false
            ) // Явно указываем isArchived = false
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