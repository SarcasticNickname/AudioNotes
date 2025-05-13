package com.myprojects.audionotes.ui.screens.notelist // или com.myprojects.audionotes.ui.model

enum class SortOrder {
    ASCENDING,
    DESCENDING
}

enum class SortBy(val displayName: String) {
    UPDATED_AT("Date Modified"),
    CREATED_AT("Date Created"),
    TITLE("Title (A-Z)"), // Пока не реализуем, но добавим для полноты
    TITLE_DESC("Title (Z-A)") // Пока не реализуем
}

data class SortOption(
    val sortBy: SortBy,
    val sortOrder: SortOrder
) {
    val
            displayName: String
        get() = when (sortBy) {
            SortBy.TITLE -> if (sortOrder == SortOrder.ASCENDING) "Title (A-Z)" else "Title (Z-A)"
            SortBy.TITLE_DESC -> if (sortOrder == SortOrder.ASCENDING) "Title (Z-A)" else "Title (A-Z)"
            else -> "${sortBy.displayName} (${if (sortOrder == SortOrder.DESCENDING) "Newest" else "Oldest"})"
        }

    companion object {
        val DEFAULT = SortOption(SortBy.UPDATED_AT, SortOrder.DESCENDING)
    }
}