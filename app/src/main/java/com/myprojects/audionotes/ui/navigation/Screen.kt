package com.myprojects.audionotes.ui.navigation

sealed class Screen(val route: String) {
    object NoteList : Screen("note_list")
    object NoteDetail : Screen("note_detail/{noteId}") {
        fun createRoute(noteId: Long) = "note_detail/$noteId"
    }

    object ArchivedNotes : Screen("archived_notes_list")
    object Settings : Screen("settings_screen")
}