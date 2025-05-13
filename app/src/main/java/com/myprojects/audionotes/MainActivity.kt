package com.myprojects.audionotes

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.myprojects.audionotes.ui.navigation.Screen
import com.myprojects.audionotes.ui.screens.archived.ArchivedNotesScreen
import com.myprojects.audionotes.ui.screens.notedetail.NoteDetailScreen
import com.myprojects.audionotes.ui.screens.notelist.NoteListScreen
import com.myprojects.audionotes.ui.screens.settings.SettingsScreen // Импорт экрана настроек
import com.myprojects.audionotes.ui.theme.AudioNotesTheme
import com.myprojects.audionotes.util.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var navControllerRef: NavHostController? = null
    private val TAG_MAIN_ACTIVITY = "MainActivityLogs"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG_MAIN_ACTIVITY, "onCreate called. Intent: $intent")
        setContent {
            AudioNotesAppContent(
                onNavControllerReady = { navController ->
                    navControllerRef = navController
                    if (savedInstanceState == null) {
                        handleIntent(intent)
                    }
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG_MAIN_ACTIVITY, "onNewIntent called. New Intent: $intent")
        intent?.let {
            setIntent(it)
            handleIntent(it)
        }
    }

    private fun handleIntent(intent: Intent) {
        Log.d(
            TAG_MAIN_ACTIVITY,
            "Handling intent. Action: ${intent.action}, Data: ${intent.dataString}, Extras: ${intent.extras}"
        )
        val navigateToNoteId =
            intent.getLongExtra(NotificationHelper.NAVIGATE_TO_NOTE_ID_EXTRA, -1L)
        Log.d(TAG_MAIN_ACTIVITY, "Extracted NAVIGATE_TO_NOTE_ID_EXTRA: $navigateToNoteId")

        if (navigateToNoteId != -1L && navControllerRef != null) {
            Log.i(TAG_MAIN_ACTIVITY, "Navigating to NoteDetail for ID: $navigateToNoteId")
            navControllerRef?.navigate(Screen.NoteDetail.createRoute(navigateToNoteId)) {
                launchSingleTop = true
            }
            intent.removeExtra(NotificationHelper.NAVIGATE_TO_NOTE_ID_EXTRA)
        } else {
            if (navigateToNoteId == -1L) Log.d(
                TAG_MAIN_ACTIVITY,
                "No NAVIGATE_TO_NOTE_ID_EXTRA or it's -1."
            )
            if (navControllerRef == null) Log.w(
                TAG_MAIN_ACTIVITY,
                "navControllerRef is null for intent handling."
            )
        }
    }
}

@Composable
fun AudioNotesAppContent(onNavControllerReady: (NavHostController) -> Unit) {
    AudioNotesTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val navController = rememberNavController()
            LaunchedEffect(navController) {
                onNavControllerReady(navController)
            }

            NavHost(navController = navController, startDestination = Screen.NoteList.route) {
                composable(route = Screen.NoteList.route) {
                    NoteListScreen(
                        navController = navController,
                        // Эти параметры теперь не нужны, т.к. NoteListScreen сам управляет навигацией
                        // Оставляю для возможной совместимости с Preview или если они используются иначе
                        onNoteClick = { noteId ->
                            navController.navigate(
                                Screen.NoteDetail.createRoute(
                                    noteId
                                )
                            )
                        },
                        onAddNoteClick = { noteId ->
                            navController.navigate(
                                Screen.NoteDetail.createRoute(
                                    noteId
                                )
                            )
                        }
                    )
                }
                composable(
                    route = Screen.NoteDetail.route,
                    arguments = listOf(navArgument("noteId") { type = NavType.LongType })
                ) {
                    NoteDetailScreen(navController = navController)
                }
                composable(route = Screen.ArchivedNotes.route) {
                    ArchivedNotesScreen(navController = navController)
                }
                composable(route = Screen.Settings.route) { // Добавляем маршрут для экрана настроек
                    SettingsScreen(navController = navController)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    AudioNotesTheme {
        AudioNotesAppContent(onNavControllerReady = {})
    }
}