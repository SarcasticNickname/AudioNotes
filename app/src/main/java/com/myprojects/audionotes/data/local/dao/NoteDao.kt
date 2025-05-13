package com.myprojects.audionotes.data.local.dao

import android.util.Log
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import com.myprojects.audionotes.data.local.entity.*
import com.myprojects.audionotes.util.createAudioPlaceholder
import kotlinx.coroutines.flow.Flow

// Вспомогательные функции для работы с плейсхолдерами
fun String.escapeForHtmlPlaceholder(): String =
    this.replace("[", "[")
        .replace("]", "]")
        .replace(":", ":")
        .replace("_", "_")

fun String.unescapeFromHtmlPlaceholder(): String =
    this.replace("[", "[")
        .replace("]", "]")
        .replace(":", ":")
        .replace("_", "_")

fun String.cleanAudioPlaceholdersForRegex(): String =
    this.unescapeFromHtmlPlaceholder()
        .replace("\u200B", "")
        .replace("\u00A0", " ")


private const val TEMP_ID_THRESHOLD = 1_000_000_000_000L

@Dao
interface NoteDao {

    // Получаем НЕархивированные заметки через SupportSQLiteQuery
    // Условие isArchived = 0 будет добавлено в ViewModel при формировании query
    @RawQuery(observedEntities = [Note::class])
    fun getNotes(query: SupportSQLiteQuery): Flow<List<Note>>

    // Получаем АРХИВИРОВАННЫЕ заметки через SupportSQLiteQuery
    // Условие isArchived = 1 будет добавлено в ViewModel при формировании query
    @RawQuery(observedEntities = [Note::class])
    fun getArchivedNotes(query: SupportSQLiteQuery): Flow<List<Note>>

    // Обновляем статус архивации заметки и время последнего изменения
    @Query("UPDATE notes SET isArchived = :isArchived, updatedAt = :updatedTime WHERE id = :noteId")
    suspend fun setArchivedStatus(
        noteId: Long,
        isArchived: Boolean,
        updatedTime: Long
    ) // Убрал значение по умолчанию для updatedTime

    // Получаем все заметки (не Flow) для процесса бэкапа
    @Query("SELECT * FROM notes")
    suspend fun getAllNotesForBackup(): List<Note>

    // Вставляем или заменяем список заметок (для восстановления из бэкапа)
    // OnConflictStrategy.REPLACE гарантирует, что существующие заметки с теми же ID будут обновлены.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceNotes(notes: List<Note>)


    @Transaction
    @Query("SELECT * FROM notes WHERE id = :noteId")
    fun getNoteWithContentAndAudioBlocksById(noteId: Long): Flow<NoteWithContentAndAudioBlocks?>

    @Query("SELECT * FROM notes WHERE id = :noteId")
    suspend fun getNoteById(noteId: Long): Note?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: Note): Long

    @Update
    suspend fun updateNote(note: Note) // Этот метод обновит все поля, включая isArchived

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

    // Получение заметок по категории, теперь также фильтрует по isArchived = 0 для главного экрана
    @Query("SELECT * FROM notes WHERE category = :categoryName AND isArchived = 0 ORDER BY updatedAt DESC")
    fun getNotesByCategory(categoryName: String): Flow<List<Note>>

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
        // 1. Сохраняем / обновляем заметку
        val noteForDb = noteToSave.copy(updatedAt = System.currentTimeMillis())
        val noteId = if (noteForDb.id == 0L) insertNote(noteForDb) else {
            updateNote(noteForDb)
            noteForDb.id
        }

        // 2. Чистим старые аудио-блоки
        deleteAllAudioBlocksByNoteId(noteId)

        // 3. Вставляем новые блоки
        //    ⬐ сбрасываем id только для «временных» (больших) идентификаторов
        val realIds = insertAudioBlocks(
            audioBlocksToSave.mapIndexed { idx, block ->
                val idForInsert =
                    if (block.id in 1 until TEMP_ID_THRESHOLD) block.id else 0L
                block.copy(
                    id = idForInsert,   // оставляем «маленькие», обнуляем «временные»
                    noteId = noteId,
                    orderIndex = idx
                )
            }
        )

        // 4. Обновляем плейсхолдеры в HTML (только если id поменялся)
        var html = noteForDb.content
        audioBlocksToSave.forEachIndexed { idx, oldBlock ->
            val oldPh = createAudioPlaceholder(oldBlock.id)
            val newPh = createAudioPlaceholder(realIds[idx])
            if (oldPh != newPh && html.contains(oldPh)) {
                html = html.replace(oldPh, newPh)
            }
        }

        if (html != noteForDb.content) {
            // перезаписываем только content + updatedAt
            getNoteById(noteId)?.let {
                updateNote(it.copy(content = html, updatedAt = System.currentTimeMillis()))
            }
        }

        return noteId
    }

}