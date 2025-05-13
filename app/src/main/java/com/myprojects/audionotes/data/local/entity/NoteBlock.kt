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
    indices = [Index("noteId"), Index("noteId", "type")]
)
data class NoteBlock(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,

    val orderIndex: Int = 0,
    val type: BlockType,
    val audioFilePath: String? = null,
    val audioDurationMs: Long? = null,
    var audioDisplayName: String? = null
)