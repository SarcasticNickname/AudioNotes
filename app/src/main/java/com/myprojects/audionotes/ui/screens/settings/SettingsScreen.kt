package com.myprojects.audionotes.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue // Добавлен импорт для var expanded by remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.myprojects.audionotes.data.preferences.SpeechLanguage // Импорт SpeechLanguage
import com.myprojects.audionotes.ui.theme.AudioNotesTheme
import com.myprojects.audionotes.ui.viewmodel.SettingsUiState
import com.myprojects.audionotes.ui.viewmodel.SettingsViewModel
import com.myprojects.audionotes.ui.viewmodel.UserInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.handleSignInResult(result)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { errorMessage ->
            snackbarHostState.showSnackbar(
                message = errorMessage,
                duration = SnackbarDuration.Long,
                actionLabel = "Dismiss"
            ).also {
                // viewModel.clearError() // Оставим возможность закрыть через кнопку или по таймауту
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings & Backup") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp) // Немного уменьшил общий отступ
        ) {
            if (uiState.isLoading && (uiState.currentUser == null || uiState.isSigningIn)) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                AccountSection(
                    userInfo = uiState.currentUser,
                    isSigningIn = uiState.isSigningIn,
                    onSignInClick = { viewModel.initiateSignIn(googleSignInLauncher) },
                    onSignOutClick = { viewModel.signOut() }
                )

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                BackupSection(
                    isUserLoggedIn = uiState.currentUser != null,
                    isActionInProgress = uiState.isLoading || uiState.isSigningIn,
                    onBackupClick = { viewModel.backupNotesToFirestore() },
                    onRestoreClick = { viewModel.restoreNotesFromFirestore() },
                    lastBackupTimestamp = uiState.lastBackupTimestamp
                )

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                // МОЯ СЕКЦИЯ: Добавлена секция для выбора языка распознавания
                SpeechLanguageSection(
                    selectedLanguage = uiState.selectedSpeechLanguage,
                    onLanguageSelected = { viewModel.updateSpeechLanguage(it) }
                )
            }
        }
    }
}

@Composable
fun AccountSection(
    userInfo: UserInfo?,
    isSigningIn: Boolean,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Account", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        if (userInfo != null) {
            Text("Signed in as:", style = MaterialTheme.typography.bodyMedium)
            userInfo.displayName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
            }
            userInfo.email?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onSignOutClick, enabled = !isSigningIn) {
                Icon(Icons.Filled.Logout, contentDescription = "Sign Out")
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Sign Out")
            }
        } else {
            Text(
                "Sign in with your Google Account to enable cloud backup and restore of your notes.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onSignInClick, enabled = !isSigningIn) {
                if (isSigningIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Signing in...")
                } else {
                    Icon(Icons.Filled.Login, contentDescription = "Sign In with Google")
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Sign in with Google")
                }
            }
        }
    }
}

