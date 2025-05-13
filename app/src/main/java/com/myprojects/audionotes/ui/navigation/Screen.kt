package com.myprojects.audionotes.ui.navigation

// Запечатанный класс для определения экранов и их роутов
sealed class Screen(val route: String) {
    object NoteList : Screen("note_list") // Экран списка заметок
    object NoteDetail : Screen("note_detail/{noteId}") { // Экран деталей, принимает ID заметки
        // Вспомогательная функция для построения роута с конкретным ID
        fun createRoute(noteId: Long) = "note_detail/$noteId"
    }
}