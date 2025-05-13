package com.myprojects.audionotes.data.local.dao

import androidx.room.*
import com.myprojects.audionotes.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun getAllNotes(): Flow<List<Note>> // Note теперь содержит content

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :noteId")
    // Возвращаем NoteWithContentAndAudioBlocks, который будет содержать note.content и список audioBlocks
    fun getNoteWithContentAndAudioBlocksById(noteId: Long): Flow<NoteWithContentAndAudioBlocks?>

    @Query("SELECT * FROM notes WHERE id = :noteId")
    suspend fun getNoteById(noteId: Long): Note?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: Note): Long // Note теперь содержит content

    @Update
    suspend fun updateNote(note: Note) // Note теперь содержит content

    @Delete
    suspend fun deleteNote(note: Note) // Удалит заметку и каскадно все связанные NoteBlock (аудио)

    // --- Методы для аудио блоков (NoteBlock с type = AUDIO) ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudioBlock(audioBlock: NoteBlock): Long // Возвращаем ID блока

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudioBlocks(audioBlocks: List<NoteBlock>): List<Long>

    @Update
    suspend fun updateAudioBlock(audioBlock: NoteBlock)

    @Delete
    suspend fun deleteAudioBlock(audioBlock: NoteBlock)

    @Query("DELETE FROM note_blocks WHERE noteId = :noteId AND type = :type") // Удаляем все аудио блоки для заметки
    suspend fun deleteAllAudioBlocksByNoteId(noteId: Long, type: BlockType = BlockType.AUDIO)

    @Query("SELECT * FROM note_blocks WHERE noteId = :noteId AND type = :type ORDER BY orderIndex ASC")
    suspend fun getAudioBlocksForNote(noteId: Long, type: BlockType = BlockType.AUDIO): List<NoteBlock>

    // Новый транзакционный метод сохранения
    @Transaction
    suspend fun saveNoteAndAudioBlocks(noteToSave: Note, audioBlocksToSave: List<NoteBlock>): Long {
        val finalNote = noteToSave.copy(updatedAt = System.currentTimeMillis())

        val noteId: Long
        if (finalNote.id == 0L) { // Новая заметка
            noteId = insertNote(finalNote)
        } else { // Существующая заметка
            updateNote(finalNote)
            noteId = finalNote.id
        }

        // Удаляем все СТАРЫЕ аудио блоки для этой заметки
        deleteAllAudioBlocksByNoteId(noteId)

        // Вставляем НОВЫЕ аудио блоки (если они есть)
        // Убедимся, что у них правильный noteId и тип AUDIO
        if (audioBlocksToSave.isNotEmpty()) {
            val processedAudioBlocks = audioBlocksToSave.mapIndexed { index, block ->
                block.copy(
                    noteId = noteId,
                    type = BlockType.AUDIO, // Гарантируем тип
                    orderIndex = index // Порядок для отображения в списке плееров
                )
            }
            insertAudioBlocks(processedAudioBlocks)
        }
        return noteId
    }
}