@Composable
fun BackupSection(
    isUserLoggedIn: Boolean,
    isActionInProgress: Boolean,
    onBackupClick: () -> Unit,
    onRestoreClick: () -> Unit,
    lastBackupTimestamp: Long?
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Cloud Backup & Restore", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        if (!isUserLoggedIn) {
            Text(
                "Please sign in to use backup and restore features.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        } else {
            Button(onClick = onBackupClick, enabled = !isActionInProgress) {
                Icon(Icons.Filled.CloudUpload, contentDescription = "Backup Notes")
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Backup Notes Now")
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRestoreClick, enabled = !isActionInProgress) {
                Icon(Icons.Filled.Restore, contentDescription = "Restore Notes")
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Restore Notes from Cloud")
            }
            Spacer(Modifier.height(16.dp))
            val lastBackupText = remember(lastBackupTimestamp) {
                if (lastBackupTimestamp == null) {
                    "Last backup: Never"
                } else {
                    try {
                        "Last backup: ${
                            SimpleDateFormat(
                                "dd MMM yyyy, HH:mm:ss",
                                Locale.getDefault()
                            ).format(Date(lastBackupTimestamp))
                        }"
                    } catch (e: Exception) {
                        "Last backup: Error"
                    }
                }
            }
            Text(lastBackupText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// МОЯ ФУНКЦИЯ: Новая Composable-функция для выбора языка
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechLanguageSection(
    selectedLanguage: SpeechLanguage,
    onLanguageSelected: (SpeechLanguage) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val availableLanguages = SpeechLanguage.entries // Получаем все доступные языки из enum

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Speech Recognition Language", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(0.8f) // Ограничиваем ширину для лучшего вида
        ) {
            OutlinedTextField(
                value = selectedLanguage.displayName,
                onValueChange = {}, // Текстовое поле только для чтения
                readOnly = true,
                label = { Text("Language") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor() // Важно для привязки выпадающего меню к этому полю
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                availableLanguages.forEach { language ->
                    DropdownMenuItem(
                        text = { Text(language.displayName) },
                        onClick = {
                            onLanguageSelected(language)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, name = "Settings Screen - Not Logged In")
@Composable
fun SettingsScreenPreviewNotLoggedIn() {
    AudioNotesTheme {
        val navController = rememberNavController()
        // МОЙ КОММЕНТАРИЙ: Добавил selectedSpeechLanguage для превью
        val fakeUiState = SettingsUiState(
            currentUser = null,
            isLoading = false,
            isSigningIn = false,
            lastBackupTimestamp = null,
            selectedSpeechLanguage = SpeechLanguage.RUSSIAN // Пример для превью
        )
        val rememberedUiState by remember { mutableStateOf(fakeUiState) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Settings Preview") },
                    navigationIcon = {
                        IconButton(onClick = {}) {
                            Icon(
                                Icons.Default.ArrowBack,
                                ""
                            )
                        }
                    })
            }
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AccountSection(
                    userInfo = rememberedUiState.currentUser,
                    isSigningIn = rememberedUiState.isSigningIn,
                    onSignInClick = { },
                    onSignOutClick = {})
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                BackupSection(
                    isUserLoggedIn = rememberedUiState.currentUser != null,
                    isActionInProgress = rememberedUiState.isLoading || rememberedUiState.isSigningIn,
                    onBackupClick = { },
                    onRestoreClick = { },
                    lastBackupTimestamp = rememberedUiState.lastBackupTimestamp
                )
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                // МОЙ КОММЕНТАРИЙ: Добавил вызов новой секции в превью
                SpeechLanguageSection(
                    selectedLanguage = rememberedUiState.selectedSpeechLanguage,
                    onLanguageSelected = {}
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, name = "Settings Screen - Logged In")
@Composable
fun SettingsScreenPreviewLoggedIn() {
    AudioNotesTheme {
        val navController = rememberNavController()
        // МОЙ КОММЕНТАРИЙ: Добавил selectedSpeechLanguage для превью
        val fakeUiState = SettingsUiState(
            currentUser = UserInfo("123", "Test User", "test@example.com"),
            isLoading = false,
            isSigningIn = false,
            lastBackupTimestamp = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(5),
            selectedSpeechLanguage = SpeechLanguage.ENGLISH // Пример для превью
        )
        val rememberedUiState by remember { mutableStateOf(fakeUiState) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Settings Preview") },
                    navigationIcon = {
                        IconButton(onClick = {}) {
                            Icon(
                                Icons.Default.ArrowBack,
                                ""
                            )
                        }
                    })
            }
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AccountSection(
                    userInfo = rememberedUiState.currentUser,
                    isSigningIn = rememberedUiState.isSigningIn,
                    onSignInClick = { },
                    onSignOutClick = {})
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                BackupSection(
                    isUserLoggedIn = rememberedUiState.currentUser != null,
                    isActionInProgress = rememberedUiState.isLoading || rememberedUiState.isSigningIn,
                    onBackupClick = { },
                    onRestoreClick = { },
                    lastBackupTimestamp = rememberedUiState.lastBackupTimestamp
                )
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                // МОЙ КОММЕНТАРИЙ: Добавил вызов новой секции в превью
                SpeechLanguageSection(
                    selectedLanguage = rememberedUiState.selectedSpeechLanguage,
                    onLanguageSelected = {}
                )
            }
        }
    }
}