package com.myprojects.audionotes.data.local.entity

import androidx.room.*

@Entity(
    tableName = "note_blocks",
    foreignKeys = [ForeignKey(
        entity = Note::class,
        parentColumns = ["id"],
        childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE
    )],
    // Индекс по noteId и type будет полезен для выборки всех аудио блоков заметки
    indices = [Index("noteId"), Index("noteId", "type")]
)
data class NoteBlock(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    // orderIndex теперь больше для аудио блоков, если их нужно как-то упорядочивать вне текста
    // Но основной порядок аудио будет диктоваться плейсхолдерами в тексте
    val orderIndex: Int = 0, // Можно использовать для сортировки в UI предпросмотра аудио
    val type: BlockType, // Будет в основном AUDIO
    // textContent больше не нужен для AUDIO блоков
    // val textContent: String? = null,
    val audioFilePath: String? = null, // Только для AUDIO
    val audioDurationMs: Long? = null  // Только для AUDIO
)