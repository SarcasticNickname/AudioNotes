package com.myprojects.audionotes.data.local.dao

import android.util.Log
import androidx.room.*
import com.myprojects.audionotes.data.local.entity.*
import com.myprojects.audionotes.util.createAudioPlaceholder
import kotlinx.coroutines.flow.Flow

fun String.escapeForHtmlPlaceholder(): String =
    this.replace("[", "&lsqb;")
        .replace("]", "&rsqb;")
        .replace(":", "&colon;")
        .replace("_", "&lowbar;")

fun String.unescapeFromHtmlPlaceholder(): String =
    this.replace("&lsqb;", "[")
        .replace("&rsqb;", "]")
        .replace("&colon;", ":")
        .replace("&lowbar;", "_")


fun String.cleanAudioPlaceholdersForRegex(): String =
    this.unescapeFromHtmlPlaceholder()
        .replace("\u200B", "")
        .replace("\u00A0", " ")

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun getAllNotes(): Flow<List<Note>>

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :noteId")
    fun getNoteWithContentAndAudioBlocksById(noteId: Long): Flow<NoteWithContentAndAudioBlocks?>

    @Query("SELECT * FROM notes WHERE id = :noteId")
    suspend fun getNoteById(noteId: Long): Note?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: Note): Long

    @Update
    suspend fun updateNote(note: Note)

    @Delete
    suspend fun deleteNote(note: Note)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudioBlock(audioBlock: NoteBlock): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudioBlocks(audioBlocks: List<NoteBlock>): List<Long>

    @Update
    suspend fun updateAudioBlock(audioBlock: NoteBlock)

    @Delete
    suspend fun deleteAudioBlock(audioBlock: NoteBlock)

    @Query("DELETE FROM note_blocks WHERE noteId = :noteId AND type = :type")
    suspend fun deleteAllAudioBlocksByNoteId(noteId: Long, type: BlockType = BlockType.AUDIO)

    @Query("SELECT * FROM notes WHERE category = :categoryName ORDER BY updatedAt DESC")
    fun getNotesByCategory(categoryName: String): Flow<List<Note>>

    @Query("SELECT * FROM note_blocks WHERE noteId = :noteId AND type = :type ORDER BY orderIndex ASC")
    suspend fun getAudioBlocksForNote(
        noteId: Long,
        type: BlockType = BlockType.AUDIO
    ): List<NoteBlock>

    // -------- ЕДИНАЯ точка сохранения --------
    @Transaction
    suspend fun saveNoteAndAudioBlocks(
        noteToSave: Note,
        audioBlocksToSave: List<NoteBlock>
    ): Long {
        // 0. Готовим временный HTML (ID ещё временные)
        var html = noteToSave.content

        // 1. Сохраняем саму заметку (insert / update) СРАЗУ с "сырым" HTML
        val noteId = if (noteToSave.id == 0L)
            insertNote(noteToSave.copy(content = html))
        else {
            updateNote(noteToSave.copy(content = html)); noteToSave.id
        }

        // 2. Перезаписываем аудио‑блоки
        deleteAllAudioBlocksByNoteId(noteId)
        val realIds = insertAudioBlocks(
            audioBlocksToSave.mapIndexed { index, blk ->
                blk.copy(id = 0L, noteId = noteId, orderIndex = index)
            }
        )

        // 3. Подмена временных ID на реальные прямо в строке html
        audioBlocksToSave.forEachIndexed { i, tmpBlk ->
            val oldRaw = createAudioPlaceholder(tmpBlk.id)
            val newRaw = createAudioPlaceholder(realIds[i])
            if (oldRaw != newRaw) html = html.replace(oldRaw, newRaw)
        }

        // 4. Если html изменился – обновляем только поле content
        if (html != noteToSave.content) {
            updateNote(
                noteToSave.copy(
                    id = noteId,
                    content = html,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        return noteId
    }
}