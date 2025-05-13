package com.myprojects.audionotes.data.local.entity

import androidx.room.Embedded
import androidx.room.Relation

data class NoteWithContentAndAudioBlocks( // Переименовываем для ясности
    @Embedded val note: Note,
    @Relation(
        parentColumn = "id",
        entityColumn = "noteId",
        entity = NoteBlock::class,
        // Можно добавить проекцию, чтобы выбирать только аудио блоки, если нужно
        // projection = ["id", "noteId", "orderIndex", "type", "audioFilePath", "audioDurationMs"]
    )
    // Фильтруем на уровне DAO или репозитория, чтобы здесь были только аудио блоки
    val audioBlocks: List<NoteBlock>
)