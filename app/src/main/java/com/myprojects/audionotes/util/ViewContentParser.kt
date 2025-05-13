package com.myprojects.audionotes.util // или util

import android.util.Log
import com.myprojects.audionotes.data.local.entity.NoteBlock

sealed interface ViewContentPart
data class TextPart(val text: String) : ViewContentPart
data class AudioPart(val block: NoteBlock) : ViewContentPart


// Константы для плейсхолдеров аудио
const val AUDIO_PLACEHOLDER_PREFIX = "[AUDIO_ID:"
const val AUDIO_PLACEHOLDER_SUFFIX = "]"

// Функция для создания плейсхолдера
fun createAudioPlaceholder(audioBlockId: Long): String {
    return "$AUDIO_PLACEHOLDER_PREFIX$audioBlockId$AUDIO_PLACEHOLDER_SUFFIX"
}

// Функция для извлечения ID из плейсхолдера
fun extractAudioIdFromPlaceholder(placeholder: String): Long? {
    if (placeholder.startsWith(AUDIO_PLACEHOLDER_PREFIX) && placeholder.endsWith(AUDIO_PLACEHOLDER_SUFFIX)) {
        return placeholder.substringAfter(AUDIO_PLACEHOLDER_PREFIX).substringBefore(AUDIO_PLACEHOLDER_SUFFIX).toLongOrNull()
    }
    return null
}

fun parseContentForViewMode(
    textContent: String,
    availableAudioBlocks: List<NoteBlock> // Список всех аудио блоков, доступных для этой заметки
): List<ViewContentPart> {
    val parts = mutableListOf<ViewContentPart>()
    var lastIndex = 0
    val regex = Regex("\\[AUDIO_ID:(\\d+)\\]")

    regex.findAll(textContent).forEach { matchResult ->
        val placeholderStart = matchResult.range.first
        val placeholderEnd = matchResult.range.last + 1
        val audioBlockIdFromPlaceholder = matchResult.groupValues[1].toLongOrNull()

        // Текст до плейсхолдера
        if (placeholderStart > lastIndex) {
            parts.add(TextPart(textContent.substring(lastIndex, placeholderStart)))
        }

        // Аудио блок
        audioBlockIdFromPlaceholder?.let { id ->
            availableAudioBlocks.find { it.id == id }?.let { block -> // Ищем блок в общем списке по ID
                parts.add(AudioPart(block))
            } ?: run {
                // Если плейсхолдер есть, а блока нет (например, ошибка данных), можно отобразить плейсхолдер как текст
                parts.add(TextPart(matchResult.value))
                Log.w("ViewContentParser", "Audio block for placeholder ${matchResult.value} not found in available blocks.")
            }
        }
        lastIndex = placeholderEnd
    }

    // Оставшийся текст после последнего плейсхолдера
    if (lastIndex < textContent.length) {
        parts.add(TextPart(textContent.substring(lastIndex)))
    }
    // Убираем пустые текстовые части, если они случайно образовались
    return parts.filterNot { it is TextPart && it.text.isEmpty() }
}