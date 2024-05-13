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
        .replace("\u00A0", "")

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

    @Query("SELECT * FROM note_blocks WHERE noteId = :noteId AND type = :type ORDER BY orderIndex ASC")
    suspend fun getAudioBlocksForNote(
        noteId: Long,
        type: BlockType = BlockType.AUDIO
    ): List<NoteBlock>

    @Transaction
    suspend fun saveNoteAndAudioBlocks(
        noteToSave: Note,
        audioBlocksToSave: List<NoteBlock>
    ): Long {

        val noteId = if (noteToSave.id == 0L) insertNote(noteToSave)
        else {
            updateNote(noteToSave); noteToSave.id
        }

        // 1. Чистим старые блоки и вставляем новые
        deleteAllAudioBlocksByNoteId(noteId)
        val realIds = insertAudioBlocks(
            audioBlocksToSave.mapIndexed { i, blk ->
                blk.copy(id = 0L, noteId = noteId, orderIndex = i)
            }
        )

        // 2. Готовим карту temp → real
        val idMap = audioBlocksToSave.mapIndexed { i, tmpBlk ->
            tmpBlk.id to realIds[i]
        }.toMap()

        // 3. Заменяем _escaped_ плейсхолдеры
        var html = noteToSave.content
        idMap.forEach { (tmpId, realId) ->
            val oldEsc = createAudioPlaceholder(tmpId).escapeForHtmlPlaceholder()
            val newEsc = createAudioPlaceholder(realId).escapeForHtmlPlaceholder()
            html = html.replace(oldEsc, newEsc, /*ignoreCase =*/ false)
        }

        // 4. Обновляем note c уже «правильным» HTML
        updateNote(noteToSave.copy(content = html, updatedAt = System.currentTimeMillis()))
        return noteId
    }
}