package com.myprojects.audionotes.data.local.entity

import androidx.compose.ui.graphics.Color

object CategoryColors {
    val ColorDefault = Color(0xFFB0BEC5)  // Светло-серый, более нейтральный для "Без категории"
    val ColorWork = Color(0xFF42A5F5)     // Синий
    val ColorPersonal = Color(0xFF66BB6A)  // Зеленый
    val ColorIdeas = Color(0xFFFFCA28)    // Янтарный (вместо чисто желтого, лучше читается)
    val ColorImportant = Color(0xFFEF5350)  // Красный
    val ColorLearning = Color(0xFFAB47BC)   // Фиолетовый
}

enum class NoteCategory(
    val displayName: String,
    val color: Color
) {
    NONE("Без категории", CategoryColors.ColorDefault),
    WORK("Работа", CategoryColors.ColorWork),
    PERSONAL("Личное", CategoryColors.ColorPersonal),
    IDEAS("Идеи", CategoryColors.ColorIdeas),
    IMPORTANT("Важное", CategoryColors.ColorImportant),
    LEARNING("Обучение", CategoryColors.ColorLearning);

    companion object {
        /**
         * Возвращает NoteCategory по его строковому имени (enum.name).
         * Если имя не найдено или null, возвращает NONE.
         */
        fun fromName(name: String?): NoteCategory {
            return entries.find { it.name == name } ?: NONE
        }

        /**
         * Список всех категорий, которые пользователь может выбрать.
         * NONE обычно не предлагается для активного выбора, а является состоянием "не выбрано".
         */
        val selectableEntries: List<NoteCategory>
            get() = entries // Если хочешь, чтобы NONE тоже был в списке выбора, оставь entries
        // Если NONE не должен быть выбираемым, то: entries.filter { it != NONE }
    }
}