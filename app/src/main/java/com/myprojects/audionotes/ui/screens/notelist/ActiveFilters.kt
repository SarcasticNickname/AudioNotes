package com.myprojects.audionotes.ui.screens.notelist

// Вспомогательный enum для трех состояний фильтра
enum class TriStateFilterSelection {
    ANY, // Неважно
    YES, // Да
    NO   // Нет
}

data class ActiveFilters(
    val selectedCategories: Set<String> = emptySet(), // Храним имена категорий (enum.name)
    val reminderFilter: TriStateFilterSelection = TriStateFilterSelection.ANY,
    val audioFilter: TriStateFilterSelection = TriStateFilterSelection.ANY
) {
    companion object {
        val NONE = ActiveFilters() // Фильтры не применены
    }

    // Свойство для проверки, применены ли какие-либо фильтры (кроме стандартных)
    val isAnyFilterActive: Boolean
        get() = selectedCategories.isNotEmpty() ||
                reminderFilter != TriStateFilterSelection.ANY ||
                audioFilter != TriStateFilterSelection.ANY
}