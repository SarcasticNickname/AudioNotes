package com.myprojects.audionotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.myprojects.audionotes.ui.navigation.Screen
import com.myprojects.audionotes.ui.screens.notelist.NoteListScreen
import com.myprojects.audionotes.ui.screens.notedetail.NoteDetailScreen // Импортируем экран деталей
import com.myprojects.audionotes.ui.theme.AudioNotesTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AudioNotesAppContent()
        }
    }
}

@Composable
fun AudioNotesAppContent() {
    AudioNotesTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            val navController = rememberNavController()

            NavHost(
                navController = navController,
                startDestination = Screen.NoteList.route
            ) {
                composable(route = Screen.NoteList.route) {
                    NoteListScreen(
                        onNoteClick = { noteId ->
                            navController.navigate(Screen.NoteDetail.createRoute(noteId))
                        },
                        onAddNoteClick = { noteId ->
                            // Навигация после создания заметки в NoteListViewModel
                            navController.navigate(Screen.NoteDetail.createRoute(noteId))
                        }
                    )
                }

                composable(
                    route = Screen.NoteDetail.route,
                    arguments = listOf(navArgument("noteId") {
                        type = NavType.LongType
                    })
                ) { backStackEntry ->
                    val noteId = backStackEntry.arguments?.getLong("noteId") ?: -1L
                    // NoteDetailViewModel сам получит noteId через SavedStateHandle
                    NoteDetailScreen(navController = navController) // Передаем navController
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    AudioNotesTheme {
        Surface(modifier=Modifier.fillMaxSize()){
            Text("App Preview")
        }
    }
}