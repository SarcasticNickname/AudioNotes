package com.myprojects.audionotes.util

import androidx.room.TypeConverter
import com.myprojects.audionotes.data.local.entity.BlockType

// Конвертер для хранения Enum BlockType в базе данных как String
class Converters {
    @TypeConverter
    fun fromBlockType(value: BlockType?): String? = value?.name

    @TypeConverter
    fun toBlockType(value: String?): BlockType? = value?.let { BlockType.valueOf(it) }
}